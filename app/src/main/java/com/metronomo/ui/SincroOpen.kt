package com.metronomo.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.metronomo.SessionController
import com.metronomo.SessionController.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pergunta o que fazer com um arquivo .sincro aberto de fora do app (gerenciador de arquivos, e-mail, compartilhar).
 * Só o anfitrião tem playlist: sem sessão de anfitrião, o arquivo espera aqui até ele começar uma.
 */
@Composable
fun SincroOpenDialog(ui: UiState) {
    val uri by SessionController.pendingSincro.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val u = uri ?: return
    val host = ui.active && ui.role == SessionController.Role.HOST
    fun done() { SessionController.pendingSincro.value = null }
    fun run(replace: Boolean) {
        done()
        scope.launch {
            val msg = withContext(Dispatchers.IO) { SessionController.importSincro(ctx, u, replace) }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    if (host) {
        AlertDialog(
            onDismissRequest = { done() },
            title = { Text("Abrir arquivo .sincro") },
            text = { Text("Juntar as músicas do arquivo à playlist atual, ou substituir a playlist toda? (Substituir só com tudo parado.)") },
            confirmButton = { TextButton(onClick = { run(false) }) { Text("Juntar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { run(true) }) { Text("Substituir") }
                    TextButton(onClick = { done() }) { Text("Cancelar") }
                }
            },
        )
    } else if (!waiting) {
        AlertDialog(
            onDismissRequest = { waiting = true },
            title = { Text("Arquivo .sincro recebido") },
            text = { Text("Para abrir, comece uma sessão como anfitrião. O arquivo fica esperando aqui; depois da sessão iniciada, aparece a pergunta de juntar ou substituir a playlist.") },
            confirmButton = { TextButton(onClick = { waiting = true }) { Text("Esperar") } },
            dismissButton = { TextButton(onClick = { done() }) { Text("Descartar") } },
        )
    }
}
