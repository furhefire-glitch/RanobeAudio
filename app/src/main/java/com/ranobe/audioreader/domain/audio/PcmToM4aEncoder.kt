package com.ranobe.audioreader.domain.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PcmToM4aEncoder(
    private val sampleRate: Int,
    output: File,
    bitrate: Int = 48000,
) : Closeable {
    private val encoder: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var encoderStarted = false
    private var finished = false
    private var closed = false
    private var outputEnded = false
    private var lastProgress = SystemClock.elapsedRealtime()
    var sampleCount: Long = 0L
        private set
    val durationMs: Long get() = sampleCount * 1000L / sampleRate

    init {
        require(sampleRate in setOf(8000, 24000, 48000))
        require(bitrate in 16000..192000)
        output.parentFile?.mkdirs()
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        muxer = try {
            MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    suspend fun write(samples: FloatArray) {
        check(!closed && !finished)
        lastProgress = SystemClock.elapsedRealtime()
        var offset = 0
        while (offset < samples.size) {
            checkProgress()
            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = checkNotNull(encoder.getInputBuffer(index)).apply {
                    clear()
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                val count = minOf(buffer.remaining() / 2, samples.size - offset)
                check(count > 0) { "Пустой входной буфер AAC" }
                repeat(count) {
                    val value = samples[offset + it]
                    require(value.isFinite()) { "Некорректный PCM-сэмпл" }
                    buffer.putShort((value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
                encoder.queueInputBuffer(index, 0, count * 2, sampleCount * 1_000_000L / sampleRate, 0)
                sampleCount += count
                offset += count
                lastProgress = SystemClock.elapsedRealtime()
            }
            drain()
        }
    }

    suspend fun finish(): Long {
        check(!closed && !finished)
        check(sampleCount > 0) { "Нет сэмплов для кодирования" }
        lastProgress = SystemClock.elapsedRealtime()
        while (true) {
            checkProgress()
            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, sampleCount * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                lastProgress = SystemClock.elapsedRealtime()
                break
            }
            drain()
        }
        while (!outputEnded) {
            checkProgress()
            drain()
        }
        check(muxerStarted) { "AAC-кодировщик не создал звуковую дорожку" }
        muxer.stop()
        muxerStarted = false
        finished = true
        return durationMs
    }

    private suspend fun checkProgress() {
        currentCoroutineContext().ensureActive()
        check(SystemClock.elapsedRealtime() - lastProgress < 30_000) { "AAC-кодировщик не отвечает" }
    }

    private fun drain() {
        while (!outputEnded) {
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(muxerStarted)
                            val buffer = checkNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
                else -> return
            }
            lastProgress = SystemClock.elapsedRealtime()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (encoderStarted) runCatching { encoder.stop() }
            encoder.release()
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
