package com.example.signage_front

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.signage_front.ui.composables.TopNavBar
import com.example.signage_front.ui.screens.AdScreen
import com.example.signage_front.ui.screens.HomeScreen
import com.example.signage_front.ui.theme.SignagefrontTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var isDarkMode by remember { mutableStateOf(false) }
            var showLanguageDialog by remember { mutableStateOf(false) }
            val navController = rememberNavController()

            SignagefrontTheme(darkTheme = isDarkMode) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopNavBar(
                            onHomeClick = { navController.navigate("home") { launchSingleTop = true } },
                            onDarkModeClick = { isDarkMode = !isDarkMode },
                            onLanguageClick = { showLanguageDialog = true }
                        )
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("home") {
                            HomeScreen(onNavigateToAd = { navController.navigate("ad") })
                        }
                        composable("ad") {
                            AdScreen()
                        }
                    }
                }

                if (showLanguageDialog) {
                    LanguageDialog(
                        onDismiss = { showLanguageDialog = false },
                        onLanguageSelected = {
                            // TODO: Handle language change
                            showLanguageDialog = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit, onLanguageSelected: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language") },
        text = {
            // In a real app, you'd have a more robust language selection UI
            Button(onClick = { onLanguageSelected("Hungarian") }) {
                Text("Hungarian")
            }
            Button(onClick = { onLanguageSelected("German") }) {
                Text("German")
            }
            Button(onClick = { onLanguageSelected("English") }) {
                Text("English")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
