package dev.buzzverse.buzzapp.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.buzzverse.buzzapp.ui.screens.Screens
import dev.buzzverse.buzzapp.ui.screens.all_devices.AllDevicesScreen

@Composable
fun Navigation(
    navController: NavHostController
) {
    NavHost(navController = navController, startDestination = Screens.AllDevicesScreen.route) {
        composable(route = Screens.AllDevicesScreen.route) {
            AllDevicesScreen()
        }
        composable(route = Screens.BluetoothDevicesScreen.route) {
            AllDevicesScreen()
        }
        composable(route = Screens.DeviceScreen.route) {
            AllDevicesScreen()
        }
    }
}