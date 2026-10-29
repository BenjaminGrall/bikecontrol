package io.github.benjamingrall.click2whoosh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.benjamingrall.click2whoosh.core.Obc

/** Annonce `_openbikecontrol._tcp` via NsdManager pour que MyWhoosh trouve le pont. */
class MdnsAdvertiser(context: Context) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    @Volatile var registeredName: String? = null
        private set

    fun register(name: String, port: Int, id: String) {
        unregister()
        val nsd = nsd ?: return Hub.log("mDNS indisponible sur cet appareil")
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Obc.SERVICE_TYPE
            this.port = port
            setAttribute("version", "1")
            setAttribute("id", id)
            setAttribute("name", name)
            setAttribute("service-uuids", Obc.SERVICE_UUID)
            setAttribute("manufacturer", "click2whoosh")
            setAttribute("model", "Android")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                registeredName = s.serviceName
                Hub.log("mDNS : annoncé sous « ${s.serviceName} » (port $port)")
            }

            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                Hub.log("mDNS : échec de l'annonce (code $errorCode)")
            }

            override fun onServiceUnregistered(s: NsdServiceInfo) {
                Hub.debug("mDNS : annonce retirée")
            }

            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                Hub.debug("mDNS : échec du retrait (code $errorCode)")
            }
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        val l = registration ?: return
        registration = null
        registeredName = null
        try {
            nsd?.unregisterService(l)
        } catch (_: IllegalArgumentException) {
            // Jamais enregistré (échec de l'annonce).
        }
    }
}
