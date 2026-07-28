package com.flick.sender.ui.screens

import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import com.flick.sender.net.PairedTv
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedDeviceMatchTest {

    private fun discovered(
        name: String = "Living Room",
        host: String = "192.168.42.17",
        port: Int = 8009,
        tvId: String? = "tv-a",
        state: TvAvailability = TvAvailability.READY,
    ) = DiscoveredTv(name = name, host = host, port = port, tvId = tvId, model = null, state = state)

    private fun paired(
        name: String = "Living Room",
        host: String = "192.168.42.17",
        port: Int = 8009,
        tvId: String = "tv-a",
    ) = PairedTv(name = name, host = host, port = port, tvId = tvId)

    @Test
    fun onlyALiveControlLinkMakesARowTheConnectedOne() {
        val tv = discovered()
        assertTrue(isConnectedDevice(ConnectionStatus.CONNECTED, paired(), tv))
        for (status in ConnectionStatus.entries.filter { it != ConnectionStatus.CONNECTED }) {
            assertFalse(status.name, isConnectedDevice(status, paired(), tv))
        }
        assertFalse(isConnectedDevice(ConnectionStatus.CONNECTED, null, tv))
    }

    @Test
    fun anAdvertisedIdentityNeverMovesTheBadgeToAnotherAddress() {
        // mDNS is unauthenticated. A LAN host that claims the paired tvId at its own
        // address must not collect the badge — nor the address printed under it, which
        // is the one the user reads off the phone.
        assertFalse(
            isConnectedDevice(
                ConnectionStatus.CONNECTED,
                paired(host = "192.168.42.17"),
                discovered(host = "192.168.42.66"),
            ),
        )
        assertFalse(
            isConnectedDevice(
                ConnectionStatus.CONNECTED,
                paired(port = 8009),
                discovered(port = 9100),
            ),
        )
    }

    @Test
    fun theIdentityVetoesAnAddressThatChangedHands() {
        // Same endpoint, different receiver: the record's address is the verified one,
        // but the TV answering at it now is not the one the socket is open to.
        assertFalse(
            isConnectedDevice(
                ConnectionStatus.CONNECTED,
                paired(host = "192.168.42.17", tvId = "tv-a"),
                discovered(host = "192.168.42.17", tvId = "tv-b"),
            ),
        )
    }

    @Test
    fun theEndpointStandsAloneWhenEitherSideCarriesNoIdentity() {
        val record = paired(tvId = "")
        assertTrue(isConnectedDevice(ConnectionStatus.CONNECTED, record, discovered(tvId = null)))
        assertTrue(isConnectedDevice(ConnectionStatus.CONNECTED, record, discovered(tvId = "tv-a")))
        assertFalse(
            isConnectedDevice(ConnectionStatus.CONNECTED, record, discovered(tvId = null, port = 9100)),
        )
        assertFalse(
            isConnectedDevice(
                ConnectionStatus.CONNECTED,
                record,
                discovered(tvId = null, host = "192.168.42.99"),
            ),
        )
    }

    @Test
    fun aSharedNameNeverMatchesTwoDifferentTvs() {
        // Two identical TVs out of the same box advertise the same name; matching on it
        // would mark both rows connected.
        assertFalse(
            isConnectedDevice(
                ConnectionStatus.CONNECTED,
                paired(name = "TV", host = "192.168.42.17", tvId = "tv-a"),
                discovered(name = "TV", host = "192.168.42.18", tvId = "tv-b"),
            ),
        )
        assertFalse(
            isConnectedDevice(
                ConnectionStatus.CONNECTED,
                paired(name = "TV", host = "192.168.42.17", tvId = ""),
                discovered(name = "TV", host = "192.168.42.18", tvId = null),
            ),
        )
    }

    @Test
    fun aSleepingAdvertisementDoesNotUnseatALiveLink() {
        // The advertisement is a cache; a TV answering control frames is awake.
        assertTrue(
            isConnectedDevice(
                ConnectionStatus.CONNECTED,
                paired(),
                discovered(state = TvAvailability.SLEEPING),
            ),
        )
    }

    @Test
    fun linkLiveNeedsBothAConnectedStatusAndARecord() {
        assertTrue(linkLive(ConnectionStatus.CONNECTED, paired()))
        assertFalse(linkLive(ConnectionStatus.CONNECTED, null))
        assertFalse(linkLive(ConnectionStatus.CONNECTING, paired()))
        assertFalse(linkLive(ConnectionStatus.DISCONNECTED, paired()))
    }
}
