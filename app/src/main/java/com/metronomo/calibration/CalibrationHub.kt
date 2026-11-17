package com.metronomo.calibration

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Coordena a calibração no servidor (anfitrião), em endpoints próprios (/api/calib...),
 * sem mexer no estado nem no SSE do metrônomo.
 *
 *   GET  /api/calib?device=ID&name=NOME&rec=0|1&host=0|1&auto=MS&cal=0|1&route=TIPO
 *        registra o aparelho e devolve a sessão atual
 *   POST /api/calib/start  {"initiator":ID}                          calibração de todos
 *   (em qualquer modo, "cable":true = calibração por cabo: o som vai por cabo a uma mesa que o soma
 *    e só o aparelho que iniciou grava, pela entrada ligada à saída da mesa)
 *   POST /api/calib/start  {"initiator":ID,"mode":"single","partner":ID}   calibra só o iniciador contra um aparelho já calibrado
 *   POST /api/calib/result {...}                                      cada gravador envia suas medições
 *
 * A referência é sempre o anfitrião: o ajuste de cada aparelho é "quanto ele soa depois do
 * anfitrião". Na calibração em par, o ajuste do aparelho novo é a medida contra o parceiro
 * somada ao ajuste que o parceiro já tem (0 se o parceiro for o anfitrião).
 */
class CalibrationHub(private val nowMs: () -> Double) {

    class Reply(val status: Int, val json: String)

    private class Peer(
        var name: String,
        var lastSeenMs: Double,
        var canRecord: Boolean,
        var isHost: Boolean,
        var autoMs: Double,
        var calibrated: Boolean,
        var route: String,
    )

    private val lock = Any()
    private val peers = LinkedHashMap<String, Peer>()

    private var id = 0L
    private var state = "idle" // idle | running | done | failed
    private var mode = "all" // all | single
    private var cable = false // sinal entregue por cabo (mesa de som): só o aparelho que iniciou grava
    private var startAt = 0L
    private var initiator = ""
    private var reference = "" // índice 0 de devices (anfitrião, ou o parceiro na calibração em par)
    private var target = ""
    private var partnerAutoMs = 0.0
    private var devices: List<Pair<String, String>> = emptyList() // id, nome
    private var recorders: List<String> = emptyList()
    private var results = JSONObject()
    private var message = ""
    private var audioEndMs = 0.0
    private var deadlineMs = 0.0

    private val measurements = ArrayList<CalibrationDsp.Measurement>()
    private val reported = HashSet<String>()
    private val errors = ArrayList<String>()

    fun handle(method: String, rawPath: String, body: String): Reply {
        val path = rawPath.substringBefore('?')
        return synchronized(lock) {
            advance()
            when {
                method == "GET" && path == "/api/calib" -> {
                    register(parseQuery(rawPath))
                    Reply(200, sessionJson())
                }

                method == "POST" && path == "/api/calib/start" -> start(body)
                method == "POST" && path == "/api/calib/result" -> result(body)
                else -> fail(404, "Não encontrado.")
            }
        }
    }

    private fun register(query: Map<String, String>) {
        val device = query["device"].orEmpty()
        if (device.isEmpty()) return
        val peer = peers.getOrPut(device) { Peer("Aparelho", nowMs(), false, false, 0.0, false, "") }
        peer.name = query["name"].orEmpty().ifBlank { "Aparelho" }
        peer.lastSeenMs = nowMs()
        peer.canRecord = query["rec"] == "1"
        peer.isHost = query["host"] == "1"
        peer.autoMs = query["auto"]?.toDoubleOrNull() ?: 0.0
        peer.calibrated = query["cal"] == "1"
        peer.route = query["route"].orEmpty()
    }

    private fun livePeers(): Map<String, Peer> {
        val now = nowMs()
        return peers.filter { now - it.value.lastSeenMs < PEER_TTL_MS }
    }

    private fun hostId(): String = livePeers().entries.firstOrNull { it.value.isHost }?.key ?: ""

