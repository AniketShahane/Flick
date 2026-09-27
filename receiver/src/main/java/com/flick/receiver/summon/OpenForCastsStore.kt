package com.flick.receiver.summon

import android.content.Context
import android.content.SharedPreferences

private const val PREFS = "flick_open_for_casts"

private const val KEY_ENABLED = "enabled"
private const val KEY_STRIKES = "strikes"
private const val KEY_BLOCKED = "blocked"
private const val KEY_STRIKE_FINGERPRINT = "strike_fingerprint"
private const val KEY_STRIKE_VERSION = "strike_version"

/**
 * The viewer's "Open when you cast" choice and this TV's launch-strike record.
 * Main thread only.
 *
 * The fingerprint and version scope the whole strike record, `blocked` included:
 * strikes describe what this OS build let this app version do, so a restored
 * backup or an update must not inherit them.
 */
class OpenForCastsStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Off by default: off must behave exactly as a TV without the feature. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    val strikes: Int
        get() = prefs.getInt(KEY_STRIKES, 0)

    val blocked: Boolean
        get() = prefs.getBoolean(KEY_BLOCKED, false)

    val strikeFingerprint: String
        get() = prefs.getString(KEY_STRIKE_FINGERPRINT, "").orEmpty()

    val strikeVersion: Long
        get() = prefs.getLong(KEY_STRIKE_VERSION, 0L)

    fun saveStrikes(strikes: Int, blocked: Boolean, fingerprint: String, version: Long) {
        prefs.edit()
            .putInt(KEY_STRIKES, strikes)
            .putBoolean(KEY_BLOCKED, blocked)
            .putString(KEY_STRIKE_FINGERPRINT, fingerprint)
            .putLong(KEY_STRIKE_VERSION, version)
            .apply()
    }

    fun clearStrikes() = saveStrikes(strikes = 0, blocked = false, fingerprint = "", version = 0L)
}
