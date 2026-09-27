package com.flick.receiver.summon

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import com.flick.receiver.MainActivity
import com.flick.receiver.util.FlickLog

/**
 * What a [WakeActivity] and the [ForegroundSummoner] that launched it tell each
 * other. Main thread only: both sides run on the main looper.
 */
object SummonSignals {
    const val EXTRA_ATTEMPT = "com.flick.receiver.summon.ATTEMPT"
    const val EXTRA_ISSUED_AT = "com.flick.receiver.summon.ISSUED_AT"

    /** Main thread. Set by ForegroundSummoner.start(), cleared by its stop(). */
    var onResumed: ((Long) -> Unit)? = null

    private var latestIssued = 0L
    private var abandoned = 0L
    private var resumedId = 0L

    /**
     * Attempt ids restart at 1 in every new composition while this object lives as
     * long as the process, so a reused id must not inherit the old attempt's marks.
     */
    fun issue(attemptId: Long) {
        latestIssued = attemptId
        if (abandoned == attemptId) abandoned = 0L
        if (resumedId == attemptId) resumedId = 0L
    }

    fun abandon(attemptId: Long) {
        abandoned = attemptId
    }

    /** Only the newest attempt, and never one its cast has let go of. */
    fun wanted(attemptId: Long): Boolean =
        attemptId > 0L && attemptId == latestIssued && attemptId != abandoned

    fun markResumed(attemptId: Long) {
        resumedId = attemptId
        onResumed?.invoke(attemptId)
    }

    fun resumed(attemptId: Long): Boolean = attemptId > 0L && resumedId == attemptId
}

/**
 * A translucent, contentless trampoline in its own task. It turns the screen on,
 * gets past a non-secure keyguard, and hands off to MainActivity's existing task.
 *
 * It exists so the background launch never targets MainActivity's task directly:
 * that task keeps its back stack, and only this throwaway task is ever cleared.
 *
 * Deliberately not NoDisplay (such an activity never gets a window, so it can
 * neither turn the screen on nor take focus), not noHistory, and never finished
 * in onStop.
 */
class WakeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var attempt = -1L
    private var focusHandled = false
    private val giveUpOnDeadline = Runnable { giveUp("timeout") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attempt = intent.getLongExtra(SummonSignals.EXTRA_ATTEMPT, -1L)
        val issuedAt = intent.getLongExtra(SummonSignals.EXTRA_ISSUED_AT, SystemClock.elapsedRealtime())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }

        // Before the screen is turned on: a cast cancelled before this activity
        // was created must not even wake the panel.
        if (!SummonSignals.wanted(attempt)) {
            giveUp("abandoned")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        FlickLog.i("summon", "wake created attempt=$attempt")
        // Measured from the request, not from onCreate: the summoner's wait is.
        val remaining = (issuedAt + SummonPolicy.WAKE_GIVE_UP_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        handler.postDelayed(giveUpOnDeadline, remaining)
    }

    override fun onResume() {
        super.onResume()
        // Being resumed proves the OS let the background launch through, which is
        // what separates a late summon from a blocked one.
        if (!isFinishing) SummonSignals.markResumed(attempt)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || focusHandled || isFinishing) return
        focusHandled = true
        if (!SummonSignals.wanted(attempt)) {
            giveUp("abandoned")
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = hop()
                    override fun onDismissError() = giveUp("keyguard")
                    override fun onDismissCancelled() = giveUp("keyguard")
                },
            )
        } else {
            hop()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun hop() {
        if (isFinishing) return
        if (!SummonSignals.wanted(attempt)) {
            giveUp("abandoned")
            return
        }
        FlickLog.i("summon", "wake hop attempt=$attempt")
        // This lands in MainActivity's own task, which must keep its back stack.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ),
            )
        }
        finishQuietly()
    }

    private fun giveUp(reason: String) {
        if (isFinishing) return
        FlickLog.i("summon", "wake giveup attempt=$attempt reason=$reason")
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