    private fun start(body: String): Reply {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return fail(400, "JSON inválido.")
        val initiatorId = o.optString("initiator").ifEmpty { o.optString("recorder") }
        val startMode = o.optString("mode").ifEmpty { "all" }
        val startCable = o.optBoolean("cable", false)
        if (state == "running") return fail(400, "Já existe uma calibração em andamento.")
        val live = livePeers()
        if (initiatorId.isEmpty() || initiatorId !in live) {
            return fail(400, "Este aparelho não está registrado na sessão. Tente de novo em alguns segundos.")
        }

        val ordered: List<String>
        var targetId = ""
        var partnerAuto = 0.0
        if (startMode == "single") {
            val partnerId = o.optString("partner")
            val partner = live[partnerId] ?: return fail(400, "O aparelho escolhido não está mais na sessão.")
            if (partnerId == initiatorId) return fail(400, "Escolha outro aparelho como referência.")
            if (!partner.isHost && !partner.calibrated) {
                return fail(400, "O aparelho escolhido ainda não foi calibrado. Escolha o anfitrião ou um aparelho já calibrado.")
            }
            partnerAuto = if (partner.isHost) 0.0 else partner.autoMs
            ordered = listOf(partnerId, initiatorId)
            targetId = initiatorId
        } else {
            if (live.size < 2) return fail(400, "Conecte pelo menos um outro aparelho com este app para calibrar.")
            // A referência é sempre o anfitrião (se ele não estiver na lista, quem iniciou).
            val host = hostId().ifEmpty { initiatorId }
            ordered = listOf(host) + live.keys.filter { it != host }
        }

        // Por cabo, só quem iniciou grava (os outros aparelhos não estão ligados à saída da mesa).
        val recorderIds = if (startCable) {
            listOf(initiatorId).filter { live.getValue(it).canRecord }
        } else {
            ordered.filter { live.getValue(it).canRecord }
        }
        if (recorderIds.isEmpty()) {
            return fail(
                400,
                if (startCable) "No modo cabo, o aparelho que inicia grava o sinal da mesa: permita o microfone nele."
                else "Nenhum aparelho com o microfone permitido. Permita o microfone em pelo menos um aparelho.",
            )
        }
        devices = ordered.map { it to live.getValue(it).name }
        recorders = recorderIds
        initiator = initiatorId
        reference = ordered[0]
        target = targetId
        partnerAutoMs = partnerAuto
        mode = startMode
        cable = startCable
        id++
        state = "running"
        startAt = (nowMs() + LEAD_MS).toLong()
        results = JSONObject()
        message = ""
        measurements.clear()
        reported.clear()
        errors.clear()
        val totalMs = (REPEATS + 1).toLong() * devices.size * SLOT_MS
        audioEndMs = startAt + totalMs + 1000.0
        deadlineMs = audioEndMs + DEADLINE_AFTER_MS
        return Reply(200, sessionJson())
    }

    private fun result(body: String): Reply {
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            return fail(400, "JSON inválido.")
        }
        if (o.optLong("id", -1) != id || state != "running") {
            return fail(400, "Sessão de calibração inválida ou já encerrada.")
        }
        val recorderId = o.optString("recorder")
        if (recorderId !in recorders || recorderId in reported) {
            return fail(400, "Gravador inesperado.")
        }
        reported.add(recorderId)
        val recorderIndex = devices.indexOfFirst { it.first == recorderId }

