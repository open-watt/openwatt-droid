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
     * Fetch the energy circuit + island element model in one request.
     *
     * Energy state is published as elements on the synthetic `energy` device;
     * the old `GET /api/energy/circuit` endpoint has been retired on the
     * backend. The flat path->value result is reshaped into the circuit graph
     * by [com.openwatt.droid.model.energy.graph.EnergyModelParser]. Mirrors the
     * web client's `getEnergyElements`.
     */
    suspend fun getEnergyElements(server: Server): Result<Map<String, Any?>> =
        getValuesRaw(server, listOf("energy.circuit.*", "energy.islands.*"))

    /**
     * Fetch the planner element model (policies + allocations). Fetched
     * separately from [getEnergyElements] so a planner outage can't break the
     * energy/flow views. Mirrors the web client's `getPlannerElements`.
     */
    suspend fun getPlannerElements(server: Server): Result<Map<String, Any?>> =
        getValuesRaw(server, listOf("energy.policy.*", "energy.allocation.*"))

    /**
     * Like [getValues] but returns the raw flat path->entry map without forcing
     * each entry to be an object. Energy element values include scalars, arrays
     * ([sum,L1,L2,L3]) and quantity objects ({q,u}); a single bare value would
     * make the stricter [getValues] parse throw, so the energy/planner helpers
     * use this looser shape and let the model parser normalise each entry.
     */
    private suspend fun getValuesRaw(
        server: Server,
        paths: List<String>,
    ): Result<Map<String, Any?>> = withContext(Dispatchers.IO) {
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

                val type = object : TypeToken<Map<String, Any?>>() {}.type
                val result: Map<String, Any?> = gson.fromJson(responseBody, type)
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
     * Parse the /api/schema response into CollectionSchema objects.
     */
    private fun parseSchemaResponse(json: JsonObject): Map<String, CollectionSchema> {
        val result = mutableMapOf<String, CollectionSchema>()
        for ((collectionName, collectionJson) in json.entrySet()) {
            if (!collectionJson.isJsonObject) continue
            val obj = collectionJson.asJsonObject

            val path = obj.get("path")?.asString ?: continue
            val propertiesJson = obj.getAsJsonObject("properties") ?: continue
            val isCollection = obj.get("collection")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val isAbstract = obj.get("abstract")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val collectionId = obj.get("collection_id")?.takeIf { !it.isJsonNull }?.asInt ?: 0

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

            result[collectionName] = CollectionSchema(
                path = path,
                properties = properties,
                collection = isCollection,
                abstract = isAbstract,
                collectionId = collectionId,
                typeName = collectionName,
            )
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
