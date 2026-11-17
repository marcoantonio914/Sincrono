package com.metronomo.net

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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta o volume que o anfitrião definiu para este aparelho (GET /api/mix, a cada ~1 s)
 * e permite ao anfitrião definir o de qualquer aparelho (POST /api/mix).
 * Se o anfitrião não responde, o último volume conhecido continua valendo.
 */
class MixClient(
    private val baseUrl: String,
    private val scope: CoroutineScope,
    private val deviceId: String,
    /** Recebe todos os níveis (id -> 0..1) a cada resposta. */
    private val onLevels: (Map<String, Double>) -> Unit,
    /** Recebe os grupos e a atribuição dos aparelhos (servidores sem grupos simplesmente não chamam). */
    private val onGroups: (GroupsInfo) -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                try {
                    val c = open("/api/mix", "GET")
                    if (c.responseCode == 200) {
                        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        onLevels(parse(text))
                    }
                    c.disconnect()
                    val g = open("/api/groups", "GET")
                    if (g.responseCode == 200) {
                        onGroups(parseGroups(g.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }))
                    }
                    g.disconnect()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Define o volume (0..1) do aparelho [id]. Devolve true se o anfitrião aceitou. */
    suspend fun set(id: String, level: Double): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().put("device", id).put("level", level).toString().toByteArray(Charsets.UTF_8)
            val c = open("/api/mix", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(payload.size)
            c.outputStream.use { it.write(payload) }
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    /** Operação de grupo: add, rename, remove ou assign (veja SessionServer). Devolve true se aceita. */
    suspend fun groupOp(payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            val c = open("/api/groups", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val ok = c.responseCode == 200
            if (ok) onGroups(parseGroups(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }))
            c.disconnect()
            ok
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    private fun parseGroups(text: String): GroupsInfo {
        val o = JSONObject(text)
        val arr = o.optJSONArray("groups")
        val groups = ArrayList<GroupInfo>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                groups.add(GroupInfo(g.getString("id"), g.optString("name"), if (g.isNull("file")) null else g.optString("file"), g.optBoolean("playing")))
            }
        }
        val assign = HashMap<String, String>()
        o.optJSONObject("assign")?.let { a -> a.keys().forEach { k -> assign[k] = a.optString(k) } }
        return GroupsInfo(o.optLong("rev"), groups.ifEmpty { GroupsInfo().groups }, assign)
    }

    private fun parse(text: String): Map<String, Double> {
        val levels = JSONObject(text).optJSONObject("levels") ?: return emptyMap()
        val out = HashMap<String, Double>()
        levels.keys().forEach { k -> out[k] = levels.optDouble(k, 1.0) }
        return out
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 3000
            useCaches = false
        }

    companion object {
        private const val POLL_MS = 1000L
    }
}
