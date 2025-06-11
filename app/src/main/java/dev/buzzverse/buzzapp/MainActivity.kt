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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.buzzverse.buzzapp.ui.MainScreen
import dev.buzzverse.buzzapp.ui.theme.BuzzAppTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val bluetoothPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        var allBluetoothPermissionsGranted = true
        permissionsResult.entries.forEach {
            Log.d(TAG, "Permission ${it.key} granted: ${it.value}")
            if (!it.value && it.key != Manifest.permission.POST_NOTIFICATIONS) {
                allBluetoothPermissionsGranted = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted.")
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestRelevantPermissions()

        setContent {
            BuzzAppTheme {
                MainScreen()
            }
        }
    }

    private fun requestRelevantPermissions() {
        val basePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mutableListOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            mutableListOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            handleNotificationPermission()
        }

        val permissionsToRequestInitially = basePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequestInitially.isNotEmpty()) {
            Log.i(TAG, "Requesting Bluetooth/Location permissions: ${permissionsToRequestInitially.joinToString()}")
            bluetoothPermissionRequestLauncher.launch(permissionsToRequestInitially.toTypedArray())
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun handleNotificationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.i(TAG, "POST_NOTIFICATIONS permission already granted.")
            }
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission.")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}