        val err = o.optString("error")
        if (err.isNotBlank()) {
            errors.add(err)
        } else {
            val list = o.optJSONArray("measurements") ?: JSONArray()
            for (i in 0 until list.length()) {
                val m = list.optJSONObject(i) ?: continue
                val deviceIndex = devices.indexOfFirst { it.first == m.optString("device") }
                val offset = m.optDouble("offsetMs", Double.NaN)
                if (deviceIndex < 0 || deviceIndex == recorderIndex || offset.isNaN()) continue
                val spread = m.optDouble("spreadMs", 0.0).let { if (it.isNaN()) 0.0 else it }
                measurements.add(CalibrationDsp.Measurement(recorderIndex, deviceIndex, offset, spread, m.optInt("count", 0)))
            }
        }
        advance()
        return Reply(200, sessionJson())
    }

    /** Decide se já dá para fechar a calibração (todos enviaram, ou passou a tolerância). */
    private fun advance() {
        if (state != "running") return
        val now = nowMs()
        val allIn = recorders.isNotEmpty() && reported.containsAll(recorders)
        val graceOver = now > audioEndMs + GRACE_MS && reported.isNotEmpty()
        if (allIn || graceOver) {
            finish()
        } else if (now > deadlineMs) {
            state = "failed"
            message = errors.firstOrNull() ?: "Tempo esgotado: nenhum aparelho enviou medições."
        }
    }

    private fun finish() {
        if (measurements.isEmpty()) {
            state = "failed"
            message = errors.firstOrNull()
                ?: if (cable) {
                    "Nenhum aparelho foi medido pela mesa. Confira os cabos, os canais ligados e o nível do sinal."
                } else {
                    "Nenhum aparelho conseguiu medir os outros. Aumente o volume, aproxime os aparelhos e fique em silêncio."
                }
            return
        }
        val solution = CalibrationDsp.solve(devices.size, measurements)
        // Menor número de repetições aproveitadas numa ligação que envolve cada aparelho (para a confiança).
        val minCounts = IntArray(devices.size) { Int.MAX_VALUE }
        for (m in measurements) {
            if (m.count < minCounts[m.device]) minCounts[m.device] = m.count
            if (m.count < minCounts[m.recorder]) minCounts[m.recorder] = m.count
        }
        fun confidence(i: Int) = CalibrationDsp.confidence(solution.spreadMs[i], if (minCounts[i] == Int.MAX_VALUE) 0 else minCounts[i])
        val out = JSONObject()
        if (mode == "single") {
            val measured = solution.offsetsMs[1]
            if (measured.isNaN()) {
                state = "failed"
                message = "Não consegui medir este aparelho. Encoste-o no outro, aumente o volume e fique em silêncio."
                return
            }
            // ajuste contra o anfitrião = medida contra o parceiro + ajuste do parceiro
            out.put(
                target,
                JSONObject()
                    .put("name", devices[1].second)
                    .put("offsetMs", measured + partnerAutoMs)
                    .put("spreadMs", solution.spreadMs[1])
                    .put("quality", confidence(1))
                    .put("count", solution.links[1]),
            )
        } else {
            for ((i, device) in devices.withIndex()) {
                val offset = solution.offsetsMs[i]
                if (offset.isNaN()) continue
                out.put(
                    device.first,
                    JSONObject()
                        .put("name", device.second)
                        .put("offsetMs", offset)
                        .put("spreadMs", solution.spreadMs[i])
                        .put("quality", confidence(i))
                        .put("count", solution.links[i]),
                )
            }
        }
        results = out
        state = "done"
        message = ""
    }

    private fun sessionJson(): String {
        val list = JSONArray()
        for ((deviceId, name) in devices) list.put(JSONObject().put("id", deviceId).put("name", name))
        val peerList = JSONArray()
        for ((peerId, p) in livePeers()) {
            peerList.put(
                JSONObject()
                    .put("id", peerId)
                    .put("name", p.name)
                    .put("host", p.isHost)
                    .put("calibrated", p.calibrated)
                    .put("canRecord", p.canRecord)
                    .put("route", p.route),
            )
        }
        return JSONObject()
            .put("id", id)
            .put("state", state)
            .put("mode", mode)
            .put("cable", cable)
            .put("amp", if (cable) CABLE_AMPLITUDE else ACOUSTIC_AMPLITUDE)
            .put("startAt", startAt)
            .put("slotMs", SLOT_MS)
            .put("repeats", REPEATS)
            .put("host", hostId())
            .put("recorder", reference) // aparelho de referência desta medição (índice 0)
            .put("target", target)
            .put("recorders", JSONArray(recorders))
            .put("devices", list)
            .put("peers", peerList)
            .put("results", results)
            .put("message", message)
            .toString()
    }

    private fun fail(status: Int, text: String) =
        Reply(status, JSONObject().put("error", text).toString())

    companion object {
        const val SLOT_MS = 600
        const val REPEATS = 4

        /** Volume do som de teste: no ar, alto; por cabo, mais baixo para não saturar a entrada. */
        const val ACOUSTIC_AMPLITUDE = 0.9
        const val CABLE_AMPLITUDE = 0.5

        /** Antecedência do 1º som, para todos receberem a sessão e se prepararem. */
        const val LEAD_MS = 4500
        private const val PEER_TTL_MS = 6000.0
        private const val GRACE_MS = 10_000.0
        private const val DEADLINE_AFTER_MS = 30_000.0

        fun parseQuery(rawPath: String): Map<String, String> {
            val q = rawPath.substringAfter('?', "")
            if (q.isEmpty()) return emptyMap()
            val out = HashMap<String, String>()
            for (pair in q.split('&')) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                out[pair.substring(0, idx)] = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault("")
            }
            return out
        }
    }
}
