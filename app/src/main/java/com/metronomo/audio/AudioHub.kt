package com.metronomo.audio

import com.metronomo.calibration.CalibrationHub
import org.json.JSONObject
import java.io.File

/**
 * Estado do áudio sincronizado, no anfitrião. Fica em endpoints próprios (/api/audio...),
 * separado do metrônomo e da calibração.
 *
 *   GET  /api/audio?device=ID&ready=ID_DO_ARQUIVO   registra o aparelho e devolve o estado
 *   POST /api/audio/cmd {"type":"play|pause|seek|stop","positionMs":N}
 *   GET  /api/audio/file?id=N                        baixa o arquivo (servido pelo SessionServer)
 *
 * A posição do áudio é uma função do relógio do servidor, não um fluxo: com `startAt` (instante
 * do servidor) e `positionMs` (posição do arquivo nesse instante), qualquer aparelho sabe qual
 * ponto do arquivo deve ser ouvido a cada momento. Quem entra atrasado já cai no ponto certo.
 */
class AudioHub(private val nowMs: () -> Double) {

    class Reply(val status: Int, val json: String)

    class FileInfo(val id: Long, val name: String, val path: File, val size: Long, val mime: String)

    private class Peer(var lastSeenMs: Double, var readyId: Long)

    private val lock = Any()
    private val peers = HashMap<String, Peer>()
    private var file: FileInfo? = null
    private var nextFileId = 1L
    private var revision = 0L
    private var playing = false
    private var startAt = 0.0
    private var positionMs = 0.0
    private var stopAt = Double.NaN

    /** A parada agendada em [stopAt] é um "parar" (volta ao início), não uma pausa. */
    private var resetOnStop = false

    /**
     * Chamado (fora do lock) com o estado completo em JSON sempre que um comando ou arquivo novo
     * muda o estado; o servidor o envia aos ouvintes de /events/audio. O polling continua como recuperação.
     */
    @Volatile
    var onChange: ((String) -> Unit)? = null

    /** Estado completo atual (sem registrar nenhum aparelho). */
    fun snapshotJson(): String = synchronized(lock) {
        normalize()
        stateJson()
    }

    /** Define o arquivo da sessão (já copiado para o armazenamento do app). Devolve o id dele. */
    fun setFile(path: File, name: String, mime: String): Long {
        val id = synchronized(lock) {
            val old = file
            val newId = nextFileId++
            file = FileInfo(newId, name, path, path.length(), mime)
            playing = false
            positionMs = 0.0
            stopAt = Double.NaN
            resetOnStop = false
            revision++
            if (old != null) runCatching { old.path.delete() }
            newId
        }
        onChange?.invoke(snapshotJson())
        return id
    }

    fun clear() {
        synchronized(lock) {
            file?.let { runCatching { it.path.delete() } }
            file = null
            playing = false
            positionMs = 0.0
            stopAt = Double.NaN
            resetOnStop = false
            revision++
        }
        onChange?.invoke(snapshotJson())
    }

    fun currentFile(id: Long): FileInfo? = synchronized(lock) { file?.takeIf { it.id == id } }

    /** Nome do arquivo atual (null se não há). */
    fun fileName(): String? = synchronized(lock) { file?.name }

    fun isPlaying(): Boolean = synchronized(lock) { normalize(); playing && stopAt.isNaN() }

    fun hasFile(): Boolean = synchronized(lock) { file != null }

    /**
     * [atMs]: instante do servidor a usar como "agora" nos comandos. Serve para que vários grupos
     * recebam o MESMO início/parada (o servidor lê o relógio uma vez e passa o valor a todos).
     */
    fun handle(method: String, rawPath: String, body: String, atMs: Double = Double.NaN): Reply {
        val path = rawPath.substringBefore('?')
        val reply = handleLocked(method, rawPath, path, body, atMs)
        if (method == "POST" && path == "/api/audio/cmd" && reply.status == 200) onChange?.invoke(reply.json)
        return reply
    }

    private fun handleLocked(method: String, rawPath: String, path: String, body: String, atMs: Double): Reply {
        return synchronized(lock) {
            normalize()
            when {
                method == "GET" && path == "/api/audio" -> {
                    register(CalibrationHub.parseQuery(rawPath))
                    Reply(200, stateJson())
                }

                method == "POST" && path == "/api/audio/cmd" -> command(body, atMs)
                else -> fail(404, "Não encontrado.")
            }
        }
    }

