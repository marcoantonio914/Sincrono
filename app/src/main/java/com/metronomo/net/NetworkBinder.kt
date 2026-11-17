package com.metronomo.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Faz o app usar sempre a rede Wi-Fi, mesmo quando ela não tem internet. Sem isso, o Android
 * pode preferir os dados móveis para o tráfego e deixar a conexão com o anfitrião instável.
 */
object NetworkBinder {

    /** Começa a prender o app ao Wi-Fi. Devolve a função que desfaz. */
    fun start(context: Context): () -> Unit {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { manager.bindProcessToNetwork(network) }
            }

            override fun onLost(network: Network) {
                runCatching { manager.bindProcessToNetwork(null) }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            return {}
        }
        return {
            runCatching { manager.unregisterNetworkCallback(callback) }
            runCatching { manager.bindProcessToNetwork(null) }
        }
    }
}
