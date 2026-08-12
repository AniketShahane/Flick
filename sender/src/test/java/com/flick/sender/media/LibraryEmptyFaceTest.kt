package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The full cross-product of access × count × complete. */
class LibraryEmptyFaceTest {

    @Test
    fun `no access outranks everything else`() {
        for (count in listOf(0, 1, 40)) {
            for (complete in listOf(false, true, null)) {
                assertEquals(
                    "count=$count complete=$complete",
                    LibraryEmptyFace.NO_ACCESS,
                    libraryEmptyFace(MediaAccess.NONE, count, complete),
                )
            }
        }
    }

    @Test
    fun `rows in hand are never an empty library`() {
        for (access in listOf(MediaAccess.PARTIAL, MediaAccess.FULL)) {
            for (complete in listOf(false, true, null)) {
                assertEquals(
                    "$access complete=$complete",
                    LibraryEmptyFace.NOTHING_HERE,
                    libraryEmptyFace(access, itemCount = 1, complete = complete),
                )
            }
        }
    }

    /**
     * The read stopped partway, so its emptiness is not evidence about this phone. It
     * must outrank the partial-grant arm: a walk that never finished cannot say the
     * selection is empty either.
     */
    @Test
    fun `an unfinished read is never reported as an empty phone`() {
        for (access in listOf(MediaAccess.PARTIAL, MediaAccess.FULL)) {
            val face = libraryEmptyFace(access, itemCount = 0, complete = false)
            assertEquals(access.name, LibraryEmptyFace.UNREADABLE, face)
            assertNotEquals(access.name, LibraryEmptyFace.NOTHING_HERE, face)
        }
    }

    /**
     * The first read of every cold start is in flight for as long as MediaStore takes, and
     * for all of it the empty state is on screen with nothing read yet. UNREADABLE there
     * accuses Android of a failure that is not happening — which is what shipped.
     */
    @Test
    fun `a read that has not answered yet accuses nobody`() {
        assertEquals(
            LibraryEmptyFace.NOTHING_HERE,
            libraryEmptyFace(MediaAccess.FULL, itemCount = 0, complete = null),
        )
        assertEquals(
            LibraryEmptyFace.NOTHING_SELECTED,
            libraryEmptyFace(MediaAccess.PARTIAL, itemCount = 0, complete = null),
        )
        for (access in listOf(MediaAccess.PARTIAL, MediaAccess.FULL)) {
            assertNotEquals(
                access.name,
                LibraryEmptyFace.UNREADABLE,
                libraryEmptyFace(access, itemCount = 0, complete = null),
            )
        }
    }

    @Test
    fun `a finished read under a partial grant is an empty selection`() {
        assertEquals(
            LibraryEmptyFace.NOTHING_SELECTED,
            libraryEmptyFace(MediaAccess.PARTIAL, itemCount = 0, complete = true),
        )
    }

    // The only combination that can honestly claim the phone has no films: everything was
    // granted, the whole cursor was walked, and it held nothing.
    @Test
    fun `a finished read under a full grant is a genuinely empty phone`() {
        assertEquals(
            LibraryEmptyFace.NOTHING_HERE,
            libraryEmptyFace(MediaAccess.FULL, itemCount = 0, complete = true),
        )
    }
}
