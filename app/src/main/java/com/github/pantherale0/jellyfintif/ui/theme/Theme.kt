package com.github.pantherale0.jellyfintif.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

private val DarkColorScheme =
    darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF00A4DC),
        onPrimary = androidx.compose.ui.graphics.Color.White,
        background = androidx.compose.ui.graphics.Color(0xFF101010),
        onBackground = androidx.compose.ui.graphics.Color.White,
        surface = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
        onSurface = androidx.compose.ui.graphics.Color.White,
    )

@Composable
fun JellyfinTifTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else lightColorScheme(),
        content = content,
    )
}
