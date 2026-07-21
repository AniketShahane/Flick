package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeResponseValidatorTest {
    @Test fun acceptsExactPartialBodyContract() {
        assertEquals(1024L, RangeResponseValidator.expectedLength(206, "bytes 0-1023/4096", 1024))
        assertEquals(17L, RangeResponseValidator.expectedLength(206, "bytes 0-16/17", 17))
    }
    @Test fun rejectsRedirectsAndIncoherentRanges() {
        assertNull(RangeResponseValidator.expectedLength(200, "bytes 0-1023/4096", 1024))
        assertNull(RangeResponseValidator.expectedLength(302, null, 0))
        assertNull(RangeResponseValidator.expectedLength(206, "bytes 0-1022/4096", 1024))
        assertNull(RangeResponseValidator.expectedLength(206, "bytes 0-1023/0", 1024))
        assertNull(RangeResponseValidator.expectedLength(206, "bytes 0-1023/4096", 1023))
    }
}
