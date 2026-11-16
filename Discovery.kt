package com.roohu.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Found(val name: String, val host: String, val port: Int)

/** Finds PCs on the same Wi-Fi (mDNS service _roohu._tcp). */
class Discovery(ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    val found = MutableStateFlow<List<Found>>(emptyList())

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(s: NsdServiceInfo) {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolved(r: NsdServiceInfo) {
                    val h = r.host?.hostAddress ?: return
                    found.update { l -> l.filter { it.name != r.serviceName } + Found(r.serviceName, h, r.port) }
                }
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
            })
        }
        override fun onServiceLost(s: NsdServiceInfo) { found.update { l -> l.filter { it.name != s.serviceName } } }
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    fun start() { runCatching { nsd.discoverServices("_roohu._tcp", NsdManager.PROTOCOL_DNS_SD, listener) } }
    fun stop() { runCatching { nsd.stopServiceDiscovery(listener) } }
}
