package dev.buzzverse.buzzapp.service

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buzzverse.buzzapp.MainActivity
import dev.buzzverse.buzzapp.R
import dev.buzzverse.buzzapp.model.DiscoveredPeripheral
import dev.buzzverse.buzzapp.model.LocationData
import dev.buzzverse.buzzapp.model.SensorData
import dev.buzzverse.buzzapp.model.SensorSample
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@SuppressLint("MissingPermission")
@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val app: Application,
    private val gpsServiceController: GpsServiceController,
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

    private val _sensorHistory = MutableStateFlow<List<SensorSample>>(emptyList())
    val sensorHistory: StateFlow<List<SensorSample>> = _sensorHistory.asStateFlow()
    private var pollingJob: Job? = null

    private val scanResultBuffer = mutableListOf<ScanResult>()
    private var scanUpdateJob: Job? = null
    private val scanUpdateDebouncePeriodMs = 500L

    companion object {
        private const val TAG = "BluetoothViewModel"
        val SENSOR_CHARACTERISTIC_UUID = UUID.fromString("0000BEEF-0000-1000-8000-00805F9B34FB")
        val LOCATION_CHARACTERISTIC_UUID = UUID.fromString("0000CAFE-0000-1000-8000-00805F9B34FB")
        private const val MAX_SENSOR_HISTORY = 120

        private const val DISCONNECTION_CHANNEL_ID = "bluetooth_disconnection_channel"
        private const val DISCONNECTION_CHANNEL_NAME = "Bluetooth Disconnections"
        private const val DISCONNECTION_NOTIFICATION_ID = 12345
    }

    init {
        createDisconnectionNotificationChannel()
    }

    private fun createDisconnectionNotificationChannel() {
        val channel = NotificationChannel(
            DISCONNECTION_CHANNEL_ID,
            DISCONNECTION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager: NotificationManager =
            app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun showDisconnectionNotification(deviceName: String) {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            app, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(app, DISCONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Połączenie utracone")
            .setContentText("Utracono połączenie z urządzeniem: [nazwa urządzenia]")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            with(NotificationManagerCompat.from(app)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notify(DISCONNECTION_NOTIFICATION_ID, builder.build())
                } else {
                    notify(DISCONNECTION_NOTIFICATION_ID, builder.build())
                }
                Log.d(TAG, "Disconnection notification shown for $deviceName.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while trying to show notification. Check POST_NOTIFICATIONS permission.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception while trying to show notification.", e)
        }
    }


    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.w(TAG, "Bluetooth turned OFF")
                        stopScanInternally("Bluetooth turned off (receiver)")
                        _discoveredPeripherals.value = emptyList()
                        cleanupConnection("Bluetooth turned off (receiver)", notifyUser = true)
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth turned ON (receiver)")
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
        scanUpdateJob?.cancel()
        stopScanInternally("ViewModel cleared")
        disconnectFromDevice(notifyUser = false)
        stopSensorPolling()
        Log.d(TAG, "ViewModel onCleared: All resources released.")
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
        Log.i(TAG, "Bluetooth components initialized. Adapter enabled: ${bluetoothAdapter?.isEnabled}, Scanner: ${if (bluetoothLeScanner != null) "Available" else "Not Available"}")

        if (forceReinitializeReceiver) {
            try {
                app.unregisterReceiver(bluetoothStateReceiver)
                Log.d(TAG, "Unregistered existing BluetoothStateReceiver during reinitialization.")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "BluetoothStateReceiver was not registered, no need to unregister during reinitialization.")
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
        if (!viewModelScope.isActive) {
            Log.w(TAG, "Scan attempt while ViewModel scope is not active. Aborting.")
            return
        }

        if (bluetoothAdapter == null || bluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth adapter/scanner not initialized. Initializing components first.")
            initializeBluetoothComponents()
            if (bluetoothAdapter == null || bluetoothLeScanner == null) {
                Log.e(TAG, "Scan aborted: Bluetooth adapter/scanner could not be initialized.")
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

        if (_isConnecting.value || _connectedPeripheral.value != null) {
            Log.w(TAG, "Scan not started: either connecting or already connected to a device.")
            return
        }

        if (internalScanActive.getAndSet(true)) {
            Log.w(TAG, "Scan already in progress (internalScanActive was true). Ignoring startScan request.")
            return
        }

        _isScanning.value = true
        _discoveredPeripherals.value = emptyList()
        scanUpdateJob?.cancel()
        synchronized(scanResultBuffer) {
            scanResultBuffer.clear()
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()
        try {
            bluetoothLeScanner?.startScan(null, scanSettings, leScanCallback)
            Log.i(TAG, "Scan successfully initiated via system call.")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on startScan system call: ${e.message}")
            internalScanActive.set(false)
            _isScanning.value = false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on startScan: ${e.message}. Check BLUETOOTH_SCAN permission.")
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
        scanUpdateJob?.cancel()
        synchronized(scanResultBuffer) {
            scanResultBuffer.clear()
        }

        if (!internalScanActive.getAndSet(false)) {
            if (_isScanning.value) _isScanning.value = false
            Log.d(TAG, "Scan was not internally active or already stopping. Reason: $reason")
            return
        }
        _isScanning.value = false

        if (bluetoothAdapter?.isEnabled != true || bluetoothLeScanner == null) {
            Log.w(TAG, "Cannot effectively stop scan: Bluetooth not enabled or scanner not available. Flags reset for reason: $reason.")
            return
        }
        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.i(TAG, "Scan successfully stopped via system call for reason: $reason.")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on stopScan system call: ${e.message} for reason: $reason")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on stopScan: ${e.message}. Check BLUETOOTH_SCAN permission.")
        } catch (e: Exception) {
            Log.e(TAG, "Exception on stopScan system call: ${e.message} for reason: $reason")
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!internalScanActive.get() && !_isScanning.value) return

            synchronized(scanResultBuffer) {
                scanResultBuffer.add(result)
            }

            if (scanUpdateJob?.isActive != true) {
                scanUpdateJob = viewModelScope.launch {
                    delay(scanUpdateDebouncePeriodMs)
                    processScanResultBuffer()
                }
            }
        }
        override fun onBatchScanResults(results: List<ScanResult>) {
            if (!internalScanActive.get() && !_isScanning.value) return

            synchronized(scanResultBuffer) {
                scanResultBuffer.addAll(results)
            }
            if (scanUpdateJob?.isActive != true) {
                scanUpdateJob = viewModelScope.launch {
                    delay(scanUpdateDebouncePeriodMs)
                    processScanResultBuffer()
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: Error Code: $errorCode")
            internalScanActive.set(false)
            _isScanning.value = false
            scanUpdateJob?.cancel()
            synchronized(scanResultBuffer) { scanResultBuffer.clear() }

            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                else -> "Unknown scan error code: $errorCode"
            }
            Log.e(TAG, "Scan Error: $errorMsg")
        }
    }

    @SuppressLint("MissingPermission")
    private fun processScanResultBuffer() {
        if (!viewModelScope.isActive) return

        val resultsToProcess: List<ScanResult>
        synchronized(scanResultBuffer) {
            if (scanResultBuffer.isEmpty()) return
            resultsToProcess = ArrayList(scanResultBuffer)
            scanResultBuffer.clear()
        }
        _discoveredPeripherals.update { currentList ->
            val newList = currentList.toMutableList()
            val seenAddresses = currentList.map { it.device.address }.toMutableSet()

            resultsToProcess.forEach { result ->
                val device = result.device
                val deviceAddress = device.address ?: return@forEach

                val existingIndex = newList.indexOfFirst { it.device.address == deviceAddress }

                if (existingIndex != -1) {
                    val existingPeripheral = newList[existingIndex]
                    newList[existingIndex] = existingPeripheral.copy(
                        rssi = result.rssi,
                        advertisedDataString = formatAdvertisementData(result.scanRecord?.bytes)
                    )
                } else {
                    if (deviceAddress !in seenAddresses) {
                        newList.add(
                            DiscoveredPeripheral(
                                device = device,
                                rssi = result.rssi,
                                advertisedDataString = formatAdvertisementData(result.scanRecord?.bytes)
                            )
                        )
                        seenAddresses.add(deviceAddress)
                    }
                }
            }
            newList
        }
    }

    private fun formatAdvertisementData(bytes: ByteArray?): String {
        return bytes?.take(20)?.joinToString(separator = "") { "%02X".format(it) } ?: "N/A"
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(peripheral: DiscoveredPeripheral) {
        Log.i(TAG, "Attempting to connect to ${peripheral.displayName} (${peripheral.device.address})")
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Connect aborted: Bluetooth is not enabled.")
            return
        }
        if (_isConnecting.value || _connectedPeripheral.value != null) {
            Log.w(TAG, "Connect aborted: Already connecting or connected to a device: ${connectedGatt?.device?.address ?: _connectedPeripheral.value?.device?.address}")
            return
        }

        stopScanInternally("Starting connection to ${peripheral.device.address}")
        _isConnecting.value = true
        _connectedPeripheral.value = peripheral

        val deviceToConnect = bluetoothAdapter?.getRemoteDevice(peripheral.device.address)
        if (deviceToConnect == null) {
            Log.e(TAG, "Failed to get remote device for ${peripheral.device.address}")
            cleanupConnection("Failed to get remote device", notifyUser = true)
            return
        }

        Log.d(TAG, "Calling connectGatt for ${deviceToConnect.name ?: deviceToConnect.address}")
        connectedGatt = deviceToConnect.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        if (connectedGatt == null) {
            Log.e(TAG, "connectGatt system call returned null for ${peripheral.device.address}")
            cleanupConnection("connectGatt returned null for ${peripheral.device.address}", notifyUser = true)
        } else {
            Log.i(TAG, "connectGatt call succeeded for ${peripheral.device.address}. Waiting for onConnectionStateChange.")
        }
    }

    fun disconnectFromDevice(notifyUser: Boolean = true) {
        Log.i(TAG, "disconnectFromDevice called. Current GATT: ${connectedGatt?.device?.address}, Notify: $notifyUser")
        stopSensorPolling()
        val gattToDisconnect = connectedGatt
        if (gattToDisconnect == null) {
            Log.w(TAG, "No active GATT connection to disconnect.")
            if (_connectedPeripheral.value != null) {
                cleanupConnection("Disconnect called, GATT was null but a peripheral was set", notifyUser)
            }
            return
        }
        Log.i(TAG, "Requesting GATT disconnect from ${gattToDisconnect.device.address}")
        try {
            gattToDisconnect.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during gatt.disconnect(): ${e.message}")
            cleanupConnection("Exception during gatt.disconnect()", notifyUser)
        }
    }

    private fun cleanupConnection(reason: String = "Unknown", notifyUser: Boolean = false) {
        Log.i(TAG, "Cleaning up connection. Reason: $reason. Notify: $notifyUser. Current GATT: ${connectedGatt?.device?.address}, Target: ${_connectedPeripheral.value?.device?.address}")
        stopSensorPolling()
        gpsServiceController.stop()

        val previouslyConnectedDevice = _connectedPeripheral.value
        val gatt = connectedGatt
        connectedGatt = null

        try {
            gatt?.close()
            Log.d(TAG, "GATT closed for device: ${gatt?.device?.address}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during gatt.close(): ${e.message}")
        }

        _isConnecting.value = false
        if (_connectedPeripheral.value != null) {
            Log.d(TAG, "Clearing connected peripheral state for ${_connectedPeripheral.value?.device?.address}")
        }
        _connectedPeripheral.value = null
        _sensorHistory.value = emptyList()

        if (notifyUser && previouslyConnectedDevice != null) {
            showDisconnectionNotification(previouslyConnectedDevice.displayName)
        }

        if (viewModelScope.isActive && bluetoothAdapter?.isEnabled == true) {
            Log.d(TAG, "CleanupConnection: Attempting to restart scan. Reason: $reason")
            startScan()
        } else {
            Log.w(TAG, "CleanupConnection: ViewModel scope not active or BT not enabled, not restarting scan. Reason: $reason")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = gatt.device.name ?: "Unknown Device"
            val statusString = gattStatusToString(status)
            val newStateString = gattProfileStateToString(newState)

            Log.i(TAG, "onConnectionStateChange for $deviceName ($deviceAddress): Status: $statusString, NewState: $newStateString. Target: ${_connectedPeripheral.value?.device?.address}")

            val currentTargetPeripheral = _connectedPeripheral.value

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (currentTargetPeripheral?.device?.address == deviceAddress) {
                        Log.i(TAG, "Successfully connected to target $deviceName ($deviceAddress)")
                        connectedGatt = gatt
                        _isConnecting.value = false
                        _connectedPeripheral.update { cp ->
                            cp?.copy(rssi = null, servicesDiscovered = false, sensorData = null)
                        }
                        _sensorHistory.value = emptyList()

                        viewModelScope.launch {
                            delay(600)
                            Log.d(TAG, "Discovering services for $deviceAddress...")
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "Failed to initiate service discovery for $deviceAddress.")
                                cleanupConnection("Service discovery initiation failed for $deviceAddress", notifyUser = true)
                            }
                        }
                    } else {
                        Log.w(TAG, "Connected to $deviceAddress, but target was ${currentTargetPeripheral?.device?.address ?: "null"}. Disconnecting this unexpected device.")
                        gatt.disconnect()
                        gatt.close()
                    }
                } else {
                    Log.e(TAG, "Connection attempt failed for $deviceAddress with status: $statusString ($status)")
                    if (currentTargetPeripheral?.device?.address == deviceAddress) {
                        cleanupConnection("Connection failed (Status: $statusString) for $deviceAddress", notifyUser = true)
                    } else {
                        gatt.close()
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt == connectedGatt || deviceAddress == currentTargetPeripheral?.device?.address) {
                    Log.i(TAG, "Disconnected from $deviceName ($deviceAddress), status: $statusString ($status)")
                    val shouldNotify = currentTargetPeripheral != null && status != BluetoothGatt.GATT_SUCCESS
                    cleanupConnection("Disconnected (Status: $statusString) for $deviceAddress", notifyUser = shouldNotify)
                } else {
                    Log.w(TAG, "Received disconnect for non-target/non-active gatt $deviceAddress. Current gatt: ${connectedGatt?.device?.address}, target: ${currentTargetPeripheral?.device?.address}. Closing this rogue gatt.")
                    gatt.close()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = gatt.device.name ?: "Unknown Device"
            Log.i(TAG, "onServicesDiscovered for $deviceName ($deviceAddress). Status: ${gattStatusToString(status)}. Target: ${_connectedPeripheral.value?.device?.address}")

            if (gatt != connectedGatt || deviceAddress != _connectedPeripheral.value?.device?.address) {
                Log.w(TAG, "onServicesDiscovered for non-target device $deviceAddress or non-active gatt. Ignoring.")
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered successfully for $deviceName. Services count: ${gatt.services.size}")
                val sensorChar = gatt.findSensorCharacteristic()
                val locationChar = gatt.findLocationCharacteristic()

                if (sensorChar == null) Log.w(TAG, "SENSOR_CHARACTERISTIC_UUID ($SENSOR_CHARACTERISTIC_UUID) NOT FOUND on $deviceName")
                if (locationChar == null) Log.w(TAG, "LOCATION_CHARACTERISTIC_UUID ($LOCATION_CHARACTERISTIC_UUID) NOT FOUND on $deviceName")

                _connectedPeripheral.update { current -> current?.copy(servicesDiscovered = true) }

                if (sensorChar != null) {
                    startSensorPolling()
                    gpsServiceController.start()
                } else {
                    Log.w(TAG, "Sensor characteristic not found, not starting polling for $deviceName.")
                }
            } else {
                Log.e(TAG, "Service discovery failed for $deviceName with status: ${gattStatusToString(status)}")
                cleanupConnection("Service discovery failed (Status: ${gattStatusToString(status)}) for $deviceName", notifyUser = true)
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (gatt != connectedGatt) { Log.w(TAG, "onCharacteristicRead (T) for non-active gatt. Ignored."); return }
            handleCharacteristicRead(characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (gatt != connectedGatt) { Log.w(TAG, "onCharacteristicRead (L) for non-active gatt. Ignored."); return }
                handleCharacteristicRead(characteristic, characteristic.value ?: byteArrayOf(), status)
            }
        }

        private fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (characteristic.uuid != SENSOR_CHARACTERISTIC_UUID) {
                Log.w(TAG, "handleCharacteristicRead: Read from unexpected characteristic ${characteristic.uuid}")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (value.isEmpty()) {
                    Log.w(TAG, "handleCharacteristicRead: Read successful but value is empty for ${characteristic.uuid}")
                    return
                }
                try {
                    val sensor = SensorData.parseFrom(value)
                    val sample = SensorSample(System.currentTimeMillis(), sensor.temperature.toFloat(), sensor.pressure.toFloat(), sensor.humidity.toFloat())
                    _sensorHistory.update { old -> (old + sample).takeLast(MAX_SENSOR_HISTORY) }
                    _connectedPeripheral.update { it?.copy(sensorData = sensor) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse SensorData from ${characteristic.uuid}. Value: ${value.joinToString("") { "%02X".format(it) }}", e)
                }
            } else {
                Log.e(TAG, "Read failed for ${characteristic.uuid}, status=${gattStatusToString(status)}")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt != connectedGatt) { Log.w(TAG, "onCharacteristicWrite for non-active gatt. Ignored."); return }
            Log.d(TAG, "onCharacteristicWrite to ${gatt.device.address}, char ${characteristic.uuid}, status ${gattStatusToString(status)}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Successfully wrote to characteristic ${characteristic.uuid}.")
            } else {
                Log.e(TAG, "Failed to write to characteristic ${characteristic.uuid}, status: ${gattStatusToString(status)}")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (gatt != connectedGatt) { Log.w(TAG, "onReadRemoteRssi for non-active gatt. Ignored."); return }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectedPeripheral.update { it?.copy(rssi = rssi) }
            } else {
                Log.w(TAG, "Failed to read remote RSSI, status: ${gattStatusToString(status)}")
            }
        }
    }


    fun readSensorData() {
        val gatt = connectedGatt ?: run { Log.e(TAG, "readSensorData: GATT not available."); return }
        if (_connectedPeripheral.value?.servicesDiscovered != true) {
            Log.w(TAG, "readSensorData: Services not discovered yet for ${gatt.device.address}.")
            return
        }
        val char = gatt.findSensorCharacteristic() ?: run {
            Log.e(TAG, "readSensorData: Sensor characteristic ($SENSOR_CHARACTERISTIC_UUID) not found on ${gatt.device.address}.")
            return
        }
        if ((char.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            Log.e(TAG, "readSensorData: Sensor characteristic not readable.")
            return
        }
        Log.d(TAG, "Attempting to read sensor characteristic ${char.uuid} on ${gatt.device.address}")
        if (!gatt.readCharacteristic(char)) Log.e(TAG, "readSensorData: Failed to initiate read for sensor characteristic.")
        if (!gatt.readRemoteRssi()) Log.w(TAG, "readSensorData: Failed to initiate readRemoteRssi.")
    }

    fun writeDataToLocationCharacteristic(data: ByteArray) {
        val gatt = connectedGatt ?: run { Log.e(TAG, "writeDataToLocationCharacteristic: GATT not available."); return }
        if (_connectedPeripheral.value?.servicesDiscovered != true) {
            Log.w(TAG, "writeDataToLocationCharacteristic: Services not discovered yet for ${gatt.device.address}.")
            return
        }
        val char = gatt.findLocationCharacteristic() ?: run {
            Log.e(TAG, "writeDataToLocationCharacteristic: Location characteristic ($LOCATION_CHARACTERISTIC_UUID) not found on ${gatt.device.address}.")
            return
        }

        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            Log.e(TAG, "writeDataToLocationCharacteristic: Location characteristic is not writable.")
            return
        }

        Log.d(TAG, "Attempting to write ${data.size} bytes to Location characteristic ${char.uuid} (WriteType: $writeType)")
        val success: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(char, data, writeType)
            if (result != BluetoothStatusCodes.SUCCESS) Log.e(TAG, "writeData (API 33+) failed to initiate: ${bleWriteStatusCodeToString(result)}")
            result == BluetoothStatusCodes.SUCCESS
        } else {
            char.value = data
            char.writeType = writeType
            val initiated = gatt.writeCharacteristic(char)
            if (!initiated) Log.e(TAG, "writeData (Legacy): Failed to initiate characteristic write.")
            initiated
        }
        if (success) Log.i(TAG, "writeDataToLocationCharacteristic: Write initiated successfully.")
    }

    private fun startSensorPolling(periodMillis: Long = 5_000L) {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "Sensor polling already active.")
            return
        }
        stopSensorPolling()

        pollingJob = viewModelScope.launch {
            Log.i(TAG, "Starting sensor polling every $periodMillis ms for ${connectedGatt?.device?.address}.")
            while (isActive && connectedGatt != null && _connectedPeripheral.value?.servicesDiscovered == true) {
                readSensorData()
                delay(periodMillis)
                gpsServiceController.lastKnownLocation.let { location ->
                    val latitude = location.value?.latitude ?: 0.0
                    val longitude = location.value?.longitude ?: 0.0
                    val locationProto = LocationData.newBuilder()
                        .setLatitude(latitude.toInt())
                        .setLongitude(longitude.toInt())
                        .build()
                    val dataBytes = locationProto.toByteArray()
                    writeDataToLocationCharacteristic(dataBytes)
                }
                delay(periodMillis)
            }
            Log.i(TAG, "Sensor polling loop ended. isActive: $isActive, gatt: ${connectedGatt != null}, servicesDiscovered: ${_connectedPeripheral.value?.servicesDiscovered}")
        }
    }

    private fun stopSensorPolling() {
        if (pollingJob?.isActive == true) {
            Log.i(TAG, "Stopping sensor polling for ${connectedGatt?.device?.address}.")
            pollingJob?.cancel()
        }
        pollingJob = null
    }

    private fun BluetoothGatt.findSensorCharacteristic(): BluetoothGattCharacteristic? =
        this.services.firstNotNullOfOrNull { it.getCharacteristic(SENSOR_CHARACTERISTIC_UUID) }

    private fun BluetoothGatt.findLocationCharacteristic(): BluetoothGattCharacteristic? =
        this.services.firstNotNullOfOrNull { it.getCharacteristic(LOCATION_CHARACTERISTIC_UUID) }

    private fun isLocationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return true
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    private fun gattStatusToString(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
            BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> "GATT_CONNECTION_CONGESTED" // Deprecated in API 31
            BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
            133 -> "GATT_ERROR_133 (Often a generic error or stack issue)"
            8 -> "GATT_CONN_TIMEOUT (8)"
            19 -> "GATT_CONN_TERMINATE_PEER_USER (19)"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST (22)"
            else -> "UNKNOWN_GATT_STATUS ($status)"
        }
    }

    private fun gattProfileStateToString(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN_PROFILE_STATE ($state)"
        }
    }
    private fun bleWriteStatusCodeToString(statusCode: Int): String {
        return when (statusCode) {
            BluetoothStatusCodes.SUCCESS -> "SUCCESS"
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> "ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION"
            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> "ERROR_BLUETOOTH_NOT_ENABLED"
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> "ERROR_GATT_WRITE_REQUEST_BUSY"
            else -> "UNKNOWN_WRITE_STATUS_CODE ($statusCode)"
        }
    }
}
