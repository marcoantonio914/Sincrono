package com.metronomo.calibration

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
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
