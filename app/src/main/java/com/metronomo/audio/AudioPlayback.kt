package com.metronomo.audio

import android.content.Context
import com.metronomo.net.ClockSync
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lado do cliente do áudio sincronizado: consulta o anfitrião, baixa e decodifica o arquivo
 * e entrega ao [AudioFilePlayer] o estado de reprodução (quando e de onde tocar).
 */
class AudioPlayback(
    context: Context,
    private val baseUrl: String,
    private val clock: ClockSync,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val player: AudioFilePlayer,
    private val onUi: (Ui) -> Unit,
    /** Pasta de trabalho (cada tocador tem a sua: um não apaga os arquivos do outro). */
    dirName: String = "audio",
) {
    data class Ui(
        val supported: Boolean = true,
        val fileName: String = "",
        /** Mensagem de preparo: baixando, decodificando, pronto, erro. */
        val status: String = "",
        /** Este aparelho já tem o arquivo decodificado e pronto. */
        val ready: Boolean = false,
        val playing: Boolean = false,
        val readyCount: Int = 0,
        val deviceCount: Int = 0,
        val durationMs: Double = 0.0,
    )

    private val dir = File(context.applicationContext.cacheDir, dirName).also {
        it.mkdirs()
        it.listFiles()?.forEach { f -> runCatching { f.delete() } } // sobras de sessões anteriores
    }

    private var ui = Ui()
    private var lastRevision = Long.MIN_VALUE
    private var lastEpoch = 0L

    /** Grupo ("ilha") cujo arquivo este aparelho toca. Muda quando o anfitrião move o aparelho de grupo. */
    @Volatile
    private var group = "g1"

    @Volatile
    private var lastPlan: AudioFilePlayer.Plan? = null

    @Volatile
    private var readyId = 0L
    @Volatile
    private var wantedId = 0L
    private var prepareJob: Job? = null

    @Volatile
    private var sseConnection: HttpURLConnection? = null

    /** Depois de [stop] os laços de consulta terminam e nada mais é aplicado (os tocadores de "Ouvir também" são parados no meio da sessão). */
    @Volatile
    private var stopped = false

    fun start() {
        scope.launch(Dispatchers.IO) { pollLoop() }
        scope.launch(Dispatchers.IO) { eventsLoop() }
    }

    fun stop() {
        stopped = true
        runCatching { sseConnection?.disconnect() }
        prepareJob?.cancel()
        player.media = null
        player.plan = AudioFilePlayer.Plan(-1, false, 0.0, 0.0, Double.NaN)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** Troca de grupo: descarta o arquivo e o estado do grupo anterior e passa a seguir o novo. */
    @Synchronized
    fun setGroup(newGroup: String) {
        if (newGroup == group) return
        group = newGroup
        lastRevision = Long.MIN_VALUE
        wantedId = 0L
        readyId = 0L
        prepareJob?.cancel()
        player.media = null
        player.plan = AudioFilePlayer.Plan(-1, false, 0.0, 0.0, Double.NaN)
        lastPlan = null
        publish { Ui(supported = it.supported) }
        runCatching { sseConnection?.disconnect() } // o laço de eventos reconecta já no novo grupo
    }

    @Synchronized
    private fun publish(change: (Ui) -> Ui) {
        ui = change(ui)
        onUi(ui)
    }

    /** Posição atual do arquivo, em ms, segundo o estado do anfitrião (null se não há arquivo). */
    fun positionMs(): Double? {
        val p = lastPlan ?: return null
        if (ui.fileName.isEmpty()) return null
        if (!p.playing) return p.positionMs
        val now = clock.nowServerMs()
        if (p.resetOnStop && !p.stopAt.isNaN() && now >= p.stopAt) return 0.0
        val end = if (p.stopAt.isNaN()) now else minOf(now, p.stopAt)
        val pos = p.positionMs + maxOf(0.0, end - p.startAt)
        return if (ui.durationMs > 0) minOf(pos, ui.durationMs) else pos
    }

    /** Envia um comando (play, pause, seek, stop). Devolve null se deu certo, ou a mensagem de erro. */
    suspend fun command(type: String, positionMs: Double? = null, targetGroup: String? = null, startAt: Double? = null): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("type", type)
            if (positionMs != null) body.put("positionMs", positionMs)
            if (startAt != null) body.put("startAt", startAt)
            val (code, text) = request("POST", "/api/audio/cmd?group=${URLEncoder.encode(targetGroup ?: group, "UTF-8")}", body.toString())
            if (code in 200..299) {
                null
            } else {
                runCatching { JSONObject(text).optString("error") }.getOrNull()
                    .takeUnless { it.isNullOrBlank() } ?: "Não foi possível enviar o comando de áudio."
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Não foi possível falar com o anfitrião."
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && !stopped) {
            try {
                val g = group
                val path = "/api/audio?device=${URLEncoder.encode(deviceId, "UTF-8")}&ready=$readyId&group=${URLEncoder.encode(g, "UTF-8")}"
                val (code, text) = request("GET", path, null)
                if (code == 404 && g == "g1") {
                    publish { it.copy(supported = false) }
                    return
                }
                if (code in 200..299) handle(JSONObject(text), g)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            // O comando chega por /events/audio; o polling fica lento, só para registrar este aparelho,
            // atualizar as contagens de "prontos" e recuperar o estado se o SSE cair.
            delay(1500)
        }
    }

    /** Recebe o estado completo do áudio por SSE, assim que o anfitrião muda algo. */
    private suspend fun eventsLoop() {
        var backoffMs = 500L
        while (currentCoroutineContext().isActive && !stopped) {
            try {
                val sseGroup = group
                val c = URL("$baseUrl/events/audio?group=${URLEncoder.encode(sseGroup, "UTF-8")}").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 30_000 // o servidor envia um "ping" a cada 10 s
                c.useCaches = false
                sseConnection = c
                val code = c.responseCode
                if (code == 404 && sseGroup == "g1") return // servidor sem suporte a áudio
                if (code != 200) throw java.io.IOException("HTTP $code")
                c.inputStream.bufferedReader().use { reader ->
                    backoffMs = 500L
                    var data: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data:")) {
                            data = line.removePrefix("data:").trim()
                        } else if (line.isEmpty()) {
                            val payload = data
                            data = null
                            if (payload != null) runCatching { handle(JSONObject(payload), sseGroup) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 4000L)
        }
    }

    /** Só publica se o arquivo ainda é o desejado (evita mensagens de um preparo antigo). */
    private fun publishFor(fileId: Long, change: (Ui) -> Ui) {
        if (wantedId == fileId) publish(change)
    }

    /** Aceita sempre o estado COMPLETO; ignora o que for mais antigo que o último já aplicado (SSE e polling se cruzam). */
    @Synchronized
    private fun handle(json: JSONObject, forGroup: String) {
        if (stopped || forGroup != group) return // resposta de um grupo que este aparelho já deixou
        val incoming = json.optLong("revision")
        // Anfitrião reiniciou (outra "época"): as revisões recomeçam e o id do arquivo pode se repetir com outro conteúdo.
        // Sem isso, os estados novos eram ignorados como "antigos" e o cursor/áudio ficavam parados.
        val epoch = json.optLong("epoch", 0L)
        if (epoch != 0L && lastEpoch != 0L && epoch != lastEpoch) {
            lastRevision = Long.MIN_VALUE
            wantedId = 0L
            readyId = 0L
            lastPlan = null
            player.plan = AudioFilePlayer.Plan(-1, false, 0.0, 0.0, Double.NaN)
        }
        if (epoch != 0L) lastEpoch = epoch
        if (incoming < lastRevision) return
        val fileJson = json.optJSONObject("file")
        val fileId = fileJson?.optLong("id") ?: 0L
        val name = fileJson?.optString("name").orEmpty()

        if (fileId != wantedId) {
            wantedId = fileId
            prepareJob?.cancel()
            player.media = null
            readyId = 0L
            publish { it.copy(fileName = name, ready = false, status = if (fileId == 0L) "" else "Aguardando…", durationMs = 0.0) }
            if (fileId != 0L) prepareJob = scope.launch(Dispatchers.IO) { prepare(fileId, group) }
        }

        val revision = incoming
        if (revision > lastRevision) {
            lastRevision = revision
            val stopAt = if (json.isNull("stopAt")) Double.NaN else json.optDouble("stopAt")
            val plan = AudioFilePlayer.Plan(
                revision = revision,
                playing = json.optBoolean("playing"),
                startAt = json.optDouble("startAt"),
                positionMs = json.optDouble("positionMs"),
                stopAt = stopAt,
                resetOnStop = json.optBoolean("resetOnStop"),
            )
            lastPlan = plan
            player.plan = plan
        }

        publish {
            it.copy(
                playing = json.optBoolean("playing"),
                readyCount = json.optInt("ready"),
                deviceCount = json.optInt("devices"),
            )
        }
    }

    private fun prepare(fileId: Long, forGroup: String) {
        for (attempt in 1..3) {
            try {
                val download = File(dir, "dl-$forGroup-$fileId")
                val pcm = File(dir, "pcm-$forGroup-$fileId")
                publishFor(fileId) { it.copy(status = "Baixando o arquivo…") }
                if (!download(fileId, forGroup, download)) throw IllegalStateException("download")
                publishFor(fileId) { it.copy(status = "Preparando o áudio…") }
                val info = AudioDecoder.decode(download, pcm) { progress ->
                    publishFor(fileId) { u -> u.copy(status = "Preparando o áudio… ${(progress * 100).toInt()}%") }
                } ?: throw IllegalStateException("decode")
                runCatching { download.delete() }
                if (stopped || wantedId != fileId || group != forGroup) return // chegou outro arquivo (ou outro grupo) enquanto isso
                player.media = AudioFilePlayer.Media(pcm, info.sampleRate, info.channels, info.frames)
                readyId = fileId
                publishFor(fileId) { it.copy(ready = true, status = "Pronto.", durationMs = info.durationMs) }
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (wantedId != fileId) return
                publishFor(fileId) { it.copy(status = "Falha ao preparar o áudio (tentativa $attempt de 3).") }
                Thread.sleep(3000)
            }
        }
        publishFor(fileId) { it.copy(ready = false, status = "Não foi possível preparar este arquivo neste aparelho.") }
    }

    private fun download(fileId: Long, forGroup: String, target: File): Boolean {
        val c = URL("$baseUrl/api/audio/file?id=$fileId&group=${URLEncoder.encode(forGroup, "UTF-8")}").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 20_000
        c.useCaches = false
        try {
            if (c.responseCode != 200) return false
            val total = c.contentLengthLong
            var done = 0L
            var lastPercent = -1
            c.inputStream.use { input ->
                target.outputStream().buffered(1 shl 16).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                publishFor(fileId) { it.copy(status = "Baixando o arquivo… $percent%") }
                            }
                        }
                    }
                }
            }
            return total <= 0 || done == total
        } finally {
            c.disconnect()
        }
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.useCaches = false
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return code to text
    }
}
