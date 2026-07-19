package com.flick.sender.net

import android.content.Context

/**
 * Persists pairings in the app's private storage (control-channel.md §3): a
 * per-TV pairing key so subsequent connects are one-tap `resume`. Keys are never
 * logged and never leave the device. This is a PUBLIC repo — nothing here is a
 * committed secret; keys are minted at runtime by the paired TV.
 */
class PairingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("flick_pairings", Context.MODE_PRIVATE)

    data class Pairing(val name: String, val host: String, val port: Int, val key: String)

    fun save(pairing: Pairing) {
        prefs.edit()
            .putString(keyOf(pairing.host), pairing.key)
            .putString(nameOf(pairing.host), pairing.name)
            .putInt(portOf(pairing.host), pairing.port)
            .putString(LAST_HOST, pairing.host)
            .apply()
    }

    fun keyFor(host: String): String? = prefs.getString(keyOf(host), null)

    fun last(): Pairing? {
        val host = prefs.getString(LAST_HOST, null) ?: return null
        val key = prefs.getString(keyOf(host), null) ?: return null
        val name = prefs.getString(nameOf(host), null) ?: host
        val port = prefs.getInt(portOf(host), 0)
        if (port == 0) return null
        return Pairing(name, host, port, key)
    }

    /**
     * Refresh only the stored control port for an already-paired [host] (the TV's
     * control port is ephemeral and changes on every receiver restart). No-op if the
     * host was never paired, so this never mints a phantom entry or clobbers the name.
     */
    fun updatePort(host: String, port: Int) {
        if (port <= 0 || prefs.getString(keyOf(host), null) == null) return
        prefs.edit().putInt(portOf(host), port).apply()
    }

    fun forget(host: String) {
        prefs.edit()
            .remove(keyOf(host))
            .remove(nameOf(host))
            .remove(portOf(host))
            .apply()
    }

    private fun keyOf(host: String) = "key_$host"
    private fun nameOf(host: String) = "name_$host"
    private fun portOf(host: String) = "port_$host"

    private companion object {
        const val LAST_HOST = "last_host"
    }
}
