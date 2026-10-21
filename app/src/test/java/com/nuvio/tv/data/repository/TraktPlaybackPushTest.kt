package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class TraktPlaybackPushTest {
    @Test
    fun `classifies trakt responses`() {
        assertEquals(TraktPlaybackPushResult.STORED, classifyTraktPlaybackPush(201))
        assertEquals(TraktPlaybackPushResult.STORED, classifyTraktPlaybackPush(409))
        assertEquals(TraktPlaybackPushResult.REJECTED, classifyTraktPlaybackPush(422))
        assertEquals(TraktPlaybackPushResult.REJECTED, classifyTraktPlaybackPush(404))
        assertEquals(TraktPlaybackPushResult.RETRY_LATER, classifyTraktPlaybackPush(429))
        assertEquals(TraktPlaybackPushResult.RETRY_LATER, classifyTraktPlaybackPush(401))
        assertEquals(TraktPlaybackPushResult.RETRY_LATER, classifyTraktPlaybackPush(503))
        assertEquals(TraktPlaybackPushResult.RETRY_LATER, classifyTraktPlaybackPush(null))
    }
}
