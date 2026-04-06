package com.netcam.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.netcam.app.di.AppGraph
import com.netcam.app.di.NetCamAppHolder
import com.netcam.app.ui.theme.NetCamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetCamAppHolder.appContext = application
        enableEdgeToEdge()
        setContent {
            NetCamTheme {
                NetCamApp()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumedByNetCam =
            AppGraph
                .volumeButtonGestureInterpreter
                .handleKeyEvent(event)

        return if (consumedByNetCam) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }
}