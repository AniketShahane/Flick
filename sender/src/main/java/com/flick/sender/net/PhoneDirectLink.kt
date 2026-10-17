package com.flick.sender.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.flick.sender.util.FlickLog
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Drops a Wi-Fi TDLS direct link this phone holds to the TV once the TV has stopped
 * hearing it.
 *
 * The phone's driver sets a direct link up on its own as a cast's traffic starts. When
 * the TV never receives the last of the three setup frames, this phone believes the link
 * is up and the TV does not: everything this phone sends — the film, every control frame,
 * every new connection's replies — goes into a link the TV is not listening on, while the
 * TV's own frames still arrive through the AP. The TV's Teardown is then dropped here
 * (`TDLS: No FTIE in TDLS Teardown`), so the phone keeps the dead link until its own idle
 * teardown about fifty seconds later: measured as a cast that never started.
 *
 * Constraint: only on evidence that the TV stopped hearing this phone — a ping that timed
 * out on a line still open, or a dial with no SYN-ACK or no route while nothing is being
 * served. A teardown of a link both sides hold can leave the
 * TV holding it alone, which is the blackout the receiver's own reset exists to clear.
 * On a phone with no link to that address it is a no-op.
 */
internal object PhoneDirectLink {
    /** Bytes written to the TV this recently mean a stream is still flowing; leave its link. */
    const val SERVING_PROOF_MS = 3_000L

    /** A dial the TV never answered: no SYN-ACK, or no route. A refusal or denial is the TV hearing. */
    fun provesUnheard(result: ControlClient.Result): Boolean = when (result) {
        is ControlClient.Result.TimedOut -> result.fault == DialFault.NO_ANSWER || result.fault == DialFault.NO_ROUTE
        is ControlClient.Result.Unreachable -> result.fault == DialFault.NO_ANSWER || result.fault == DialFault.NO_ROUTE
        else -> false
    }

    private val worker = ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue()) { Thread(it, "flick-tdls-reset").apply { isDaemon = true } }

    fun release(context: Context, tvHost: String, reason: String) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        worker.execute {
            runCatching {
                val address = InetAddress.getByName(tvHost)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    wifi.setTdlsEnabled(address, false, Runnable::run) { ok ->
                        FlickLog.i("lan", "tdls teardown reason=$reason accepted=$ok")
                    }
                } else {
                    wifi.setTdlsEnabled(address, false)
                    FlickLog.i("lan", "tdls teardown reason=$reason")
                }
            }.onFailure { FlickLog.w("lan", "tdls teardown failed reason=$reason", it) }
        }
    }
}
