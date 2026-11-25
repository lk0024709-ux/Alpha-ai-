package com.alphaai.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alphaai.app.ui.screens.ArtifactsScreen
import com.alphaai.app.ui.screens.HomeScreen
import com.alphaai.app.ui.screens.RunScreen
import com.alphaai.app.ui.screens.SessionsScreen
import com.alphaai.app.ui.screens.SettingsScreen
import com.alphaai.app.ui.theme.AlphaAiTheme
import com.alphaai.app.ui.viewmodel.ThemeViewModel

@Composable
fun AlphaAiRoot(initialSessionId: Long = -1L, themeViewModel: ThemeViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val mode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val dark = when (mode) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }
    LaunchedEffect(initialSessionId) {
        if (initialSessionId > 0) nav.navigate("run/$initialSessionId") { launchSingleTop = true }
    }
    AlphaAiTheme(darkTheme = dark) {
        Scaffold { padding ->
            NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen(
                    onOpenRun = { nav.navigate("run/$it") },
                    onSettings = { nav.navigate("settings") },
                    onSessions = { nav.navigate("sessions") },
                    onArtifacts = { nav.navigate("artifacts") }
                ) }
                composable("run/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.LongType })) {
                    RunScreen(onBack = { nav.popBackStack() }, onArtifacts = { nav.navigate("artifacts") })
                }
                composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                composable("sessions") { SessionsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("run/$it") }) }
                composable("artifacts") { ArtifactsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}
