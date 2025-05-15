package dev.buzzverse.buzzapp.ui.composables

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.buzzverse.buzzapp.ui.screens.Screens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar (
    navController: NavController
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route ?: ""

    val title = Screens.fromRoute(currentRoute)?.title ?: ""

    val childScreens = listOf(
        Screens.DeviceScreen.route
    )

    TopAppBar(
        title = {
            Text(text = title)
        },
        navigationIcon = {
            if (childScreens.any { currentRoute == it }) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Default.ArrowBack, "back")
                }
            }
        }
    )
}
