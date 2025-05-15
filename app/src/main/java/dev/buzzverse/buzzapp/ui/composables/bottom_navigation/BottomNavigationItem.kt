package dev.buzzverse.buzzapp.ui.composables.bottom_navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.graphics.vector.ImageVector
import dev.buzzverse.buzzapp.ui.screens.Screens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object BottomNavigationState {
    private val _shouldScrollTop = MutableStateFlow(false)
    val shouldScrollTop: StateFlow<Boolean> get() = _shouldScrollTop

    fun setScrollTop(value: Boolean) {
        _shouldScrollTop.update { value }
    }
}

sealed class BottomNavigationItem(var screen: Screens, val icon: ImageVector) {
    object BluetoothDevicesScreen : BottomNavigationItem(Screens.BluetoothDevicesScreen, Icons.AutoMirrored.Filled.List)
    object AllDevicesScreen : BottomNavigationItem(Screens.AllDevicesScreen, Icons.AutoMirrored.Filled.List)

    companion object {
        fun fromRoute(route: String?): BottomNavigationItem? {
            return when (route) {
                Screens.BluetoothDevicesScreen.route -> BluetoothDevicesScreen
                Screens.AllDevicesScreen.route -> AllDevicesScreen
                else -> null
            }
        }
    }
}