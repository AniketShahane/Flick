package com.flick.receiver.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flick.receiver.util.FlickLog

/**
 * Advertises the TV's control server over NSD/mDNS (control-channel.md §2) so the
 * phone can discover it: service type `_flick._tcp.`, the real bound control
 * port, and TXT attributes `model`, `v`, `state`. Registration needs no runtime
 * permission. All calls are guarded — discovery is best-effort (some routers
 * block mDNS; the phone falls back to manual entry).
 *
 * A visibility change flips TXT `state` between `ready` and `sleeping` by
 * re-registering under the SAME service name and the SAME port. NsdManager has no
 * update primitive, so a re-register is the only way to change a TXT record; the
 * phone must therefore treat a same-name re-registration as an UPDATE, never as a
 * loss. The socket stays bound throughout — `sleeping` means "not accepting new
 * casts", not "gone".
 */
class NsdAdvertiser(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private data class Request(val serviceName: String, val port: Int, val model: String, val state: String, val tvId: String)
    private var request: Request? = null
    private var attempt = 0
    private var generation = 0L
    private var retry: Runnable? = null

    /**
     * Whether this TV is still announcing itself, or has spent the retry ladder and
     * given up for good.
     *
     * Compose state because the pair screen has to stop claiming otherwise: it kept a
     * pulsing live dot and "Listening" over a service registration that had failed three
     * times and would never be attempted again. It degrades ONE route — the QR and the
     * manual-entry card on the same screen still work — so the screen may say so
     * without saying the TV is offline.
     */
    var advertising by mutableStateOf(true)
        private set

    fun register(
        serviceName: String,
        port: Int,
        model: String,
        state: String,
        tvId: String,
    ) {
        unregister()
        FlickLog.i("nsd", "register name=$serviceName port=$port state=$state v=$PROTOCOL_VERSION")
        advertising = true
        request = Request(serviceName, port, model, state, tvId)
        startRegistration(request!!, generation)
    }

    private fun startRegistration(request: Request, expectedGeneration: Long) {
        val info = NsdServiceInfo().apply {
            this.serviceName = request.serviceName
            serviceType = SERVICE_TYPE
            setPort(request.port)
            setAttribute("model", request.model)
            setAttribute("v", PROTOCOL_VERSION)
            setAttribute("state", request.state)
            setAttribute("id", request.tvId)
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                FlickLog.i("nsd", "registered name=${request.serviceName} port=${request.port} state=${request.state}")
                if (generation == expectedGeneration) attempt = 0
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // The errorCode used to be discarded, so the 3-retry ladder gave up
                // forever with no signal at all.
                FlickLog.w("nsd", "register failed code=$errorCode attempt=$attempt port=${request.port}")
                retry(expectedGeneration)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                FlickLog.w("nsd", "unregister failed code=$errorCode attempt=$attempt")
                retry(expectedGeneration)
            }
        }
        listener = reg
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }
            .onFailure {
                FlickLog.w("nsd", "registerService threw port=${request.port}", it)
                retry(expectedGeneration)
            }
    }

    private fun retry(expectedGeneration: Long) {
        if (generation != expectedGeneration || request == null) return
        if (attempt >= MAX_RETRIES) {
            // The ladder is spent and nothing re-arms it short of a rebind, so this is
            // permanent for this binding — and the screen may not go on claiming
            // discovery works.
            advertising = false
            FlickLog.w("nsd", "gave up attempts=$attempt port=${request?.port ?: -1}")
            return
        }
        val delay = RETRY_DELAYS_MS[attempt++]
        retry?.let(handler::removeCallbacks)
        retry = Runnable { if (generation == expectedGeneration) request?.let { startRegistration(it, expectedGeneration) } }
        handler.postDelayed(retry!!, delay)
    }

    fun unregister() {
        if (listener != null) FlickLog.i("nsd", "unregister port=${request?.port ?: -1}")
        generation++
        retry?.let(handler::removeCallbacks)
        retry = null
        request = null
        attempt = 0
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_flick._tcp."
        const val PROTOCOL_VERSION = "2"
        const val STATE_READY = "ready"
        const val STATE_SLEEPING = "sleeping"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(250L, 1_000L, 4_000L)
    }
}
