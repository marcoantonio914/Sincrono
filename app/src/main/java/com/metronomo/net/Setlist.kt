package com.metronomo.net

import com.metronomo.audio.AudioHub
import com.metronomo.diag.Groups
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Repertório do anfitrião: músicas preparadas (partitura, áudio por grupo, parte de cada grupo, andamento, marcadores).
 * Cada música guarda CÓPIAS dos arquivos em [dir]/song-N/; carregar uma música copia de volta e troca tudo de uma vez.
 * Igual ao repertório do servidor do PC (mesmas operações e mensagens). Só troca com tudo parado.
 */
class Setlist(private val server: SessionServer, private val dir: File, private val audioDir: File, private val onGroups: () -> Unit) {
    private val lock = Any()
    private var next = 1
    private var current = -1
    private val songs = ArrayList<JSONObject>()
    private var seq = 0

    init {
        dir.mkdirs()
        audioDir.mkdirs()
        runCatching {
            val o = JSONObject(File(dir, "repertorio.json").readText())
            next = o.optInt("next", 1)
            current = o.optInt("current", -1)
            val a = o.getJSONArray("songs")
            for (i in 0 until a.length()) songs.add(a.getJSONObject(i))
        }
    }

    private fun save() {
        val o = JSONObject().put("next", next).put("current", current).put("songs", JSONArray(songs))
        val tmp = File(dir, "repertorio.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "repertorio.json"))
    }

    fun json(): JSONObject = synchronized(lock) {
        val arr = JSONArray()
        for (m in songs) {
            val audios = JSONArray()
            val a = m.optJSONArray("audio") ?: JSONArray()
            for (i in 0 until a.length()) audios.put(JSONObject().put("group", a.getJSONObject(i).getString("group")).put("name", a.getJSONObject(i).getString("name")))
            arr.put(
                JSONObject().put("id", m.getInt("id")).put("name", m.getString("name"))
                    .put("score", m.optJSONObject("score")?.optString("name") ?: JSONObject.NULL)
                    .put("audios", audios)
                    .put("bpm", if (m.has("bpm")) m.getDouble("bpm") else JSONObject.NULL)
                    .put("beats", if (m.has("beats")) m.getInt("beats") else JSONObject.NULL)
                    .put("marks", m.optJSONObject("marks")?.length() ?: 0),
            )
        }
        val cur = find(current)
        val dirty = cur != null && hasConfig() && fpLive() != fpSong(cur)
        JSONObject().put("songs", arr).put("current", if (current < 0) JSONObject.NULL else current)
            .put("dirty", dirty).put("unsaved", cur == null && hasConfig())
    }

    private fun err(status: Int, text: String) = Pair(status, JSONObject().put("error", text))
    private fun find(id: Int) = songs.firstOrNull { it.getInt("id") == id }
    private fun clean(s: String, max: Int) = s.replace(Regex("[\\p{Cntrl}]"), "").trim().take(max)
    private fun songDir(id: Int) = File(dir, "song-$id")

    /** Copia a configuração atual da sessão para [m]. */
    private fun snapshot(m: JSONObject) {
        val d = songDir(m.getInt("id"))
        d.deleteRecursively()
        d.mkdirs()
        m.remove("score")
        val sc = server.scoreSnapshot()
        if (sc != null) {
            File(d, "score.bin").writeBytes(sc.bytes)
            m.put("score", JSONObject().put("file", "score.bin").put("name", sc.name).put("size", sc.bytes.size))
        }
        val audio = JSONArray()
        for (gid in server.groups.ids()) {
            val f = server.hub(gid)?.fileInfo() ?: continue
            val fn = "audio-${audio.length() + 1}.bin"
            f.path.copyTo(File(d, fn), overwrite = true)
            audio.put(JSONObject().put("group", server.groups.name(gid)).put("file", fn).put("name", f.name).put("mime", f.mime).put("size", f.path.length()))
        }
        m.put("audio", audio)
        val parts = JSONObject()
        sc?.parts?.forEach { (g, i) -> if (server.groups.exists(g)) parts.put(server.groups.name(g), i) }
        m.put("parts", parts)
        val st = server.metronomeState()
        m.put("bpm", st.bpm).put("beats", st.beatsPerBar)
        val marks = JSONObject()
        sc?.marks?.forEach { (sig, v) -> marks.put(sig, JSONObject().put("marks", JSONArray(v.first)).put("offset", v.second)) }
        m.put("marks", marks)
    }

    private fun hasConfig(): Boolean =
        server.scoreSnapshot() != null || server.groups.ids().any { server.hub(it)?.hasFile() == true }

    private fun fp(score: String, audio: List<String>, parts: List<String>, marks: String, bpm: Double, beats: Int) =
        listOf(score, audio.sorted().joinToString(";"), parts.sorted().joinToString(";"), marks, bpm.toString(), beats.toString()).joinToString("#")

    /** "Impressão digital" da configuração viva da sessão (para saber se mudou desde que a música foi guardada). */
    private fun fpLive(): String {
        val sc = server.scoreSnapshot()
        val audio = server.groups.ids().mapNotNull { gid -> server.hub(gid)?.fileInfo()?.let { "${server.groups.name(gid)}|${it.name}|${it.path.length()}" } }
        val parts = sc?.parts?.filter { server.groups.exists(it.key) }?.map { "${server.groups.name(it.key)}=${it.value}" } ?: emptyList()
        val marks = sc?.marks?.entries?.sortedBy { it.key }?.joinToString(";") { "${it.key}:${it.value.second}:${it.value.first.joinToString(",")}" } ?: ""
        val st = server.metronomeState()
        return fp(sc?.let { "${it.name}|${it.bytes.size}" } ?: "-", audio, parts, marks, st.bpm, st.beatsPerBar)
    }

    private fun fpSong(m: JSONObject): String {
        val sc = m.optJSONObject("score")
        val a = m.optJSONArray("audio") ?: JSONArray()
        val audio = List(a.length()) { a.getJSONObject(it).let { x -> "${x.getString("group")}|${x.getString("name")}|${x.optLong("size", -1)}" } }
        val p = m.optJSONObject("parts") ?: JSONObject()
        val parts = p.keys().asSequence().map { "$it=${p.getInt(it)}" }.toList()
        val mk = m.optJSONObject("marks") ?: JSONObject()
        val marks = mk.keys().asSequence().sorted().joinToString(";") { k ->
            val o = mk.getJSONObject(k); val arr = o.getJSONArray("marks")
            "$k:${o.optDouble("offset", 0.0)}:${List(arr.length()) { arr.getDouble(it) }.joinToString(",")}"
        }
        return fp(sc?.let { "${it.getString("name")}|${it.optInt("size")}" } ?: "-", audio, parts, marks, m.optDouble("bpm", 0.0), m.optInt("beats", 4))
    }

    /** A música carregada guarda sozinha o que mudou na configuração (nada tocando; ignora sessão vazia). Devolve true se gravou. */
    fun autosave(): Boolean = synchronized(lock) {
        val m = find(current) ?: return false
        if (!hasConfig() || !nothingPlaying() || fpLive() == fpSong(m)) return false
        snapshot(m)
        save()
        true
    }

    /** Ao abrir a sessão: volta a música que estava carregada (se a sessão ainda está vazia e nada toca). */
    fun restoreLast() = synchronized(lock) {
        val m = find(current) ?: return@synchronized
        if (hasConfig() || !nothingPlaying()) return@synchronized
        runCatching { apply(m) }
    }

    /** Impressão atual (para o laço que só grava quando a configuração parou de mudar). */
    fun liveFingerprint(): String = synchronized(lock) { fpLive() }

    private fun nothingPlaying(): Boolean {
        if (server.metronomeState().playing) return false
        return server.groups.ids().none { server.hub(it)?.isPlaying() == true }
    }

    private fun apply(m: JSONObject) {
        val d = songDir(m.getInt("id"))
        val ids = HashMap<String, String>()
        for (gid in server.groups.ids()) ids.putIfAbsent(server.groups.name(gid), gid)
        val need = ArrayList<String>()
        val a = m.optJSONArray("audio") ?: JSONArray()
        for (i in 0 until a.length()) need.add(a.getJSONObject(i).getString("group"))
        m.optJSONObject("parts")?.keys()?.forEach { need.add(it) }
        var groupsChanged = false
        for (name in need) if (name !in ids) {
            val id = server.groups.add(name)
            if (id != null) { ids[name] = id; groupsChanged = true }
        }
        if (groupsChanged) onGroups()
        val have = HashMap<String, JSONObject>()
        for (i in 0 until a.length()) have[a.getJSONObject(i).getString("group")] = a.getJSONObject(i)
        for (gid in server.groups.ids()) {
            val h: AudioHub = server.hub(gid) ?: continue
            val name = server.groups.name(gid)
            val e = if (ids[name] == gid) have[name] else null
            val src = e?.let { File(d, it.getString("file")) }
            if (e != null && src != null && src.exists()) {
                val dst = File(audioDir, "host-rep-${System.currentTimeMillis()}-${seq++}.bin")
                src.copyTo(dst, overwrite = true)
                h.setFile(dst, e.getString("name"), e.optString("mime", "application/octet-stream"))
            } else if (h.hasFile()) h.clear()
        }
        val sc = m.optJSONObject("score")
        val scFile = sc?.let { File(d, it.getString("file")) }
        if (sc != null && scFile != null && scFile.exists()) {
            server.setScore(scFile.readBytes(), sc.getString("name"))
            m.optJSONObject("parts")?.keys()?.forEach { n -> ids[n]?.let { gid -> server.setScorePart(gid, m.getJSONObject("parts").getInt(n)) } }
            val marks = m.optJSONObject("marks")
            marks?.keys()?.forEach { sig ->
                val o = marks.getJSONObject(sig)
                val arr = o.getJSONArray("marks")
                server.setScoreMarks(sig, List(arr.length()) { arr.getDouble(it) }, o.optDouble("offset", 0.0))
            }
        } else server.clearScore()
        if (m.has("bpm")) server.applySettings(m.getDouble("bpm"), m.optInt("beats", 4))
    }

    /** Executa uma operação ({"op": ...}); devolve (status HTTP, resposta). */
    fun op(body: JSONObject): Pair<Int, JSONObject> = synchronized(lock) {
        when (body.optString("op")) {
            "add" -> {
                if (songs.size >= 200) return err(400, "Repertório cheio (200 músicas).")
                val hasAudio = server.groups.ids().any { server.hub(it)?.hasFile() == true }
                val sc = server.scoreSnapshot()
                if (sc == null && !hasAudio) return err(400, "Carregue uma partitura ou um áudio antes de salvar a música.")
                var name = clean(body.optString("name"), 60)
                if (name.isEmpty()) name = sc?.name ?: "Música $next"
                val m = JSONObject().put("id", next).put("name", name)
                next++
                snapshot(m)
                songs.add(m)
                current = m.getInt("id")
                save()
                Pair(200, json())
            }
            "update" -> {
                val m = find(body.optInt("id", -1)) ?: return err(404, "Música não encontrada.")
                snapshot(m)
                current = m.getInt("id")
                save()
                Pair(200, json())
            }
            "save" -> {
                autosave()
                Pair(200, json())
            }
            "rename" -> {
                val m = find(body.optInt("id", -1)) ?: return err(404, "Música não encontrada.")
                val n = clean(body.optString("name"), 60)
                if (n.isEmpty()) return err(400, "Nome vazio.")
                m.put("name", n)
                save()
                Pair(200, json())
            }
            "remove" -> {
                val m = find(body.optInt("id", -1)) ?: return err(404, "Música não encontrada.")
                songs.remove(m)
                songDir(m.getInt("id")).deleteRecursively()
                if (current == m.getInt("id")) current = -1
                save()
                Pair(200, json())
            }
            "move" -> {
                val m = find(body.optInt("id", -1)) ?: return err(404, "Música não encontrada.")
                val i = songs.indexOf(m)
                val j = i + if (body.optInt("dir", 1) == -1) -1 else 1
                if (j in songs.indices) { songs[i] = songs[j]; songs[j] = m }
                save()
                Pair(200, json())
            }
            "apply", "next", "prev" -> {
                val op = body.optString("op")
                val m: JSONObject
                if (op == "apply") m = find(body.optInt("id", -1)) ?: return err(404, "Música não encontrada.")
                else {
                    if (songs.isEmpty()) return err(400, "O repertório está vazio.")
                    val ids = songs.map { it.getInt("id") }
                    val i = ids.indexOf(current).let { if (it >= 0) it else if (op == "next") -1 else ids.size }
                    val j = i + if (op == "next") 1 else -1
                    if (j !in ids.indices) return err(400, "Não há música ${if (op == "next") "depois desta" else "antes desta"}.")
                    m = songs[j]
                }
                if (!nothingPlaying()) return err(409, "Pare a música e o metrônomo antes de trocar de música.")
                apply(m)
                current = m.getInt("id")
                save()
                Pair(200, json())
            }
            else -> err(400, "Operação inválida.")
        }
    }
}
