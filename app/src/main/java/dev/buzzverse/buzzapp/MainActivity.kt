package dev.buzzverse.buzzapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.buzzverse.buzzapp.ui.MainScreen
import dev.buzzverse.buzzapp.ui.theme.BuzzAppTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val permissionRequestLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissionsResult ->
            var allPermissionsGranted = true
            permissionsResult.entries.forEach {
                Log.d(TAG, "Permission ${it.key} granted: ${it.value}")
                if (!it.value) {
                    allPermissionsGranted = false
                }
            }

            if (allPermissionsGranted) {
                Log.i(TAG, "All required Bluetooth permissions granted.")
                // Now it's safe to start scanning or other Bluetooth operations
                // bluetoothViewModel.startScan() // ViewModel can decide when to start based on its state
                // or you can trigger it here.
                // Often, an initial scan is desired.
            } else {
                Log.e(TAG, "Not all required Bluetooth permissions were granted.")
                // Handle the case where permissions are denied: show a message to the user,
                // disable Bluetooth features, etc.
                // You might want to show a Snackbar or dialog explaining why permissions are needed.
            }
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionRequestLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.i(TAG, "All required permissions already granted.")
        }


        setContent {
            BuzzAppTheme {
                MainScreen()
            }
        }
    }
}
