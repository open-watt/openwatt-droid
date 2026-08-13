package com.openwatt.droid.util

import com.openwatt.droid.model.CollectionSchema

/**
 * Client-side console command completion.
 *
 * The backend's real completion engine (`Console.suggest` in
 * manager/console/console.d) has no wire access yet, so this synthesizes
 * candidates for the dominant case — auto-generated collection scopes — from
 * data a client can reach today:
 *
 *  - scope paths and property schemas from `/api/schema`
 *  - live item names from each scope's auto-generated `list` command
 *  - enum members via the sync channel's `enum_req`
 *
 * Hand-written commands nested in deep scopes are not remotely enumerable, so
 * they won't be offered. When the backend grows a `suggest` verb over /sync,
 * a second [DataSource]-shaped provider can replace this wholesale.
 *
 * Suggestions carry explicit replace ranges so the UI can complete the token
 * under the cursor without re-parsing the line.
 */
class ConsoleCompleter(private val data: DataSource) {

    interface DataSource {
        /** Collection schemas from /api/schema (empty until loaded). */
        fun schemas(): Collection<CollectionSchema>

        /** Item names in a collection scope (e.g. "/interface/modbus"). Cached by the caller. */
        suspend fun listItems(scopePath: String): List<String>

        /** Members of a named backend enum. Cached by the caller. */
        suspend fun enumMembers(enumName: String): List<String>
    }

    /** Replace `line[start, end)` with [replacement] to apply the completion. */
    data class Suggestion(
        val label: String,
        val replacement: String,
        val start: Int,
        val end: Int,
    )

    suspend fun suggest(line: String): List<Suggestion> {
        val spaceIdx = line.indexOf(' ')
        return if (spaceIdx < 0) suggestPath(line) else suggestArguments(line, spaceIdx)
    }

    // -- path / command position --

    private fun suggestPath(token: String): List<Suggestion> {
        if (token.startsWith(":")) {
            // Script-scope builtins; only `help` is universally useful remotely.
            return listOf(":help")
                .filter { it.startsWith(token, ignoreCase = true) && it != token }
                .map { Suggestion(it, "$it ", 0, token.length) }
        }
        if (token.isNotEmpty() && !token.startsWith("/")) return emptyList()

        val segments = if (token.isEmpty()) listOf("") else token.substring(1).split("/")
        val partial = segments.last()
        var node = buildTree()
        for (seg in segments.dropLast(1)) {
            node = node.children[seg] ?: return emptyList()
        }

        val start = token.length - partial.length
        val out = mutableListOf<Suggestion>()
        for (name in node.children.keys.sorted()) {
            if (!name.startsWith(partial, ignoreCase = true)) continue
            // A node can be both a scope (has children) and a collection
            // (has commands); completing with "/" keeps the user walking.
            out.add(Suggestion("$name/", "$name/", start, token.length))
        }
        if (node.schema != null) {
            for (cmd in COLLECTION_COMMANDS) {
                if (!cmd.startsWith(partial, ignoreCase = true)) continue
                out.add(Suggestion(cmd, "$cmd ", start, token.length))
            }
        }
        return out.take(MAX_SUGGESTIONS)
    }

    // -- argument position --

    private suspend fun suggestArguments(line: String, headEnd: Int): List<Suggestion> {
        val head = line.substring(0, headEnd)
        if (!head.startsWith("/")) return emptyList()

        val segments = head.substring(1).split("/")
        if (segments.size < 2) return emptyList()
        val command = segments.last().lowercase()

        var node = buildTree()
        for (seg in segments.dropLast(1)) {
            node = node.children[seg] ?: return emptyList()
        }
        val schema = node.schema ?: return emptyList()
        val scopePath = "/" + segments.dropLast(1).joinToString("/")

        val tokenStart = line.lastIndexOf(' ') + 1
        val token = line.substring(tokenStart)
        val priorArgs = line.substring(headEnd, tokenStart).trim()
            .split(' ').filter { it.isNotEmpty() }

        val eq = token.indexOf('=')
        if (eq >= 0) {
            return suggestValue(schema, token, eq, tokenStart, line.length)
        }

        val out = mutableListOf<Suggestion>()

        // Item-targeting commands take the item name as their first argument.
        if (command in ITEM_COMMANDS && priorArgs.isEmpty()) {
            for (name in data.listItems(scopePath)) {
                if (!name.startsWith(token, ignoreCase = true)) continue
                out.add(Suggestion(name, "$name ", tokenStart, line.length))
            }
        }

        // Property keys. `add`/`set` write, so offer writable keys as `key=`;
        // `get`/`reset` name properties bare.
        val wantsKeys = when (command) {
            "add", "print" -> true
            "set", "get", "reset" -> priorArgs.isNotEmpty()
            else -> false
        }
        if (wantsKeys) {
            val writingCommand = command == "add" || command == "set"
            for ((key, prop) in schema.properties.toSortedMap()) {
                if (prop.isHidden) continue
                if (writingCommand && prop.isReadOnly) continue
                if (!key.startsWith(token, ignoreCase = true)) continue
                val replacement = if (writingCommand || command == "print") "$key=" else "$key "
                out.add(Suggestion(if (replacement.endsWith("=")) "$key=" else key,
                    replacement, tokenStart, line.length))
            }
        }
        if (command == "print" && "--json".startsWith(token) && token.isNotEmpty()) {
            out.add(Suggestion("--json", "--json ", tokenStart, line.length))
        }
        return out.take(MAX_SUGGESTIONS)
    }

    /** Complete the value side of a `key=partial` token. */
    private suspend fun suggestValue(
        schema: CollectionSchema,
        token: String,
        eq: Int,
        tokenStart: Int,
        lineEnd: Int,
    ): List<Suggestion> {
        val key = token.substring(0, eq)
        val partial = token.substring(eq + 1)
        val valueStart = tokenStart + eq + 1
        val prop = schema.properties[key] ?: return emptyList()
        val type = prop.primaryType

        val candidates: List<String> = when {
            type == "bool" -> listOf("true", "false")
            type.startsWith("enum_") -> data.enumMembers(type.removePrefix("enum_"))
            type.startsWith("#") -> {
                // Reference property: complete with the target collection's
                // live item names.
                val refType = type.removePrefix("#")
                val target = data.schemas().firstOrNull {
                    it.typeName.equals(refType, ignoreCase = true)
                }
                if (target != null) data.listItems(target.path) else emptyList()
            }
            else -> emptyList()
        }

        return candidates
            .filter { it.startsWith(partial, ignoreCase = true) }
            .map { Suggestion(it, "$it ", valueStart, lineEnd) }
            .take(MAX_SUGGESTIONS)
    }

    // -- scope tree --

    private class Node {
        val children = mutableMapOf<String, Node>()
        var schema: CollectionSchema? = null
    }

    private fun buildTree(): Node {
        val root = Node()
        for (schema in data.schemas()) {
            if (!schema.collection) continue
            val parts = schema.path.split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var node = root
            for (part in parts) {
                node = node.children.getOrPut(part) { Node() }
            }
            node.schema = schema
        }
        return root
    }

    companion object {
        /** Auto-generated commands every collection scope carries. */
        val COLLECTION_COMMANDS = listOf("add", "get", "list", "print", "remove", "reset", "set")

        /** Commands whose first argument is an item name. */
        val ITEM_COMMANDS = setOf("get", "set", "reset", "remove")

        const val MAX_SUGGESTIONS = 24
    }
}
