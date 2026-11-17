package com.metronomo.diag

/** Resumo do diagnóstico de sincronização mostrado na tela. Atualizado fora da thread de áudio. */
data class SyncQuality(
    val level: SyncMath.Level = SyncMath.Level.UNRELIABLE,
    val rttMinMs: Double = Double.NaN,
    val rttMedianMs: Double = Double.NaN,
    val rttLastMs: Double = Double.NaN,
    val jitterMs: Double = Double.NaN,
    val precisionMs: Double = Double.NaN,
    val lastGoodAgeMs: Double = Double.NaN,
    val accepted: Int = 0,
    val rejected: Int = 0,
    /** Estado do relógio de áudio (timestamp do AudioTrack) do que está tocando; null se nada toca. */
    val audioClock: AudioClockTracker.State? = null,
    val audioJumps: Int = 0,
    /** Música: erro de posição (ms), velocidade aplicada (1,000 = normal), saltos e tempo com a velocidade saturada (%). */
    val musicErrorMs: Double = Double.NaN,
    val musicRate: Double = 1.0,
    val musicJumps: Int = 0,
    val musicSaturatedPercent: Double = 0.0,
) {
    val levelLabel: String
        get() = when (level) {
            SyncMath.Level.EXCELLENT -> "Excelente"
            SyncMath.Level.ACCEPTABLE -> "Aceitável"
            SyncMath.Level.DEGRADED -> "Degradada"
            SyncMath.Level.UNRELIABLE -> "Não confiável"
        }

    val audioClockLabel: String
        get() = when (audioClock) {
            null -> "aguardando reprodução"
            AudioClockTracker.State.WARMING_UP -> "iniciando"
            AudioClockTracker.State.PRECISE -> "timestamp preciso"
            AudioClockTracker.State.FALLBACK -> "estimativa reduzida (este aparelho não informa o horário do áudio)"
            AudioClockTracker.State.UNSTABLE -> "instável (horários do áudio variando)"
        }

    /** 0 = nada, 1 = estimativa reduzida, 2 = instável. */
    val audioPenalty: Int
        get() = when (audioClock) {
            AudioClockTracker.State.FALLBACK -> 1
            AudioClockTracker.State.UNSTABLE -> 2
            else -> 0
        }
}
