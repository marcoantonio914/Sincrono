package com.metronomo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.metronomo.SessionController
import com.metronomo.SessionController.UiState

/**
 * "Ouvir também": além do áudio do próprio grupo, este aparelho toca junto o de outros grupos (ex.: o piano de
 * acompanhamento + a sua voz), cada um com o seu volume. Só mexe neste aparelho. Só aparece se há outro grupo com áudio.
 */
@Composable
fun ExtraAudioCard(ui: UiState) {
    val groups by SessionController.groups.collectAsState()
    val extras by SessionController.extras.collectAsState()
    val mine = ui.groupId
    val others = groups.groups.filter { it.id != mine && it.file != null }
    if (others.isEmpty()) return
    Module("Ouvir também", extras.keys.count { k -> others.any { it.id == k } }.takeIf { it > 0 }?.toString()) {
        Text(
            "Além do áudio do seu grupo, toque junto o de outros grupos (por exemplo o acompanhamento), cada um com o seu volume. Só este aparelho muda.",
            color = Pal.Graphite, style = MaterialTheme.typography.bodySmall,
        )
        for (g in others) {
            val on = g.id in extras
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { SessionController.setExtra(g.id, !on) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = { SessionController.setExtra(g.id, it) })
                    androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(groupColor(g)))
                    Text(g.name, Modifier.weight(1f))
                }
                if (on) {
                    Slider(
                        value = extras[g.id] ?: 1f,
                        onValueChange = { SessionController.setExtraVolume(g.id, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
