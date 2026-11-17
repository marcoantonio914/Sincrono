package com.metronomo.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Descoberta automática de sessões na rede local (mDNS / "DNS-SD", pelo NsdManager do Android).
 *
 * O anfitrião se anuncia como "_metronomo._tcp." com a porta do servidor; os convidados listam
 * os anunciantes e entram com um toque. Não substitui o endereço manual nem o QR code: redes
 * com "isolamento de clientes" (muitos Wi-Fi de visitantes) bloqueiam o mDNS.
 */
class Discovery(context: Context) {

    data class Found(val name: String, val host: String, val port: Int) {
        val address: String get() = "$host:$port"
    }

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var resolving = false
    private val lock = Any()

    // ---- anfitrião ----

    fun register(port: Int, deviceName: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "Metronomo $deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            registration = null
        }
    }

    fun unregister() {
        val l = registration ?: return
        registration = null
        runCatching { nsd.unregisterService(l) }
    }

    // ---- convidado ----

    fun startBrowsing() {
        if (discovery != null) return
        acquireMulticast()
        _found.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _searching.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.orEmpty().startsWith("_metronomo._tcp")) return
                resolveQueue.add(serviceInfo)
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _found.value = _found.value.filterNot { it.name == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _searching.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _searching.value = false
                discovery = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discovery = null
            _searching.value = false
        }
    }

    fun stopBrowsing() {
        val l = discovery
        discovery = null
        if (l != null) runCatching { nsd.stopServiceDiscovery(l) }
        resolveQueue.clear()
        _searching.value = false
        releaseMulticast()
    }

    /** O NsdManager só resolve um serviço por vez; os demais esperam na fila. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(lock) {
            if (resolving) return
            val n = resolveQueue.poll() ?: return
            resolving = true
            n
        }
        val done = {
            synchronized(lock) { resolving = false }
            resolveNext()
        }
        try {
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        done()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val addr = serviceInfo.host
                        // Só IPv4: é o que o servidor do app escuta e o que o resto do app usa.
                        if (addr is Inet4Address && serviceInfo.port > 0) {
                            val item = Found(serviceInfo.serviceName, addr.hostAddress ?: "", serviceInfo.port)
                            if (item.host.isNotEmpty()) {
                                _found.value = _found.value.filterNot { it.name == item.name } + item
                            }
                        }
                        done()
                    }
                },
            )
        } catch (e: Exception) {
            done()
        }
    }

    private fun acquireMulticast() {
        try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("metronomo-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            multicastLock = null
        }
    }

    private fun releaseMulticast() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_metronomo._tcp."
    }
}
