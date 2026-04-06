package com.netcam.app.data.camera

import android.content.Context
import com.netcam.app.domain.model.CameraRole

class CameraCalibrationStore(
    appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(role: CameraRole): String? {
        return prefs.getString(keyFor(role), null)?.takeIf { it.isNotBlank() }
    }

    fun set(role: CameraRole, cameraId: String?) {
        val editor = prefs.edit()
        if (cameraId.isNullOrBlank()) {
            editor.remove(keyFor(role))
        } else {
            editor.putString(keyFor(role), cameraId)
        }
        editor.apply()
    }

    fun clearAll() {
        prefs.edit()
            .remove(keyFor(CameraRole.BACK_MAIN))
            .remove(keyFor(CameraRole.BACK_REDUCED))
            .remove(keyFor(CameraRole.FRONT))
            .apply()
    }

    fun getAll(): Map<CameraRole, String> {
        return buildMap {
            CameraRole.entries.forEach { role ->
                get(role)?.let { put(role, it) }
            }
        }
    }

    fun isComplete(): Boolean {
        return get(CameraRole.BACK_MAIN) != null &&
            get(CameraRole.BACK_REDUCED) != null &&
            get(CameraRole.FRONT) != null
    }

    /**
     * Garante exclusividade: um cameraId não fica marcado em dois papéis ao mesmo tempo.
     */
    fun setExclusive(role: CameraRole, cameraId: String?) {
        if (cameraId.isNullOrBlank()) {
            set(role, null)
            return
        }
        CameraRole.entries.forEach { r ->
            if (r != role && get(r) == cameraId) {
                set(r, null)
            }
        }
        set(role, cameraId)
    }

    private fun keyFor(role: CameraRole): String = "camera_role_${role.name.lowercase()}"

    companion object {
        private const val PREFS_NAME = "netcam_camera_calibration"
    }
}

