package com.flick.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtomicMediaSessionTest {
    private data class Session(val uri: String, val token: String)

    @Test fun retargetPublishesOnlyMatchedImmutablePairs() {
        val publication = AtomicMediaSession<Session>()
        publication.publish(Session("content://a", "token-a"))
        assertEquals(Session("content://a", "token-a"), publication.snapshot())
        publication.publish(Session("content://b", "token-b"))
        assertEquals(Session("content://b", "token-b"), publication.snapshot())
        publication.clear()
        assertNull(publication.snapshot())
    }
}
