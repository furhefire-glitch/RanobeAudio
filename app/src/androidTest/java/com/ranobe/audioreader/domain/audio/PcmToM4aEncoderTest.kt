package com.ranobe.audioreader.domain.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmToM4aEncoderTest {
    @Test fun streamsMultipleChunksAndFinishesAacOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File.createTempFile("encoder-test-", ".m4a", context.cacheDir)
        try {
            PcmToM4aEncoder(48000, output).use { encoder ->
                repeat(3) {
                    encoder.write(FloatArray(48000) { i -> (0.2 * sin(2 * PI * 440 * i / 48000)).toFloat() })
                    encoder.write(FloatArray(0))
                }
                assertEquals(144000L, encoder.sampleCount)
                assertEquals(3000L, encoder.finish())
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(3_000_000.0, format.getLong(MediaFormat.KEY_DURATION).toDouble(), 100_000.0)
                extractor.selectTrack(0)
                val buffer = ByteBuffer.allocate(65536)
                var lastTime = -1L
                var frames = 0
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    assertTrue(extractor.sampleTime >= lastTime)
                    lastTime = extractor.sampleTime
                    frames++
                    if (!extractor.advance()) break
                    buffer.clear()
                }
                assertTrue(frames > 100)
            } finally { extractor.release() }
        } finally { output.delete() }
    }

    @Test fun rejectsAnEmptyRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File.createTempFile("encoder-empty-", ".m4a", context.cacheDir)
        try {
            PcmToM4aEncoder(24000, output).use { encoder ->
                try {
                    encoder.finish()
                    fail("An empty recording must not become ready")
                } catch (_: IllegalStateException) { }
            }
        } finally { output.delete() }
    }
}
