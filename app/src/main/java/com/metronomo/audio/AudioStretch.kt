package com.metronomo.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Muda a velocidade de um áudio SEM mudar o tom (WSOLA: sobreposição de trechos escolhidos por semelhança).
 * Mesmo algoritmo de stretch.js (testado no navegador). O resultado é um WAV comum: os outros aparelhos
 * o recebem como qualquer arquivo, então a sincronia não muda.
 */
object AudioStretch {
    private const val DEC = 4

    /** Gera [output] (WAV PCM 16 bits) a partir de [input]. [factor] = velocidade nova / original (0,4 a 2,5). */
    fun stretch(input: File, output: File, tmpDir: File, factor: Double, onProgress: (Double) -> Unit): Boolean {
        if (!(factor in 0.4..2.5)) return false
        val pcm = File(tmpDir, "stretch-${System.nanoTime()}.pcm")
        try {
            val info = AudioDecoder.decode(input, pcm) { onProgress(it * 0.3) } ?: return false
            return process(pcm, info, output, factor) { onProgress(0.3 + it * 0.7) }
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { pcm.delete() }
        }
    }

    private fun process(pcm: File, info: AudioDecoder.Info, output: File, f: Double, onProgress: (Double) -> Unit): Boolean {
        val ch = info.channels
        val sr = info.sampleRate
        val len = info.frames.toInt()
        val n = if (sr >= 32000) 2048 else 1024
        val hs = n / 2
        val tol = n / 4
        if (len < n * 2) return false
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }   // Hann periódica: soma 1 com 50% de sobreposição
        RandomAccessFile(pcm, "r").use { raf ->
            val src = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, len.toLong() * ch * 2)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            // sinal mono reduzido (1/DEC da taxa) para a busca do melhor ponto
            val dl = len / DEC
            val md = FloatArray(dl + n)
            for (i in 0 until dl) {
                var a = 0f
                for (c in 0 until ch) for (j in 0 until DEC) a += src.get((i * DEC + j) * ch + c)
                md[i] = a / (DEC * ch * 32768f)
            }
            val outLen = (len / f).toLong()
            val m = hs / DEC
            val td = tol / DEC
            val lastStart = (len - n) / DEC
            val acc = Array(ch) { FloatArray(n) }
            val bytes = outLen * ch * 2
            output.outputStream().buffered(1 shl 16).use { out ->
                out.write(wavHeader(ch, sr, bytes))
                val chunk = ByteBuffer.allocate(hs * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
                var prev = 0
                var k = 0
                var pos = 0L
                while (pos < outLen) {
                    var c = 0
                    if (k > 0) {
                        val nominal = Math.round(pos * f).toInt()
                        val tgt = (prev + hs) / DEC
                        val base = nominal / DEC
                        var best = -Float.MAX_VALUE
                        var bestI = max(0, min(base, lastStart))
                        val lo = max(0, base - td)
                        val hi = min(lastStart, base + td)
                        for (ci in lo..hi) {
                            var corr = 0f
                            var e = 1e-9f
                            for (i in 0 until m) {
                                val v = md[ci + i]
                                corr += md[tgt + i] * v
                                e += v * v
                            }
                            val sc = corr / sqrt(e)
                            if (sc > best) { best = sc; bestI = ci }
                        }
                        c = bestI * DEC
                    }
                    c = c.coerceIn(0, len - n)
                    for (chn in 0 until ch) {
                        val a = acc[chn]
                        for (i in 0 until n) {
                            val w = if (k == 0 && i < hs) 1f else win[i]   // o começo não entra com fade
                            a[i] += src.get((c + i) * ch + chn) / 32768f * w
                        }
                    }
                    // as primeiras hs amostras estão completas: grava e desloca
                    val emit = min(hs.toLong(), outLen - pos).toInt()
                    chunk.clear()
                    for (i in 0 until emit) for (chn in 0 until ch) {
                        chunk.putShort((acc[chn][i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                    }
                    out.write(chunk.array(), 0, emit * ch * 2)
                    for (chn in 0 until ch) {
                        val a = acc[chn]
                        System.arraycopy(a, hs, a, 0, n - hs)
                        java.util.Arrays.fill(a, n - hs, n, 0f)
                    }
                    prev = c
                    pos += hs
                    k++
                    if (k % 200 == 0) onProgress(min(1.0, pos.toDouble() / outLen))
                }
            }
        }
        return true
    }

    private fun wavHeader(ch: Int, sr: Int, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(sr).putInt(sr * ch * 2)
            .putShort((ch * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}
