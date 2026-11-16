package com.roohu.connect

import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val text: String) : LinkState
    data class Connected(val pcName: String) : LinkState
    data class Failed(val message: String) : LinkState
}

/**
 * Secure WebSocket link to one PC.
 * TLS cert is self-signed. We pin its SHA-256 fingerprint (from the QR code,
 * or from the first successful pairing). The pairing PIN is never sent: we send
 * an HMAC(pin, nonce + certFingerprintWeSaw), so a man-in-the-middle with a
 * different certificate cannot pass.
 */
class Link(private val store: Store) {
    val state = MutableStateFlow<LinkState>(LinkState.Idle)
    val pcs = MutableStateFlow(store.load())

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var generation = 0
    private var retry = 0

    private class Pin(val expected: String?) : X509TrustManager {
        @Volatile var seen: String = ""
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>, a: String?) {
            seen = sha256Hex(c[0].encoded)
            if (expected != null && expected != seen) throw CertificateException("PC certificate changed")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun connectSaved(pc: Pc) = open(pc.host, pc.port, pc.fp, null, pc)
    fun pair(host: String, port: Int, fp: String?, pin: String) = open(host, port, fp, pin, null)

    fun disconnect() {
        generation++
        socket?.cancel(); client?.dispatcher?.executorService?.shutdown()
        state.value = LinkState.Idle
    }

    fun forget(pc: Pc) {
        if ((state.value as? LinkState.Connected)?.pcName == pc.name) disconnect()
        val left = store.load().filter { it.id != pc.id }
        store.save(left); pcs.value = left
    }

    private fun open(host: String, port: Int, fp: String?, pin: String?, initialSaved: Pc?) {
        var saved: Pc? = initialSaved
        val gen = ++generation
        socket?.cancel(); client?.dispatcher?.executorService?.shutdown()
        state.value = LinkState.Connecting(if (pin != null) "Pairing…" else "Connecting to ${saved?.name}…")

        val tm = Pin(fp)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        val c = OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }   // identity is checked by fingerprint, not hostname
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
        client = c

        fun fail(msg: String, retryable: Boolean) {
            if (gen != generation) return
            val sv = saved
            if (retryable && sv != null) {
                state.value = LinkState.Connecting("Reconnecting to ${sv.name}…")
                val wait = minOf(30_000L, 1000L shl minOf(retry, 5)); retry++
                main.postDelayed({ if (gen == generation) open(host, port, sv.fp, null, sv) }, wait)
            } else state.value = LinkState.Failed(msg)
        }

        socket = c.newWebSocket(Request.Builder().url("wss://$host:$port/ws").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                try {
                    val m = JSONObject(text)
                    when (m.getString("t")) {
                        "hello" -> {
                            val id = store.deviceId
                            if (pin != null) {
                                val proof = hmacHex(pin, m.getString("nonce") + tm.seen)
                                webSocket.send(JSONObject().put("t", "pair").put("id", id)
                                    .put("name", Build.MODEL).put("proof", proof).toString())
                            } else {
                                webSocket.send(JSONObject().put("t", "auth").put("id", id)
                                    .put("token", saved?.token ?: return).toString())
                            }
                        }
                        "paired" -> {
                            val pc = Pc(m.getString("pcId"), m.getString("pcName"), host, port, tm.seen, m.getString("token"))
                            val list = store.load().filter { it.id != pc.id } + pc
                            store.save(list); pcs.value = list
                            saved = pc
                            retry = 0; state.value = LinkState.Connected(pc.name)
                        }
                        "ok" -> { retry = 0; state.value = LinkState.Connected(saved?.name ?: "PC") }
                        "error" -> { generation++; webSocket.cancel(); state.value = LinkState.Failed(m.optString("msg", "Error")) }
                    }
                } catch (e: Exception) {
                    state.value = LinkState.Failed("Bad message from PC")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = if (t is javax.net.ssl.SSLException || t.cause is CertificateException)
                    "This PC's security certificate does not match. Forget the PC and pair again."
                else "Cannot reach the PC. Check Wi-Fi and that Roohu Connect is open on the PC."
                fail(msg, t.cause !is CertificateException)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (state.value is LinkState.Connected) fail("Connection closed", true)
            }
        })
    }
}
