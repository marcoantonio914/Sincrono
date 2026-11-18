package com.metronomo.calibration

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import com.metronomo.net.ClockSync
import kotlin.math.max

/** Grava o microfone entre dois instantes (relógio do servidor). Precisa da permissão RECORD_AUDIO. */
class CalibrationRecorder {

    class Recording(
        val samples: ShortArray,
        val length: Int,
        val sampleRate: Int,
        /** Instante (servidor, ms) em que a gravação foi iniciada. */
        val startServerMs: Double,
    )

    @SuppressLint("MissingPermission")
    fun record(clock: ClockSync, startServerMs: Double, endServerMs: Double): Recording? {
        val sampleRate = 48_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val bufferBytes = max(minBuffer * 2, sampleRate)

        val record = open(MediaRecorder.AudioSource.UNPROCESSED, sampleRate, bufferBytes)
            ?: open(MediaRecorder.AudioSource.MIC, sampleRate, bufferBytes)
            ?: return null

        val capacity = ((endServerMs - startServerMs) / 1000.0 * sampleRate).toInt() + sampleRate
        val samples = ShortArray(capacity)
        try {
            while (clock.nowServerMs() < startServerMs) Thread.sleep(5)
            record.startRecording()
            val startedAt = clock.nowServerMs()
            var length = 0
            while (clock.nowServerMs() < endServerMs && length < capacity) {
                val n = record.read(samples, length, minOf(1024, capacity - length))
                if (n < 0) return null
                length += n
            }
            return Recording(samples, length, sampleRate, startedAt)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * Gravação com carimbo de tempo: [originServerMs] é o instante (relógio do servidor) em que a
     * amostra 0 foi CAPTURADA pelo microfone, já contando o atraso de entrada, calculado a partir de
     * AudioRecord.getTimestamp(). É NaN se o aparelho não informa esse carimbo. [originSpreadMs] é a
     * variação entre as estimativas (instabilidade do carimbo).
     */
    class TimedRecording(
        val samples: ShortArray,
        val length: Int,
        val sampleRate: Int,
        val originServerMs: Double,
        val originSpreadMs: Double,
        /** Instante (servidor, ms) em que startRecording() retornou: aproximação grosseira, sem o atraso de entrada. */
        val startedServerMs: Double,
    )

    @SuppressLint("MissingPermission")
    fun recordTimed(clock: ClockSync, startServerMs: Double, endServerMs: Double): TimedRecording? {
        val sampleRate = 48_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val bufferBytes = max(minBuffer * 2, sampleRate)

        // UNPROCESSED (sem cancelamento de eco nem controle de ganho) primeiro; se o aparelho não tem, MIC.
        val record = open(MediaRecorder.AudioSource.UNPROCESSED, sampleRate, bufferBytes)
            ?: open(MediaRecorder.AudioSource.MIC, sampleRate, bufferBytes)
            ?: return null

        val capacity = ((endServerMs - startServerMs) / 1000.0 * sampleRate).toInt() + sampleRate
        val samples = ShortArray(capacity)
        val origins = ArrayList<Double>()
        try {
            while (clock.nowServerMs() < startServerMs) Thread.sleep(5)
            record.startRecording()
            val startedAt = clock.nowServerMs()
            val timestamp = AudioTimestamp()
            var length = 0
            var lastPollNs = 0L
            while (clock.nowServerMs() < endServerMs && length < capacity) {
                val n = record.read(samples, length, minOf(1024, capacity - length))
                if (n < 0) return null
                length += n
                val nowNs = System.nanoTime()
                // Depois de 300 ms de gravação o carimbo já se estabilizou; consulta a cada ~40 ms.
                if (length > sampleRate * 3 / 10 && nowNs - lastPollNs > 40_000_000L) {
                    lastPollNs = nowNs
                    if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val originNs = timestamp.nanoTime - timestamp.framePosition * 1e9 / sampleRate
                        origins.add(clock.monotonicNsToServerMs(originNs))
                    }
                }
            }
            var origin = Double.NaN
            var spread = 0.0
            if (origins.size >= 5) {
                val sorted = origins.sorted()
                origin = sorted[sorted.size / 2]
                spread = sorted.last() - sorted.first()
            }
            return TimedRecording(samples, length, sampleRate, origin, spread, startedAt)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, sampleRate: Int, bufferBytes: Int): AudioRecord? {
        return try {
            val r = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                r
            } else {
                r.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
