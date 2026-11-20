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
)

/** Lê só a lista de instrumentos (`part-list`) de um MusicXML (.musicxml/.xml) ou MXL (.mxl, um zip). */
object ScoreParts {
    fun names(bytes: ByteArray): List<String> = try {
        val xml = if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) unzip(bytes) else bytes
        if (xml == null) emptyList() else parseNames(xml)
    } catch (e: Exception) {
        emptyList()
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
