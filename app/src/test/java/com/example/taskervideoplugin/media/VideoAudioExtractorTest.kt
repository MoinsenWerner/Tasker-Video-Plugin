package com.example.taskervideoplugin.media

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAudioExtractorTest {
    @Test fun normalizesSupportedFormats() {
        assertEquals("mp3", VideoAudioExtractor.normalizedFormat(".MP3"))
        assertEquals("obb", VideoAudioExtractor.normalizedFormat("OBB"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedFormats() {
        VideoAudioExtractor.normalizedFormat("wav")
    }

    @Test fun buildsMp3ExtractionCommandWithoutVideo() {
        assertArrayEquals(
            arrayOf("ffmpeg", "-y", "-i", "/video.mp4", "-vn", "-map", "0:a:0", "-c:a", "libmp3lame", "-q:a", "2", "-f", "mp3", "/audio.mp3"),
            VideoAudioExtractor.command(File("/video.mp4"), File("/audio.mp3"), "mp3")
        )
    }

    @Test fun forcesOggContainerForObbExtension() {
        val command = VideoAudioExtractor.command(File("/video.mp4"), File("/audio.obb"), "obb")
        assertEquals("ogg", command[command.indexOf("-f") + 1])
        assertEquals("/audio.obb", command.last())
    }
}
