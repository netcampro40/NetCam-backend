package com.netcam.app.domain.recording

import com.netcam.app.domain.model.RecordingSegmentType
import kotlinx.coroutines.flow.StateFlow

interface ClipController {
    val lastTriggeredRequest: StateFlow<RecordingSegmentType?>

    fun saveLastMinute()

    fun saveLastTwoMinutes()

    fun saveFullSession()
}

