package com.metronomo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
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
fun SetlistCard(ui: com.metronomo.SessionController.UiState) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Pair<Int, String>?>(null) }   // (id, nome atual) da música sendo renomeada

    val ctx = LocalContext.current
    var exportIds by remember { mutableStateOf<Set<Int>?>(null) }      // null = a playlist toda
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    // Salvar .sincro: o sistema pergunta onde gravar
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val ids = exportIds
            scope.launch {
                message = "Gravando o arquivo…"
                val n = withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openOutputStream(uri)?.use { SessionController.setlistExport(it, ids) } ?: -1 }.getOrDefault(-1)
                }
                message = if (n > 0) "Arquivo .sincro gravado ($n ${if (n == 1) "música" else "músicas"})." else "Não foi possível gravar o arquivo."
            }
        }
    }
    // Abrir .sincro: escolhe o arquivo e depois pergunta se junta ou substitui
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingImport = uri }
    fun doImport(uri: android.net.Uri, replace: Boolean) {
        scope.launch {
            message = "Abrindo o arquivo…"
            message = withContext(Dispatchers.IO) { SessionController.importSincro(ctx, uri, replace) }
            data = withContext(Dispatchers.IO) { SessionController.setlistJson() }
        }
    }
    pendingImport?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Abrir arquivo .sincro") },
            text = { Text("Juntar as músicas do arquivo à playlist atual, ou substituir a playlist toda? (Substituir só com tudo parado.)") },
            confirmButton = { TextButton(onClick = { pendingImport = null; doImport(uri, false) }) { Text("Juntar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null; doImport(uri, true) }) { Text("Substituir", color = Pal.Bad) }
                    TextButton(onClick = { pendingImport = null }) { Text("Cancelar") }
                }
            },
        )
    }
    fun exportSongs(ids: Set<Int>?, fileName: String) { exportIds = ids; saver.launch(fileName) }

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

    renaming?.let { (rid, old) ->
        var text by remember(rid) { mutableStateOf(old) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renomear música") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = { run(JSONObject().put("op", "rename").put("id", rid).put("name", text.trim()), "Música renomeada."); renaming = null },
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }
    val withMetro by SessionController.playWithMetro.collectAsState()
    val withAudio by SessionController.playWithAudio.collectAsState()
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
    // controles: anterior, pausar, TOCAR (no centro), parar, próxima. Dois de cada lado, então o tocar fica centralizado.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        TransportButton(Glyph.TO_START, "Música anterior", { run(JSONObject().put("op", "prev"), "Anterior carregada.") })
        TransportButton(Glyph.PAUSE, "Pausar todos", { SessionController.pauseTogether(false) })
        TransportButton(Glyph.PLAY, "Tocar todos", { SessionController.playTogether(withMetro, withAudio) }, enabled = withMetro || withAudio, primary = true)
        TransportButton(Glyph.STOP, "Parar todos e voltar ao início", { SessionController.pauseTogether(true) })
        TransportButton(Glyph.FORWARD, "Próxima música", { run(JSONObject().put("op", "next"), "Próxima carregada.") })
    }
    // a partitura (com o botão do modo palco) fica aqui, junto dos controles: quem toca na PLAYLIST vê o cursor sem trocar de aba
    ScoreCard(ui)
    // o que o Tocar dispara
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = withMetro, onCheckedChange = { SessionController.playWithMetro.value = it; SessionController.liveToggle("metro", it) })
        Text("Metrônomo", Modifier.clickable { SessionController.playWithMetro.value = !withMetro; SessionController.liveToggle("metro", !withMetro) })
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Checkbox(checked = withAudio, onCheckedChange = { SessionController.playWithAudio.value = it; SessionController.liveToggle("audio", it) })
        Text("Áudio", Modifier.clickable { SessionController.playWithAudio.value = !withAudio; SessionController.liveToggle("audio", !withAudio) })
    }
    Text(
        "O Tocar dispara áudio, metrônomo ou os dois, começando juntos. A partitura anda com o áudio; sem áudio, com o metrônomo (andamento da aba SYNC). Pausar e Parar valem para tudo.",
        color = Pal.Graphite, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    // a configuração não some: a música carregada guarda sozinha as mudanças
    if (data?.optBoolean("dirty") == true) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF3C7)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Alterações ainda não guardadas (guardo sozinho em instantes).", Modifier.weight(1f), color = Color(0xFF78350F), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { run(JSONObject().put("op", "save"), "Alterações guardadas.") }) { Text("Salvar agora") }
        }
    } else if (data?.optBoolean("unsaved") == true) {
        Text(
            "Esta configuração ainda não está na playlist. Toque em \"+ Adicionar a música atual\" para não perdê-la ao sair.",
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF3C7)).padding(12.dp),
            color = Color(0xFF78350F), style = MaterialTheme.typography.bodySmall,
        )
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
                        DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; renaming = Pair(id, m.getString("name")) })
                        DropdownMenuItem(text = { Text("Atualizar com o atual") }, onClick = { menu = false; run(JSONObject().put("op", "update").put("id", id), "Música atualizada.") })
                        DropdownMenuItem(text = { Text("Salvar esta música (.sincro)") }, onClick = { menu = false; exportSongs(setOf(id), m.getString("name").replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "musica" } + ".sincro") })
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
        OutlinedButton(onClick = { run(JSONObject().put("op", "new"), "Pronto: configure a nova música na aba MÚSICA e toque em \"+ Adicionar a música atual\".") }, modifier = Modifier.fillMaxWidth()) {
            Text("Nova música")
        }
        Button(onClick = { run(JSONObject().put("op", "add").put("name", newName), "Música adicionada à playlist.") { newName = "" } }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Adicionar a música atual")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportSongs(null, "playlist.sincro") }, enabled = count > 0, modifier = Modifier.weight(1f)) { Text("Salvar playlist (.sincro)") }
            OutlinedButton(onClick = { opener.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Abrir .sincro") }
        }
        if (message.isNotEmpty()) Text(message, color = Pal.Graphite, style = MaterialTheme.typography.bodySmall)
        Text("Cada música guarda a partitura, o áudio de cada grupo, a parte de cada grupo, o andamento e os marcadores. Só troca com tudo parado.", color = Pal.Graphite, style = MaterialTheme.typography.bodySmall)
    }
}
