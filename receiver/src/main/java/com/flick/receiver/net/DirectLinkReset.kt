package com.flick.receiver.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.flick.receiver.util.FlickLog
import java.net.InetAddress
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Tears down any Wi-Fi TDLS direct link this TV holds to a phone once that phone
 * goes quiet.
 *
 * The phone's driver sets a direct link up on its own under a cast's sustained
 * unicast and tears it down on its own about ten seconds after the stream idles.
 * When that teardown frame fails to send (logged on the phone as `TDLS: Failed to
 * send message (action_code=3)`), this TV keeps a link the phone no longer has:
 * unicast in both directions is lost while multicast still crosses via the AP, so
 * mDNS keeps showing the TV as ready and every connect fails with EHOSTUNREACH.
 * A teardown requested on this side is the measured cure; one requested on the
 * phone is not, because the phone has already forgotten the peer.
 *
 * Constraints: the phone's own teardown lands after the cast ends, so a teardown
 * at the end alone can precede the failure it is meant to clear — hence the later
 * repeats. And the address overload is the only usable one: this TV cannot learn
 * the phone's MAC, so the platform resolves it from the TV's ARP table, which
 * keeps the phone's entry while the link is stuck.
 */
object DirectLinkReset {
    private val REPEATS_MS = longArrayOf(0L, 20_000L, 60_000L)

    private val scheduler = ScheduledThreadPoolExecutor(1) { Thread(it, "flick-tdls-reset").apply { isDaemon = true } }.apply {
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    /** [stillQuiet] is re-read before each repeat: a new cast from any phone ends them. */
    fun release(context: Context, peerIp: String, trigger: String, stillQuiet: () -> Boolean) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        val address = runCatching { InetAddress.getByName(peerIp) }.getOrNull() ?: return
        for (delayMs in REPEATS_MS) {
            scheduler.schedule({
                if (delayMs == 0L || stillQuiet()) teardown(wifi, address, "$trigger+${delayMs / 1000}s")
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun teardown(wifi: WifiManager, address: InetAddress, label: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                wifi.setTdlsEnabled(address, false, Runnable::run) { ok ->
                    FlickLog.i("lan", "tdls teardown at=$label accepted=$ok")
                }
            } else {
                wifi.setTdlsEnabled(address, false)
                FlickLog.i("lan", "tdls teardown at=$label")
            }
        }.onFailure { FlickLog.w("lan", "tdls teardown failed at=$label", it) }
    }
}
