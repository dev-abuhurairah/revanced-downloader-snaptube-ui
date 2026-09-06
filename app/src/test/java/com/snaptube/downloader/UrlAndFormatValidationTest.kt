package com.snaptube.downloader

import com.snaptube.downloader.core.extractor.VideoExtractorEngine
import com.snaptube.downloader.data.model.PlatformType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlAndFormatValidationTest {

    @Test
    fun testValidHttpUrlDetection() {
        assertTrue(VideoExtractorEngine.isValidHttpUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(VideoExtractorEngine.isValidHttpUrl("http://example.com/video.mp4"))
        assertTrue(VideoExtractorEngine.isValidHttpUrl("https://instagram.com/reel/C12345/"))
        assertTrue(VideoExtractorEngine.isValidHttpUrl("https://vt.tiktok.com/ZS12345/"))

        assertFalse(VideoExtractorEngine.isValidHttpUrl("not_a_url"))
        assertFalse(VideoExtractorEngine.isValidHttpUrl("ftp://server.com/file"))
        assertFalse(VideoExtractorEngine.isValidHttpUrl("search query for cat videos"))
        assertFalse(VideoExtractorEngine.isValidHttpUrl(""))
    }

    @Test
    fun testExtractUrlFromText() {
        val textWithUrl = "Check out this reel: https://www.instagram.com/reel/C3456789/ it's amazing!"
        val extracted = VideoExtractorEngine.extractUrlFromText(textWithUrl)
        assertEquals("https://www.instagram.com/reel/C3456789/", extracted)

        val plainText = "no link here"
        assertEquals("no link here", VideoExtractorEngine.extractUrlFromText(plainText))
    }

    @Test
    fun testPlatformDetection() {
        assertEquals(
            PlatformType.YOUTUBE,
            VideoExtractorEngine.detectPlatform("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            PlatformType.YOUTUBE,
            VideoExtractorEngine.detectPlatform("https://youtu.be/dQw4w9WgXcQ")
        )
        assertEquals(
            PlatformType.INSTAGRAM,
            VideoExtractorEngine.detectPlatform("https://www.instagram.com/reel/C3456789/")
        )
        assertEquals(
            PlatformType.TIKTOK,
            VideoExtractorEngine.detectPlatform("https://www.tiktok.com/@user/video/123456789")
        )
        assertEquals(
            PlatformType.FACEBOOK,
            VideoExtractorEngine.detectPlatform("https://www.facebook.com/watch?v=12345")
        )
        assertEquals(
            PlatformType.TWITTER,
            VideoExtractorEngine.detectPlatform("https://x.com/user/status/12345")
        )
        assertEquals(
            PlatformType.OTHER,
            VideoExtractorEngine.detectPlatform("https://vimeo.com/12345")
        )
    }

    @Test
    fun testExtractYouTubeId() {
        assertEquals(
            "dQw4w9WgXcQ",
            VideoExtractorEngine.extractYouTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            VideoExtractorEngine.extractYouTubeId("https://youtu.be/dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            VideoExtractorEngine.extractYouTubeId("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
    }

    @Test
    fun testCleanMediaUrlStripsChunkRange() {
        val chunkUrl = "https://rr4.googlevideo.com/videoplayback?expire=123&range=0-150000&rn=1&itag=18"
        val cleaned = VideoExtractorEngine.cleanMediaUrl(chunkUrl)
        assertFalse(cleaned.contains("range="))
        assertFalse(cleaned.contains("rn="))
        assertTrue(cleaned.contains("itag=18"))
        assertTrue(cleaned.contains("expire=123"))
    }
}
