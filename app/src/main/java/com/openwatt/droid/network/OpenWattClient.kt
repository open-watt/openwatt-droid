package com.openwatt.droid.network

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.ComponentStructure
import com.openwatt.droid.model.ElementMeta
import com.openwatt.droid.model.PropertySchema
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

class OpenWattClient {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    /**
     * List devices and their structure tree.
     * POST /api/list { "path": "", "shallow": false }
     * Returns map of device ID -> ComponentStructure
     */
    suspend fun listDevices(server: Server): Result<Map<String, ComponentStructure>> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("path" to "", "shallow" to false))
            val request = Request.Builder()
                .url("${server.baseUrl}/api/list")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }

                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                val result = parseListResponse(jsonObject)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get element values by path.
     * POST /api/get { "paths": [...] }
     * Returns map of path -> { "value": ..., "unit": ..., "age": ... }
     */
    suspend fun getValues(
        server: Server,
        paths: List<String>,
    ): Result<Map<String, Map<String, Any?>>> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) {
            return@withContext Result.success(emptyMap())
        }

        try {
            val body = gson.toJson(mapOf("paths" to paths))
            val request = Request.Builder()
                .url("${server.baseUrl}/api/get")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }

                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val type = object : TypeToken<Map<String, Map<String, Any?>>>() {}.type
                val result: Map<String, Map<String, Any?>> = gson.fromJson(responseBody, type)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set element values.
     * POST /api/set { "values": { "path": value, ... } }
     */
    suspend fun setValues(
        server: Server,
        values: Map<String, Any>,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("values" to values))
            val request = Request.Builder()
                .url("${server.baseUrl}/api/set")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }

                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val result = gson.fromJson(responseBody, JsonObject::class.java)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the configuration schema.
     * GET /api/schema
     * Returns map of collection name -> CollectionSchema
     */
    suspend fun getSchema(server: Server): Result<Map<String, CollectionSchema>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${server.baseUrl}/api/schema")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }

                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                val result = parseSchemaResponse(jsonObject)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get enum values by name.
     * GET /api/enum/{name}
     * Returns map of display name -> numeric value
     */
    suspend fun getEnum(
        server: Server,
        enumName: String,
    ): Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${server.baseUrl}/api/enum/${enumName}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }

                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                // Response is {EnumName: {key: value, ...}} — unwrap the outer object
                val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                val inner = jsonObject.entrySet().firstOrNull()?.value?.asJsonObject
                    ?: return@withContext Result.success(emptyMap())

                val result = mutableMapOf<String, Any>()
                for ((key, value) in inner.entrySet()) {
                    result[key] = when {
                        value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber
                        value.isJsonPrimitive -> value.asString
                        else -> value.toString()
                    }
                }
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse the /api/schema response into CollectionSchema objects.
     */
    private fun parseSchemaResponse(json: JsonObject): Map<String, CollectionSchema> {
        val result = mutableMapOf<String, CollectionSchema>()
        for ((collectionName, collectionJson) in json.entrySet()) {
            if (!collectionJson.isJsonObject) continue
            val obj = collectionJson.asJsonObject

            val path = obj.get("path")?.asString ?: continue
            val propertiesJson = obj.getAsJsonObject("properties") ?: continue

            val properties = mutableMapOf<String, PropertySchema>()
            for ((propName, propJson) in propertiesJson.entrySet()) {
                if (!propJson.isJsonObject) continue
                val propObj = propJson.asJsonObject

                val typeArray = propObj.getAsJsonArray("type")?.map { it.asString } ?: listOf("str")
                val access = propObj.get("access")?.asString ?: "rw"
                val default = propObj.get("default")?.let {
                    when {
                        it.isJsonNull -> null
                        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean -> it.asBoolean
                        it.isJsonPrimitive && it.asJsonPrimitive.isNumber -> it.asNumber
                        it.isJsonPrimitive -> it.asString
                        else -> it.toString()
                    }
                }
                val category = propObj.get("category")?.takeIf { !it.isJsonNull }?.asString
                val flags = propObj.get("flags")?.takeIf { !it.isJsonNull }?.asString

                properties[propName] = PropertySchema(
                    type = typeArray,
                    access = access,
                    default = default,
                    category = category,
                    flags = flags,
                )
            }

            result[collectionName] = CollectionSchema(path = path, properties = properties)
        }
        return result
    }

    /**
     * Parse the /api/list response into ComponentStructure objects.
     * The response is a nested JSON object representing the device hierarchy.
     */
    private fun parseListResponse(json: JsonObject): Map<String, ComponentStructure> {
        val result = mutableMapOf<String, ComponentStructure>()
        for ((deviceId, deviceJson) in json.entrySet()) {
            if (deviceJson.isJsonObject) {
                result[deviceId] = parseComponent(deviceJson.asJsonObject)
            }
        }
        return result
    }

    /**
     * Recursively parse a component from JSON.
     * Expected structure:
     * {
     *   "template": "Switch",
     *   "elements": { "power": { "name": "Power", "unit": "W", "mode": "sampled", "access": "r" } },
     *   "components": { "meter": { ... } }
     * }
     */
    private fun parseComponent(json: JsonObject): ComponentStructure {
        val template = json.get("template")?.takeIf { it.isJsonPrimitive }?.asString

        // Parse elements
        val elements = mutableMapOf<String, ElementMeta>()
        json.getAsJsonObject("elements")?.let { elemObj ->
            for ((elemId, elemJson) in elemObj.entrySet()) {
                if (elemJson.isJsonObject) {
                    val ej = elemJson.asJsonObject
                    elements[elemId] = ElementMeta(
                        name = ej.get("name")?.takeIf { it.isJsonPrimitive }?.asString,
                        desc = ej.get("desc")?.takeIf { it.isJsonPrimitive }?.asString,
                        unit = ej.get("unit")?.takeIf { it.isJsonPrimitive }?.asString,
                        mode = ej.get("mode")?.takeIf { it.isJsonPrimitive }?.asString,
                        access = ej.get("access")?.takeIf { it.isJsonPrimitive }?.asString,
                    )
                }
            }
        }

        // Parse sub-components
        val components = mutableMapOf<String, ComponentStructure>()
        json.getAsJsonObject("components")?.let { compObj ->
            for ((compId, compJson) in compObj.entrySet()) {
                if (compJson.isJsonObject) {
                    components[compId] = parseComponent(compJson.asJsonObject)
                }
            }
        }

        return ComponentStructure(
            template = template,
            elements = elements,
            components = components,
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
