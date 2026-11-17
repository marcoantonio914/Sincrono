package com.metronomo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Paleta "Acoustic Metric": claro, neutro, sem sombras; verde/vermelho só para estado funcional. */
object Pal {
    val Canvas = Color(0xFFF8F9FA)
    val Surface = Color(0xFFFFFFFF)
    val Recessed = Color(0xFFE9ECEF)
    val Ink = Color(0xFF111827)
    val Graphite = Color(0xFF4B5563)
    val Hairline = Color(0xFFE9ECEF)
    val Ok = Color(0xFF059669)
    val Bad = Color(0xFFDC2626)
}

@Composable
fun MetronomoTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Pal.Ink,
        onPrimary = Color.White,
        secondary = Pal.Graphite,
        onSecondary = Color.White,
        secondaryContainer = Pal.Recessed,
        onSecondaryContainer = Pal.Ink,
        background = Pal.Canvas,
        onBackground = Pal.Ink,
        surface = Pal.Canvas,
        onSurface = Pal.Ink,
        onSurfaceVariant = Pal.Graphite,
        outline = Pal.Graphite,
        outlineVariant = Pal.Hairline,
        error = Pal.Bad,
    )
    val base = Typography()
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}

/** Texto pequeno em maiúsculas com espaçamento, para rótulos de módulo. */
val LabelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.7.sp, color = Pal.Graphite)

/** Números de largura fixa (BPM, ms): os dígitos não "dançam" quando mudam. */
val TabularStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
