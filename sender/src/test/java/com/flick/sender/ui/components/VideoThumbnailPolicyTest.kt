package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VideoThumbnailPolicyTest {

    @Test
    fun `normal cache uses one eighth of a small heap`() {
        assertEquals(12 * MIB, frameCacheBudgetBytes(memoryClassMb = 96, lowRam = false))
    }

    @Test
    fun `normal cache is capped on large heaps`() {
        assertEquals(16 * MIB, frameCacheBudgetBytes(memoryClassMb = 512, lowRam = false))
    }

    @Test
    fun `low ram cache has a smaller hard cap`() {
        assertEquals(8 * MIB, frameCacheBudgetBytes(memoryClassMb = 256, lowRam = true))
    }

    @Test
    fun `thumbnail key is stable for an unchanged MediaStore row`() {
        val first = key(modified = 100L, size = 2_000L)
        val second = key(modified = 100L, size = 2_000L)

        assertEquals(first, second)
    }

    @Test
    fun `thumbnail key changes when source revision changes`() {
        assertNotEquals(key(modified = 100L, size = 2_000L), key(modified = 101L, size = 2_000L))
        assertNotEquals(key(modified = 100L, size = 2_000L), key(modified = 100L, size = 2_001L))
    }

    @Test
    fun `modern thumbnail key changes with generation`() {
        assertNotEquals(
            modernKey(generation = 7L, version = "store-a"),
            modernKey(generation = 8L, version = "store-a"),
        )
    }

    @Test
    fun `modern thumbnail key namespaces generations by MediaStore version`() {
        assertNotEquals(
            modernKey(generation = 7L, version = "store-a"),
            modernKey(generation = 7L, version = "store-b"),
        )
    }

    private fun key(modified: Long, size: Long): String = libraryThumbnailCacheKey(
        uri = "content://media/external/video/media/42",
        dateModifiedSeconds = modified,
        generationModified = null,
        mediaStoreVersion = null,
        sizeBytes = size,
        durationMs = 90_000L,
        width = 3_840,
        height = 2_160,
    )

    private fun modernKey(generation: Long, version: String): String = libraryThumbnailCacheKey(
        uri = "content://media/external/video/media/42",
        dateModifiedSeconds = 100L,
        generationModified = generation,
        mediaStoreVersion = version,
        sizeBytes = 2_000L,
        durationMs = 90_000L,
        width = 3_840,
        height = 2_160,
    )

    private companion object {
        const val MIB = 1_048_576
    }
}
