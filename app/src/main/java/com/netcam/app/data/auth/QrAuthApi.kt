package com.netcam.app.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

class QrAuthApi(
    private val baseUrl: String,
) {
    suspend fun validateQr(
        qrToken: String,
    ): ValidateQrResponse =
        withContext(Dispatchers.IO) {
            val url = URL("${baseUrl.trimEnd('/')}/auth/validate-qr")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                val body = JSONObject().put("qrToken", qrToken.trim()).toString().toByteArray()
                BufferedOutputStream(connection.outputStream).use { output ->
                    output.write(body)
                    output.flush()
                }

                val raw =
                    if (connection.responseCode in 200..299) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }

                parseValidateQrResponse(raw)
            } finally {
                connection.disconnect()
            }
        }

    private fun parseValidateQrResponse(raw: String): ValidateQrResponse {
        val json = JSONObject(raw.ifBlank { "{}" })
        val authorized = json.optBoolean("authorized", false)
        return if (authorized) {
            ValidateQrResponse(
                authorized = true,
                arenaId = json.optString("arenaId"),
                arenaName = json.optString("arenaName"),
                expiresInHours = json.optInt("expiresInHours", 12),
                reason = null,
            )
        } else {
            ValidateQrResponse(
                authorized = false,
                arenaId = null,
                arenaName = null,
                expiresInHours = null,
                reason = json.optString("reason", "invalid_qr"),
            )
        }
    }
}

data class ValidateQrResponse(
    val authorized: Boolean,
    val arenaId: String?,
    val arenaName: String?,
    val expiresInHours: Int?,
    val reason: String?,
)
