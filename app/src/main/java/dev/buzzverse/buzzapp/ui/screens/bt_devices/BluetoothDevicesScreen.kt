package dev.buzzverse.buzzapp.ui.screens.bt_devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.buzzverse.buzzapp.R
import dev.buzzverse.buzzapp.model.DiscoveredPeripheral
import dev.buzzverse.buzzapp.service.BluetoothViewModel
import dev.buzzverse.buzzapp.ui.composables.SensorChart

@Composable
fun BluetoothDevicesScreen(
    viewModel: BluetoothViewModel = hiltViewModel(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val discoveredPeripherals by viewModel.discoveredPeripherals.collectAsState()
    val connectedPeripheral by viewModel.connectedPeripheral.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.initializeBluetoothComponents()
    }

    DisposableEffect(viewModel, lifecycleOwner, connectedPeripheral, isConnecting) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (connectedPeripheral == null && !isConnecting) {
                        viewModel.startScan()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.stopScan()
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isConnecting) {
            Text("Connecting to ${viewModel.connectedPeripheral.value?.displayName ?: "device"}...")
            CircularProgressIndicator()
        }

        connectedPeripheral?.let { peripheral ->
            ConnectedPeripheralView(peripheral = peripheral, viewModel = viewModel)
        } ?: run {
            if (isScanning && discoveredPeripherals.isEmpty() && !isConnecting) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Scanning for devices...")
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator()
                }
            } else if (!isScanning && discoveredPeripherals.isEmpty() && !isConnecting) {
                Text("No devices found. Ensure Bluetooth & Location are on. Scan might start automatically or press 'Start Scan'.")
            }
            DiscoveredPeripheralsList(
                peripherals = discoveredPeripherals,
                onConnect = { viewModel.connectToDevice(it) }
            )
        }
    }
}

@Composable
fun DiscoveredPeripheralsList(
    peripherals: List<DiscoveredPeripheral>,
    onConnect: (DiscoveredPeripheral) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(peripherals, key = { it.id }, contentType = { "peripheral_item" }) { peripheral ->
            PeripheralItem(
                peripheral = peripheral,
                onConnect = { onConnect(peripheral) }
            )
            Divider()
        }
    }
}

@Composable
fun PeripheralItem(
    peripheral: DiscoveredPeripheral,
    onConnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(peripheral.displayName, style = MaterialTheme.typography.titleMedium)
            Text(peripheral.device.address, style = MaterialTheme.typography.bodySmall)
        }
        Text("RSSI: ${peripheral.rssi ?: "N/A"}", modifier = Modifier.padding(horizontal = 8.dp))
        Button(onClick = onConnect) {
            Text(stringResource(id = R.string.connect))
        }
    }
}

@Composable
fun ConnectedPeripheralView(peripheral: DiscoveredPeripheral, viewModel: BluetoothViewModel) {
    val history by viewModel.sensorHistory.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Connected to: ${peripheral.displayName}",
                    style = MaterialTheme.typography.titleLarge
                )
                Text("Address: ${peripheral.device.address}")
                Text("RSSI: ${peripheral.rssi ?: "N/A"}") // This updates if readRemoteRssi is called

                Spacer(modifier = Modifier.height(8.dp))
                Text("Sensor Data:", style = MaterialTheme.typography.titleMedium)
                peripheral.sensorData.let { sensorData ->
                    Text("Temperature: ${sensorData?.temperature?.let { "%.1f".format(it) } ?: "N/A"} °C")
                    Text("Humidity: ${sensorData?.humidity?.let { "%.1f".format(it) } ?: "N/A"} %")
                    Text("Pressure: ${sensorData?.pressure?.let { "%.0f".format(it) } ?: "N/A"} hPa")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.disconnectFromDevice() }) {
                    Text("Disconnect")
                }
            }
        }

        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Sensor History:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
            SensorChart(samples = history, modifier = Modifier.padding(top = 8.dp).height(200.dp)) // Give chart a fixed height or aspect ratio
        }
    }
}