package com.metronomo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.metronomo.diag.AudioClockTracker
import com.metronomo.diag.GainRamp
import com.metronomo.net.ClockSync
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Toca um arquivo de áudio já decodificado em sincronia com o relógio do servidor.
 *
 * Usa o mesmo mapeamento "quadro de áudio -> instante em que será ouvido" do metrônomo
 * (AudioTrack.getTimestamp). A cada bloco calcula em que ponto do arquivo ele deveria
 * começar e, se a leitura se desviou, corrige aos poucos ([AudioSync.Slew]) em vez de
 * deixar a deriva do relógio de áudio acumular.
 */
class AudioFilePlayer(private val clock: ClockSync) {

    class Media(val pcmFile: File, val sampleRate: Int, val channels: Int, val frames: Long) {
        val durationMs: Double get() = frames * 1000.0 / sampleRate
    }

    /** Estado de reprodução pedido pelo anfitrião (veja [AudioHub]). */
    class Plan(
        val revision: Long,
        val playing: Boolean,
        /** Instante (servidor, ms) em que o arquivo está em [positionMs]. */
        val startAt: Double,
        val positionMs: Double,
        /** Instante agendado da parada (NaN = sem parada agendada). */
        val stopAt: Double,
        /** A parada agendada é um "parar": depois dela a posição volta a 0. */
        val resetOnStop: Boolean = false,
    )

    @Volatile
    var plan = Plan(-1, false, 0.0, 0.0, Double.NaN)

    @Volatile
    var media: Media? = null

    @Volatile
    var userOffsetMs: Double = 0.0

    @Volatile
    var autoOffsetMs: Double = 0.0

    /** Diagnóstico (lido pela interface; escrito pela thread de áudio sem alocação). */
    @Volatile
    var clockState: AudioClockTracker.State? = null

    @Volatile
    var clockJumps: Int = 0

    @Volatile
    var diagErrorMs: Double = Double.NaN

    @Volatile
    var diagRate: Double = 1.0

    @Volatile
    var diagJumps: Int = 0

    @Volatile
    var diagSaturatedPercent: Double = 0.0

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "metronomo-audiofile").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            runCatching { it.join(1000) }
        }
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                val m = media
                val p = plan
                if (m == null || !p.playing || finished(p, m)) {
                    Thread.sleep(40)
                    continue
                }
                playSession(m)
            }
        } catch (e: InterruptedException) {
            // encerrando
        }
    }

    /** A parada agendada já passou, ou o arquivo já terminou: não há o que tocar. */
    private fun finished(p: Plan, m: Media): Boolean {
        val now = clock.nowServerMs()
        if (!p.stopAt.isNaN() && now > p.stopAt + 300) return true
        return p.positionMs + (now - p.startAt) > m.durationMs + 500
    }

    private fun playSession(m: Media) {
        val channelMask = if (m.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(m.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Thread.sleep(200)
            return
        }
        val raf = RandomAccessFile(m.pcmFile, "r")
        val shorts: ShortBuffer = try {
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        } catch (e: Exception) {
            runCatching { raf.close() }
            Thread.sleep(500)
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
                    .setSampleRate(m.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, CHUNK_FRAMES * m.channels * 2 * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val sr = m.sampleRate
        val ch = m.channels
        val slew = AudioSync.Slew()
        try {
            track.play()
            val buffer = ShortArray(CHUNK_FRAMES * ch)
            val timestamp = AudioTimestamp()
            val tracker = AudioClockTracker(sr, FALLBACK_LATENCY_NS, System.nanoTime().toDouble())
            val ramp = GainRamp(Mix.effective(), GAIN_RAMP_MS, sr.toDouble())
            var framesWritten = 0L
            var lastRevision = Long.MIN_VALUE

            while (running) {
                val p = plan
                if (!p.playing || media !== m || finished(p, m)) break
                if (p.revision != lastRevision) {
                    lastRevision = p.revision
                    slew.reset()
                }

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
                if (!interceptNs.isNaN()) {
                    // Instante (servidor) em que o 1º quadro deste bloco será ouvido, já com os ajustes deste aparelho.
                    val chunkStartMs = clock.monotonicNsToServerMs(interceptNs + framesWritten * 1e9 / sr) +
                        userOffsetMs + autoOffsetMs
                    val startFrames = p.positionMs * sr / 1000.0
                    val desired = startFrames + (chunkStartMs - p.startAt) * sr / 1000.0
                    slew.step(desired, sr)
                    diagErrorMs = slew.lastError / sr * 1000.0
                    diagRate = slew.rate
                    diagJumps = slew.jumps
                    diagSaturatedPercent = if (slew.chunks > 0) slew.saturatedChunks * 100.0 / slew.chunks else 0.0

                    val stopFrames = if (p.stopAt.isNaN()) Double.NaN
                    else (p.positionMs + (p.stopAt - p.startAt)) * sr / 1000.0
                    val fadeInFrames = FADE_IN_SECONDS * sr
                    val fadeOutFrames = FADE_OUT_SECONDS * sr

                    for (i in 0 until CHUNK_FRAMES) {
                        val idx = slew.cursor + i * slew.rate
                        // Antes do início agendado, depois do fim do arquivo ou depois da parada: silêncio.
                        if (idx < startFrames - 0.5 || idx < 0 || idx >= m.frames - 1) continue
                        if (!stopFrames.isNaN() && idx >= stopFrames) continue
                        var gain = 1.0
                        val sinceStart = idx - startFrames
                        if (sinceStart < fadeInFrames) gain = sinceStart / fadeInFrames
                        if (!stopFrames.isNaN()) gain *= ((stopFrames - idx) / fadeOutFrames).coerceIn(0.0, 1.0)

                        val i0 = floor(idx).toLong()
                        val frac = idx - i0
                        for (c in 0 until ch) {
                            val a = shorts.get((i0 * ch + c).toInt()).toDouble()
                            val b = shorts.get(((i0 + 1) * ch + c).toInt()).toDouble()
                            buffer[i * ch + c] = ((a + (b - a) * frac) * gain).toInt().toShort()
                        }
                    }
                    slew.advance(CHUNK_FRAMES)
                }

                // Volume (próprio × anfitrião) com rampa, aplicado ao bloco já montado.
                val target = Mix.effective()
                for (i in 0 until CHUNK_FRAMES) {
                    val g = ramp.next(target)
                    for (c in 0 until ch) buffer[i * ch + c] = (buffer[i * ch + c] * g).toInt().toShort()
                }

                val written = track.write(buffer, 0, buffer.size)
                if (written < 0) {
                    Thread.sleep(50)
                    break // recria a trilha na próxima volta
                }
                framesWritten += written / ch
            }
        } finally {
            clockState = null
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            runCatching { raf.close() }
        }
    }

    companion object {
        private const val CHUNK_FRAMES = 512
        private const val GAIN_RAMP_MS = 150.0
        private const val FALLBACK_LATENCY_NS = 20_000_000.0
        private const val FADE_IN_SECONDS = 0.010
        private const val FADE_OUT_SECONDS = 0.030
    }
}
