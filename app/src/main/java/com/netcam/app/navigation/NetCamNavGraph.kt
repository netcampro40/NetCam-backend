package com.netcam.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.netcam.app.di.AppGraph
import com.netcam.app.ui.screens.access.AccessValidationRoute
import com.netcam.app.ui.screens.access.AccessValidationViewModel
import com.netcam.app.ui.screens.ble.BleDebugRoute
import com.netcam.app.ui.screens.camera.CameraDiagnosticsRoute
import com.netcam.app.ui.screens.camera.CameraIdleRoute
import com.netcam.app.ui.screens.gallery.GalleryRoute
import com.netcam.app.ui.screens.home.HomeRoute
import com.netcam.app.ui.screens.volume.VolumePlusControlRoute
import kotlinx.coroutines.launch

@Composable
fun NetCamNavGraph(
    contentPadding: PaddingValues,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val bleStatusState = AppGraph.bleDebugController.uiState.collectAsState()
    val controlStatusText =
        when (bleStatusState.value.status) {
            com.netcam.app.data.ble.BleDebugStatus.CONNECTED -> "Controle conectado"
            else -> "Controle não pareado"
        }
    val accessValidationViewModel =
        remember {
            AccessValidationViewModel(
                cameraAccessRepository = AppGraph.cameraAccessRepository,
                qrAuthApi = AppGraph.qrAuthApi,
            )
        }

    NavHost(
        navController = navController,
        startDestination = NetCamRoute.Home.route,
        modifier = modifier,
    ) {
        composable(NetCamRoute.Home.route) {
            HomeRoute(
                contentPadding = contentPadding,
                onFilmClick = {
                    scope.launch {
                        val authorized = AppGraph.cameraAccessRepository.isAuthorizedNow()
                        if (authorized) {
                            navController.navigate(NetCamRoute.Camera.route)
                        } else {
                            navController.navigate(NetCamRoute.AccessValidation.route)
                        }
                    }
                },
                onGalleryClick = { navController.navigate(NetCamRoute.Gallery.route) },
                onPairControlClick = { navController.navigate(NetCamRoute.BleDebug.route) },
                controlStatusText = controlStatusText,
                onDebugCameraBypass = {
                    navController.navigate(NetCamRoute.Camera.route)
                },
            )
        }
        composable(NetCamRoute.AccessValidation.route) {
            AccessValidationRoute(
                contentPadding = contentPadding,
                viewModel = accessValidationViewModel,
                onAuthorized = {
                    navController.navigate(NetCamRoute.Camera.route) {
                        popUpTo(NetCamRoute.AccessValidation.route) { inclusive = true }
                    }
                },
            )
        }
        composable(NetCamRoute.Camera.route) {
            CameraIdleRoute(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(NetCamRoute.Gallery.route) {
            GalleryRoute(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(NetCamRoute.CameraDiagnostics.route) {
            CameraDiagnosticsRoute(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(NetCamRoute.BleDebug.route) {
            BleDebugRoute(
                contentPadding = contentPadding,
                controller = AppGraph.bleDebugController,
                onBack = { navController.popBackStack() },
            )
        }

        composable(NetCamRoute.VolumePlusControl.route) {
            VolumePlusControlRoute(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
                onOpenCamera = { navController.navigate(NetCamRoute.Camera.route) },
            )
        }
    }
}

