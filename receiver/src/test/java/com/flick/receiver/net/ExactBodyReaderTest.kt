package com.flick.receiver.net

import java.io.ByteArrayInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactBodyReaderTest {
    @Test fun requiresExactLengthAndEof() {
        assertTrue(ExactBodyReader.readExactlyThenEof(ByteArrayInputStream(ByteArray(4)),4))
        assertFalse(ExactBodyReader.readExactlyThenEof(ByteArrayInputStream(ByteArray(3)),4))
        assertFalse(ExactBodyReader.readExactlyThenEof(ByteArrayInputStream(ByteArray(5)),4))
    }

    @Test fun dripFeedCannotOutrunAbsoluteDeadline() {
        var now = 0L
        val drip = object : java.io.InputStream() {
            private var left = 4
            override fun read(): Int = if (left-- > 0) { now += 1_500L; 1 } else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (left-- <= 0) return -1
                now += 1_500L
                buffer[offset] = 1
                return 1
            }
        }
        assertFalse(ExactBodyReader.readExactlyThenEof(drip, 4, deadlineElapsedMs = 6_000L) { now })
    }
}
