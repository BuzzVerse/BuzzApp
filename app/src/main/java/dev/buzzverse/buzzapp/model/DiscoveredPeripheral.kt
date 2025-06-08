package dev.buzzverse.buzzapp.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic

data class DiscoveredPeripheral(
    val device: BluetoothDevice,
    var rssi: Int?,
    var advertisedDataString: String,
    var scanRecordBytes: ByteArray?,
    var sensorCharacteristic: BluetoothGattCharacteristic? = null,
    var locationCharacteristic: BluetoothGattCharacteristic? = null,
    //var sensorData: SensorData? = null,
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


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DiscoveredPeripheral
        return try {
            this.id == other.id
        } catch (e: SecurityException) {
            device == other.device
        }
    }

    override fun hashCode(): Int {
        return try {
            id.hashCode()
        } catch (e: SecurityException) {
            device.hashCode()
        }
    }

    @SuppressLint("MissingPermission")
    override fun toString(): String {
        val nameToDisplay = try {
            device.name ?: "N/A"
        } catch (e: SecurityException) {
            "N/A (Permission Denied)"
        }
        val addressToDisplay = try {
            device.address ?: "N/A"
        } catch (e: SecurityException) {
            "N/A (Permission Denied)"
        }
        return "DiscoveredPeripheral(name=$nameToDisplay, address=$addressToDisplay, rssi=$rssi)"
    }
}