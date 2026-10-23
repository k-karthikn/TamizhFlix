package com.karthi.tamizhflix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

private val fallbackDark = darkColorScheme(
    primary = Color(0xFFD1BCFF), onPrimary = Color(0xFF33205F),
    background = Color(0xFF101014), surface = Color(0xFF101014),
    surfaceContainer = Color(0xFF202027), secondaryContainer = Color(0xFF383447)
)

private val fallbackLight = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White,
    background = Color(0xFFFCF8FF), surface = Color(0xFFFCF8FF),
    surfaceContainer = Color(0xFFF1ECF6), secondaryContainer = Color(0xFFE8DEF8)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TamizhFlixTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    MaterialExpressiveTheme(
        colorScheme = when {
            Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
            dark -> fallbackDark
            else -> fallbackLight
        },
        typography = Typography,
        content = content
    )
}
