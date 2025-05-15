package dev.buzzverse.buzzapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import dev.buzzverse.buzzapp.ui.MainScreen
import dev.buzzverse.buzzapp.ui.theme.BuzzAppTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {}
        locationPermissionRequest.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))

        setContent {
            BuzzAppTheme {
                MainScreen()
            }
        }
    }
}
