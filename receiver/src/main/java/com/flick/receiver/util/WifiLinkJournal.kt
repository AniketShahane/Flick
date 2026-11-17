package com.flick.receiver.util

/** Journals this TV's own Wi-Fi link when [WifiLinkChange] says it moved. One owner thread. */
internal class WifiLinkJournal {
    private var anyRecorded = false
    private var recordedFrequencyMhz: Int? = null
    private var recordedRssiDbm: Int? = null

    fun observe(link: WifiTelemetry.Link?) {
        val frequencyMhz = link?.frequencyMhz
        val rssiDbm = link?.rssiDbm
        if (!WifiLinkChange.worthRecording(recordedFrequencyMhz, recordedRssiDbm, frequencyMhz, rssiDbm, anyRecorded)) return
        anyRecorded = true
        recordedFrequencyMhz = frequencyMhz
        recordedRssiDbm = rssiDbm
        if (link == null) {
            IncidentJournal.record("wifi_link", "up" to false)
        } else {
            IncidentJournal.record(
                "wifi_link",
                "up" to true,
                "freq_mhz" to link.frequencyMhz,
                "rssi_dbm" to link.rssiDbm,
                "link_mbps" to link.linkSpeedMbps,
            )
        }
    }
}
