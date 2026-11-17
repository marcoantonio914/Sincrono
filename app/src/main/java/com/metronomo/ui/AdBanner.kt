package com.metronomo.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/** Bloco de anúncios (banner) do AdMob. Sem internet o banner simplesmente não aparece. */
const val AD_UNIT_ID = "ca-app-pub-1765384677170727/4637477838"

@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val adView = remember { mutableListOf<AdView>() }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).also {
                it.setAdSize(AdSize.BANNER)
                it.adUnitId = AD_UNIT_ID
                it.loadAd(AdRequest.Builder().build())
                adView.add(it)
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose { adView.forEach { it.destroy() }; adView.clear() }
    }
}
