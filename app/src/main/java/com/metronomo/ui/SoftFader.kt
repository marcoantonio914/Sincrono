package com.metronomo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Controle de volume leve: trilho fino na cor [accent], marcador redondo branco com borda. Toque ou arraste.
 * [onChange] vem a cada movimento; [onFinish] ao soltar. Feito só com gestos básicos (sem depender da versão do Slider).
 */
@Composable
fun SoftFader(
    value: Float,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Pal.Ink,
    description: String = "Volume",
) {
    val thumb = 22.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }
    var width by remember { mutableIntStateOf(0) }
    fun fromX(x: Float): Float {
        val span = (width - thumbPx).coerceAtLeast(1f)
        return ((x - thumbPx / 2f) / span).coerceIn(0f, 1f)
    }
    val v = value.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .semantics { contentDescription = "$description ${(v * 100).roundToInt()}%" }
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) { detectTapGestures { off -> onChange(fromX(off.x)); onFinish() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off -> onChange(fromX(off.x)) },
                    onDragEnd = { onFinish() },
                    onDragCancel = { onFinish() },
                ) { change, _ ->
                    change.consume()
                    onChange(fromX(change.position.x))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = thumb / 2)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Pal.Recessed))
            Box(Modifier.fillMaxWidth(v).height(6.dp).clip(CircleShape).background(accent))
        }
        Box(
            Modifier
                .offset { IntOffset((v * (width - thumbPx).coerceAtLeast(0f)).roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.dp, accent, CircleShape),
        )
    }
}
