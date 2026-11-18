package com.metronomo.calibration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.metronomo.diag.AudioClockTracker
import com.metronomo.net.ClockSync
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Lado do cliente da calibração automática. Todos os aparelhos tocam o som de teste nos
 * horários combinados; os que têm o microfone permitido também gravam, medem todos os
 * outros e enviam as medições ao anfitrião, que combina tudo e devolve o ajuste de cada um.
 */
class CalibrationManager(
    private val baseUrl: String,
    private val clock: ClockSync,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: () -> String,
    /** Este aparelho é o anfitrião da sessão (a referência de todas as calibrações). */
    private val isHost: Boolean,
    /** Diz se este aparelho pode gravar (permissão de microfone concedida). */
    private val canRecord: () -> Boolean,
    /** Ajuste automático efetivamente em uso agora (ms), para o anfitrião poder usá-lo como parceiro. */
    private val autoOffset: () -> Double,
    /** Este aparelho tem uma calibração válida (contra o anfitrião atual) para a saída em uso? */
    private val calibrated: () -> Boolean,
    /** Tipo da saída de áudio em uso (SPEAKER, WIRED, USB, BLUETOOTH). */
    private val routeName: () -> String,
    /** Chamado quando o anfitrião informa o próprio id (para validar calibrações guardadas). */
    private val onHost: (String) -> Unit,
    private val onUi: (Ui) -> Unit,
    /**
     * Chamado com o ajuste (ms, positivo = tocar mais cedo), o id do anfitrião de referência, a confiança
     * ("high"/"medium"/"low") e a variação (ms). Devolve false se o resultado NÃO foi aplicado
     * (baixa confiança, com uma calibração melhor já em uso).
     */
    private val onApplyOffset: (Double, String, String, Double) -> Boolean,
) {
    /** Aparelho visto pelo anfitrião: [host] é a referência; [calibrated] pode servir de parceiro. */
    data class PeerInfo(
        val id: String,
        val name: String,
        val host: Boolean,
        val calibrated: Boolean,
        val me: Boolean,
    )

    data class Ui(
        val supported: Boolean = true,
        val running: Boolean = false,
        val status: String = "",
        val lines: List<String> = emptyList(),
        val peers: List<PeerInfo> = emptyList(),
    )

    private var ui = Ui()
    private var lastStartedId = Long.MIN_VALUE
    private var lastResultId = Long.MIN_VALUE
    private var firstResponse = true

    @Volatile
    private var playerClockState: AudioClockTracker.State? = null

    fun start() {
        scope.launch(Dispatchers.IO) { pollLoop() }
    }

    @Synchronized
    private fun publish(change: (Ui) -> Ui) {
        ui = change(ui)
        onUi(ui)
    }

    /**
     * Pede ao anfitrião para iniciar. Sem [partnerId], calibra todos contra o anfitrião; com ele,
     * calibra só este aparelho contra o parceiro (o anfitrião ou um aparelho já calibrado).
     * Devolve null se deu certo, ou a mensagem de erro.
     */
    suspend fun requestStart(partnerId: String? = null, cable: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("initiator", deviceId)
            if (partnerId != null) body.put("mode", "single").put("partner", partnerId)
            if (cable) body.put("cable", true)
            val bodyText = body.toString()
            val (code, text) = request("POST", "/api/calib/start", bodyText)
            if (code in 200..299) {
                null
            } else {
                runCatching { JSONObject(text).optString("error") }.getOrNull()
                    .takeUnless { it.isNullOrBlank() } ?: "Não foi possível iniciar a calibração."
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Não foi possível falar com o anfitrião."
        }
    }

    /**
     * Autocalibração: este aparelho toca e grava o próprio som, sem outro aparelho nem referência.
     * O resultado é absoluto (contra o relógio compartilhado) e é guardado com a marca [SELF_REF].
     */
    fun startSelf() {
        if (ui.running) return
        publish {
            it.copy(
                running = true,
                lines = emptyList(),
                status = "Autocalibrando… este aparelho toca e ouve o próprio som (uns 6 segundos). Fique em silêncio e não mexa nele.",
            )
        }
        scope.launch(Dispatchers.Default) {
            try {
                val previous = if (calibrated()) autoOffset() else Double.NaN
                val o = SelfCalibrator(clock).run()
                playerClockState = o.clockState
                if (!o.ok) {
                    publish { it.copy(running = false, status = o.message) }
                    return@launch
                }
                val applied = onApplyOffset(o.offsetMs, SELF_REF, o.quality, o.spreadMs)
                val clockWarning = when (o.clockState) {
                    AudioClockTracker.State.FALLBACK, AudioClockTracker.State.UNSTABLE ->
                        " Atenção: o áudio deste aparelho não informou horários precisos de saída; o valor pode variar de uma medição para outra."
                    else -> ""
                }
                val before = if (previous.isNaN()) "" else String.format(Locale.ROOT, " Valor anterior desta saída: %+.1f ms.", previous)
                val text = when {
                    !applied -> String.format(
                        Locale.ROOT,
                        "Resultado de baixa confiança (variação %.1f ms): mantive a calibração anterior. Tente de novo em silêncio, com o aparelho mais perto do microfone.",
                        o.spreadMs,
                    )
                    o.quality == "low" -> String.format(
                        Locale.ROOT,
                        "Autocalibração aplicada com BAIXA confiança (variação %.1f ms, ajuste %+.1f ms). Refaça em silêncio.%s",
                        o.spreadMs, o.offsetMs, before,
                    )
                    else -> String.format(
                        Locale.ROOT,
                        "Autocalibração concluída (confiança %s, variação %.1f ms, %d de %d sons). Este aparelho foi ajustado em %+.1f ms.%s",
                        qualityLabel(o.quality), o.spreadMs, o.count, o.total, o.offsetMs, before,
                    )
                }
                publish { it.copy(running = false, lines = emptyList(), status = text + clockWarning) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publish { it.copy(running = false, status = "Falha na autocalibração: ${e.message}") }
            }
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val rec = if (canRecord()) 1 else 0
                val host = if (isHost) 1 else 0
                val cal = if (calibrated()) 1 else 0
                val auto = String.format(Locale.ROOT, "%.3f", autoOffset())
                val path = "/api/calib?device=${enc(deviceId)}&name=${enc(deviceName())}&rec=$rec" +
                    "&host=$host&auto=$auto&cal=$cal&route=${enc(routeName())}"
                val (code, text) = request("GET", path, null)
                if (code == 404) {
                    // Servidor sem suporte (ex.: servidor Node.js do protótipo).
                    publish { it.copy(supported = false) }
                    return
                }
                if (code in 200..299) handle(JSONObject(text))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            // Em repouso basta consultar de vez em quando; durante a calibração, mais rápido.
            delay(if (ui.running) 500 else 1500)
        }
    }

    private fun handle(json: JSONObject) {
        val id = json.optLong("id", 0)
        val state = json.optString("state")

        val hostId = json.optString("host")
        if (hostId.isNotEmpty()) onHost(hostId)
        val peerArray = json.optJSONArray("peers")
        if (peerArray != null) {
            val list = (0 until peerArray.length()).map {
                val p = peerArray.getJSONObject(it)
                PeerInfo(
                    id = p.optString("id"),
                    name = p.optString("name"),
                    host = p.optBoolean("host"),
                    calibrated = p.optBoolean("calibrated"),
                    me = p.optString("id") == deviceId,
                )
            }
            if (list != ui.peers) publish { it.copy(peers = list) }
        }

        if (firstResponse) {
            firstResponse = false
            // Resultados de sessões antigas não devem ser aplicados ao entrar.
            if (state != "running") {
                lastStartedId = id
                lastResultId = id
                return
            }
        }

        when (state) {
            "running" -> if (id > lastStartedId) {
                lastStartedId = id
                participate(json)
            }

            "done", "failed" -> if (id > lastResultId) {
                lastResultId = id
                showResult(json, state)
            }
        }
    }

    private fun participate(json: JSONObject) {
        val sessionId = json.optLong("id")
        val startAt = json.optLong("startAt")
        val slotMs = json.optInt("slotMs", 600)
        val repeats = json.optInt("repeats", 4)
        val cable = json.optBoolean("cable", false)
        val amplitude = json.optDouble("amp", 0.9).let { if (it.isNaN() || it <= 0.0 || it > 1.0) 0.9 else it }
        val deviceArray = json.optJSONArray("devices") ?: return
        val ids = (0 until deviceArray.length()).map { deviceArray.getJSONObject(it).optString("id") }
        val names = (0 until deviceArray.length()).map { deviceArray.getJSONObject(it).optString("name") }
        val recorderIds = ArrayList<String>()
        val recorderArray = json.optJSONArray("recorders")
        if (recorderArray != null) for (i in 0 until recorderArray.length()) recorderIds.add(recorderArray.optString(i))

        val me = ids.indexOf(deviceId)
        if (me < 0) return
        val n = ids.size
        val expectedToRecord = deviceId in recorderIds
        val isRecorder = expectedToRecord && canRecord()

        if (clock.nowServerMs() > startAt - 800) {
            publish { it.copy(running = false, status = "A calibração começou antes de este aparelho se preparar. Tente de novo.") }
            if (expectedToRecord) postResult(sessionId, error = "Aparelho não se preparou a tempo.")
            return
        }

        // Todos tocam em todas as rodadas (repeats + 1), um slot por rodada.
        val times = (0..repeats).map { r -> startAt + (r * n + me).toDouble() * slotMs }

        publish {
            it.copy(
                running = true,
                lines = emptyList(),
                status = when {
                    cable && isRecorder -> "Calibrando por cabo… este aparelho grava o sinal da mesa. Não mexa nos cabos nem no volume."
                    cable -> "Calibrando por cabo… mantenha este aparelho ligado à mesa pela saída de fone. Não mexa nos cabos nem no volume." +
                        if (routeName() == "SPEAKER") " ATENÇÃO: nenhum cabo detectado na saída de fone deste aparelho; ele está tocando pelo alto-falante." else ""
                    isRecorder -> "Calibrando… todos os aparelhos tocam e gravam. Mantenha silêncio e os aparelhos parados."
                    else -> "Calibrando… mantenha silêncio e este aparelho parado, com o alto-falante voltado para os outros."
                },
            )
        }

        scope.launch(Dispatchers.Default) {
            try {
                if (!isRecorder) {
                    playerClockState = withContext(Dispatchers.IO) { CalibrationPlayer(clock, amplitude).play(times) }
                    if (expectedToRecord) postResult(sessionId, error = "Sem permissão de microfone neste aparelho.")
                    return@launch // o resultado chega pelo anfitrião
                }

                val totalMs = (repeats + 1).toLong() * n * slotMs
                val recording = async(Dispatchers.IO) {
                    CalibrationRecorder().record(clock, startAt - 1500.0, startAt + totalMs + 1000.0)
                }
                val playing = async(Dispatchers.IO) { CalibrationPlayer(clock, amplitude).play(times) }
                playerClockState = playing.await()
                val rec = recording.await()
                if (rec == null) {
                    postResult(sessionId, error = "Não foi possível gravar o microfone. Confira a permissão.")
                    return@launch
                }

                if (cable) {
                    // Entrada de linha ou de microfone ligada à mesa: confere se o nível está utilizável.
                    var peak = 0
                    for (i in 0 until rec.length) {
                        val v = Math.abs(rec.samples[i].toInt())
                        if (v > peak) peak = v
                    }
                    if (peak >= 32_000) {
                        postResult(sessionId, error = "O sinal gravado saturou (estourou). Diminua o ganho da mesa, o volume dos aparelhos ou use um atenuador.")
                        return@launch
                    }
                    if (peak < 300) {
                        postResult(sessionId, error = "Quase nenhum sinal chegou a este aparelho. Confira os cabos, a saída da mesa e o ganho.")
                        return@launch
                    }
                }
                publish { it.copy(status = "Analisando a gravação…") }
                val slotSamples = slotMs * rec.sampleRate / 1000
                val estimatedFirst = (startAt - rec.startServerMs) * rec.sampleRate / 1000.0
                val analysis = withContext(Dispatchers.Default) {
                    CalibrationDsp.analyze(rec.samples, rec.length, rec.sampleRate, estimatedFirst, slotSamples, n, repeats, me, cable)
                }
                if (!analysis.ok) {
                    postResult(sessionId, error = analysis.error)
                    return@launch
                }

                val measurements = JSONArray()
                for (i in 0 until n) {
                    if (i == me) continue
                    val off = analysis.offsetsMs[i]
                    if (off.isNaN()) continue
                    measurements.put(
                        JSONObject()
                            .put("device", ids[i])
                            .put("offsetMs", off)
                            .put("spreadMs", analysis.spreadMs[i])
                            .put("count", analysis.counts[i]),
                    )
                }
                postResult(sessionId, measurements = measurements)
                publish { it.copy(status = "Medições enviadas. Aguardando os outros aparelhos…") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publish { it.copy(running = false, status = "Falha na calibração: ${e.message}") }
            }
        }
    }

    private fun postResult(id: Long, measurements: JSONArray? = null, error: String = "") {
        val body = JSONObject().put("id", id).put("recorder", deviceId)
        if (error.isNotBlank()) body.put("error", error) else body.put("measurements", measurements ?: JSONArray())
        try {
            request("POST", "/api/calib/result", body.toString())
        } catch (e: Exception) {
            publish { it.copy(running = false, status = "Não foi possível enviar o resultado ao anfitrião.") }
        }
    }

    private fun showResult(json: JSONObject, state: String) {
        if (state == "failed") {
            publish { it.copy(running = false, status = json.optString("message").ifBlank { "A calibração falhou." }, lines = emptyList()) }
            return
        }
        val results = json.optJSONObject("results") ?: JSONObject()
        val deviceArray = json.optJSONArray("devices")
        val single = json.optString("mode") == "single"
        val targetId = json.optString("target")
        val lines = ArrayList<String>()
        if (deviceArray != null) {
            for (i in 0 until deviceArray.length()) {
                val d = deviceArray.getJSONObject(i)
                // Na calibração em par só o aparelho novo recebe resultado.
                if (single && d.optString("id") != targetId) continue
                val r = results.optJSONObject(d.optString("id"))
                val name = d.optString("name")
                lines.add(
                    if (r == null) {
                        "$name: não foi ouvido por nenhum aparelho"
                    } else {
                        val off = r.optDouble("offsetMs")
                        val spread = r.optDouble("spreadMs")
                        val tag = if (!single && d.optString("id") == json.optString("host")) " (anfitrião, referência)" else ""
                        String.format(
                            Locale.ROOT, "%s: %+.1f ms (variação %.1f ms, confiança %s)%s",
                            name, off, spread, qualityLabel(r.optString("quality", "medium")), tag,
                        )
                    },
                )
            }
        }
        if (single && targetId != deviceId) {
            // Este aparelho foi só o parceiro de referência: não muda nada nele.
            publish { it.copy(running = false, lines = lines, status = "Calibração em par concluída. Este aparelho não foi alterado.") }
            return
        }
        val mine = results.optJSONObject(deviceId)
        if (mine == null) {
            publish { it.copy(running = false, lines = lines, status = "Este aparelho não foi ouvido por nenhum outro. Aproxime-o dos outros e aumente o volume.") }
            return
        }
        val offset = mine.optDouble("offsetMs", 0.0)
        val quality = mine.optString("quality", "medium")
        val spread = mine.optDouble("spreadMs", 0.0)
        val applied = onApplyOffset(offset, json.optString("host"), quality, spread)
        val clockWarning = when (playerClockState) {
            AudioClockTracker.State.FALLBACK, AudioClockTracker.State.UNSTABLE ->
                " Atenção: o áudio deste aparelho não informou horários precisos; o ajuste pode ter erro maior."
            else -> ""
        }
        val text = when {
            !applied -> String.format(
                Locale.ROOT,
                "Resultado de baixa confiança (variação %.1f ms): mantive a calibração anterior. Tente de novo com os aparelhos mais próximos e em silêncio.",
                spread,
            )
            quality == "low" -> String.format(
                Locale.ROOT,
                "Calibração aplicada com BAIXA confiança (variação %.1f ms, ajuste %+.1f ms). Refaça com os aparelhos mais próximos e em silêncio.",
                spread, offset,
            )
            else -> String.format(
                Locale.ROOT,
                "Calibração concluída (confiança %s, variação %.1f ms). Este aparelho foi ajustado em %+.1f ms.",
                qualityLabel(quality), spread, offset,
            )
        }
        publish { it.copy(running = false, lines = lines, status = text + clockWarning) }
    }

    private fun qualityLabel(q: String) = when (q) {
        "high" -> "alta"
        "low" -> "baixa"
        else -> "média"
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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** Marca de uma calibração absoluta (autocalibração): vale com qualquer anfitrião. */
        const val SELF_REF = "self"
    }
}
