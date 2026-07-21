package com.flick.receiver.net

import java.io.InputStream

object ExactBodyReader {
    /**
     * Reads the advertised body and one EOF byte under one absolute deadline.
     * A read timeout alone is insufficient: a peer can drip one byte just before
     * every timeout indefinitely.
     */
    fun readExactlyThenEof(
        input: InputStream,
        expected: Long,
        deadlineElapsedMs: Long,
        nowElapsedMs: () -> Long,
    ): Boolean {
        var remaining = expected
        val buffer = ByteArray(1024)
        while (remaining > 0) {
            if (nowElapsedMs() >= deadlineElapsedMs) return false
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0 || nowElapsedMs() > deadlineElapsedMs) return false
            remaining -= read
        }
        if (nowElapsedMs() >= deadlineElapsedMs) return false
        return input.read() == -1 && nowElapsedMs() <= deadlineElapsedMs
    }

    /** Kept for the small pure contract tests that do not exercise time. */
    fun readExactlyThenEof(input: InputStream, expected: Long): Boolean {
        var remaining = expected; val buffer = ByteArray(1024)
        while (remaining > 0) { val read=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt()); if(read<=0)return false; remaining-=read }
        return input.read() == -1
    }
}
