package com.flick.sender.net

/**
 * What the hand feels — the phone's half of the motion (design §6 / Part 4).
 *
 * Its own file, free of Android imports, so [ScrubFeel] and its test can name a cue
 * without the vibrator on the classpath.
 */
enum class HapticCue { GRIP, DETENT, EDGE, SNAP, CONFIRM }
