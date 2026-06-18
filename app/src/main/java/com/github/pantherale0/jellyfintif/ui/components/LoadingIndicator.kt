package com.github.pantherale0.jellyfintif.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

private val JellyfinBlue = Color(0xFF00A4DC)

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        color = JellyfinBlue,
        modifier = modifier.size(48.dp),
    )
}
