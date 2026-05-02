package com.sohanreddy.sevak.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Saathi colour palette ───────────────────────────────────────────
object SaathiColors {
    val Background      = Color(0xFF050D1F)
    val Surface         = Color(0xFF0C1C41)
    val SurfaceAlpha    = Color(0x990C1C41)   // ~60 % opacity
    val CardBorder      = Color(0x2E5082DC)
    val Primary         = Color(0xFF3B5998)
    val PrimaryBright   = Color(0xFF4A8FFF)
    val Accent          = Color(0xFF2060FF)
    val TextPrimary     = Color(0xE5C8D7F0)   // rgba(200,215,240,0.9)
    val TextDim         = Color(0x8C8CA5D2)   // rgba(140,165,210,0.55)
    val Error           = Color(0xFFEF5350)
    val White           = Color(0xFFFFFFFF)
    val Black           = Color(0xFF000000)
}

private val SaathiDarkScheme = darkColorScheme(
    primary            = SaathiColors.Primary,
    onPrimary          = SaathiColors.White,
    secondary          = SaathiColors.PrimaryBright,
    background         = SaathiColors.Background,
    surface            = SaathiColors.Surface,
    onBackground       = SaathiColors.TextPrimary,
    onSurface          = SaathiColors.TextPrimary,
    error              = SaathiColors.Error,
    onError            = SaathiColors.White,
    surfaceVariant     = SaathiColors.SurfaceAlpha,
    onSurfaceVariant   = SaathiColors.TextDim,
)

@Composable
fun SaathiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SaathiDarkScheme,
        content = content
    )
}
