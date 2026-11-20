package com.metronomo.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
fun ScoreWebView(baseUrl: String, height: Dp, modifier: Modifier = Modifier) {
    val ref = remember { arrayOfNulls<WebView>(1) }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(height),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true // guarda a parte escolhida por este aparelho
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = WebViewClient()
                loadUrl("$baseUrl/score.html")
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
        Module("Partitura", null) {
            ScoreWebView(ui.baseUrl, height = 380.dp)
        }
    }
}
