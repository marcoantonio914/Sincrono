package com.metronomo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metronomo.SessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Repertório (só anfitrião): carrega uma música preparada (partitura, áudio por grupo, partes, andamento, marcadores) de uma vez. */
@Composable
fun SetlistCard() {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch { data = withContext(Dispatchers.IO) { SessionController.setlistJson() } }
    }
    fun run(body: JSONObject, ok: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { SessionController.setlistOp(body) }
            message = if (r.first == 200) ok else r.second.optString("error", "Não foi possível.")
            if (r.first == 200) data = r.second
        }
    }
    LaunchedEffect(Unit) {
        while (true) { refresh(); delay(4000) }
    }

    Module("Repertório", null) {
        Text("Prepare cada música uma vez e salve aqui; depois é só carregar. Só troca com tudo parado.")
        Button(onClick = { run(JSONObject().put("op", "add"), "Música salva no repertório.") }) { Text("Salvar a configuração atual como música") }
        val songs = data?.optJSONArray("songs")
        val current = data?.optInt("current", -1) ?: -1
        if (songs == null || songs.length() == 0) Text("Nenhuma música salva ainda.")
        else for (i in 0 until songs.length()) {
            val m = songs.getJSONObject(i)
            val id = m.getInt("id")
            val info = listOf(
                if (m.isNull("score")) "sem partitura" else "partitura",
                "${m.getJSONArray("audios").length()} áudio(s)",
                if (m.isNull("bpm")) "" else "${Math.round(m.getDouble("bpm"))} bpm",
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            Text((if (id == current) "▶ " else "") + m.getString("name") + "  ($info)")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { run(JSONObject().put("op", "apply").put("id", id), "Música carregada.") }) { Text("Carregar") }
                TextButton(onClick = { run(JSONObject().put("op", "move").put("id", id).put("dir", -1), "") }, enabled = i > 0) { Text("↑") }
                TextButton(onClick = { run(JSONObject().put("op", "move").put("id", id).put("dir", 1), "") }, enabled = i < songs.length() - 1) { Text("↓") }
                TextButton(onClick = { run(JSONObject().put("op", "update").put("id", id), "Música atualizada.") }) { Text("Atualizar") }
                TextButton(onClick = { run(JSONObject().put("op", "remove").put("id", id), "Música removida.") }) { Text("Remover") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run(JSONObject().put("op", "prev"), "Música anterior carregada.") }) { Text("◀ Anterior") }
            Button(onClick = { run(JSONObject().put("op", "next"), "Próxima música carregada.") }) { Text("Próxima ▶") }
        }
        if (message.isNotEmpty()) Text(message)
    }
}
