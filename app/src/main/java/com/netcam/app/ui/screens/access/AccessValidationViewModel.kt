package com.netcam.app.ui.screens.access

import com.netcam.app.data.auth.QrAuthApi
import com.netcam.app.domain.auth.CameraAccessAuthorization
import com.netcam.app.domain.auth.CameraAccessRepository

class AccessValidationViewModel(
    private val cameraAccessRepository: CameraAccessRepository,
    private val qrAuthApi: QrAuthApi,
) {
    suspend fun validateToken(tokenInput: String): ValidateTokenResult {
        val sanitized = extractToken(tokenInput)
        if (sanitized.isBlank()) {
            return ValidateTokenResult.Error("Digite um token válido.")
        }

        return runCatching {
            qrAuthApi.validateQr(sanitized)
        }.fold(
            onSuccess = { response ->
                if (!response.authorized) {
                    val message =
                        when (response.reason) {
                            "inactive" -> "Este QR está inativo. Procure o responsável da arena."
                            "invalid_qr" -> "QR inválido. Tente novamente."
                            "bad_request" -> "Token inválido. Revise e tente novamente."
                            else -> "Não foi possível validar agora. Tente novamente."
                        }
                    return ValidateTokenResult.Error(message)
                }

                val expiresInHours = response.expiresInHours ?: 12
                val now = System.currentTimeMillis()
                val authorization =
                    CameraAccessAuthorization(
                        arenaToken = sanitized,
                        arenaName = response.arenaName ?: "Arena",
                        authorizedAtEpochMs = now,
                        expiresAtEpochMs = now + expiresInHours * HOUR_IN_MILLIS,
                    )
                cameraAccessRepository.saveAuthorization(authorization)
                ValidateTokenResult.Success(authorization.arenaName)
            },
            onFailure = {
                ValidateTokenResult.Error("Sem conexão ou erro de rede. Verifique a internet e tente novamente.")
            },
        )
    }

    private fun extractToken(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val fromQuery = trimmed.substringAfter("t=", missingDelimiterValue = "").trim()
            if (fromQuery.isNotBlank()) {
                fromQuery.substringBefore("&").trim()
            } else {
                trimmed.substringAfterLast("/").substringBefore("?").trim()
            }
        } else {
            trimmed
        }
    }

    companion object {
        private const val HOUR_IN_MILLIS = 3_600_000L
    }
}

sealed class ValidateTokenResult {
    data class Success(
        val arenaName: String,
    ) : ValidateTokenResult()

    data class Error(
        val message: String,
    ) : ValidateTokenResult()
}
