package dev.buzzverse.buzzapp.ui.composables.bottom_navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun BottomNavigationBar(navController: NavController) {
    val items: List<BottomNavigationItem> = listOf(
        BottomNavigationItem.BluetoothDevicesScreen,
        BottomNavigationItem.AllDevicesScreen
    )

    var currentRoute by remember { mutableStateOf(items.first().screen) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val navigationRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(key1 = navigationRoute) {
        val item = BottomNavigationItem.fromRoute(navigationRoute)?.screen
        if (item != null) {
            currentRoute = item
        }
    }

    var navGraphSetupComplete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        navController.addOnDestinationChangedListener { _, _, _ ->
            navGraphSetupComplete = true
        }
    }

    NavigationBar {
        items.forEachIndexed { _, item ->
            val route = item.screen.route
            val title = item.screen.title
            NavigationBarItem(
                alwaysShowLabel = true,
                icon = { Icon(item.icon, contentDescription = title) },
                label = { Text(title) },
                selected = currentRoute.route == route,
                onClick = {
                    if (navGraphSetupComplete.not()) return@NavigationBarItem

                    if (currentRoute.route == route && navigationRoute != route) {
                        navController.navigate(route)
                        return@NavigationBarItem
                    }
                    if (currentRoute.route == route) {
                        BottomNavigationState.setScrollTop(true)
                        return@NavigationBarItem
                    }
                    currentRoute = item.screen
                    navController.navigate(route) {
                        navController.graph.startDestinationRoute?.let { route ->
                            popUpTo(route) {
                                saveState = true
                            }
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
