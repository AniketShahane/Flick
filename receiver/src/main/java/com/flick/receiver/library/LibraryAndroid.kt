package com.flick.receiver.library

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** The TV's own library choices. Holds nothing the phone sent (§12). */
class LibraryPrefs internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** §10: on by default. */
    var autoplayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()
        }

    private companion object {
        const val PREFS = "flick_library"
        const val KEY_AUTOPLAY = "autoplay_next"
    }
}

/** Where the TV keeps its [CreditsLeadBook]. Excluded from backup and device transfer. */
class CreditsLeadPrefs(context: Context) : CreditsLeadStorage {
    private val prefs = context.applicationContext.getSharedPreferences(CREDITS_PREFS, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_SAMPLES, null)

    override fun save(encoded: String) {
        prefs.edit().putString(KEY_SAMPLES, encoded).apply()
    }

    private companion object {
        const val CREDITS_PREFS = "flick_credits"
        const val KEY_SAMPLES = "samples_v1"
    }
}

/** Where the TV keeps its [KnockAddressBook]. Excluded from backup and device transfer. */
class KnockAddressPrefs(context: Context) : KnockAddressStorage {
    private val prefs = context.applicationContext.getSharedPreferences(DOORBELL_PREFS, Context.MODE_PRIVATE)

    override fun loadAll(): Map<String, String> =
        prefs.all.mapNotNull { (name, value) ->
            val keyId = name.removePrefix(KEY_PREFIX).takeIf { name.startsWith(KEY_PREFIX) }
            if (keyId != null && value is String) keyId to value else null
        }.toMap()

    override fun save(keyId: String, encoded: String) {
        prefs.edit().putString(KEY_PREFIX + keyId, encoded).apply()
    }

    override fun remove(keyId: String) {
        prefs.edit().remove(KEY_PREFIX + keyId).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val DOORBELL_PREFS = "flick_doorbell"
        const val KEY_PREFIX = "addr_"
    }
}

/**
 * A phone thumbnail (§6.4) as a tile image. RGB_565 halves the memory of the ~120 kept:
 * a 384×216 still has no alpha and its banding is invisible at tile size.
 */
fun decodeLibraryThumbnail(jpeg: ByteArray): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.asImageBitmap()
}
