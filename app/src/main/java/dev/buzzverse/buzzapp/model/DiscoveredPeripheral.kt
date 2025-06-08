package dev.buzzverse.buzzapp.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

data class DiscoveredPeripheral(
    val device: BluetoothDevice,
    var rssi: Int?,
    var advertisedDataString: String,
    var servicesDiscovered: Boolean = false,
    var sensorData: SensorData? = null,
    var isWritePending: Boolean = false,
    var writeSuccess: Boolean? = null
) {

    val displayName: String
        @SuppressLint("MissingPermission")
        get() {
            return try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                "Unknown (Permission Denied)"
            }
        }

    val id: String
        @SuppressLint("MissingPermission")
        get() {
            return try {
                device.address
            } catch (e: SecurityException) {
                "No Address (Permission Denied)"
            }
        }
}