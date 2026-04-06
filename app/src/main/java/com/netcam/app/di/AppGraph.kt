package com.netcam.app.di

import com.netcam.app.BuildConfig
import com.netcam.app.data.camera.FakeNetCamCameraController
import com.netcam.app.data.ble.BleDebugController
import com.netcam.app.data.camera.Camera2LensCatalogProvider
import com.netcam.app.data.auth.DataStoreCameraAccessRepository
import com.netcam.app.data.auth.QrAuthApi
import com.netcam.app.data.recording.CameraXContinuousRecordingController
import com.netcam.app.data.recording.DefaultClipController
import com.netcam.app.data.recording.MediaMuxerVideoSegmentExtractor
import com.netcam.app.data.recording.RealRecordingSegmentController
import com.netcam.app.ui.components.ClipSavedSoundPlayer
import com.netcam.app.data.session.FakeNetCamSessionController
import com.netcam.app.data.storage.FileSegmentGalleryPersistence
import com.netcam.app.data.storage.MediaStoreVideoGallerySaver
import com.netcam.app.data.storage.MediaStoreGalleryUriValidator
import com.netcam.app.data.storage.FakeNetCamStorage
import com.netcam.app.domain.camera.NetCamCameraController
import com.netcam.app.domain.camera.LensCatalogProvider
import com.netcam.app.domain.input.VolumeButtonGestureInterpreter
import com.netcam.app.domain.recording.ClipController
import com.netcam.app.domain.recording.ContinuousRecordingController
import com.netcam.app.domain.recording.RecordingSegmentController
import com.netcam.app.domain.session.NetCamSessionController
import com.netcam.app.domain.storage.NetCamStorage
import com.netcam.app.domain.auth.CameraAccessRepository

object AppGraph {
    val cameraController: NetCamCameraController by lazy { FakeNetCamCameraController() }
    val sessionController: NetCamSessionController by lazy { FakeNetCamSessionController() }
    val lensCatalogProvider: LensCatalogProvider by lazy {
        val appContext = NetCamAppHolder.appContext
            ?: error("NetCamAppHolder.appContext não inicializado")
        Camera2LensCatalogProvider(appContext)
    }
    val continuousRecordingController: ContinuousRecordingController by lazy {
        val appContext = NetCamAppHolder.appContext
            ?: error("NetCamAppHolder.appContext não inicializado")
        CameraXContinuousRecordingController(appContext)
    }
    val recordingSegmentController: RecordingSegmentController by lazy {
        val appContext = NetCamAppHolder.appContext
            ?: error("NetCamAppHolder.appContext não inicializado")
        val extractor = MediaMuxerVideoSegmentExtractor(appContext)
        val gallerySaver = MediaStoreVideoGallerySaver(appContext)
        val galleryPersistence = FileSegmentGalleryPersistence(appContext)
        val galleryUriValidator = MediaStoreGalleryUriValidator(appContext)
        RealRecordingSegmentController(
            videoSegmentExtractor = extractor,
            videoGallerySaver = gallerySaver,
            galleryPersistence = galleryPersistence,
            galleryUriValidator = galleryUriValidator,
        )
    }
    val storage: NetCamStorage by lazy { FakeNetCamStorage() }
    val cameraAccessRepository: CameraAccessRepository by lazy {
        val appContext = NetCamAppHolder.appContext
            ?: error("NetCamAppHolder.appContext não inicializado")
        DataStoreCameraAccessRepository(appContext)
    }
    val qrAuthApi: QrAuthApi by lazy {
        QrAuthApi(baseUrl = BuildConfig.NETCAM_API_BASE_URL)
    }

    val volumeButtonGestureInterpreter: VolumeButtonGestureInterpreter by lazy {
        VolumeButtonGestureInterpreter(
            sessionController = sessionController,
            clipController = clipController,
        )
    }

    val bleDebugController: BleDebugController by lazy {
        val appContext = NetCamAppHolder.appContext
            ?: error("NetCamAppHolder.appContext não inicializado")
        BleDebugController(appContext)
    }

    val clipController: ClipController by lazy {
        DefaultClipController(
            sessionController = sessionController,
            recordingSegmentController = recordingSegmentController,
            onClipActionAccepted = { ClipSavedSoundPlayer.playImmediateClipAckSound() },
        )
    }
}

