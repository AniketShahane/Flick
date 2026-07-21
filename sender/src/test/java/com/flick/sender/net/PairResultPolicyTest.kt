package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairResultPolicyTest {
    private val endpoint = ControlClient.AuthenticatedEndpoint(
        tvId = "ABEiM0RVZneImaq7zN3u_w",
        keyId = "AQIDBAUGBwgJCgsMDQ4PEA",
        tv = "Demo TV",
        peerIp = "192.168.42.17",
        host = "192.168.42.88",
        port = 42421,
    )
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test fun clearsOnlyPostSendOutcomesThatCannotBeSafelyRetried() {
        assertTrue(PairResultPolicy.clearCode(ControlClient.Result.Denied))
        assertTrue(PairResultPolicy.clearCode(ControlClient.Result.Busy))
        assertTrue(PairResultPolicy.clearCode(ControlClient.Result.Unreachable(pairCodeSent = true)))
        assertTrue(PairResultPolicy.clearCode(ControlClient.Result.ProtocolError(pairCodeSent = true)))
        assertFalse(PairResultPolicy.clearCode(ControlClient.Result.Unreachable(pairCodeSent = false)))
        assertFalse(PairResultPolicy.clearCode(ControlClient.Result.ProtocolError(pairCodeSent = false)))
        assertFalse(PairResultPolicy.clearCode(ControlClient.Result.UpdateRequired))
    }

    @Test fun pairedBusyCarriesTheCredentialsTheReducerMustPersistBeforeReportingBusy() {
        val result = ControlClient.Result.PairedBusy(key, endpoint)

        assertEquals(key, result.key)
        assertEquals(endpoint, result.endpoint)
        assertTrue(PairResultPolicy.clearCode(result))
        assertFalse(PairResultPolicy.clearCode(ControlClient.Result.Paired(key, endpoint)))
    }
}
