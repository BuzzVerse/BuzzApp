package dev.buzzverse.buzzapp.ui.screens.all_devices

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.buzzverse.buzzapp.model.DiscoveredPeripheral
import dev.buzzverse.buzzapp.service.BluetoothViewModel

@Composable
fun AllDevicesScreen(
    viewModel: BluetoothViewModel = hiltViewModel()
) {
    val discoveredPeripherals by viewModel.discoveredPeripherals.collectAsState()
    val connectedPeripheral by viewModel.connectedPeripheral.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()

        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { viewModel.startScan() }, enabled = !isConnecting && connectedPeripheral == null) {
                    Text("Start Scan")
                }
                Button(onClick = { viewModel.stopScan() }, enabled = !isConnecting && connectedPeripheral == null) {
                    Text("Stop Scan")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isConnecting) {
                Text("Connecting to ${connectedPeripheral?.displayName ?: "device"}...")
                CircularProgressIndicator()
            }

            connectedPeripheral?.let { peripheral ->
                ConnectedPeripheralView(peripheral = peripheral, viewModel = viewModel)
            } ?: run {
                DiscoveredPeripheralsList(
                    peripherals = discoveredPeripherals,
                    onConnect = { viewModel.connectToDevice(it) },
                    enabled = !isConnecting
                )
            }
        }
    }

@Composable
fun DiscoveredPeripheralsList(
    peripherals: List<DiscoveredPeripheral>,
    onConnect: (DiscoveredPeripheral) -> Unit,
    enabled: Boolean
) {
    if (peripherals.isEmpty()) {
        Text("No devices found. Start scanning.")
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(peripherals, key = { it.id }) { peripheral ->
                PeripheralItem(
                    peripheral = peripheral,
                    onConnect = { onConnect(peripheral) },
                    enabled = enabled
                )
                Divider()
            }
        }
    }
}

@Composable
fun PeripheralItem(
    peripheral: DiscoveredPeripheral,
    onConnect: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect, enabled = enabled)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(peripheral.displayName, style = MaterialTheme.typography.titleMedium)
            Text(peripheral.device.address, style = MaterialTheme.typography.bodySmall)
        }
        Text("RSSI: ${peripheral.rssi ?: "N/A"}")
        Button(onClick = onConnect, enabled = enabled) {
            Text("Connect")
        }
    }
}

@Composable
fun ConnectedPeripheralView(peripheral: DiscoveredPeripheral, viewModel: BluetoothViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Connected to: ${peripheral.displayName}", style = MaterialTheme.typography.titleLarge)
            Text("Address: ${peripheral.device.address}")
            Text("RSSI: ${peripheral.rssi ?: "N/A"}")
            peripheral.sensorCharacteristic?.let {
                Text("Sensor Char: ${it.uuid.toString().takeLast(10)}")
            }
            peripheral.locationCharacteristic?.let {
                Text("Location Char: ${it.uuid.toString().takeLast(10)}")
            }

            if (peripheral.isWritePending) {
                Text("Write pending...")
            } else if (peripheral.writeSuccess != null) {
                Text(if (peripheral.writeSuccess == true) "Write successful!" else "Write failed.")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val testData = "HelloBT".toByteArray()
                    viewModel.writeDataToSensorCharacteristic(testData)
                }) {
                    Text("Write 'HelloBT'")
                }
                Button(onClick = { viewModel.readSensorData() }) {
                    Text("Read Sensor")
                }
            }


            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.disconnectFromDevice() }) {
                Text("Disconnect")
            }
        }
    }
}