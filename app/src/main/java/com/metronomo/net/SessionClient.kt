package com.metronomo.net

import com.metronomo.core.MetronomeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente da sessão: recebe o estado por SSE, mede o relógio do servidor
 * periodicamente e envia comandos. Funciona tanto com o servidor embutido
 * no app quanto com o servidor Node.js do protótipo.
 */
class SessionClient(
    private val baseUrl: String,
    private val clock: ClockSync,
    private val scope: CoroutineScope,
    private val onState: (MetronomeState) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) {
    @Volatile
    private var eventsConnection: HttpURLConnection? = null
    private var jobs: List<Job> = emptyList()

    fun start() {
        jobs = listOf(
            scope.launch(Dispatchers.IO) { eventsLoop() },
            scope.launch(Dispatchers.IO) { syncLoop() },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        runCatching { eventsConnection?.disconnect() }
    }

    /** Devolve null se deu certo, ou uma mensagem de erro. */
    suspend fun sendCommand(type: String, bpm: Int, beatsPerBar: Int): String? =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("type", type)
                    .put("bpm", bpm)
                    .put("beatsPerBar", beatsPerBar)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val c = open("/api/command", "POST")
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(payload.size)
                c.outputStream.use { it.write(payload) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code in 200..299) null else runCatching { JSONObject(text).optString("error") }
                    .getOrNull().takeUnless { it.isNullOrBlank() } ?: "Erro $code ao enviar o comando."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Não foi possível enviar o comando."
            }
        }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = 5000
            useCaches = false
        }

    private suspend fun eventsLoop() {
        var backoffMs = 500L
        while (currentCoroutineContext().isActive) {
            try {
                val c = open("/events", "GET")
                c.readTimeout = 30_000 // o servidor envia um "ping" a cada 10 s
                eventsConnection = c
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                var lastRevision = Long.MIN_VALUE // a 1ª mensagem de cada conexão sempre vale
                c.inputStream.bufferedReader().use { reader ->
                    onConnection(true)
                    backoffMs = 500L
                    var data: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("data:") -> data = line.removePrefix("data:").trim()
                            line.isEmpty() -> {
                                val payload = data
                                data = null
                                if (payload != null) {
                                    runCatching { MetronomeState.fromJson(payload) }.getOrNull()?.let { s ->
                                        if (s.revision >= lastRevision) {
                                            lastRevision = s.revision
                                            onState(s)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            onConnection(false)
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 4000L)
        }
    }

    private suspend fun syncLoop() {
        var count = 0
        while (currentCoroutineContext().isActive) {
            try {
                syncOnce()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                clock.noteFailure()
            }
            count++
            // Rajada inicial de amostras para convergir rápido; depois, a cada 2 s.
            delay(if (count < 12) 200L else 2000L)
        }
    }

    private fun syncOnce() {
        val body = "{\"clientSentAt\":0}".toByteArray(Charsets.UTF_8)
        val c = open("/api/sync", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        val t0 = System.nanoTime() / 1e6
        c.outputStream.use { it.write(body) }
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val t3 = System.nanoTime() / 1e6
        val o = JSONObject(text)
        clock.addSample(t0, o.getDouble("serverReceivedAt"), o.getDouble("serverSentAt"), t3)
    }
}
