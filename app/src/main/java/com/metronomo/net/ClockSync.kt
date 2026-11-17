package com.metronomo.net

import com.metronomo.diag.SyncMath
import kotlin.math.abs

/**
 * Estima a diferença entre o relógio monotônico deste aparelho (System.nanoTime)
 * e o relógio do servidor da sessão, no estilo NTP: de várias amostras recentes,
 * usa a de menor tempo de ida e volta (a menos afetada por atraso de rede).
 *
 * Tudo no app é medido em relógio monotônico, que não sofre ajustes de data/hora.
 */
class ClockSync {
    private class Sample(val rttMs: Double, val offsetMs: Double)

    private val samples = ArrayDeque<Sample>()

    /** servidor = monotônico local (ms) + offsetMs */
    @Volatile
    var offsetMs: Double = 0.0
        private set

    @Volatile
    var rttMs: Double = Double.NaN
        private set

    @Volatile
    var sampleCount: Int = 0
        private set

    val isSynced: Boolean get() = sampleCount >= 3

    /** Amostras aceitas e descartadas (RTT inválido), e instante (ns monotônico) da última aceita. */
    @Volatile
    var acceptedCount: Int = 0
        private set

    @Volatile
    var rejectedCount: Int = 0
        private set

    @Volatile
    private var lastGoodNs: Long = 0L

    /** Uma tentativa de medição falhou (sem resposta). */
    fun noteFailure() {
        rejectedCount++
    }

    /** Idade da última amostra boa, em ms (grande se nunca houve). */
    fun lastGoodAgeMs(): Double = if (lastGoodNs == 0L) Double.MAX_VALUE else (System.nanoTime() - lastGoodNs) / 1e6

    /** Estatísticas das amostras recentes (RTT mínimo/mediano/atual, jitter, precisão estimada); null se não há amostras. */
    @Synchronized
    fun stats(): SyncMath.Stats? {
        val n = samples.size
        if (n == 0) return null
        val rtt = DoubleArray(n)
        val off = DoubleArray(n)
        var i = 0
        for (s in samples) {
            rtt[i] = s.rttMs
            off[i] = s.offsetMs
            i++
        }
        return SyncMath.stats(rtt, off, n)
    }

    fun nowServerMs(): Double = System.nanoTime() / 1e6 + offsetMs

    /** Converte um instante do relógio monotônico (ns) para o relógio do servidor (ms). */
    fun monotonicNsToServerMs(ns: Double): Double = ns / 1e6 + offsetMs

    /**
     * @param t0 instante local do envio (ms monotônico)
     * @param serverReceived instante em que o servidor recebeu (ms, relógio do servidor)
     * @param serverSent instante em que o servidor respondeu (ms, relógio do servidor)
     * @param t3 instante local do recebimento da resposta (ms monotônico)
     */
    @Synchronized
    fun addSample(t0: Double, serverReceived: Double, serverSent: Double, t3: Double) {
        val rtt = (t3 - t0) - (serverSent - serverReceived)
        if (rtt < 0 || rtt > 2000) {
            rejectedCount++
            return
        }
        acceptedCount++
        lastGoodNs = System.nanoTime()
        val offset = ((serverReceived - t0) + (serverSent - t3)) / 2

        samples.addLast(Sample(rtt, offset))
        while (samples.size > 12) samples.removeFirst()
        sampleCount++

        val best = samples.minByOrNull { it.rttMs } ?: return
        val target = best.offsetMs
        // Correção gradual para o metrônomo não "pular" no meio da música;
        // só salta direto na primeira amostra ou se a diferença for grande.
        offsetMs = if (sampleCount == 1 || abs(target - offsetMs) > 30.0) {
            target
        } else {
            offsetMs + (target - offsetMs) * 0.3
        }
        rttMs = best.rttMs
    }
}
