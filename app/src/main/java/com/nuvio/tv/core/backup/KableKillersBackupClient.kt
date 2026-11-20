package com.nuvio.tv.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class KableKillersBackupHttpException(val status: Int, message: String) : IOException(message)

/** Talks to the Kable Killers backup Worker. Never talks to Nuvio. */
internal class KableKillersBackupClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    suspend fun post(
        path: String,
        body: JsonObject,
        bearer: String? = null,
        deviceId: String? = null
    ): JsonObject = execute(
        Request.Builder().url(base + path).post(body.toString().toRequestBody(jsonType)),
        bearer,
        deviceId
    )

    suspend fun get(path: String, bearer: String?, deviceId: String?): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base + path).get().withAuth(bearer, deviceId).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw KableKillersBackupHttpException(response.code, errorMessage(text, response.code))
            text
        }
    }

    private suspend fun execute(builder: Request.Builder, bearer: String?, deviceId: String?): JsonObject =
        withContext(Dispatchers.IO) {
            http.newCall(builder.withAuth(bearer, deviceId).build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw KableKillersBackupHttpException(response.code, errorMessage(text, response.code))
                if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
            }
        }

    private fun Request.Builder.withAuth(bearer: String?, deviceId: String?): Request.Builder = apply {
        header("Accept", "application/json")
        if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
        if (!deviceId.isNullOrBlank()) header("X-KK-Device-Id", deviceId)
    }

    private fun errorMessage(text: String, status: Int): String = runCatching {
        Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull() ?: "Backup server returned HTTP $status"
}
