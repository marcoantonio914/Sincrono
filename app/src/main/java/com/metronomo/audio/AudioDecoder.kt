package com.metronomo.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Decodifica um arquivo de áudio (MP3, WAV, FLAC, M4A/AAC, OGG...) para PCM de 16 bits
 * intercalado, gravado em arquivo (assim músicas longas não ocupam a memória).
 * Todos os aparelhos decodificam o mesmo arquivo com o mesmo decodificador do Android,
 * então o conteúdo (inclusive o corte do atraso inicial do MP3) é idêntico entre eles.
 */
object AudioDecoder {

    class Info(val sampleRate: Int, val channels: Int, val frames: Long) {
        val durationMs: Double get() = frames * 1000.0 / sampleRate
    }

    /** Devolve null se o arquivo não pôde ser decodificado. [onProgress] recebe 0..1. */
    fun decode(input: File, output: File, onProgress: (Double) -> Unit): Info? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var bytesWritten = 0L
            var lastProgress = -1.0
            var idleLoops = 0

            output.outputStream().buffered(1 shl 16).use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex >= 0 -> {
                            idleLoops = 0
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            if (info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                bytesWritten += writePcm(out, bytes, channels)
                                if (durationUs > 0) {
                                    val progress = (info.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0)
                                    if (progress - lastProgress >= 0.02) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }

                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }

                        else -> {
                            // Sem saída ainda; se travar de vez (arquivo corrompido), desiste.
                            if (inputDone && ++idleLoops > 500) return null
                        }
                    }
                }
            }

            val outChannels = if (channels >= 2) 2 else 1
            val frames = bytesWritten / (2L * outChannels)
            if (frames <= 0) return null
            return Info(sampleRate, outChannels, frames)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Grava [bytes] (PCM 16 bits intercalado com [channels] canais); mais de 2 canais viram os 2 primeiros. */
    private fun writePcm(out: java.io.OutputStream, bytes: ByteArray, channels: Int): Int {
        if (channels <= 2) {
            out.write(bytes)
            return bytes.size
        }
        val frameBytes = channels * 2
        val frames = bytes.size / frameBytes
        val stereo = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val src = i * frameBytes
            val dst = i * 4
            stereo[dst] = bytes[src]
            stereo[dst + 1] = bytes[src + 1]
            stereo[dst + 2] = bytes[src + 2]
            stereo[dst + 3] = bytes[src + 3]
        }
        out.write(stereo)
        return stereo.size
    }
}
