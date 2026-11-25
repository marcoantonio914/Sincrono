package com.metronomo.net

import kotlin.math.abs
import android.content.res.AssetManager
import com.metronomo.audio.AudioHub
import com.metronomo.calibration.CalibrationHub
import com.metronomo.core.MetronomeState
import com.metronomo.diag.Groups
import com.metronomo.diag.MixLevels
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Servidor HTTP mínimo (sem bibliotecas externas), compatível com o servidor Node.js
 * do protótipo:
 *
 *   GET  /api/state    estado atual
 *   POST /api/sync     medição de relógio
 *   POST /api/command  play | pause | restart | settings
 *   GET  /events       Server-Sent Events com o estado
 *
 * Suporta conexões keep-alive, para que as medições de relógio não paguem o
 * handshake TCP a cada amostra.
 */
class SessionServer(
    private val preferredPort: Int,
    /** Se informado, serve a página web (pasta assets/web) para abrir no navegador do computador. */
    private val assets: AssetManager? = null,
) {

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val receivedAtMs: Double,
        /** Só no envio de um .sincro (corpo grande, lido em fluxo pelo tratador, sem passar por String). */
        val stream: InputStream? = null,
        val streamLength: Long = 0,
    )

    private class SseClient(val socket: Socket, private val out: OutputStream, val group: String = "") {
        fun send(text: String): Boolean = try {
            synchronized(this) {
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (e: IOException) {
            runCatching { socket.close() }
            false
        }
    }

    // Relógio do servidor: base em ms "de parede" + avanço monotônico com fração de ms.
    private val epochBaseMs = System.currentTimeMillis()
    private val nanoBase = System.nanoTime()
    private fun nowMs(): Double = epochBaseMs + (System.nanoTime() - nanoBase) / 1e6

    @Volatile
    private var state = MetronomeState()
    private val stateLock = Any()

    private val sseClients = CopyOnWriteArrayList<SseClient>()

    /** Ouvintes de /events/audio (endpoint separado, para não afetar clientes que só usam /events). */
    private val audioSseClients = CopyOnWriteArrayList<SseClient>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "metronomo-http").apply { isDaemon = true }
    }
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "metronomo-heartbeat").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private val calibration = CalibrationHub { nowMs() }

    /** Grupos ("ilhas"): cada um tem o seu arquivo e o seu estado de áudio; o relógio é um só. */
    val groups = Groups()

    /** Chamado (fora de locks) quando grupos ou atribuições mudam; o app guarda o estado. */
    @Volatile
    var onGroupsChanged: (() -> Unit)? = null

    private val hubs = ConcurrentHashMap<String, AudioHub>()

    /** Estado do áudio do grupo [id] (cria sob demanda); null se o grupo não existe. */
    fun hub(id: String): AudioHub? {
        if (!groups.exists(id)) return null
        return hubs.getOrPut(id) {
            AudioHub { nowMs() }.also { h ->
                h.onChange = { json ->
                    try {
                        pool.execute { broadcastAudio(id, json) }
                    } catch (e: Exception) {
                        // servidor encerrando
                    }
                }
            }
        }
    }

    /** O áudio do grupo padrão (compatível com clientes sem grupos). */
    val audio: AudioHub get() = hub(Groups.DEFAULT_ID)!!

    /** Apaga os arquivos de todos os grupos. */
    fun clearAllAudio() {
        hubs.values.forEach { it.clear() }
    }

    /** Volume de cada aparelho definido pelo anfitrião. */
    val mix = MixLevels()

    // ---- partitura: uma por sessão (MusicXML/MXL completo); cada grupo escolhe a parte que vê.
    // Fica na memória (é pequena). A página (score.js) lê o arquivo e desenha a parte certa.
    private val scoreLock = Any()
    private var scoreBytes: ByteArray? = null
    private var scoreName = ""
    private var scoreId = 0L
    private var scoreRev = 1L
    private val scoreParts = HashMap<String, Int>()
    private var scorePartNames: List<String> = emptyList()
    private var scoreMeta: ScoreMeta = ScoreMeta()
    private val scoreMarks = LinkedHashMap<String, Pair<List<Double>, Double>>() // 'nome:tamanho' do áudio -> (ms de cada compasso, ajuste)
    private var scorePos: Double? = null     // posição (ms da partitura) escolhida pelo anfitrião na linha do tempo

    /** Posição da linha do tempo da partitura (null = nenhuma). Não mexe na revisão: os clientes leem em /api/score. */
    fun setScorePos(ms: Double?) = synchronized(scoreLock) { scorePos = ms?.coerceAtLeast(0.0) }

    /** Chamado (fora de locks) quando a partitura, as partes ou a escolha de um grupo mudam. */
    @Volatile
    var onScoreChanged: (() -> Unit)? = null

    fun setScore(bytes: ByteArray, name: String) {
        val names = ScoreParts.names(bytes)
        synchronized(scoreLock) {
            scoreBytes = bytes
            scoreName = name
            scorePartNames = names
            scoreMeta = ScoreParts.meta(bytes)
            scoreId++
            scoreParts.clear()
            scorePos = null
            scoreMarks.clear()
            scoreRev++
        }
        onScoreChanged?.invoke()
    }

    fun clearScore() {
        synchronized(scoreLock) {
            scoreBytes = null
            scoreName = ""
            scorePartNames = emptyList()
            scoreMeta = ScoreMeta()
            scoreParts.clear()
            scorePos = null
            scoreMarks.clear()
            scoreRev++
        }
        onScoreChanged?.invoke()
    }

    /** Marcadores de compasso de um áudio (null apaga). Falso se inválidos. */
    fun setScoreMarks(sig: String, marks: List<Double>?, offset: Double): Boolean = synchronized(scoreLock) {
        if (sig.isEmpty() || sig.length > 300) return false
        if (marks == null) scoreMarks.remove(sig)
        else {
            if (marks.isEmpty() || marks.size > 20000 || marks[0] < 0 || abs(offset) > 5000) return false
            for (i in 1 until marks.size) if (marks[i] < marks[i - 1]) return false
            scoreMarks[sig] = Pair(marks, offset)
        }
        scoreRev++
        true
    }

    /** O anfitrião escolhe a parte que o [group] vê. Os clientes percebem em ~1 s (a página consulta /api/score). */
    fun setScorePart(group: String, part: Int): Boolean {
        val ok = synchronized(scoreLock) {
            val limit = if (scorePartNames.isEmpty()) 64 else scorePartNames.size
            if (scoreBytes != null && groups.exists(group) && part in 0 until limit) {
                scoreParts[group] = part
                scoreRev++
                true
            } else false
        }
        if (ok) onScoreChanged?.invoke()
        return ok
    }

    fun scoreInfo(): ScoreInfo = synchronized(scoreLock) {
        ScoreInfo(if (scoreBytes == null) "" else scoreName, scorePartNames, HashMap(scoreParts), scoreMeta.bpm, scoreMeta.beats, scoreMeta.estimated)
    }

    /** {"rev":N,"file":{"id","name","size"}|null,"parts":{"grupo":índice}} */
    private fun scoreJson(): String = synchronized(scoreLock) {
        val bytes = scoreBytes
        val parts = JSONObject()
        scoreParts.forEach { (k, v) -> parts.put(k, v) }
        val file: Any = if (bytes == null) JSONObject.NULL else JSONObject().put("id", scoreId).put("name", scoreName).put("size", bytes.size)
        JSONObject().put("rev", scoreRev).put("file", file).put("parts", parts).put("pos", scorePos ?: JSONObject.NULL).put("marks", marksJson()).toString()
    }

    /** Foto da partitura da sessão para o repertório (null se não há). */
    class ScoreSnap(val bytes: ByteArray, val name: String, val parts: Map<String, Int>, val marks: Map<String, Pair<List<Double>, Double>>)

    fun scoreSnapshot(): ScoreSnap? = synchronized(scoreLock) {
        val b = scoreBytes ?: return null
        ScoreSnap(b, scoreName, HashMap(scoreParts), LinkedHashMap(scoreMarks))
    }

    /** Novo startAt ao mudar o andamento sem parar: a batida atual (e a posição na partitura) não pula, só a velocidade muda. */
    private fun keepPhase(cur: MetronomeState, newBpm: Double, now: Double): Long? {
        val s = cur.startAt ?: return null
        if (!cur.playing || cur.bpm <= 0.0) return s
        return Math.round(now - (now - s) * cur.bpm / newBpm)
    }

    /** Estado atual do metrônomo. */
    fun metronomeState(): MetronomeState = state

    /** Muda andamento e compasso sem tocar nem parar nada (como o comando "settings"). */
    fun applySettings(bpm: Double, beats: Int) {
        if (!(bpm >= MetronomeState.MIN_BPM && bpm <= MetronomeState.MAX_BPM && beats in MetronomeState.MIN_BEATS..MetronomeState.MAX_BEATS)) return
        val next = synchronized(stateLock) {
            val updated = state.copy(bpm = bpm, beatsPerBar = beats, startAt = keepPhase(state, bpm, nowMs()), revision = state.revision + 1)
            state = updated
            updated
        }
        pool.execute { broadcastRaw(sseEvent(next)) }
    }

    /** Repertório (criado pelo controlador do app, que sabe onde guardar os arquivos). */
    @Volatile
    var setlist: Setlist? = null

    private fun handleSetlist(req: Request, out: OutputStream, close: Boolean) {
        val sl = setlist ?: return respond(out, 404, "{\"error\":\"Repertorio indisponivel.\"}", close)
        if (req.method == "GET") return respond(out, 200, sl.json().toString(), close)
        val body = runCatching { JSONObject(req.body) }.getOrNull()
            ?: return respond(out, 400, "{\"error\":\"JSON invalido.\"}", close)
        val r = sl.op(body)
        respond(out, r.first, r.second.toString(), close)
    }

    /** Baixa um .sincro (playlist toda, ou ?ids=1,2). O arquivo é montado direto na conexão, sem copiar para disco. */
    private fun handleSetlistExport(req: Request, out: OutputStream) {
        val sl = setlist ?: return respond(out, 404, "{\"error\":\"Repertorio indisponivel.\"}", true)
        val q = CalibrationHub.parseQuery(req.path)
        var ids: Set<Int>? = null
        val raw = q["ids"].orEmpty()
        if (raw.isNotEmpty()) {
            ids = runCatching { raw.split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }.toSet() }.getOrNull()
                ?: return respond(out, 400, "{\"error\":\"Lista de musicas invalida.\"}", true)
        }
        if (sl.exportCount(ids) == 0) return respond(out, 404, "{\"error\":\"Nada para exportar.\"}", true)
        val name = (q["name"] ?: "").replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifEmpty { "playlist" }
        val encoded = java.net.URLEncoder.encode("$name.sincro", "UTF-8").replace("+", "%20")
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$encoded\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"   // sem Content-Length: o fim do arquivo é o fim da conexão
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        sl.exportTo(out, ids)
        out.flush()
    }

    /** Recebe um .sincro (corpo = o arquivo). ?mode=replace troca a playlist; senão junta. */
    private fun handleSetlistImport(req: Request, out: OutputStream) {
        val sl = setlist ?: return respond(out, 404, "{\"error\":\"Repertorio indisponivel.\"}", true)
        val input = req.stream ?: return respond(out, 400, "{\"error\":\"Sem arquivo.\"}", true)
        val replace = CalibrationHub.parseQuery(req.path)["mode"] == "replace"
        // lê só o tamanho combinado do corpo
        val limited = object : InputStream() {
            private var left = req.streamLength
            override fun read(): Int {
                if (left <= 0) return -1
                val b = input.read()
                if (b >= 0) left--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = input.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            fun drain() { val buf = ByteArray(65536); while (left > 0 && read(buf, 0, buf.size) > 0) { /* descarta o resto */ } }
        }
        val r = runCatching { sl.importFrom(limited, replace) }.getOrElse { Pair(400, JSONObject().put("error", "Arquivo .sincro invalido.")) }
        runCatching { limited.drain() }
        respond(out, r.first, r.second.toString(), true)
    }

    private fun marksJson(): JSONObject {
        val o = JSONObject()
        scoreMarks.forEach { (sig, v) -> o.put(sig, JSONObject().put("marks", org.json.JSONArray(v.first)).put("offset", v.second)) }
        return o
    }

    private fun handleScorePart(req: Request, out: OutputStream, close: Boolean) {
        val body = runCatching { JSONObject(req.body) }.getOrNull()
        val group = body?.optString("group").orEmpty()
        val part = body?.optInt("part", -1) ?: -1
        val ok = setScorePart(group, part)
        if (!ok) return respond(out, 400, "{\"error\":\"Grupo ou parte invalidos (importe a partitura antes).\"}", close)
        respond(out, 200, scoreJson(), close)
    }

    private fun serveScoreFile(out: OutputStream, rawPath: String, close: Boolean) {
        val id = CalibrationHub.parseQuery(rawPath)["id"]?.toLongOrNull()
        val bytes = synchronized(scoreLock) { if (id != null && id == scoreId) scoreBytes else null }
        if (bytes == null) return respond(out, 404, "{\"error\":\"Nao encontrado.\"}", close)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: ${if (close) "close" else "keep-alive"}\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1) + bytes)
        out.flush()
    }

    var port: Int = 0
        private set

    /** Abre o servidor e devolve a porta efetivamente usada. */
    fun start(): Int {
        val ss = bind(preferredPort)
        serverSocket = ss
        port = ss.localPort
        pool.execute { acceptLoop(ss) }
        hub(Groups.DEFAULT_ID)
        heartbeat.scheduleWithFixedDelay({
            broadcastRaw(": ping\n\n")
            broadcastAudioRaw(": ping\n\n")
        }, 10, 10, TimeUnit.SECONDS)
        return port
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        hubs.values.forEach { it.onChange = null }
        sseClients.forEach { runCatching { it.socket.close() } }
        sseClients.clear()
        audioSseClients.forEach { runCatching { it.socket.close() } }
        audioSseClients.clear()
        heartbeat.shutdownNow()
        pool.shutdownNow()
    }

    private fun bind(requested: Int): ServerSocket {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(requested))
            }
        } catch (e: BindException) {
            // Porta ocupada: deixa o sistema escolher outra.
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            try {
                pool.execute { handle(socket) }
            } catch (e: Exception) {
                runCatching { socket.close() }
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        var keepOpen = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 60_000
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()
            while (true) {
                val request = readRequest(input) ?: break
                if (request.method == "GET" && request.path.substringBefore('?') == "/events") {
                    startSse(socket, output)
                    keepOpen = true
                    return
                }
                if (request.method == "GET" && request.path.substringBefore('?') == "/events/audio") {
                    val group = CalibrationHub.parseQuery(request.path)["group"] ?: Groups.DEFAULT_ID
                    val groupHub = hub(group)
                    if (groupHub == null) {
                        respond(output, 404, "{\"error\":\"Grupo nao encontrado.\"}", true)
                        return
                    }
                    startSse(socket, output, audioSseClients, audioEvent(groupHub.snapshotJson()), group)
                    keepOpen = true
                    return
                }
                // exportar/abrir .sincro usa a conexão até o fim: depois dela, fecha
                val close = request.headers["connection"].equals("close", ignoreCase = true) || request.stream != null ||
                    request.path.substringBefore('?') == "/api/setlist/export"
                route(request, output, close)
                if (close) break
            }
        } catch (e: Exception) {
            // Cliente desconectou ou enviou algo inválido: encerra esta conexão.
        } finally {
            if (!keepOpen) runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 8192) throw IOException("Linha longa demais")
        }
        return sb.toString("UTF-8")
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("Requisição inválida")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("Fim inesperado")
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        if (parts[0] == "POST" && parts[1].substringBefore('?') == "/api/setlist/import") {
            val big = headers["content-length"]?.toLongOrNull() ?: -1L
            if (big < 0 || big > 2_000_000_000L) throw IOException("Corpo grande demais")
            return Request(parts[0], parts[1], headers, "", nowMs(), input, big)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > 65_536) throw IOException("Corpo grande demais")
        val body = if (length > 0) {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n == -1) throw IOException("Fim inesperado")
                read += n
            }
            String(bytes, Charsets.UTF_8)
        } else ""
        return Request(parts[0], parts[1], headers, body, nowMs())
    }

    private fun route(req: Request, out: OutputStream, close: Boolean) {
        val path = req.path.substringBefore('?')
        when {
            req.method == "GET" && path == "/api/state" ->
                respond(out, 200, state.toJson(), close)

            req.method == "POST" && path == "/api/sync" -> {
                val clientSentAt = runCatching { JSONObject(req.body).optLong("clientSentAt", 0) }.getOrDefault(0)
                val sentAt = nowMs()
                val json = String.format(
                    Locale.ROOT,
                    "{\"clientSentAt\":%d,\"serverReceivedAt\":%.3f,\"serverSentAt\":%.3f}",
                    clientSentAt, req.receivedAtMs, sentAt,
                )
                respond(out, 200, json, close)
            }

            req.method == "POST" && path == "/api/command" -> handleCommand(req, out, close)

            req.method == "GET" && path == "/api/audio/file" -> serveAudioFile(out, req.path, close)

            req.method == "GET" && path == "/api/groups" -> respond(out, 200, groupsJson(), close)

            req.method == "POST" && path == "/api/groups" -> handleGroups(req, out, close)

            req.method == "GET" && path == "/api/score" -> respond(out, 200, scoreJson(), close)

            req.method == "GET" && path == "/api/score/file" -> serveScoreFile(out, req.path, close)

            req.method == "POST" && path == "/api/score/clear" -> {
                clearScore()
                respond(out, 200, scoreJson(), close)
            }

            req.method == "POST" && path == "/api/score/part" -> handleScorePart(req, out, close)

            (req.method == "GET" || req.method == "POST") && path == "/api/setlist" -> handleSetlist(req, out, close)

            req.method == "GET" && path == "/api/setlist/export" -> handleSetlistExport(req, out)

            req.method == "POST" && path == "/api/setlist/import" -> handleSetlistImport(req, out)

            req.method == "POST" && path == "/api/score/marks" -> {
                val body = runCatching { JSONObject(req.body) }.getOrNull()
                val sig = body?.optString("sig").orEmpty()
                val arr = body?.optJSONArray("marks")
                val list = arr?.let { a -> List(a.length()) { a.optDouble(it, Double.NaN) } }
                val ok = body != null && (list == null || list.none { it.isNaN() }) && setScoreMarks(sig, list, body.optDouble("offset", 0.0))
                if (ok) respond(out, 200, scoreJson(), close)
                else respond(out, 400, "{\"error\":\"Marcadores invalidos.\"}", close)
            }

            req.method == "POST" && path == "/api/score/pos" -> {
                val body = runCatching { JSONObject(req.body) }.getOrNull()
                if (body == null) respond(out, 400, "{\"error\":\"JSON invalido.\"}", close)
                else {
                    setScorePos(if (body.isNull("ms") || !body.has("ms")) null else body.optDouble("ms", 0.0))
                    respond(out, 200, scoreJson(), close)
                }
            }

            path.startsWith("/api/audio") -> handleAudio(req, out, close)

            req.method == "GET" && path == "/api/mix" -> respond(out, 200, mix.toJson(), close)

            req.method == "POST" && path == "/api/mix" -> {
                val body = runCatching { JSONObject(req.body) }.getOrNull()
                val id = body?.optString("device").orEmpty()
                if (body == null || id.isEmpty() || !body.has("level")) {
                    respond(out, 400, "{\"error\":\"Pedido invalido.\"}", close)
                } else {
                    mix.set(id, body.optDouble("level", 1.0))
                    respond(out, 200, mix.toJson(), close)
                }
            }

            path.startsWith("/api/calib") -> {
                val reply = calibration.handle(req.method, req.path, req.body)
                respond(out, reply.status, reply.json, close)
            }

            req.method == "GET" && !path.startsWith("/api/") -> serveAsset(out, path, close)

            req.method != "GET" && req.method != "POST" ->
                respond(out, 405, "{\"error\":\"Metodo nao permitido.\"}", close)

            else -> respond(out, 404, "{\"error\":\"Nao encontrado.\"}", close)
        }
    }

    private fun handleAudio(req: Request, out: OutputStream, close: Boolean) {
        if (req.method == "POST" && req.path.contains("/api/audio/cmd") &&
            runCatching { JSONObject(req.body).optString("type") }.getOrNull() == "play"
        ) setScorePos(null)    // ao tocar, o cursor volta a seguir o relógio
        val group = CalibrationHub.parseQuery(req.path)["group"] ?: Groups.DEFAULT_ID
        if (group == "all" && req.method == "POST") {
            // Um comando para todos os grupos com arquivo, com o MESMO instante de início/parada.
            val at = nowMs()
            var ok = 0
            var last: AudioHub.Reply? = null
            for (id in groups.ids()) {
                val h = hub(id) ?: continue
                if (!h.hasFile()) continue
                val r = h.handle(req.method, req.path, req.body, at)
                if (r.status == 200) ok++ else last = r
            }
            if (ok == 0) {
                val r = last ?: AudioHub.Reply(400, "{\"error\":\"Nenhum grupo tem arquivo de audio.\"}")
                respond(out, r.status, r.json, close)
            } else {
                respond(out, 200, groupsJson(), close)
            }
            return
        }
        val h = hub(group)
        if (h == null) {
            respond(out, 404, "{\"error\":\"Grupo nao encontrado.\"}", close)
            return
        }
        val reply = h.handle(req.method, req.path, req.body)
        respond(out, reply.status, reply.json, close)
    }

    /** {"rev":N,"groups":[{"id","name","file","playing","color"}],"assign":{"dispositivo":"grupo"}} */
    private fun groupsJson(): String {
        val arr = org.json.JSONArray()
        for (id in groups.ids()) {
            val h = hubs[id]
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("name", groups.name(id))
                    .put("file", h?.fileName() ?: JSONObject.NULL)
                    .put("playing", h?.isPlaying() ?: false)
                    .put("color", groups.color(id) ?: com.metronomo.diag.Groups.defaultColor(id)),
            )
        }
        val assign = JSONObject()
        groups.assignments().forEach { (k, v) -> assign.put(k, v) }
        return JSONObject().put("rev", groups.revision()).put("groups", arr).put("assign", assign).toString()
    }

    private fun handleGroups(req: Request, out: OutputStream, close: Boolean) {
        val body = runCatching { JSONObject(req.body) }.getOrNull()
        if (body == null) {
            respond(out, 400, "{\"error\":\"JSON invalido.\"}", close)
            return
        }
        val ok = when (body.optString("op")) {
            "add" -> groups.add(body.optString("name")) != null
            "rename" -> groups.rename(body.optString("id"), body.optString("name"))
            "remove" -> {
                val id = body.optString("id")
                val removed = groups.remove(id)
                if (removed) {
                    synchronized(scoreLock) { scoreParts.remove(id) }
                    onScoreChanged?.invoke()
                    hubs.remove(id)?.let { it.onChange = null; it.clear() }
                    audioSseClients.filter { it.group == id }.forEach {
                        runCatching { it.socket.close() }
                        audioSseClients.remove(it)
                    }
                }
                removed
            }
            "assign" -> groups.assign(body.optString("device"), body.optString("group"))
            "color" -> groups.setColor(body.optString("id"), body.optString("color"))
            else -> false
        }
        if (!ok) {
            respond(out, 400, "{\"error\":\"Operacao de grupo invalida.\"}", close)
            return
        }
        onGroupsChanged?.invoke()
        respond(out, 200, groupsJson(), close)
    }

    private fun handleCommand(req: Request, out: OutputStream, close: Boolean) {
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            return respond(out, 400, "{\"error\":\"JSON invalido.\"}", close)
        }
        val type = body.optString("type")
        if (type !in listOf("play", "pause", "restart", "settings")) {
            return respond(out, 400, "{\"error\":\"Comando invalido.\"}", close)
        }
        if (type == "play" || type == "restart") setScorePos(null)
        val bpm = body.optDouble("bpm", Double.NaN)
        val beats = body.optInt("beatsPerBar", -1)
        if (type != "pause") {
            val valid = bpm.isFinite() && bpm >= MetronomeState.MIN_BPM && bpm <= MetronomeState.MAX_BPM &&
                beats in MetronomeState.MIN_BEATS..MetronomeState.MAX_BEATS
            if (!valid) return respond(out, 400, "{\"error\":\"BPM ou compasso invalido.\"}", close)
        }

        val next = synchronized(stateLock) {
            val current = state
            val nowNow = nowMs()
            val wanted = body.optDouble("startAt", Double.NaN)   // início combinado com a música (opcional)
            val startAt = if (!wanted.isNaN() && wanted >= nowNow - 3_600_000.0 && wanted <= nowNow + 15000.0) wanted.toLong() else (nowNow + LEAD_MS).toLong()
            val changed = when (type) {
                "pause" -> current.copy(playing = false, startAt = startAt)
                // tempo ao vivo: com o metrônomo tocando, mantém a batida e a posição na música (só muda a velocidade)
                "settings" -> current.copy(
                    bpm = bpm,
                    beatsPerBar = beats,
                    startAt = keepPhase(current, bpm, nowNow),
                )
                else -> current.copy(bpm = bpm, beatsPerBar = beats, playing = true, startAt = startAt)
            }
            val updated = changed.copy(revision = current.revision + 1)
            state = updated
            updated
        }
        // Envia aos ouvintes fora do lock; os clientes ignoram revisões antigas.
        pool.execute { broadcastRaw(sseEvent(next)) }
        respond(out, 200, next.toJson(), close)
    }

    private fun serveAsset(out: OutputStream, path: String, close: Boolean) {
        val manager = assets
        val clean = if (path == "/") "/index.html" else path
        val bytes = if (manager == null || clean.contains("..") || clean.contains('\\')) {
            null
        } else {
            try {
                manager.open("web$clean").use { it.readBytes() }
            } catch (e: IOException) {
                null
            }
        }
        if (bytes == null) return respond(out, 404, "{\"error\":\"Nao encontrado.\"}", close)
        val type = when (clean.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "webmanifest" -> "application/manifest+json; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $type\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: ${if (close) "close" else "keep-alive"}\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1) + bytes)
        out.flush()
    }

    /** Envia o arquivo de áudio da sessão por inteiro (sem Range; os aparelhos baixam uma vez). */
    private fun serveAudioFile(out: OutputStream, rawPath: String, close: Boolean) {
        val query = CalibrationHub.parseQuery(rawPath)
        val id = query["id"]?.toLongOrNull()
        val info = if (id == null) null else hub(query["group"] ?: Groups.DEFAULT_ID)?.currentFile(id)
        if (info == null || !info.path.isFile) {
            return respond(out, 404, "{\"error\":\"Nao encontrado.\"}", close)
        }
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${info.path.length()}\r\n" +
            "Connection: ${if (close) "close" else "keep-alive"}\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        info.path.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        out.flush()
    }

    private fun sseEvent(s: MetronomeState) = "event: state\ndata: ${s.toJson()}\n\n"

    private fun audioEvent(json: String) = "event: audio\ndata: $json\n\n"

    private fun broadcastAudio(group: String, json: String) = broadcastAudioRaw(audioEvent(json), group)

    /** [group] null = todos os ouvintes (usado no "ping" de manutenção). */
    private fun broadcastAudioRaw(text: String, group: String? = null) {
        for (client in audioSseClients) {
            if (group != null && client.group != group) continue
            if (!client.send(text)) audioSseClients.remove(client)
        }
    }

    private fun startSse(
        socket: Socket,
        out: OutputStream,
        clients: CopyOnWriteArrayList<SseClient> = sseClients,
        first: String = sseEvent(state),
        group: String = "",
    ) {
        socket.soTimeout = 0
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache, no-transform\r\n" +
            "Connection: close\r\n\r\n"
        val client = SseClient(socket, out, group)
        if (!client.send(head + first)) return
        clients.add(client)
    }

    private fun broadcastRaw(text: String) {
        for (client in sseClients) {
            if (!client.send(text)) sseClients.remove(client)
        }
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        body: String,
        close: Boolean,
        contentType: String = "application/json; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: ${if (close) "close" else "keep-alive"}\r\n\r\n"
        // Uma única escrita, para a resposta sair num só pacote.
        out.write(head.toByteArray(Charsets.ISO_8859_1) + bytes)
        out.flush()
    }

    companion object {
        /** Antecedência do início, para todos os aparelhos terem tempo de agendar. */
        const val LEAD_MS = 3000
    }
}
