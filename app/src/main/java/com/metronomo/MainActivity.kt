package com.metronomo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.google.android.gms.ads.MobileAds
import com.metronomo.ui.MetronomoApp
import com.metronomo.ui.MetronomoTheme

class MainActivity : ComponentActivity() {
    /** Abrir um .sincro pelo gerenciador de arquivos ("Abrir com") ou pelo "Compartilhar": o app pergunta o que fazer com ele. */
    private fun receiveSincro(i: android.content.Intent?) {
        val uri: android.net.Uri? = when (i?.action) {
            android.content.Intent.ACTION_VIEW -> i.data
            android.content.Intent.ACTION_SEND ->
                if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) SessionController.pendingSincro.value = uri
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSincro(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveSincro(intent)
        MobileAds.initialize(this) {}
        setContent {
            MetronomoTheme {
                Surface(Modifier.fillMaxSize()) {
                    MetronomoApp()
                }
            }
        }
    }
}
