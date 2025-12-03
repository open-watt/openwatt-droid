package com.openwatt.droid.network

import com.google.gson.Gson
import com.openwatt.droid.model.CliRequest
import com.openwatt.droid.model.CliResponse
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
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
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

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
