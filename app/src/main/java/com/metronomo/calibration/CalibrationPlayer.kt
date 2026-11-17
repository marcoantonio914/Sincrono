package com.metronomo.calibration

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.metronomo.diag.AudioClockTracker
import com.metronomo.net.ClockSync
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Toca o som de calibração em horários combinados (relógio do servidor), com o mesmo
 * mapeamento "quadro de áudio -> instante em que será ouvido" do metrônomo, e SEM
 * aplicar nenhum ajuste de calibração/manual: queremos medir o erro bruto do aparelho.
 */
class CalibrationPlayer(private val clock: ClockSync, private val amplitude: Double = 0.9) {

    private val sampleRate: Int =
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000

    private val chirp: ShortArray = CalibrationDsp.chirpPcm(sampleRate, amplitude)

    /**
     * Bloqueia até tocar todos os horários (ms, relógio do servidor, em ordem crescente).
     * Devolve o estado do relógio de áudio ao final (PRECISE, FALLBACK, UNSTABLE...), ou null se não tocou.
     */
    fun play(times: List<Double>): AudioClockTracker.State? {
        if (times.isEmpty()) return null
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        val previousPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
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

        var finalState: AudioClockTracker.State? = null
        try {
            track.play()
            val buffer = ShortArray(CHUNK_FRAMES)
            val timestamp = AudioTimestamp()
            val tracker = AudioClockTracker(sampleRate, FALLBACK_LATENCY_NS, System.nanoTime().toDouble())
            val endMs = times.last() + 800.0

            var framesWritten = 0L
            var next = 0
            var chirpPos = -1

            while (clock.nowServerMs() < endMs) {
                val nowNs = System.nanoTime().toDouble()
                val valid = track.getTimestamp(timestamp)
                val head = if (!valid && !tracker.usingTimestamps()) track.playbackHeadPosition.toLong() and 0xFFFFFFFFL else 0L
                tracker.update(
                    nowNs, valid,
                    if (valid) timestamp.framePosition else 0L,
                    if (valid) timestamp.nanoTime else 0L,
                    head,
                )
                finalState = tracker.state(nowNs)
                val interceptNs = tracker.interceptNs()

                java.util.Arrays.fill(buffer, 0)
                var triggerIndex = -1
                if (!interceptNs.isNaN() && next < times.size) {
                    val chunkStartMs = clock.monotonicNsToServerMs(interceptNs + framesWritten * 1e9 / sampleRate)
                    val offsetFrames = (times[next] - chunkStartMs) * sampleRate / 1000.0
                    if (offsetFrames < CHUNK_FRAMES) {
                        // Se já passou há mais de 5 ms, descarta este horário (a análise tolera buracos).
                        if (offsetFrames >= -sampleRate * 0.005) triggerIndex = max(0, offsetFrames.roundToInt())
                        next++
                    }
                }

                for (i in 0 until CHUNK_FRAMES) {
                    if (i == triggerIndex) chirpPos = 0
                    if (chirpPos >= 0) {
                        buffer[i] = chirp[chirpPos]
                        chirpPos++
                        if (chirpPos >= chirp.size) chirpPos = -1
                    }
                }

                val written = track.write(buffer, 0, CHUNK_FRAMES)
                if (written < 0) break
                framesWritten += written
            }
        } finally {
            runCatching { track.release() }
            Process.setThreadPriority(previousPriority)
        }
        return finalState
    }

    companion object {
        private const val CHUNK_FRAMES = 256
        private const val FALLBACK_LATENCY_NS = 20_000_000.0
    }
}
