package com.flick.sender.net

/** Pure persistence policy: makes migration/revocation behavior deterministic without Android storage. */
object PairingRecordRules {
    data class Record(val tvId: String, val keyId: String, val name: String, val host: String, val port: Int, val key: String, val needsRepair: Boolean = false)
    data class Replacement(val tombstoneKeyId: String?, val removeLegacyHost: String?)

    fun valid(record: Record): Boolean = ControlProtocolV2.id(record.tvId) && ControlProtocolV2.id(record.keyId) &&
        ControlProtocolV2.key(record.key) && ControlProtocolV2.normalizedLabel(record.name, 80) == record.name && PairLaunch.isCanonicalIpv4(record.host) && record.port in 1..65535
    fun replacement(previous: Record?, next: Record): Replacement = when {
        previous == null || previous.tvId != next.tvId || previous.keyId == next.keyId -> Replacement(null, null)
        else -> Replacement(previous.keyId, previous.host)
    }
    fun legacyMigrationAllowed(legacyHost: String, candidateHost: String) = PairLaunch.isCanonicalIpv4(legacyHost) && legacyHost == candidateHost
    fun mayAutoResume(record: Record?, tombstoned: Boolean) = record != null && valid(record) && !record.needsRepair && !tombstoned
}
