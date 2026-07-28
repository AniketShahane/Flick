package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity gate on `ControlServer.forget`. `ControlServer` binds a real Ktor
 * engine and cannot be built on the JVM, so the decision it turns on lives in a
 * pure function and is exercised here.
 */
class ForgetRevokeGateTest {
    @Test fun forgettingTheConnectedPhoneEndsItsSession() {
        assertTrue(forgetRevokesActiveConnection("keyA", "keyA"))
    }

    /** The case the gate exists for: one phone's forget may not drop another's film. */
    @Test fun forgettingAnotherPhoneLeavesTheActiveConnectionAlone() {
        assertFalse(forgetRevokesActiveConnection("keyB", "keyA"))
    }

    @Test fun forgettingWithNoPhoneConnectedRevokesNothing() {
        assertFalse(forgetRevokesActiveConnection("keyA", null))
    }

    /** Key ids are compared whole — a shared prefix is a different phone. */
    @Test fun aSimilarKeyIdIsNotTheSamePhone() {
        assertFalse(forgetRevokesActiveConnection("keyA", "keyA2"))
        assertFalse(forgetRevokesActiveConnection("keyA2", "keyA"))
        assertFalse(forgetRevokesActiveConnection("keyA", "KEYA"))
    }
}
