package com.openwatt.droid.network

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.openwatt.droid.model.CliRequest
import com.openwatt.droid.model.CliResponse
import com.openwatt.droid.model.CollectionItem
import com.openwatt.droid.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

class CliClient {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    suspend fun executeCommand(server: Server, command: String): Result<CliResponse> = withContext(Dispatchers.IO) {
        try {
            val cliRequest = CliRequest(command)
            val jsonBody = gson.toJson(cliRequest)

            val request = Request.Builder()
                .url("${server.baseUrl}/api/cli/execute")
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: ${response.message}")
                    )
                }

                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val cliResponse = gson.fromJson(responseBody, CliResponse::class.java)
                Result.success(cliResponse)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testConnection(server: Server): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${server.baseUrl}/api/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Connection failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Execute a CLI command and parse the JSON output.
     * Used for commands that return structured data (e.g., print --json).
     */
    private suspend fun executeCommandJson(server: Server, command: String): Result<JsonArray> {
        val result = executeCommand(server, command)
        return result.mapCatching { response ->
            // The CLI API returns structured data in the `result` field, not `output`
            val resultElement = response.result
            if (resultElement != null && resultElement.isJsonArray) {
                resultElement.asJsonArray
            } else if (resultElement != null && resultElement.isJsonObject) {
                // Wrap single object in array
                JsonArray().apply { add(resultElement) }
            } else {
                // Fallback: try parsing output text
                val trimmed = response.output.trim()
                if (trimmed.isEmpty() || trimmed == "[]") {
                    JsonArray()
                } else {
                    gson.fromJson(trimmed, JsonArray::class.java)
                }
            }
        }
    }

    /**
     * List items in a collection.
     * Executes: {path}/print --json
     */
    suspend fun listCollection(server: Server, path: String): Result<List<CollectionItem>> {
        return executeCommandJson(server, "$path/print --json").map { jsonArray ->
            jsonArray.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                val properties = mutableMapOf<String, Any?>()
                for ((key, value) in obj.entrySet()) {
                    if (key == "name") continue
                    properties[key] = parseJsonValue(value)
                }
                CollectionItem(name = name, properties = properties)
            }
        }
    }

    /**
     * Add a new item to a collection.
     * Executes: {path}/add name=x prop=val ...
     */
    suspend fun addItem(
        server: Server,
        path: String,
        properties: Map<String, Any?>,
    ): Result<String> {
        val args = buildPropertyArgs(properties)
        val command = "$path/add $args"
        return executeCommand(server, command).map { it.output }
    }

    /**
     * Update properties on an existing item.
     * Executes: {path}/set {name} prop=val ...
     */
    suspend fun setItem(
        server: Server,
        path: String,
        name: String,
        properties: Map<String, Any?>,
    ): Result<String> {
        val args = buildPropertyArgs(properties)
        val command = "$path/set $name $args"
        return executeCommand(server, command).map { it.output }
    }

    /**
     * Reset properties on an item (clears "explicitly set" flag).
     * Executes: {path}/reset {name} key1 key2 ...
     */
    suspend fun resetItem(
        server: Server,
        path: String,
        name: String,
        keys: List<String>,
    ): Result<String> {
        val command = "$path/reset $name ${keys.joinToString(" ")}"
        return executeCommand(server, command).map { it.output }
    }

    /**
     * Remove an item from a collection.
     * Executes: {path}/remove {name}
     */
    suspend fun removeItem(
        server: Server,
        path: String,
        name: String,
    ): Result<String> {
        val command = "$path/remove $name"
        return executeCommand(server, command).map { it.output }
    }

    /**
     * Build CLI property arguments from a map.
     * Handles quoting, arrays, booleans, etc.
     */
    private fun buildPropertyArgs(properties: Map<String, Any?>): String {
        return properties.entries
            .filter { (key, value) -> value != null && key != "_originalName" }
            .joinToString(" ") { (key, value) ->
                when {
                    value is Boolean -> "$key=$value"
                    value is List<*> -> "$key=[${value.joinToString(",")}]"
                    value is String && (value.contains(' ') || value.contains('"')) ->
                        "$key=\"${value.replace("\"", "\\\"")}\""
                    else -> "$key=$value"
                }
            }
    }

    /** Parse a Gson JsonElement into a Kotlin value */
    private fun parseJsonValue(element: com.google.gson.JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                when {
                    prim.isBoolean -> prim.asBoolean
                    prim.isNumber -> {
                        val num = prim.asDouble
                        if (num == num.toLong().toDouble()) num.toLong() else num
                    }
                    else -> prim.asString
                }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                // Detect quantity objects {q, u}
                if (obj.has("q")) {
                    val q = obj.get("q")?.let { parseJsonValue(it) }
                    val u = obj.get("u")?.asString
                    if (u != null) mapOf("q" to q, "u" to u) else q
                } else {
                    obj.entrySet().associate { (k, v) -> k to parseJsonValue(v) }
                }
            }
            element.isJsonArray -> {
                element.asJsonArray.map { parseJsonValue(it) }
            }
            else -> element.toString()
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
