package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlValidatorTest {
    private val peer = "192.168.42.17"
    private val valid = "http://192.168.42.17:8080/v/ZGVtb19tZWRpYV90b2tlbg"
    @Test fun acceptsOnlyCanonicalPinnedUrl() = assertTrue(MediaUrlValidator.isValid(valid, peer))
    @Test fun rejectsRedirectAndSsrfShapes() {
        listOf(
            valid.replace("192.168.42.17", "192.168.42.18"), valid.replace(":8080", ":8081"),
            valid.replace("http://", "https://"), "$valid?x=1", "$valid#x",
            valid.replace("http://", "http://x@"), valid.replace("/v/", "/v/%5a"),
            valid.replace("/v/", "//v/"), "$valid/", valid.replace("192.168.42.17", "localhost"),
        ).forEach { assertFalse(it, MediaUrlValidator.isValid(it, peer)) }
    }
}