    private fun register(query: Map<String, String>) {
        val device = query["device"].orEmpty()
        if (device.isEmpty()) return
        val peer = peers.getOrPut(device) { Peer(nowMs(), 0L) }
        peer.lastSeenMs = nowMs()
        peer.readyId = query["ready"]?.toLongOrNull() ?: 0L
    }

    /** Depois que a parada agendada passou, consolida a posição e marca como parado. */
    private fun normalize() {
        if (playing && !stopAt.isNaN() && nowMs() > stopAt + 300) {
            positionMs = if (resetOnStop) 0.0 else positionMs + maxOf(0.0, stopAt - startAt)
            playing = false
            stopAt = Double.NaN
            resetOnStop = false
            revision++
        }
    }

    private fun positionAt(t: Double): Double = when {
        !playing -> positionMs
        t <= startAt -> positionMs
        !stopAt.isNaN() && t >= stopAt -> if (resetOnStop) 0.0 else positionMs + maxOf(0.0, stopAt - startAt)
        else -> positionMs + (t - startAt)
    }

    private fun command(body: String, atMs: Double): Reply {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return fail(400, "JSON inválido.")
        if (file == null) return fail(400, "Nenhum arquivo de áudio foi escolhido.")
        val now = if (atMs.isNaN()) nowMs() else atMs
        val requested = o.optDouble("positionMs", Double.NaN)
        when (o.optString("type")) {
            "play" -> {
                if (!(playing && stopAt.isNaN())) {
                    val from = if (requested.isNaN()) positionAt(now) else requested
                    startAt = now + LEAD_MS
                    positionMs = from.coerceAtLeast(0.0)
                    stopAt = Double.NaN
                    resetOnStop = false
                    playing = true
                    revision++
                }
            }

            "pause" -> {
                if (playing && stopAt.isNaN()) {
                    if (now < startAt) {
                        playing = false // ainda nem começou: só cancela
                    } else {
                        stopAt = now + STOP_MS
                    }
                    revision++
                }
            }

            "seek" -> {
                val target = (if (requested.isNaN()) 0.0 else requested).coerceAtLeast(0.0)
                resetOnStop = false
                if (playing) {
                    startAt = now + LEAD_MS
                    positionMs = target
                    stopAt = Double.NaN
                } else {
                    positionMs = target
                }
                revision++
            }

            "stop" -> {
                // Parar = pausar num instante combinado (todos param juntos, com fade) e voltar ao início.
                if (playing && stopAt.isNaN()) {
                    if (now < startAt) {
                        playing = false // ainda nem começou: só cancela
                        positionMs = 0.0
                    } else {
                        stopAt = now + STOP_MS
                        resetOnStop = true
                    }
                } else if (playing) {
                    resetOnStop = true // já havia uma pausa agendada: vira parada
                } else {
                    positionMs = 0.0
                }
                revision++
            }

            else -> return fail(400, "Comando inválido.")
        }
        return Reply(200, stateJson())
    }

    private fun stateJson(): String {
        val now = nowMs()
        val live = peers.values.filter { now - it.lastSeenMs < PEER_TTL_MS }
        val f = file
        val ready = if (f == null) 0 else live.count { it.readyId == f.id }
        val fileJson: Any = if (f == null) {
            JSONObject.NULL
        } else {
            JSONObject().put("id", f.id).put("name", f.name).put("size", f.size).put("mime", f.mime)
        }
        return JSONObject()
            .put("revision", revision)
            .put("serverNow", now.toLong())
            .put("file", fileJson)
            .put("playing", playing)
            .put("startAt", startAt.toLong())
            .put("positionMs", positionMs)
            .put("stopAt", if (stopAt.isNaN()) JSONObject.NULL else stopAt.toLong())
            .put("resetOnStop", resetOnStop)
            .put("ready", ready)
            .put("devices", live.size)
            .toString()
    }

    private fun fail(status: Int, text: String) =
        Reply(status, JSONObject().put("error", text).toString())

    companion object {
        /** Antecedência do início, para todos receberem o comando e se prepararem. */
        const val LEAD_MS = 2500.0

        /** Antecedência da parada agendada (todos param no mesmo instante, com fade). */
        const val STOP_MS = 1200.0
        private const val PEER_TTL_MS = 6000.0
    }
}
