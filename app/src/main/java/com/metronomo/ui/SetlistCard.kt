package com.metronomo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronomo.SessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Aba PLAYLIST (só anfitrião), no estilo de uma playlist de áudio: cartão da música carregada, controles
 * (anterior, tocar, parar, próxima) e a lista de músicas. Tocar numa linha carrega a música (partitura, áudio de
 * cada grupo, partes, andamento e marcadores) de uma vez. A aba MÚSICA fica só para configurar a música de agora.
 */
@Composable
fun SetlistCard() {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch { data = withContext(Dispatchers.IO) { SessionController.setlistJson() } }
    }
    fun run(body: JSONObject, ok: String, after: () -> Unit = {}) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { SessionController.setlistOp(body) }
            message = if (r.first == 200) ok else r.second.optString("error", "Não foi possível.")
            if (r.first == 200) { data = r.second; after() }
        }
    }
    LaunchedEffect(Unit) {
        while (true) { refresh(); delay(4000) }
    }

    val songs = data?.optJSONArray("songs")
    val count = songs?.length() ?: 0
    val current = data?.optInt("current", -1) ?: -1
    fun chips(m: JSONObject): String {
        val n = m.getJSONArray("audios").length()
        return listOfNotNull(
            if (m.isNull("score")) "sem partitura" else "partitura",
            if (n == 0) "sem áudio" else if (n == 1) "1 áudio" else "$n áudios",
            if (m.isNull("bpm")) null else "${Math.round(m.getDouble("bpm"))} bpm",
            if (m.optInt("marks", 0) > 0) "marcadores" else null,
        ).joinToString(" · ")
    }
    var cur: JSONObject? = null
    for (i in 0 until count) if (songs!!.getJSONObject(i).getInt("id") == current) cur = songs.getJSONObject(i)

    // cartão da música carregada
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Ink).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF374151)), contentAlignment = Alignment.Center) {
            Text("♪", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f)) {
            Text(if (cur != null) "CARREGADA AGORA" else "NENHUMA MÚSICA CARREGADA", style = LabelStyle, color = Color(0xFF9CA3AF))
            Text(cur?.getString("name") ?: if (count > 0) "Escolha uma música da lista" else "Playlist vazia", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(cur?.let { chips(it) } ?: if (count > 0) "Toque numa música para carregar." else "Monte uma música na aba MÚSICA e adicione aqui.", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall)
        }
    }
    // controles
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn("⏮", 52, false) { run(JSONObject().put("op", "prev"), "Anterior carregada.") }
        RoundBtn("▶", 64, true) { SessionController.playTogether(false, true) }
        RoundBtn("■", 52, false) { SessionController.pauseTogether(true) }
        RoundBtn("⏭", 52, false) { run(JSONObject().put("op", "next"), "Próxima carregada.") }
    }
    // lista
    Module("Músicas", if (count > 0) "$count" else null) {
        if (count == 0) Text("Nenhuma música ainda. Configure uma na aba MÚSICA (partitura, áudio, partes) e toque em \"Adicionar a música atual\".", color = Pal.Graphite, style = MaterialTheme.typography.bodyMedium)
        for (i in 0 until count) {
            val m = songs!!.getJSONObject(i)
            val id = m.getInt("id")
            val isCur = id == current
            var menu by remember(id) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (isCur) Pal.Canvas else Color.Transparent)
                    .clickable { run(JSONObject().put("op", "apply").put("id", id), "Carregada: ${m.getString("name")}.") }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isCur) "♪" else "${i + 1}", Modifier.size(28.dp), color = if (isCur) Pal.Ok else Pal.Graphite, fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                Column(Modifier.weight(1f)) {
                    Text(m.getString("name"), fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium)
                    Text(chips(m), color = Pal.Graphite, style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋯", style = MaterialTheme.typography.titleLarge, color = Pal.Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Subir") }, enabled = i > 0, onClick = { menu = false; run(JSONObject().put("op", "move").put("id", id).put("dir", -1), "") })
                        DropdownMenuItem(text = { Text("Descer") }, enabled = i < count - 1, onClick = { menu = false; run(JSONObject().put("op", "move").put("id", id).put("dir", 1), "") })
                        DropdownMenuItem(text = { Text("Atualizar com o atual") }, onClick = { menu = false; run(JSONObject().put("op", "update").put("id", id), "Música atualizada.") })
                        DropdownMenuItem(text = { Text("Remover", color = Pal.Bad) }, onClick = { menu = false; run(JSONObject().put("op", "remove").put("id", id), "Música removida.") })
                    }
                }
            }
            if (i < count - 1) HorizontalDivider(color = Pal.Hairline)
        }
        OutlinedTextField(
            value = newName, onValueChange = { newName = it.take(60) }, singleLine = true,
            label = { Text("Nome da música (opcional)") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { run(JSONObject().put("op", "add").put("name", newName), "Música adicionada à playlist.") { newName = "" } }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Adicionar a música atual")
        }
        if (message.isNotEmpty()) Text(message, color = Pal.Graphite, style = MaterialTheme.typography.bodySmall)
        Text("Cada música guarda a partitura, o áudio de cada grupo, a parte de cada grupo, o andamento e os marcadores. Só troca com tudo parado.", color = Pal.Graphite, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RoundBtn(symbol: String, size: Int, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (primary) Pal.Ink else Pal.Recessed).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = if (primary) Color.White else Pal.Ink, style = MaterialTheme.typography.titleLarge) }
}
