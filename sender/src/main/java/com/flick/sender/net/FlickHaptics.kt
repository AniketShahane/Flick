package com.flick.sender.net

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** What the hand feels — the phone's half of the motion (design §6 / Part 4). */
enum class HapticCue { GRIP, DETENT, SNAP, CONFIRM }

/**
 * Wraps the platform vibrator so the scrub gesture can speak back: soft ripple on
 * grip, a tick per 10s-of-film detent, a firm snap on release, a single confirm
 * pulse on play/pause.
 */
class FlickHaptics(context: Context) {

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(VibratorManager::class.java)
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun play(cue: HapticCue) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val (durationMs, amplitude) = when (cue) {
            HapticCue.GRIP -> 12L to 90
            HapticCue.DETENT -> 8L to 60
            HapticCue.SNAP -> 22L to 200
            HapticCue.CONFIRM -> 16L to 140
        }
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    }
}
