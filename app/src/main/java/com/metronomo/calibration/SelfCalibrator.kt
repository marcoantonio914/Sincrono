package com.metronomo.calibration

import com.metronomo.diag.AudioClockTracker
import com.metronomo.net.ClockSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * Autocalibração: este aparelho toca o som de teste e o PRÓPRIO microfone o grava. Mede, em valor
 * absoluto, quanto o aparelho soa depois do horário que o app combinou (mesmo mapeamento do
 * metrônomo, sem nenhum ajuste aplicado), e o ajuste resultante faz o som sair no horário do
 * relógio compartilhado, sem precisar de outro aparelho como referência.
 *
 * Precisa do carimbo de tempo do microfone (AudioRecord.getTimestamp). Sem ele o atraso de entrada
 * não pode ser separado do de saída, e o resultado NÃO é aplicado.
 */
class SelfCalibrator(private val clock: ClockSync) {

    class Outcome(
        val ok: Boolean,
        val message: String,
        val offsetMs: Double = 0.0,
        val spreadMs: Double = 0.0,
        val quality: String = "low",
        val count: Int = 0,
        val total: Int = 0,
        val clockState: AudioClockTracker.State? = null,
    )

    suspend fun run(): Outcome = coroutineScope {
        val now = clock.nowServerMs()
        val first = now + LEAD_MS
        val times = DoubleArray(SOUNDS) { first + it * STEP_MS }
        val endMs = times.last() + 1_200.0

        val recording = async(Dispatchers.IO) { CalibrationRecorder().recordTimed(clock, now, endMs) }
        val playing = async(Dispatchers.IO) { CalibrationPlayer(clock, AMPLITUDE).play(times.toList()) }
        val state = playing.await()
        val rec = recording.await()
            ?: return@coroutineScope Outcome(false, "Não foi possível gravar o microfone. Confira a permissão do microfone.")

        if (rec.originServerMs.isNaN()) {
            // Sem carimbo: mede só para informar, mas não aplica.
            val raw = SelfCalibrationDsp.analyze(rec.samples, rec.length, rec.sampleRate, rec.startedServerMs, 0.0, times)
            val extra = if (raw.ok) String.format(Locale.ROOT, " (medida bruta %+.0f ms, que inclui o atraso do microfone, desconhecido)", raw.offsetMs) else ""
            return@coroutineScope Outcome(
                false,
                "Este aparelho não informa o horário exato da gravação do microfone, então a autocalibração não é confiável nele$extra. " +
                    "Use a calibração em conjunto (microfone ou cabo).",
                clockState = state,
            )
        }

        val r = SelfCalibrationDsp.analyze(rec.samples, rec.length, rec.sampleRate, rec.originServerMs, rec.originSpreadMs, times)
        if (!r.ok) return@coroutineScope Outcome(false, r.error, clockState = state)
        Outcome(true, "", r.offsetMs, r.spreadMs, r.quality, r.count, r.total, state)
    }

    companion object {
        const val SOUNDS = 8
        private const val STEP_MS = 500.0
        /** Tempo para abrir o microfone, estabilizar o carimbo e aquecer o relógio de áudio antes do primeiro som. */
        private const val LEAD_MS = 2_500.0
        /** Volume moderado: o alto-falante está a poucos centímetros do microfone. */
        private const val AMPLITUDE = 0.5
    }
}
