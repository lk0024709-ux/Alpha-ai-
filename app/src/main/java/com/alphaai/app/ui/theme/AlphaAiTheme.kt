package com.alphaai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AlphaDark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF062E66),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFFD0BCFF),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A20),
    surfaceVariant = Color(0xFF242832),
    error = Color(0xFFFFB4AB)
)

private val AlphaLight = lightColorScheme(
    primary = Color(0xFF145DB8),
    secondary = Color(0xFF006B63),
    tertiary = Color(0xFF6750A4),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EAF0)
)

@Composable
fun AlphaAiTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val useDark = darkTheme ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (useDark) AlphaDark else AlphaLight, content = content)
}
