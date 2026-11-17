package com.flick.receiver.util

/**
 * Whether this TV's Wi-Fi link moved enough since the last journaled reading to be
 * worth a line. The radio is sampled every diagnostics tick and RSSI jitters by a
 * few dB at rest, so only a channel change, a gain or loss of the link, or an RSSI
 * swing of [RSSI_STEP_DB] counts. A null frequency is no Wi-Fi link.
 */
internal object WifiLinkChange {
    const val RSSI_STEP_DB = 10

    fun worthRecording(
        recordedFrequencyMhz: Int?,
        recordedRssiDbm: Int?,
        frequencyMhz: Int?,
        rssiDbm: Int?,
        anyRecorded: Boolean,
    ): Boolean {
        if (!anyRecorded) return true
        if (recordedFrequencyMhz != frequencyMhz) return true
        if (recordedRssiDbm == null || rssiDbm == null) return recordedRssiDbm != rssiDbm
        return kotlin.math.abs(rssiDbm - recordedRssiDbm) >= RSSI_STEP_DB
    }
}
