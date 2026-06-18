package com.github.pantherale0.jellyfintif.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

private val JellyfinBlue = Color(0xFF00A4DC)
private val Background = Color(0xFF101010)
private val Surface = Color(0xFF1A1A1A)
private val SurfaceVariant = Color(0xFF2A2A2A)
private val OnSurface = Color(0xFFFFFFFF)
private val OnSurfaceMuted = Color(0xFFBDBDBD)
private val Outline = Color(0xFF888888)

private val TvDarkColorScheme =
    darkColorScheme(
        primary = JellyfinBlue,
        onPrimary = OnSurface,
        primaryContainer = Color(0xFF004D66),
        onPrimaryContainer = OnSurface,
        secondary = JellyfinBlue,
        onSecondary = OnSurface,
        background = Background,
        onBackground = OnSurface,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceMuted,
        error = Color(0xFFCF6679),
        onError = Color.Black,
    )

private val M3DarkColorScheme =
    m3DarkColorScheme(
        primary = JellyfinBlue,
        onPrimary = OnSurface,
        primaryContainer = Color(0xFF004D66),
        onPrimaryContainer = OnSurface,
        secondary = JellyfinBlue,
        onSecondary = OnSurface,
        background = Background,
        onBackground = OnSurface,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceMuted,
        outline = Outline,
        outlineVariant = Color(0xFF555555),
        error = Color(0xFFCF6679),
        onError = Color.Black,
    )

/**
 * Android TV does not reliably report dark theme via [androidx.compose.foundation.isSystemInDarkTheme].
 * Always apply a high-contrast dark scheme and provide both TV Material3 and Material3 themes so
 * mixed components (e.g. [androidx.compose.material3.OutlinedTextField]) render readable text.
 */
@Composable
fun JellyfinTifTheme(content: @Composable () -> Unit) {
    M3MaterialTheme(colorScheme = M3DarkColorScheme) {
        MaterialTheme(
            colorScheme = TvDarkColorScheme,
            typography = Typography(),
        ) {
            CompositionLocalProvider(LocalContentColor provides OnSurface) {
                content()
            }
        }
    }
}
