package com.metronomo.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
fun ScoreWebView(baseUrl: String, height: Dp, host: Boolean = false, stage: Boolean = false, modifier: Modifier = Modifier) {
    val ref = remember { arrayOfNulls<WebView>(1) }
    AndroidView(
        modifier = if (stage) modifier.fillMaxSize() else modifier.fillMaxWidth().height(height),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true // guarda a parte escolhida por este aparelho
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = WebViewClient()
                keepScreenOn = stage // modo palco: a tela não apaga
                loadUrl("$baseUrl/score.html?" + (if (host) "host=1&" else "") + (if (stage) "stage=1" else ""))
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

/** Cartão da partitura (anfitrião e cliente): a parte do grupo do aparelho, com opção de trocar, e o cursor. Só aparece se há partitura. */
@Composable
fun ScoreCard(ui: UiState) {
    val file by SessionController.scoreFile.collectAsState()
    if (ui.baseUrl.isNotEmpty() && file.isNotEmpty()) {
        val isHost = ui.role == SessionController.Role.HOST
        Module("Partitura", null) {
            // Botão bem à vista: tela cheia só com a partitura (fonte maior, tela sempre acesa).
            androidx.compose.material3.FilledTonalButton(
                onClick = { SessionController.stageOpen.value = true },
                modifier = Modifier.fillMaxWidth(),
            ) { androidx.compose.material3.Text("▢  Modo palco (tela cheia)") }
            ScoreWebView(ui.baseUrl, height = 380.dp, host = isHost)
        }
    }
}

/** Modo palco: tela cheia só com a partitura, fonte maior, tela sempre acesa. "Sair do palco" ou o botão voltar do celular fecha. */
@Composable
fun StageDialog(ui: UiState) {
    val open by SessionController.stageOpen.collectAsState()
    val file by SessionController.scoreFile.collectAsState()
    if (open && ui.baseUrl.isNotEmpty() && file.isNotEmpty()) {
        val isHost = ui.role == SessionController.Role.HOST
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { SessionController.stageOpen.value = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
                ScoreWebView(ui.baseUrl, height = 0.dp, host = isHost, stage = true)
                androidx.compose.material3.TextButton(
                    onClick = { SessionController.stageOpen.value = false },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd),
                ) { androidx.compose.material3.Text("Sair do palco") }
            }
        }
    }
}
