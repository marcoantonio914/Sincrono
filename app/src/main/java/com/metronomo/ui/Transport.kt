package com.metronomo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Ícones de transporte desenhados na tela (sem depender da biblioteca de ícones). */
enum class Glyph { PLAY, PAUSE, STOP, TO_START, BACK, FORWARD }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
            val path = Path().apply {
                moveTo(x0 * w, y0 * h)
                lineTo(x1 * w, y1 * h)
                lineTo(x2 * w, y2 * h)
                close()
            }
            drawPath(path, color)
        }

        fun bar(x0: Float, x1: Float) {
            drawRect(color, Offset(x0 * w, 0.15f * h), Size((x1 - x0) * w, 0.7f * h))
        }

        when (glyph) {
            Glyph.PLAY -> triangle(0.25f, 0.1f, 0.9f, 0.5f, 0.25f, 0.9f)
            Glyph.PAUSE -> {
                bar(0.2f, 0.4f)
                bar(0.6f, 0.8f)
            }
            Glyph.STOP -> drawRect(color, Offset(0.2f * w, 0.2f * h), Size(0.6f * w, 0.6f * h))
            Glyph.TO_START -> {
                bar(0.12f, 0.26f)
                triangle(0.9f, 0.15f, 0.9f, 0.85f, 0.3f, 0.5f)
            }
            Glyph.BACK -> {
                triangle(0.5f, 0.2f, 0.5f, 0.8f, 0.05f, 0.5f)
                triangle(0.95f, 0.2f, 0.95f, 0.8f, 0.5f, 0.5f)
            }
            Glyph.FORWARD -> {
                triangle(0.5f, 0.2f, 0.5f, 0.8f, 0.95f, 0.5f)
                triangle(0.05f, 0.2f, 0.05f, 0.8f, 0.5f, 0.5f)
            }
        }
    }
}

/**
 * Botão de transporte REDONDO só com ícone ([description] é lida por leitores de tela): 52 dp, ou 64 dp se [primary].
 * Com [label] (ex.: "10") vira uma pílula. O tamanho é fixo: quem chama centraliza a linha (o [modifier] só é usado na pílula).
 */
@Composable
fun TransportButton(
    glyph: Glyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    label: String? = null,
) {
    val tint = if (!enabled) Pal.Graphite else if (primary) Color.White else Pal.Ink
    val shape = if (label == null) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    val m = (if (label == null) Modifier.size(if (primary) 64.dp else 52.dp) else modifier.height(48.dp))
        .semantics { contentDescription = description }
    val colors = if (primary) androidx.compose.material3.ButtonDefaults.buttonColors(
        containerColor = Pal.Ink, contentColor = Color.White, disabledContainerColor = Pal.Recessed, disabledContentColor = Pal.Graphite,
    ) else androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
        containerColor = Pal.Recessed, contentColor = Pal.Ink, disabledContainerColor = Pal.Canvas, disabledContentColor = Pal.Graphite,
    )
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = m,
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = if (label == null) androidx.compose.foundation.layout.PaddingValues(0.dp) else androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
    ) {
        GlyphIcon(glyph, tint, Modifier.size(if (primary) 28.dp else 22.dp))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}
