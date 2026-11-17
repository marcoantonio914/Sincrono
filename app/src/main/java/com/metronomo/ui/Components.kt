package com.metronomo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val ButtonShape = RoundedCornerShape(4.dp)

/** Botão principal: fundo escuro, texto branco, cantos de 4 dp (sem formato de pílula). */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = ButtonShape,
    colors = ButtonDefaults.buttonColors(
        containerColor = Pal.Ink,
        contentColor = Color.White,
        disabledContainerColor = Pal.Recessed,
        disabledContentColor = Pal.Graphite,
    ),
    content = content,
)

/** Botão secundário: cinza claro "rebaixado". */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.FilledTonalButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = ButtonShape,
    colors = ButtonDefaults.filledTonalButtonColors(
        containerColor = Pal.Recessed,
        contentColor = Pal.Ink,
        disabledContainerColor = Pal.Canvas,
        disabledContentColor = Pal.Graphite,
    ),
    content = content,
)

/** Botão de contorno fino. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = ButtonShape,
    border = BorderStroke(1.dp, Pal.Hairline),
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Pal.Surface, contentColor = Pal.Ink),
    content = content,
)

/** Módulo: cartão branco com borda fina, rótulo em maiúsculas e, à direita, uma métrica. */
@Composable
fun Module(title: String, metric: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Pal.Surface)
            .border(1.dp, Pal.Hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = LabelStyle)
            if (metric != null) Text(metric, style = TabularStyle)
        }
        content()
    }
}

/** Ponto de estado: verde = ok, vermelho = problema, cinza = neutro. */
@Composable
fun StatusDot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Selo pequeno com ponto de estado, como "REDE LOCAL PRONTA". */
@Composable
fun Badge(text: String, dot: Color = Pal.Graphite) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Pal.Canvas)
            .border(1.dp, Pal.Hairline, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(dot)
        Text(text.uppercase(), style = LabelStyle)
    }
}

@Composable
fun faderColors() = SliderDefaults.colors(
    thumbColor = Pal.Ink,
    activeTrackColor = Pal.Ink,
    inactiveTrackColor = Pal.Hairline,
)

/**
 * Controle de volume de 0 a 100%. [remote] é o valor conhecido (vindo do anfitrião); enquanto
 * o dedo arrasta, vale o valor local, e [onFinish] é chamado ao soltar.
 */
@Composable
fun Fader(label: String, remote: Float, onFinish: (Float) -> Unit, key: Any = label) {
    var dragging by remember(key) { mutableStateOf(false) }
    var value by remember(key) { mutableFloatStateOf(remote) }
    LaunchedEffect(remote, dragging) {
        if (!dragging) value = remote
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialThemeBody(), modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = TabularStyle)
        }
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                onFinish(value)
                dragging = false
            },
            colors = faderColors(),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun MaterialThemeBody() = androidx.compose.material3.MaterialTheme.typography.bodyMedium
