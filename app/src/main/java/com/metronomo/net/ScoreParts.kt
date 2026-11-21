package com.metronomo.net

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Partitura importada, como o app anfitrião a vê: nome do arquivo, nomes das partes e a parte de cada grupo. */
data class ScoreInfo(
    val fileName: String = "",
    val partNames: List<String> = emptyList(),
    /** grupo -> índice da parte. Grupo sem entrada vê a parte 0. */
    val parts: Map<String, Int> = emptyMap(),
    /** Andamento (semínimas por minuto) e batidas por compasso lidos do início da partitura; 0 = não informado. */
    val bpm: Double = 0.0,
    val beats: Int = 0,
    /** true quando o andamento foi estimado por uma palavra (Andante, Allegro...) e não por um número. */
    val bpmEstimated: Boolean = false,
)

/** Andamento e fórmula de compasso do início da partitura. */
data class ScoreMeta(val bpm: Double = 0.0, val beats: Int = 0, val estimated: Boolean = false)

/** Lê só a lista de instrumentos (`part-list`) de um MusicXML (.musicxml/.xml) ou MXL (.mxl, um zip). */
object ScoreParts {
    fun names(bytes: ByteArray): List<String> = try {
        val xml = if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) unzip(bytes) else bytes
        if (xml == null) emptyList() else parseNames(xml)
    } catch (e: Exception) {
        emptyList()
    }

    /** Lê o andamento (em semínimas por minuto) e o numerador da fórmula de compasso do começo da partitura. */
    fun meta(bytes: ByteArray): ScoreMeta = try {
        val xml = if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) unzip(bytes) else bytes
        if (xml == null) ScoreMeta() else parseMeta(xml)
    } catch (e: Exception) {
        ScoreMeta()
    }

    /** Andamento aproximado (semínimas por minuto) para as palavras italianas mais comuns. */
    private fun wordTempo(text: String): Double {
        val t = text.lowercase()
        val table = listOf(
            "larghetto" to 66.0, "largo" to 50.0, "lento" to 56.0, "adagio" to 66.0, "andantino" to 84.0, "andante" to 76.0,
            "moderato" to 108.0, "allegretto" to 112.0, "allegro" to 132.0, "vivace" to 156.0, "presto" to 184.0,
        )
        return table.firstOrNull { t.contains(it.first) }?.second ?: 0.0
    }

    private fun unitFactor(unit: String): Double = when (unit) {
        "whole" -> 4.0; "half" -> 2.0; "quarter" -> 1.0; "eighth" -> 0.5; "16th" -> 0.25; "32nd" -> 0.125; "breve" -> 8.0
        else -> 1.0
    }

    private fun parseMeta(xml: ByteArray): ScoreMeta {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(xml), null)
        var soundBpm = 0.0
        var metroBpm = 0.0
        var beats = 0
        var wordBpm = 0.0
        var measures = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "measure" -> measures++
                    "words" -> if (wordBpm == 0.0 && measures <= 3) wordBpm = wordTempo(p.nextText() ?: "")
                    "sound" -> if (soundBpm == 0.0) soundBpm = p.getAttributeValue(null, "tempo")?.toDoubleOrNull() ?: 0.0
                    "time" -> if (beats == 0) {
                        // <time><beats>3</beats><beat-type>4</beat-type></time>; "3+2" soma as batidas
                        while (true) {
                            ev = p.next()
                            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "time")) break
                            if (ev == XmlPullParser.START_TAG && p.name == "beats") {
                                beats = (p.nextText() ?: "").split("+").sumOf { it.trim().toIntOrNull() ?: 0 }
                            }
                        }
                    }
                    "metronome" -> if (metroBpm == 0.0) {
                        var unit = "quarter"
                        var dots = 0
                        var pm = 0.0
                        while (true) {
                            ev = p.next()
                            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "metronome")) break
                            if (ev == XmlPullParser.START_TAG) when (p.name) {
                                "beat-unit" -> if (pm == 0.0) unit = (p.nextText() ?: "quarter").trim()
                                "beat-unit-dot" -> if (pm == 0.0) dots++
                                "per-minute" -> pm = (p.nextText() ?: "").trim().toDoubleOrNull() ?: 0.0
                            }
                        }
                        if (pm > 0.0) metroBpm = pm * unitFactor(unit) * (if (dots > 0) 1.5 else 1.0)
                    }
                }
            }
            if (measures > 6 && beats > 0 && (soundBpm > 0.0 || metroBpm > 0.0)) break
            if (measures > 40) break
            ev = p.next()
        }
        val exact = if (soundBpm > 0.0) soundBpm else metroBpm
        return if (exact > 0.0) ScoreMeta(exact, beats) else ScoreMeta(wordBpm, beats, wordBpm > 0.0)
    }

    private fun unzip(bytes: ByteArray): ByteArray? {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = z.readBytes()
            }
        }
        val rootPath = entries["META-INF/container.xml"]?.let { rootfilePath(it) }
        if (rootPath != null && entries.containsKey(rootPath)) return entries[rootPath]
        return entries.entries.firstOrNull { !it.key.startsWith("META-INF") && (it.key.endsWith(".xml") || it.key.endsWith(".musicxml")) }?.value
    }

    private fun rootfilePath(container: ByteArray): String? {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(container), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "rootfile") return p.getAttributeValue(null, "full-path")
            ev = p.next()
        }
        return null
    }

    private fun parseNames(xml: ByteArray): List<String> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(xml), null)
        val out = ArrayList<String>()
        var inList = false
        var id = ""
        var name = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "part-list" -> inList = true
                    "score-part" -> {
                        id = p.getAttributeValue(null, "id") ?: ""
                        name = ""
                    }
                    "part-name" -> if (inList) name = (p.nextText() ?: "").trim()
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "score-part" -> out.add(name.ifBlank { id.ifBlank { "Parte ${out.size + 1}" } })
                    "part-list" -> return out
                }
            }
            ev = p.next()
        }
        return out
    }
}
