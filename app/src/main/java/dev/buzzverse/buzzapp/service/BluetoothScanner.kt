package dev.buzzverse.buzzapp.service // Your package

// import dev.buzzverse.buzzapp.model.SensorData // Assuming you have this
// import dev.buzzverse.buzzapp.model.parseSensorData // Assuming you have this
import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buzzverse.buzzapp.model.DiscoveredPeripheral
import kotlinx.coroutines.Job
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
class BluetoothViewModel @Inject constructor(private val app: Application) : AndroidViewModel(app) {

    private val bluetoothManager: BluetoothManager =
        app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

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
    private var sensorUpdateJob: Job? = null

    companion object {
        private const val TAG = "BluetoothViewModel"
        fun partialToFullUuid(shortUuid: String): UUID { /* ... */ return UUID.fromString("0000$shortUuid-0000-1000-8000-00805F9B34FB") }
        val SENSOR_SERVICE_UUID: ParcelUuid? = try { ParcelUuid(partialToFullUuid("FACE")) } catch (e:IllegalArgumentException) { null }
        val SENSOR_CHARACTERISTIC_UUID: UUID? = try { partialToFullUuid("BEEF") } catch (e:IllegalArgumentException) { null }
        val LOCATION_CHARACTERISTIC_UUID: UUID? = try { partialToFullUuid("CAFE") } catch (e:IllegalArgumentException) { null }
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
                        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner // Re-initialize
                        startScan()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> Log.d(TAG, "Bluetooth turning off")
                    BluetoothAdapter.STATE_TURNING_ON -> Log.d(TAG, "Bluetooth turning on")
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        app.registerReceiver(bluetoothStateReceiver, filter)
        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        }
        Log.d(TAG, "BluetoothViewModel initialized. BT Adapter enabled: ${bluetoothAdapter?.isEnabled}")
    }

    override fun onCleared() {
        super.onCleared()
        try {
            app.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
        stopScanInternally("ViewModel cleared")
        disconnectFromDevice()
        sensorUpdateJob?.cancel()
    }

    fun startScan() {
        Log.i(TAG, "Attempting to start scan...")
        if (!hasRequiredBluetoothPermissions()) {
            Log.e(TAG, "Scan aborted: Required Bluetooth permissions are missing.")
            // Consider emitting a state to UI to inform user about missing permissions
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled(app)) {
            Log.e(TAG, "Scan aborted (Pre-Android 12): Location services are disabled. BLE scan needs location.")
            // Consider emitting a state to UI to inform user
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Scan aborted: Bluetooth is not enabled.")
            return
        }
        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "Scan aborted: BluetoothLeScanner not available.")
                return
            }
        }

        if (_isConnecting.value || connectedGatt != null) {
            Log.w(TAG, "Scan not started: either connecting or already connected to a device.")
            return
        }

        if (internalScanActive.getAndSet(true)) { // Atomically set to true and get previous value
            Log.w(TAG, "Scan already in progress (internal flag was true). Ignoring startScan request.")
            return // Already scanning or previous attempt to set flag was successful
        }

        _isScanning.value = true // Update public state
        _discoveredPeripherals.value = emptyList()
        Log.d(TAG, "Cleared discovered peripherals list.")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

        val filters: MutableList<ScanFilter> = ArrayList()
        // To scan for everything: pass null or empty list
        // Example filter: SENSOR_SERVICE_UUID?.let { filters.add(ScanFilter.Builder().setServiceUuid(it).build()) }

        try {
            Log.i(TAG, "Calling bluetoothLeScanner.startScan with settings: $scanSettings, filters: ${if(filters.isEmpty()) "None" else filters.size}")
            //bluetoothLeScanner?.startScan(if (filters.isEmpty()) null else filters, scanSettings, leScanCallback)
            bluetoothLeScanner?.startScan(leScanCallback)
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

    // Public stopScan for UI interaction
    fun stopScan() {
        stopScanInternally("UI requested stopScan")
    }

    // Internal stopScan to manage state
    private fun stopScanInternally(reason: String) {
        Log.i(TAG, "Attempting to stop scan internally. Reason: $reason. Current internalScanActive: ${internalScanActive.get()}")
        if (!internalScanActive.getAndSet(false)) { // Atomically set to false and get previous value
            Log.d(TAG, "Scan was not active (internal flag was false). Ignoring stopScanInternally call for: $reason.")
            // Ensure public state is also false if internal was already false
            if (_isScanning.value) _isScanning.value = false
            return
        }

        _isScanning.value = false // Update public state

        if (bluetoothAdapter?.isEnabled != true || bluetoothLeScanner == null) {
            Log.w(TAG, "Cannot effectively stop scan: Bluetooth not enabled or scanner not available. Flags reset.")
            return
        }

        try {
            // It's generally safe to call stopScan even if the system isn't actively scanning from this app's perspective.
            // Permissions are checked before starting, so stopping should be fine.
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
            if (!internalScanActive.get() && _isScanning.value) {
                Log.w(TAG, "onScanResult: internalScanActive is false but _isScanning is true. Inconsistency.")
                // This state implies scan might have been stopped externally or failed without callback
            } else if (!internalScanActive.get() && !_isScanning.value){
                Log.w(TAG, "onScanResult: Scan result received but scan is marked as not active. Ignoring. Device: ${result.device.address}")
                return // If scan is not supposed to be active, ignore late results
            }


            val device = result.device
            val deviceName = try { device.name } catch (e: SecurityException) { null } // Handle potential permission issue here too
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
                    currentList + DiscoveredPeripheral(
                        device = device,
                        rssi = result.rssi,
                        advertisedDataString = advertisedDataString,
                        scanRecordBytes = result.scanRecord?.bytes
                    )
                } else {
                    currentList.map {
                        if (it.device.address == deviceAddress) {
                            it.copy(rssi = result.rssi, advertisedDataString = advertisedDataString, scanRecordBytes = result.scanRecord?.bytes)
                        } else {
                            it
                        }
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
            // Ensure flags are reset as scan is no longer active
            internalScanActive.set(false)
            _isScanning.value = false

            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> Log.e(TAG, "Scan Error: SCAN_FAILED_ALREADY_STARTED (1) - Indicates an issue with scan state management.")
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(TAG, "Scan Error: SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2) - App cannot be registered with BT stack.")
                SCAN_FAILED_INTERNAL_ERROR -> Log.e(TAG, "Scan Error: SCAN_FAILED_INTERNAL_ERROR (3) - Internal BT stack error.")
                SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "Scan Error: SCAN_FAILED_FEATURE_UNSUPPORTED (4) - BLE Scan not supported on this device.")
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> Log.e(TAG, "Scan Error: SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (5) - No hardware resources for scanning.")
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Constant is API 30
                    Log.e(TAG, "Scan Error: SCANNING_TOO_FREQUENTLY (6) - App is scanning too often (Android 11+).")
                } else {
                    Log.e(TAG, "Scan Error: Error Code 6 (Likely SCANNING_TOO_FREQUENTLY on older APIs).")
                }
                else -> Log.e(TAG, "Unknown scan error code: $errorCode")
            }
        }
    }

    private fun formatAdvertisementData(bytes: ByteArray?): String { /* ... */ return bytes?.joinToString(separator = "") { "%02X".format(it) } ?: "N/A" }

    fun connectToDevice(peripheral: DiscoveredPeripheral) {
        Log.i(TAG, "Attempting to connect to ${peripheral.device.address}")
        if (!hasRequiredBluetoothPermissions(isConnectOnly = true)) {
            Log.e(TAG, "Connect aborted: Required Bluetooth connect permissions missing.")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Connect aborted: Bluetooth is not enabled.")
            return
        }
        if (_isConnecting.value || connectedGatt != null) {
            Log.w(TAG, "Connect aborted: Already connecting or connected to a device: ${connectedGatt?.device?.address ?: _connectedPeripheral.value?.device?.address}")
            return
        }

        stopScanInternally("Starting connection to ${peripheral.device.address}") // Stop scan before connecting

        _isConnecting.value = true
        _connectedPeripheral.value = peripheral

        Log.d(TAG, "GATT Connect to: ${peripheral.device.name} (${peripheral.device.address})")
        val deviceToConnect = bluetoothAdapter.getRemoteDevice(peripheral.device.address) // Get fresh device instance
        connectedGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceToConnect.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            deviceToConnect.connectGatt(app, false, gattCallback)
        }
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
            if (_connectedPeripheral.value != null) { // If we think we are connected but GATT is null
                cleanupConnection("Disconnect called, GATT was null but peripheral was set")
            }
            return
        }
        Log.i(TAG, "Initiating disconnect from ${gattToDisconnect.device.address}")
        if (!hasRequiredBluetoothPermissions(isConnectOnly = true)) {
            Log.w(TAG, "Attempting disconnect without full connect permissions for ${gattToDisconnect.device?.address}.")
            // Proceed with disconnect attempt anyway
        }
        gattToDisconnect.disconnect()
    }

    private fun cleanupConnection(reason: String = "Unknown") {
        Log.i(TAG, "Cleaning up connection. Reason: $reason. Current GATT: ${connectedGatt?.device?.address}, Target: ${_connectedPeripheral.value?.device?.address}")
        stopUpdatingSensorData()

        val gatt = connectedGatt
        connectedGatt = null
        try {
            gatt?.close()
            Log.d(TAG, "GATT closed for device: ${gatt?.device?.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during gatt.close(): ${e.message}")
        }

        _isConnecting.value = false
        _connectedPeripheral.value = null // Clear the connected peripheral info
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            Log.i(TAG, "onConnectionStateChange: Address: $deviceAddress, Status: $status (GATT_${
                when(status) {
                    BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> "READ_NOT_PERMITTED"
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "WRITE_NOT_PERMITTED"
                    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFFICIENT_AUTHENTICATION"
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "REQUEST_NOT_SUPPORTED"
                    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFFICIENT_ENCRYPTION"
                    BluetoothGatt.GATT_INVALID_OFFSET -> "INVALID_OFFSET"
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "INVALID_ATTRIBUTE_LENGTH"
                    BluetoothGatt.GATT_CONNECTION_CONGESTED -> "CONNECTION_CONGESTED"
                    BluetoothGatt.GATT_FAILURE -> "FAILURE"
                    133 -> "GATT_ERROR_133 (Often a generic error or device out of range)"
                    8 -> "GATT_ERROR_8 (Often connection timeout or device disconnected itself)"
                    19 -> "GATT_ERROR_19 (Often device disconnected itself after connection)"
                    22 -> "GATT_ERROR_22 (Often LMP response timeout)"
                    else -> "UNKNOWN_STATUS_$status"
                }
            }), NewState: ${
                when(newState) {
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> "UNKNOWN_STATE_$newState"
                }
            }, Target: ${_connectedPeripheral.value?.device?.address}")

            val currentTargetDeviceAddress = _connectedPeripheral.value?.device?.address

            if (currentTargetDeviceAddress == null && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "Connected to $deviceAddress but no target was set. Disconnecting.")
                gatt.disconnect()
                gatt.close()
                return
            }

            if (currentTargetDeviceAddress != null && currentTargetDeviceAddress != deviceAddress) {
                Log.w(TAG, "onConnectionStateChange for an UNEXPECTED device: $deviceAddress. Current target: $currentTargetDeviceAddress.")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.w(TAG, "Unexpected device $deviceAddress connected. Disconnecting it.")
                    gatt.disconnect()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Unexpected device $deviceAddress disconnected. Closing its GATT.")
                    gatt.close()
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Successfully connected to target $deviceAddress")
                        connectedGatt = gatt
                        _isConnecting.value = false
                        _connectedPeripheral.update { cp: DiscoveredPeripheral? -> cp?.copy(rssi = null) }

                        viewModelScope.launch {
                            delay(600) // Delay for service discovery, common practice
                            Log.d(TAG, "Discovering services for $deviceAddress")
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "Failed to initiate service discovery for $deviceAddress.")
                                cleanupConnection("Service discovery initiation failed")
                                startScan() // Try scanning again
                            }
                        }
                    } else {
                        Log.e(TAG, "Connection attempt failed for $deviceAddress with status: $status")
                        cleanupConnection("Connection failed (Status: $status)")
                        startScan()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from $deviceAddress, status: $status")
                    cleanupConnection("Disconnected state (Status: $status) for $deviceAddress")
                    startScan() // Restart scan after disconnection
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            if (deviceAddress != _connectedPeripheral.value?.device?.address) {
                Log.w(TAG, "onServicesDiscovered for non-target device $deviceAddress. Ignoring.")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for $deviceAddress. Services count: ${gatt.services.size}")
                var foundSensorChar: BluetoothGattCharacteristic? = null
                var foundLocationChar: BluetoothGattCharacteristic? = null

                gatt.services.forEach { service ->
                    Log.d(TAG, "Service UUID: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        Log.d(TAG, "  Characteristic UUID: ${characteristic.uuid}, Properties: ${characteristic.properties}")
                        if (SENSOR_CHARACTERISTIC_UUID != null && characteristic.uuid == SENSOR_CHARACTERISTIC_UUID) {
                            Log.i(TAG, "Found Sensor Characteristic: ${characteristic.uuid}")
                            foundSensorChar = characteristic
                        }
                        if (LOCATION_CHARACTERISTIC_UUID != null && characteristic.uuid == LOCATION_CHARACTERISTIC_UUID) {
                            Log.i(TAG, "Found Location Characteristic: ${characteristic.uuid}")
                            foundLocationChar = characteristic
                        }
                    }
                }
                _connectedPeripheral.update { current: DiscoveredPeripheral? -> /* ... */
                    current?.copy(
                        sensorCharacteristic = foundSensorChar ?: current.sensorCharacteristic,
                        locationCharacteristic = foundLocationChar ?: current.locationCharacteristic
                    )
                }
                if (foundSensorChar != null) startUpdatingSensorData()

            } else {
                Log.e(TAG, "Service discovery failed for $deviceAddress with status: $status")
                cleanupConnection("Service discovery failed (Status: $status)")
                startScan()
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) { /* ... */ }
        @Deprecated("Use onCharacteristicRead with ByteArray value instead")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { /* ... */ }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { /* ... */ }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { /* ... */ }
        @Deprecated("Use onCharacteristicChanged with ByteArray value instead")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { /* ... */ }
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) { /* ... */ }
    }

    fun readSensorData() { /* ... */ }
    fun startUpdatingSensorData() { /* ... */ }
    fun stopUpdatingSensorData() { /* ... */ }
    fun writeDataToSensorCharacteristic(data: ByteArray) { /* ... */ }

    private fun hasRequiredBluetoothPermissions(isScanOnly: Boolean = false, isConnectOnly: Boolean = false): Boolean {
        val context = getApplication<Application>().applicationContext
        val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permissions check (Android S+): Scan=$hasScan, Connect=$hasConnect, FineLocation=$hasFineLocation")
            return if (isScanOnly) hasScan && hasFineLocation /*Scan still needs location for some scenarios/devices*/ else if (isConnectOnly) hasConnect else hasScan && hasConnect && hasFineLocation
        } else {
            val hasAdmin = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            val hasBluetooth = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permissions check (Pre-S): Admin=$hasAdmin, Bluetooth=$hasBluetooth, FineLocation=$hasFineLocation")
            return if (isScanOnly) hasAdmin && hasBluetooth && hasFineLocation else if (isConnectOnly) hasAdmin && hasBluetooth else hasAdmin && hasBluetooth && hasFineLocation
        }
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isLocationEnabled
    }
}