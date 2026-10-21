package com.nuvio.tv.core.player

import com.nuvio.tv.core.localmedia.LocalMediaStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeInfoFormatterTest {

    @Test
    fun `resume time uses minutes and seconds below an hour`() {
        assertEquals("23:14 / 58:02", ResumeInfoFormatter.resumeTime(ms(0, 23, 14), ms(0, 58, 2)))
        assertEquals("0:05 / 3:09", ResumeInfoFormatter.resumeTime(ms(0, 0, 5), ms(0, 3, 9)))
    }

    @Test
    fun `resume time switches to hours per value from an hour up`() {
        assertEquals("23:14 / 1:58:02", ResumeInfoFormatter.resumeTime(ms(0, 23, 14), ms(1, 58, 2)))
        assertEquals("1:00:00 / 2:05:09", ResumeInfoFormatter.resumeTime(ms(1, 0, 0), ms(2, 5, 9)))
    }

    @Test
    fun `resume time is unknown without both position and duration`() {
        assertNull(ResumeInfoFormatter.resumeTime(0L, ms(0, 58, 2)))
        assertNull(ResumeInfoFormatter.resumeTime(ms(0, 23, 14), 0L))
        assertNull(ResumeInfoFormatter.resumeTime(-1L, -1L))
    }

    @Test
    fun `resume time never shows a position past the end`() {
        assertEquals("58:02 / 58:02", ResumeInfoFormatter.resumeTime(ms(1, 0, 0), ms(0, 58, 2)))
    }

    @Test
    fun `clock drops milliseconds`() {
        assertEquals("1:01", ResumeInfoFormatter.clock(61_999L))
        assertEquals("0:00", ResumeInfoFormatter.clock(-5L))
    }

    @Test
    fun `local source shows the volume label`() {
        assertEquals(
            "Local · USB Drive",
            ResumeInfoFormatter.sourceLabel(
                addonName = LocalMediaStreams.ADDON_NAME,
                streamName = "Local 1080p",
                streamDescription = "USB Drive › Movies/Dune\n2.10 GB"
            )
        )
        assertEquals(
            "Local · Internal storage",
            ResumeInfoFormatter.sourceLabel(LocalMediaStreams.ADDON_NAME, "Local", "Internal storage\n700 MB")
        )
    }

    @Test
    fun `local source without a location is just local`() {
        assertEquals("Local", ResumeInfoFormatter.sourceLabel(LocalMediaStreams.ADDON_NAME, "Local 720p", "1.20 GB"))
        assertEquals("Local", ResumeInfoFormatter.sourceLabel(LocalMediaStreams.ADDON_NAME, "Local", null))
    }

    @Test
    fun `addon source drops the repeated addon name from the stream name`() {
        assertEquals("Torrentio · 1080p", ResumeInfoFormatter.sourceLabel("Torrentio", "Torrentio\n1080p", null))
        assertEquals("Torrentio · 4k HDR", ResumeInfoFormatter.sourceLabel("Torrentio", "Torrentio  4k   HDR", null))
        assertEquals("Torrentio · 720p", ResumeInfoFormatter.sourceLabel("Torrentio", "Torrentio - 720p", null))
    }

    @Test
    fun `addon name is only dropped as a whole word`() {
        assertEquals("AIO · AIOStreams 1080p", ResumeInfoFormatter.sourceLabel("AIO", "AIOStreams 1080p", null))
    }

    @Test
    fun `addon source falls back to whichever name is known`() {
        assertEquals("Torrentio", ResumeInfoFormatter.sourceLabel("Torrentio", "Torrentio", null))
        assertEquals("Torrentio", ResumeInfoFormatter.sourceLabel("Torrentio", null, null))
        assertEquals("[RD+] 1080p", ResumeInfoFormatter.sourceLabel(null, "[RD+]\n1080p", null))
        assertNull(ResumeInfoFormatter.sourceLabel(null, "  ", null))
        assertNull(ResumeInfoFormatter.sourceLabel(" ", null, "ignored"))
    }

    @Test
    fun `source label is capped`() {
        val label = ResumeInfoFormatter.sourceLabel("Addon", "x".repeat(500), null)
        assertEquals(80, label?.length)
    }

    private fun ms(hours: Long, minutes: Long, seconds: Long): Long =
        ((hours * 60L + minutes) * 60L + seconds) * 1000L
}
