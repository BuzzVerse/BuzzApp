package dev.buzzverse.buzzapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.buzzverse.buzzapp.ui.composables.AppBar
import dev.buzzverse.buzzapp.ui.composables.bottom_navigation.BottomNavigationBar

@Composable
fun MainScreen() {
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            AppBar(navController = navController)
        },
        bottomBar = {
            BottomAppBar(modifier = Modifier) {
                BottomNavigationBar(navController = navController)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Navigation(navController = navController)
        }
    }
}