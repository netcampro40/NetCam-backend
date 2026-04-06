package com.netcam.app.data.camera

import android.content.Context
import com.netcam.app.domain.model.ExperimentalWideOption

class CameraSettingsStore(
    appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getExperimentalWideOption(): ExperimentalWideOption {
        val name = prefs.getString(KEY_EXPERIMENTAL_WIDE, null) ?: return ExperimentalWideOption.OFF
        return runCatching { ExperimentalWideOption.valueOf(name) }.getOrDefault(ExperimentalWideOption.OFF)
    }

    fun setExperimentalWideOption(option: ExperimentalWideOption) {
        prefs.edit().putString(KEY_EXPERIMENTAL_WIDE, option.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "netcam_camera_settings"
        private const val KEY_EXPERIMENTAL_WIDE = "experimental_wide_option"
    }
}

