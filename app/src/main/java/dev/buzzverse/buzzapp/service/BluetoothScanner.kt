package dev.buzzverse.buzzapp.service

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buzzverse.buzzapp.model.DiscoveredPeripheral
import dev.buzzverse.buzzapp.model.SensorData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@SuppressLint("MissingPermission")
@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val app: Application
) : ViewModel() {

    private val bluetoothManager: BluetoothManager =
        app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private val _discoveredPeripherals = MutableStateFlow<List<DiscoveredPeripheral>>(emptyList())
    val discoveredPeripherals: StateFlow<List<DiscoveredPeripheral>> = _discoveredPeripherals.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectedPeripheral = MutableStateFlow<DiscoveredPeripheral?>(null)
    val connectedPeripheral: StateFlow<DiscoveredPeripheral?> = _connectedPeripheral.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val internalScanActive = AtomicBoolean(false)

    private var connectedGatt: BluetoothGatt? = null

    companion object {
        private const val TAG = "BluetoothViewModel"
        val SENSOR_CHARACTERISTIC_UUID = UUID.fromString("0000BEEF-0000-1000-8000-00805F9B34FB")
        val LOCATION_CHARACTERISTIC_UUID = UUID.fromString("0000CAFE-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned off")
                        stopScanInternally("Bluetooth turned off in receiver")
                        _discoveredPeripherals.value = emptyList()
                        cleanupConnection("Bluetooth turned off")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned on in receiver")
                        initializeBluetoothComponents(forceReinitializeReceiver = false)
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> Log.d(TAG, "Bluetooth turning off")
                    BluetoothAdapter.STATE_TURNING_ON -> Log.d(TAG, "Bluetooth turning on")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            app.unregisterReceiver(bluetoothStateReceiver)
            Log.d(TAG, "BluetoothStateReceiver unregistered in onCleared.")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver in onCleared: ${e.message}")
        }
        stopScanInternally("ViewModel cleared")
        disconnectFromDevice()
    }

    @SuppressLint("MissingPermission")
    fun initializeBluetoothComponents(forceReinitializeReceiver: Boolean = true) {
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device.")
            return
        }

        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "Failed to get BluetoothLeScanner even though adapter is enabled.")
            }
        } else {
            Log.w(TAG, "Bluetooth is not enabled. Scanner not initialized yet. Waiting for STATE_ON.")
            bluetoothLeScanner = null
        }
        Log.i(TAG, "Bluetooth components initialized. Adapter enabled: ${bluetoothAdapter?.isEnabled}, Scanner: ${if(bluetoothLeScanner != null) "Available" else "Not Available"}")

        if (forceReinitializeReceiver) {
            try {
                app.unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver during reinitialization: ${e.message}")
            }
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(bluetoothStateReceiver, filter)
            }
            Log.i(TAG, "BluetoothStateReceiver registered.")
        }
    }

    fun startScan() {
        Log.i(TAG, "Attempting to start scan...")

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth adapter not initialized. Trying to initialize components first.")
            initializeBluetoothComponents()
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Scan aborted: Bluetooth adapter could not be initialized.")
                return
            }
        }
        if (bluetoothAdapter?.isEnabled == true && bluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth adapter enabled but scanner is null. Re-initializing components.")
            initializeBluetoothComponents(forceReinitializeReceiver = false)
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "Scan aborted: BluetoothLeScanner could not be obtained.")
                return
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled(app)) {
            Log.e(TAG, "Scan aborted (Pre-Android 12): Location services are disabled. BLE scan needs location.")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Scan aborted: Bluetooth is not enabled.")
            return
        }
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "Scan aborted: BluetoothLeScanner not available.")
            return
        }

        if (_isConnecting.value || connectedGatt != null) {
            Log.w(TAG, "Scan not started: either connecting or already connected to a device.")
            return
        }

        if (internalScanActive.getAndSet(true)) {
            Log.w(TAG, "Scan already in progress. Ignoring startScan request.")
            return
        }

        _isScanning.value = true
        _discoveredPeripherals.value = emptyList()
        Log.d(TAG, "Cleared discovered peripherals list.")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

        try {
            Log.i(TAG, "Calling bluetoothLeScanner.startScan with settings: $scanSettings")
            bluetoothLeScanner?.startScan(null, scanSettings, leScanCallback)
            Log.i(TAG, "Scan successfully initiated via system call.")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on startScan system call: ${e.message}")
            internalScanActive.set(false)
            _isScanning.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Exception on startScan system call: ${e.message}")
            internalScanActive.set(false)
            _isScanning.value = false
        }
    }

    fun stopScan() {
        stopScanInternally("UI requested stopScan")
    }

    private fun stopScanInternally(reason: String) {
        Log.i(TAG, "Attempting to stop scan internally. Reason: $reason. Current internalScanActive: ${internalScanActive.get()}")
        if (!internalScanActive.getAndSet(false)) {
            if (_isScanning.value) _isScanning.value = false
            return
        }
        _isScanning.value = false

        if (bluetoothAdapter?.isEnabled != true || bluetoothLeScanner == null) {
            Log.w(TAG, "Cannot effectively stop scan: Bluetooth not enabled or scanner not available. Flags reset.")
            return
        }
        try {
            Log.i(TAG, "Calling bluetoothLeScanner.stopScan...")
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.i(TAG, "Scan successfully stopped via system call for reason: $reason.")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on stopScan system call: ${e.message} for reason: $reason")
        } catch (e: Exception) {
            Log.e(TAG, "Exception on stopScan system call: ${e.message} for reason: $reason")
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (!internalScanActive.get() && !_isScanning.value){
                Log.w(TAG, "onScanResult: Scan result received but scan is marked as not active. Ignoring. Device: ${result.device.address}")
                return
            }

            val device = result.device
            val deviceName = try { device.name } catch (e: SecurityException) { null }
            val deviceAddress = try { device.address } catch (e: SecurityException) { null }

            if (deviceAddress == null) {
                Log.w(TAG, "onScanResult: Discovered device with null address. Ignoring.")
                return
            }
            Log.d(TAG, "onScanResult: Device: ${deviceName ?: "N/A"} (${deviceAddress}), RSSI: ${result.rssi}, Data: ${formatAdvertisementData(result.scanRecord?.bytes)}")

            val advertisedDataString = formatAdvertisementData(result.scanRecord?.bytes)
            _discoveredPeripherals.update { currentList ->
                val existing = currentList.find { it.device.address == deviceAddress }
                if (existing == null) {
                    currentList + DiscoveredPeripheral(device, result.rssi, advertisedDataString)
                } else {
                    currentList.map {
                        if (it.device.address == deviceAddress) it.copy(rssi = result.rssi, advertisedDataString = advertisedDataString)
                        else it
                    }
                }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults: Received ${results.size} results.")
            if (!internalScanActive.get() && !_isScanning.value){
                Log.w(TAG, "onBatchScanResults: Batch results received but scan is marked as not active. Ignoring.")
                return
            }
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "onScanFailed: Error Code: $errorCode")
            internalScanActive.set(false)
            _isScanning.value = false
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> Log.e(TAG, "Scan Error: SCAN_FAILED_ALREADY_STARTED")
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(TAG, "Scan Error: SCAN_FAILED_APPLICATION_REGISTRATION_FAILED")
                SCAN_FAILED_INTERNAL_ERROR -> Log.e(TAG, "Scan Error: SCAN_FAILED_INTERNAL_ERROR")
                SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "Scan Error: SCAN_FAILED_FEATURE_UNSUPPORTED")
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> Log.e(TAG, "Scan Error: SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> Log.e(TAG, "Scan Error: SCANNING_TOO_FREQUENTLY (Error Code 6)")
                else -> Log.e(TAG, "Unknown scan error code: $errorCode")
            }
        }
    }

    private fun formatAdvertisementData(bytes: ByteArray?): String { return bytes?.joinToString(separator = "") { "%02X".format(it) } ?: "N/A" }

    fun connectToDevice(peripheral: DiscoveredPeripheral) {
        Log.i(TAG, "Attempting to connect to ${peripheral.device.address}")
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth adapter not initialized. Trying to initialize components first.")
            initializeBluetoothComponents()
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Connect aborted: Bluetooth adapter could not be initialized.")
                return
            }
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Connect aborted: Bluetooth is not enabled.")
            return
        }
        if (_isConnecting.value || connectedGatt != null) {
            Log.w(TAG, "Connect aborted: Already connecting or connected to a device: ${connectedGatt?.device?.address ?: _connectedPeripheral.value?.device?.address}")
            return
        }

        stopScanInternally("Starting connection to ${peripheral.device.address}")
        _isConnecting.value = true
        _connectedPeripheral.value = peripheral

        Log.d(TAG, "GATT Connect to: ${try {peripheral.device.name} catch(e: SecurityException) {"N/A"}} (${peripheral.device.address})")
        val deviceToConnect = bluetoothAdapter?.getRemoteDevice(peripheral.device.address)
        connectedGatt =
            deviceToConnect?.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        if (connectedGatt == null) {
            Log.e(TAG, "connectGatt system call returned null for ${peripheral.device.address}")
            cleanupConnection("connectGatt returned null")
        } else {
            Log.i(TAG, "connectGatt system call successful for ${peripheral.device.address}. Waiting for onConnectionStateChange.")
        }
    }

    fun disconnectFromDevice() {
        val gattToDisconnect = connectedGatt
        if (gattToDisconnect == null) {
            Log.w(TAG, "disconnectFromDevice called but no active GATT connection.")
            if (_connectedPeripheral.value != null) {
                cleanupConnection("Disconnect called, GATT was null but peripheral was set")
            }
            return
        }
        gattToDisconnect.disconnect()
    }

    private fun cleanupConnection(reason: String = "Unknown") {
        Log.i(TAG, "Cleaning up connection. Reason: $reason. Current GATT: ${connectedGatt?.device?.address}, Target: ${_connectedPeripheral.value?.device?.address}")
        val gatt = connectedGatt
        connectedGatt = null
        try {
            gatt?.close()
            Log.d(TAG, "GATT closed for device: ${gatt?.device?.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during gatt.close(): ${e.message}")
        }
        _isConnecting.value = false
        _connectedPeripheral.update {
            it?.copy(
                servicesDiscovered = false,
                sensorData = null,
                isWritePending = false,
                writeSuccess = null
            )
        }
        _connectedPeripheral.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val statusString = when(status) {
                BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
                133 -> "GATT_ERROR_133"
                8 -> "GATT_ERROR_8"
                19 -> "GATT_ERROR_19"
                22 -> "GATT_ERROR_22"
                else -> "OTHER_STATUS_$status"
            }
            val newStateString = when(newState) {
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN_STATE_$newState"
            }
            Log.i(TAG, "onConnectionStateChange: Address: $deviceAddress, Status: $statusString, NewState: $newStateString, Target: ${_connectedPeripheral.value?.device?.address}")

            val currentTargetDeviceAddress = _connectedPeripheral.value?.device?.address
            if (currentTargetDeviceAddress == null && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "Connected to $deviceAddress but no target was set. Disconnecting.")
                gatt.disconnect()
                gatt.close()
                return
            }
            if (currentTargetDeviceAddress != null && currentTargetDeviceAddress != deviceAddress) {
                Log.w(TAG, "onConnectionStateChange for an UNEXPECTED device: $deviceAddress. Current target: $currentTargetDeviceAddress.")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Successfully connected to target $deviceAddress")
                        connectedGatt = gatt
                        _isConnecting.value = false
                        _connectedPeripheral.update { cp -> cp?.copy(rssi = null) }
                        viewModelScope.launch {
                            delay(600)
                            Log.d(TAG, "Discovering services for $deviceAddress")
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "Failed to initiate service discovery for $deviceAddress.")
                                cleanupConnection("Service discovery initiation failed")
                                startScan()
                            }
                        }
                    } else {
                        Log.e(TAG, "Connection attempt failed for $deviceAddress with status: $statusString")
                        cleanupConnection("Connection failed (Status: $statusString)")
                        startScan()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from $deviceAddress, status: $statusString")
                    cleanupConnection("Disconnected state (Status: $statusString) for $deviceAddress")
                    startScan()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            Log.i(TAG, "onServicesDiscovered for $deviceAddress. Status: $status. Target: ${_connectedPeripheral.value?.device?.address}") // Added target address
            if (deviceAddress != _connectedPeripheral.value?.device?.address) {
                Log.w(TAG, "onServicesDiscovered for non-target device $deviceAddress. Ignoring.")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered successfully for $deviceAddress. Services count: ${gatt.services.size}")
                var foundSensorChar: BluetoothGattCharacteristic? = null
                var foundLocationChar: BluetoothGattCharacteristic? = null

                gatt.services.forEach { service ->
                    Log.d(TAG, "Service UUID: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        if (SENSOR_CHARACTERISTIC_UUID != null && characteristic.uuid == SENSOR_CHARACTERISTIC_UUID) {
                            Log.i(TAG, ">>> MATCH FOUND for Sensor Characteristic: ${characteristic.uuid}")
                            foundSensorChar = characteristic
                        }
                        if (LOCATION_CHARACTERISTIC_UUID != null && characteristic.uuid == LOCATION_CHARACTERISTIC_UUID) {
                            Log.i(TAG, ">>> MATCH FOUND for Location Characteristic: ${characteristic.uuid}")
                            foundLocationChar = characteristic
                        }
                    }
                }

                if (foundSensorChar == null) {
                    Log.w(TAG, "SENSOR_CHARACTERISTIC_UUID (${SENSOR_CHARACTERISTIC_UUID}) was NOT FOUND on device $deviceAddress")
                }
                if (foundLocationChar == null) {
                    Log.w(TAG, "LOCATION_CHARACTERISTIC_UUID (${LOCATION_CHARACTERISTIC_UUID}) was NOT FOUND on device $deviceAddress")
                }

                _connectedPeripheral.update { current ->
                    current?.copy(
                        servicesDiscovered   = true
                    )
                }
                readSensorData()
            } else {
                Log.e(TAG, "Service discovery failed for $deviceAddress with status: $status")
                cleanupConnection("Service discovery failed (Status: $status)")
                startScan()
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        private fun handleRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    val sensorData = SensorData.parseFrom(value)
                    _connectedPeripheral.update { it?.copy(sensorData = sensorData) }
                    Log.i(TAG, "SensorData parsed: $sensorData")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse SensorData", e)
                }
            } else {
                Log.e(TAG, "Read failed, status=$status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deviceAddress = gatt.device.address
            Log.d(TAG, "onCharacteristicWrite to $deviceAddress, characteristic ${characteristic.uuid}, status $status")

            _connectedPeripheral.update { cp ->
                cp?.copy(isWritePending = false, writeSuccess = (status == BluetoothGatt.GATT_SUCCESS))
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Successfully wrote to sensor characteristic.")
            } else {
                Log.e(TAG, "Failed to write to sensor characteristic, status: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "onCharacteristicChanged for ${characteristic.uuid}, value: ${value.toHexString()}")
        }
    }

    fun readSensorData() {
        val gatt = connectedGatt ?: run {
            Log.e(TAG, "readSensorData: BluetoothGatt not available.")
            return
        }

        val char = gatt.findSensorCharacteristic() ?: run {
            Log.e(TAG, "readSensorData: Sensor characteristic not found")
            return
        }

        if ((char.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            Log.e(TAG, "readSensorData: Sensor characteristic not readable.")
            return
        }

        val started = gatt.readCharacteristic(char)
        if (started) {
            Log.i(TAG, "readSensorData: Read initiated.")
        } else {
            Log.e(TAG, "readSensorData: readCharacteristic() returned false.")
        }
    }

    fun writeDataToSensorCharacteristic(data: ByteArray) {
        val gatt = connectedGatt
        val char = gatt?.findLocationCharacteristic() ?: run {
            Log.e(TAG, "readSensorData: Sensor characteristic not found")
            return
        }

        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            Log.e(TAG, "writeDataToSensorCharacteristic: Sensor characteristic is not writable.")
            _connectedPeripheral.update { it?.copy(isWritePending = false, writeSuccess = false) }
            return
        }

        _connectedPeripheral.update { it?.copy(isWritePending = true, writeSuccess = null) }

        val success: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(char, data, writeType)
            success = result == BluetoothStatusCodes.SUCCESS
            if (!success) Log.e(TAG, "writeDataToSensorCharacteristic (API 33+) failed to initiate: $result")

        } else {
            char.value = data
            char.writeType = writeType
            success = gatt.writeCharacteristic(char)
            if (!success) Log.e(TAG, "writeDataToSensorCharacteristic (Legacy): Failed to initiate characteristic write.")
        }

        if (success) {
            Log.i(TAG, "writeDataToSensorCharacteristic: Initiated write for sensor characteristic.")
        } else {
            // If initiation failed, immediately update pending state
            _connectedPeripheral.update { it?.copy(isWritePending = false, writeSuccess = false) }
        }
    }

    private fun BluetoothGatt.findSensorCharacteristic(): BluetoothGattCharacteristic? =
        services.firstNotNullOfOrNull { svc -> svc.getCharacteristic(SENSOR_CHARACTERISTIC_UUID) }
    private fun BluetoothGatt.findLocationCharacteristic(): BluetoothGattCharacteristic? =
        services.firstNotNullOfOrNull { svc -> svc.getCharacteristic(LOCATION_CHARACTERISTIC_UUID) }

    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isLocationEnabled
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
}