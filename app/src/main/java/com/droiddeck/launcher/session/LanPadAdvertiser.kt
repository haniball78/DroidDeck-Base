package com.droiddeck.launcher.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.privileged.TvDiagnosticsLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller-side LAN advertisement for a second player: registers the pad
 * service on the home Wi-Fi via plain NSD, so a second phone can find the
 * controller without a Wi-Fi Direct group (app-side group creation never
 * works on this phone). The P2P group stays as the fallback transport; this
 * advertiser only adds the LAN one. Registration failure is only logged —
 * it must never kill the session or block the pad server.
 */
class LanPadAdvertiser(private val padServer: P2pPadServer? = null) : SessionPart() {
    override fun start() {
        begin()
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val listener = registrationListener ?: return
        mainHandler.post { runCatching { manager?.unregisterService(listener) } }
    }

    /** Registers the pad service when the phone is on Wi-Fi; null when started or skipped, else the reason. */
    fun begin(): String? {
        synchronized(beginLock) {
            if (begun) return startReason
            begun = true
        }
        val ctx = app() ?: return "no-context".also { startReason = it }
        if (!isOnWifi(ctx)) {
            TvDiagnosticsLog.log(ctx, "tv", "lan-advertise", fields = mapOf("step" to "skipped", "result" to "not-on-wifi"))
            return null
        }
        val port = padServer?.port?.takeIf { it > 0 } ?: run {
            TvDiagnosticsLog.log(ctx, "tv", "lan-advertise", fields = mapOf("step" to "skipped", "result" to "pad-server-not-bound"))
            return null
        }
        val mgr = runCatching { ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull() ?: run {
            TvDiagnosticsLog.log(ctx, "tv", "lan-advertise", fields = mapOf("step" to "skipped", "result" to "nsd-unavailable"))
            return null
        }
        manager = mgr
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_INSTANCE
            serviceType = NSD_SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                TvDiagnosticsLog.log(ctx, "tv", "lan-advertise", fields = mapOf("step" to "registered", "port" to port.toString()))
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                TvDiagnosticsLog.log(ctx, "tv", "lan-advertise", fields = mapOf("step" to "failed", "result" to "register-$errorCode"))
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        // NsdManager calls go through the main looper: the session starts its
        // components on a background thread with no looper of its own.
        mainHandler.post {
            if (stopped.get()) return@post
            runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure {
                    TvDiagnosticsLog.log(ctx, "tv", "lan-advertise", fields = mapOf("step" to "failed", "result" to "register-threw"))
                }
        }
        return null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val beginLock = Any()

    @Volatile
    private var begun = false

    @Volatile
    private var startReason: String? = null

    private val stopped = AtomicBoolean(false)
    private var manager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private companion object {
        const val SERVICE_INSTANCE = "DroidDeck-TV"
    }
}

/** True when the phone's active network is Wi-Fi. */
internal fun isOnWifi(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}.getOrDefault(false)

/**
 * The phone's own address on the home Wi-Fi, for the second player to type
 * in by hand. Null when not on Wi-Fi or when no home-LAN address exists.
 */
fun wifiIpAddress(context: Context): String? {
    if (!isOnWifi(context)) return null
    return runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { ni -> ni.inetAddresses?.toList().orEmpty().mapNotNull { (it as? Inet4Address)?.hostAddress } }
            .let { pickLanIp(it) }
    }.getOrNull()
}

/**
 * Picks the home-LAN address out of the phone's IPv4 addresses: a 192.168.x.x
 * address first, except the Wi-Fi Direct group subnet (192.168.49.x) and the
 * USB-tether subnet (192.168.42.x); other private ranges (10.x, 172.16-31.x)
 * are the fallback. Null when nothing qualifies.
 */
internal fun pickLanIp(addresses: List<String>): String? {
    val valid = addresses.mapNotNull { ip ->
        val octets = ip.split('.')
        if (octets.size != 4 || octets.any { it.toIntOrNull() == null }) null else octets
    }
    valid.firstOrNull { it[0] == "192" && it[1] == "168" && it[2] != "49" && it[2] != "42" }
        ?.let { return it.joinToString(".") }
    valid.firstOrNull { it[0] == "10" || (it[0] == "172" && (it[1].toIntOrNull() ?: 0) in 16..31) }
        ?.let { return it.joinToString(".") }
    return null
}
