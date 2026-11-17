package com.metronomo.audio

/**
 * Volume final deste aparelho = volume próprio × volume definido pelo anfitrião (cada um de 0 a 1).
 * Só o metrônomo e a música usam este ganho; os sons da calibração o ignoram de propósito
 * (a medição precisa de um nível conhecido).
 */
object Mix {
    @Volatile
    var own: Double = 1.0

    @Volatile
    var host: Double = 1.0

    fun effective(): Double = own.coerceIn(0.0, 1.0) * host.coerceIn(0.0, 1.0)

    fun reset() {
        host = 1.0
    }
}
