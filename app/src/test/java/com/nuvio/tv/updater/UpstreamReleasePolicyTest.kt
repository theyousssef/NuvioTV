package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamReleasePolicyTest {
    private val day = UpstreamReleasePolicy.CHECK_INTERVAL_MS

    @Test
    fun `first check is always due`() {
        assertTrue(UpstreamReleasePolicy.isCheckDue(nowMs = 1_000L, lastCheckAtMs = 0L))
    }

    @Test
    fun `check is not due within a day`() {
        assertFalse(UpstreamReleasePolicy.isCheckDue(nowMs = 10 * day - 1, lastCheckAtMs = 9 * day))
    }

    @Test
    fun `check is due after a day`() {
        assertTrue(UpstreamReleasePolicy.isCheckDue(nowMs = 10 * day, lastCheckAtMs = 9 * day))
    }

    @Test
    fun `check is due when the clock went backwards`() {
        assertTrue(UpstreamReleasePolicy.isCheckDue(nowMs = 5 * day, lastCheckAtMs = 9 * day))
    }

    @Test
    fun `baseline release is not news`() {
        assertFalse(UpstreamReleasePolicy.shouldNotify("1.0.0", lastNotifiedTag = null))
        assertFalse(UpstreamReleasePolicy.shouldNotify("v1.0.0", lastNotifiedTag = null))
    }

    @Test
    fun `release older than baseline is not news`() {
        assertFalse(UpstreamReleasePolicy.shouldNotify("0.9.5", lastNotifiedTag = null))
    }

    @Test
    fun `first stable release after baseline is news`() {
        assertTrue(UpstreamReleasePolicy.shouldNotify("1.0.1", lastNotifiedTag = null))
        assertTrue(UpstreamReleasePolicy.shouldNotify("v1.1.0", lastNotifiedTag = null))
    }

    @Test
    fun `prerelease tags are never news`() {
        assertFalse(UpstreamReleasePolicy.shouldNotify("1.1.0-beta.3", lastNotifiedTag = null))
    }

    @Test
    fun `already notified tag is not shown again`() {
        assertFalse(UpstreamReleasePolicy.shouldNotify("1.1.0", lastNotifiedTag = "1.1.0"))
        assertFalse(UpstreamReleasePolicy.shouldNotify("v1.1.0", lastNotifiedTag = "1.1.0"))
    }

    @Test
    fun `newer release than the notified one is news`() {
        assertTrue(UpstreamReleasePolicy.shouldNotify("1.2.0", lastNotifiedTag = "1.1.0"))
    }

    @Test
    fun `older release than the notified one is not news`() {
        assertFalse(UpstreamReleasePolicy.shouldNotify("1.1.0", lastNotifiedTag = "1.2.0"))
    }

    @Test
    fun `unparseable latest tag is ignored`() {
        assertFalse(UpstreamReleasePolicy.shouldNotify("nightly", lastNotifiedTag = null))
        assertFalse(UpstreamReleasePolicy.shouldNotify(null, lastNotifiedTag = null))
    }

    @Test
    fun `unparseable notified tag falls back to the baseline`() {
        assertTrue(UpstreamReleasePolicy.shouldNotify("1.1.0", lastNotifiedTag = "garbage"))
    }

    @Test
    fun `release url points at the tag page`() {
        assertEquals(
            "https://github.com/NuvioMedia/NuvioTV/releases/tag/v1.1.0",
            UpstreamReleasePolicy.releaseUrl("NuvioMedia", "NuvioTV", "v1.1.0")
        )
    }
}
