package com.metronomo.core

import org.json.JSONObject

/**
 * Estado compartilhado da sessão. O formato JSON é o mesmo do servidor Node.js
 * do protótipo web, então o app também consegue entrar em sessões criadas por ele.
 *
 * [startAt] é um horário (ms) no relógio do servidor, a partir do qual as batidas
 * são calculadas: batida n = startAt + n * (60000 / bpm).
 */
data class MetronomeState(
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val playing: Boolean = false,
    val startAt: Long? = null,
    val revision: Long = -1,
    /** Identifica a execução do servidor (muda quando o anfitrião reinicia); 0 = servidor antigo, sem época. */
    val epoch: Long = 0,
    /** Relógio mudo (só partitura): o cursor anda, mas o clique não toca. */
    val silent: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("bpm", bpm)
        put("beatsPerBar", beatsPerBar)
        put("playing", playing)
        put("startAt", startAt ?: JSONObject.NULL)
        put("revision", revision)
        put("silent", silent)
        put("epoch", if (epoch != 0L) epoch else PROCESS_EPOCH)
    }.toString()

    companion object {
        const val MIN_BPM = 30
        const val MAX_BPM = 300
        const val MIN_BEATS = 1
        const val MAX_BEATS = 12
        val PROCESS_EPOCH: Long = System.currentTimeMillis()

        fun fromJson(text: String): MetronomeState {
            val o = JSONObject(text)
            return MetronomeState(
                bpm = o.optDouble("bpm", 120.0),
                beatsPerBar = o.optInt("beatsPerBar", 4),
                playing = o.optBoolean("playing", false),
                startAt = if (o.isNull("startAt")) null else o.getLong("startAt"),
                revision = o.optLong("revision", 0),
                epoch = o.optLong("epoch", 0),
                silent = o.optBoolean("silent", false),
            )
        }
    }
}
