package com.flick.receiver.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises the TV's control server over NSD/mDNS (control-channel.md §2) so the
 * phone can discover it: service type `_flick._tcp.`, the real bound control
 * port, and TXT attributes `model`, `v`, `state`. Registration needs no runtime
 * permission. All calls are guarded — discovery is best-effort (some routers
 * block mDNS; the phone falls back to manual entry).
 */
class NsdAdvertiser(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(
        serviceName: String,
        port: Int,
        model: String,
        state: String,
    ) {
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("model", model)
            setAttribute("v", PROTOCOL_VERSION)
            setAttribute("state", state)
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = reg
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_flick._tcp."
        const val PROTOCOL_VERSION = "1"
        const val STATE_READY = "ready"
        const val STATE_SLEEPING = "sleeping"
    }
}
