package dev.buzzverse.buzzapp.ui.screens

sealed class Screens(val route: String, var title: String) {
    object BluetoothDevicesScreen : Screens("bluetooth_devices", "BT Devices")
    object AllDevicesScreen : Screens("all_devices", "All Devices")
    object DeviceScreen : Screens("device/{deviceId}", "Device")

    companion object {
        fun fromRoute(route: String?): Screens? {
            return when (route) {
                BluetoothDevicesScreen.route -> BluetoothDevicesScreen
                AllDevicesScreen.route -> AllDevicesScreen
                DeviceScreen.route -> DeviceScreen
                else -> null
            }
        }
    }
}