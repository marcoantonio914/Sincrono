package com.metronomo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.metronomo.core.MetronomeState
import com.metronomo.diag.AudioClockTracker
import com.metronomo.diag.GainRamp
import com.metronomo.net.ClockSync
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gera o clique localmente, com precisão de amostra.
 *
 * Em vez de disparar cliques com timers, o motor escreve um fluxo contínuo de áudio e,
 * para cada bloco, calcula em que horário (relógio do servidor) ele será realmente
 * ouvido, usando AudioTrack.getTimestamp(). Se uma batida cai dentro do bloco, o clique
 * é colocado na amostra exata. Assim, a latência de saída do aparelho é compensada
 * e qualquer deriva é corrigida a cada poucos milissegundos.
 */
class ClickEngine(private val clock: ClockSync) {

    @Volatile
    private var state = MetronomeState()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val sampleRate: Int =
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000

    private val normalClick = makeClick(880.0, 0.5)
    private val accentClick = makeClick(1320.0, 0.7)

    /**
     * Ajuste fino deste aparelho, em ms. Positivo = tocar mais cedo
     * (use se este aparelho soa atrasado em relação aos outros).
     */
    @Volatile
    var userOffsetMs: Double = 0.0

    /** Ajuste da calibração automática, em ms (positivo = tocar mais cedo). */
    @Volatile
    var autoOffsetMs: Double = 0.0

    /** Estado do relógio de áudio (timestamp do AudioTrack) enquanto toca; null quando parado. */
    @Volatile
    var clockState: AudioClockTracker.State? = null

    @Volatile
    var clockJumps: Int = 0

    fun setState(s: MetronomeState) {
        state = s
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "metronomo-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            runCatching { it.join(1000) }
        }
        thread = null
    }

    private fun makeClick(freq: Double, amplitude: Double): ShortArray {
        val length = (sampleRate * 0.04).toInt()
        val attack = (sampleRate * 0.002).toInt().coerceAtLeast(1)
        return ShortArray(length) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = if (i < attack) {
                i.toDouble() / attack
            } else {
                exp(-(i - attack).toDouble() / (sampleRate * 0.008))
            }
            (sin(2 * PI * freq * t) * envelope * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                val s = state
                if (!s.playing || s.startAt == null) {
                    Thread.sleep(40)
                    continue
                }
                playSession()
            }
        } catch (e: InterruptedException) {
            // encerrando
        }
    }

    private fun playSession() {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Thread.sleep(200)
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, CHUNK_FRAMES * 2 * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            val buffer = ShortArray(CHUNK_FRAMES)
            val timestamp = AudioTimestamp()
            // Valida e suaviza o mapeamento quadro -> instante ouvido (veja AudioClockTracker).
            val tracker = AudioClockTracker(sampleRate, FALLBACK_LATENCY_NS, System.nanoTime().toDouble())

            val ramp = GainRamp(Mix.effective(), GAIN_RAMP_MS, sampleRate.toDouble())
            var framesWritten = 0L
            var lastBeat = -1L
            var lastRevision = Long.MIN_VALUE
            var clickPos = -1
            var clickAccent = false

            while (running) {
                val s = state
                val startAt = s.startAt
                if (!s.playing || startAt == null) break
                if (s.revision != lastRevision) {
                    lastRevision = s.revision
                    lastBeat = -1L
                }

                // Mapeia "número do quadro" -> "instante em que será ouvido" (instante do quadro 0 = interceptNs).
                val nowNs = System.nanoTime().toDouble()
                val valid = track.getTimestamp(timestamp)
                val head = if (!valid && !tracker.usingTimestamps()) track.playbackHeadPosition.toLong() and 0xFFFFFFFFL else 0L
                tracker.update(
                    nowNs, valid,
                    if (valid) timestamp.framePosition else 0L,
                    if (valid) timestamp.nanoTime else 0L,
                    head,
                )
                clockState = tracker.state(nowNs)
                clockJumps = tracker.totalJumps()
                val interceptNs = tracker.interceptNs()

                java.util.Arrays.fill(buffer, 0)
                var triggerIndex = -1
                var triggerAccent = false

                if (!interceptNs.isNaN()) {
                    val chunkStartMs = clock.monotonicNsToServerMs(
                        interceptNs + framesWritten * 1e9 / sampleRate,
                    ) + userOffsetMs + autoOffsetMs
                    val intervalMs = 60000.0 / s.bpm
                    var k = ceil((chunkStartMs - startAt - LATE_TOLERANCE_MS) / intervalMs).toLong()
                    if (k < 0) k = 0
                    if (k <= lastBeat) k = lastBeat + 1
                    val beatMs = startAt + k * intervalMs
                    val offsetFrames = (beatMs - chunkStartMs) * sampleRate / 1000.0
                    if (offsetFrames < CHUNK_FRAMES) {
                        triggerIndex = max(0, offsetFrames.roundToInt())
                        triggerAccent = k % s.beatsPerBar == 0L
                        lastBeat = k
                    }
                }

                for (i in 0 until CHUNK_FRAMES) {
                    if (i == triggerIndex && !s.silent) {
                        clickPos = 0
                        clickAccent = triggerAccent
                    }
                    if (clickPos >= 0) {
                        val wave = if (clickAccent) accentClick else normalClick
                        buffer[i] = wave[clickPos]
                        clickPos++
                        if (clickPos >= wave.size) clickPos = -1
                    }
                }

                // Volume (próprio × anfitrião) com rampa, para nunca estalar.
                val target = Mix.effective()
                for (i in 0 until CHUNK_FRAMES) {
                    buffer[i] = (buffer[i] * ramp.next(target)).toInt().toShort()
                }

                val written = track.write(buffer, 0, CHUNK_FRAMES)
                if (written < 0) {
                    Thread.sleep(50)
                    break // recria a trilha na próxima volta
                }
                framesWritten += written
            }
        } finally {
            clockState = null
            runCatching { track.release() }
        }
    }

    companion object {
        private const val CHUNK_FRAMES = 256
        private const val GAIN_RAMP_MS = 150.0
        private const val LATE_TOLERANCE_MS = 5.0
        private const val FALLBACK_LATENCY_NS = 20_000_000.0
    }
}
