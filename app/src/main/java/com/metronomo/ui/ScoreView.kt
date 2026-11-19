package com.metronomo.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.metronomo.SessionController
import com.metronomo.SessionController.UiState
import kotlinx.coroutines.delay

/**
 * Partitura numa WebView que abre `score.html` do servidor da sessão (no anfitrião, o próprio celular).
 * O desenho e o cursor são em JavaScript (score.js / score-view.js, sem internet); o relógio, o metrônomo
 * e o áudio são nativos: a cada 50 ms o app entrega os valores à página (`sincronoTick`).
 * Não toca som nenhum: só mostra.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScoreWebView(baseUrl: String, host: Boolean, height: Dp, modifier: Modifier = Modifier) {
    val ref = remember { arrayOfNulls<WebView>(1) }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(height),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true // guarda a parte escolhida por este aparelho
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = WebViewClient()
                loadUrl("$baseUrl/score.html" + if (host) "?host=1" else "")
                ref[0] = this
            }
        },
    )
    LaunchedEffect(Unit) {
        while (true) {
            ref[0]?.evaluateJavascript("window.sincronoTick&&window.sincronoTick(${SessionController.scoreTick()})", null)
            delay(50)
        }
    }
    DisposableEffect(Unit) { onDispose { ref[0]?.destroy(); ref[0] = null } }
}

/** Aba do anfitrião: importar a partitura do MuseScore, escolher a parte de cada grupo e ver o cursor. */
@Composable
fun ScoreTab(ui: UiState) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) SessionController.importScore(context, uri)
    }
    Module("Partitura", null) {
        Text(
            "No MuseScore: Arquivo → Exportar → MusicXML (a partitura completa, uma vez só). " +
                "Importe aqui e escolha abaixo a parte que cada grupo vê. Cada músico pode trocar a própria parte no aparelho dele.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Importar partitura (MusicXML)") }
        if (ui.baseUrl.isNotEmpty()) ScoreWebView(ui.baseUrl, host = true, height = 560.dp)
    }
}

/** Cartão do cliente: só a partitura (a parte do grupo dele, com opção de trocar) e o cursor. */
@Composable
fun ScoreCard(ui: UiState) {
    if (ui.baseUrl.isNotEmpty()) {
        Module("Partitura", null) {
            ScoreWebView(ui.baseUrl, host = false, height = 360.dp)
        }
    }
}
