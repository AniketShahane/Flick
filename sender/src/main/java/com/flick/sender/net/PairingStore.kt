package com.flick.sender.net

import android.content.Context

/** Private v2 pairing persistence. All writes use commit so a successful proof is durable before UI advances. */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("flick_pairings", Context.MODE_PRIVATE)

    data class Pairing(
        val tvId: String,
        val keyId: String,
        val name: String,
        val host: String,
        val port: Int,
        val key: String,
        val needsRepair: Boolean = false,
    )

    fun last(): Pairing? = prefs.getString(LAST_TV, null)?.let(::get)

    fun get(tvId: String): Pairing? {
        if (prefs.getBoolean(tombstone(tvId), false)) return null
        val key = prefs.getString(key(tvId), null) ?: return null
        val keyId = prefs.getString(keyId(tvId), null) ?: return null
        val host = prefs.getString(host(tvId), null) ?: return null
        val port = prefs.getInt(port(tvId), 0)
        val pairing = Pairing(tvId, keyId, prefs.getString(name(tvId), null) ?: "TV", host, port, key, prefs.getBoolean(repair(tvId), false))
        return pairing.takeIf { PairingRecordRules.valid(it.toRule()) }
    }

    /**
     * A legacy record can be retired only after a visible v2 pair at that exact old
     * host succeeds. The current v2 receiver has no safe legacy-tvId lookup, so no
     * legacy proof is ever sent to a discovered or changed address.
     */
    fun save(pairing: Pairing, replacing: Pairing? = get(pairing.tvId), legacyHost: String? = null): Boolean {
        if (!PairingRecordRules.valid(pairing.toRule())) return false
        val editor = prefs.edit()
        val replacement = PairingRecordRules.replacement(replacing?.toRule(), pairing.toRule())
        if (replacement.tombstoneKeyId != null) {
            editor.putBoolean(tombstone(pairing.tvId + ":" + replacement.tombstoneKeyId), true)
            replacement.removeLegacyHost?.let { removeLegacy(editor, it) }
        }
        val verifiedLegacyHost = legacyHost?.takeIf {
            it == pairing.host && PairingRecordRules.legacyMigrationAllowed(it, pairing.host) && legacyForHost(it) != null
        }
        if (verifiedLegacyHost != null) {
            editor.putString("legacy_migration_$verifiedLegacyHost", "${pairing.tvId}:${pairing.keyId}")
            editor.remove("key_$verifiedLegacyHost").remove("name_$verifiedLegacyHost").remove("port_$verifiedLegacyHost")
        }
        return editor.putString(key(pairing.tvId), pairing.key)
            .putString(keyId(pairing.tvId), pairing.keyId)
            .putString(name(pairing.tvId), pairing.name)
            .putString(host(pairing.tvId), pairing.host)
            .putInt(port(pairing.tvId), pairing.port)
            .putBoolean(repair(pairing.tvId), pairing.needsRepair)
            .putBoolean(tombstone(pairing.tvId), false)
            .putString(LAST_TV, pairing.tvId)
            .commit()
    }

    /** Endpoint/name are committed only after a valid server proof. */
    fun commitVerifiedEndpoint(tvId: String, name: String, host: String, port: Int): Boolean {
        if (get(tvId) == null) return false
        return prefs.edit().putString(this.name(tvId), name).putString(this.host(tvId), host).putInt(this.port(tvId), port)
            .putBoolean(repair(tvId), false).commit()
    }

    fun markNeedsRepair(tvId: String) { if (get(tvId) != null) prefs.edit().putBoolean(repair(tvId), true).commit() }

    fun forget(tvId: String) {
        val pairing = get(tvId) ?: return
        prefs.edit().putBoolean(tombstone(tvId), true).putBoolean(tombstone(tvId + ":" + pairing.keyId), true)
            .remove(key(tvId)).remove(keyId(tvId)).remove(name(tvId)).remove(host(tvId)).remove(port(tvId)).remove(repair(tvId))
            .also { removeLegacy(it, pairing.host); if (prefs.getString(LAST_TV, null) == tvId) it.remove(LAST_TV) }.commit()
    }

    /** Reads the v1 host record only for an exact-host proof migration. */
    fun legacyForHost(host: String): Pairing? {
        if (!PairLaunch.isCanonicalIpv4(host)) return null
        val key = prefs.getString("key_$host", null) ?: return null
        val port = prefs.getInt("port_$host", 0)
        if (!ControlProtocolV2.key(key) || port !in 1..65535) return null
        return Pairing("", ControlProtocolV2.legacyKeyId(key), prefs.getString("name_$host", null) ?: "TV", host, port, key)
    }

    fun legacyLast(): Pairing? = prefs.getString("last_host", null)
        ?.takeIf(PairLaunch::isCanonicalIpv4)
        ?.let(::legacyForHost)

    fun writeLegacyMigration(host: String, tvId: String, keyId: String): Boolean =
        prefs.edit().putString("legacy_migration_$host", "$tvId:$keyId").commit()

    private fun removeLegacy(editor: android.content.SharedPreferences.Editor, host: String) {
        if (PairLaunch.isCanonicalIpv4(host)) editor.remove("key_$host").remove("name_$host").remove("port_$host").remove("legacy_migration_$host")
    }
    private fun key(tvId: String) = "v2_key_$tvId"
    private fun keyId(tvId: String) = "v2_key_id_$tvId"
    private fun name(tvId: String) = "v2_name_$tvId"
    private fun host(tvId: String) = "v2_host_$tvId"
    private fun port(tvId: String) = "v2_port_$tvId"
    private fun repair(tvId: String) = "v2_repair_$tvId"
    private fun tombstone(id: String) = "v2_tombstone_$id"
    private fun Pairing.toRule() = PairingRecordRules.Record(tvId, keyId, name, host, port, key, needsRepair)
    private companion object { const val LAST_TV = "v2_last_tv" }
}
