package com.github.kr328.clash

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.SystemClock
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.TunService
import com.github.kr328.clash.service.util.autoproxyDir
import com.github.kr328.clash.service.util.sendBroadcastSelf
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * So trang thai duy nhat cua AutoProxy (spec D' 3.5/3.6). Dong bo bang `synchronized(this)`.
 *
 * Vong doi: CONNECT -> connecting -> (PROFILE_LOADED) connected -> (CLASH_STOPPED)
 *   - do DISCONNECT: disconnected
 *   - khong do user, lan dau: thu noi lai 1 lan (connecting)
 *   - lan 2 hoac co reason: error
 */
object AutoProxy {
    const val TAG = "AutoProxy"
    const val DEFAULT_LOCAL_PORT = 17890

    enum class State { disconnected, connecting, connected, error }

    private lateinit var app: Application

    private var state: State = State.disconnected
    private var sinceAt: Long = 0L
    private var err: String? = null
    private var profile: ProxyProfile? = null
    private var userStop = false
    private var reconnectUsed = false
    private var server: LocalHttpServer? = null

    fun init(application: Application) {
        app = application

        application.registerReceiverCompat(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intents.ACTION_PROFILE_LOADED -> onLoaded()
                        Intents.ACTION_CLASH_STOPPED ->
                            onStopped(intent.getStringExtra(Intents.EXTRA_STOP_REASON))
                    }
                }
            },
            IntentFilter().apply {
                addAction(Intents.ACTION_PROFILE_LOADED)
                addAction(Intents.ACTION_CLASH_STOPPED)
            },
            Permissions.RECEIVE_SELF_BROADCASTS,
            null,
        )

        ensureServer(DEFAULT_LOCAL_PORT)
    }

    @Synchronized
    private fun ensureServer(port: Int) {
        val current = server
        if (current != null && current.port == port) return
        current?.stop()
        server = try {
            LocalHttpServer(port).also { it.start() }
        } catch (e: Exception) {
            Log.e("AutoProxy http server bind $port failed", e)
            err = "http_bind:${e.message}"
            null
        }
    }

    @Synchronized
    fun connect(ctx: Context, p: ProxyProfile) {
        profile = p
        userStop = false
        reconnectUsed = false
        err = null
        ensureServer(p.localPort)
        startTun(ctx, p)
    }

    /** Goi duoi khoa. */
    private fun startTun(ctx: Context, p: ProxyProfile) {
        if (VpnService.prepare(ctx) != null) {
            state = State.error
            err = "consent_denied"
            Log.w("AutoProxy VpnService.prepare != null (appops ACTIVATE_VPN chua allow)")
            return
        }

        try {
            ProxyConfig.write(ctx.autoproxyDir, p)
        } catch (e: Exception) {
            state = State.error
            err = "config_write:${e.message}"
            return
        }

        state = State.connecting
        sinceAt = SystemClock.elapsedRealtime()

        ctx.startForegroundServiceCompat(Intent(ctx, TunService::class.java))
    }

    @Synchronized
    fun disconnect(ctx: Context) {
        userStop = true
        profile = null
        ctx.sendBroadcastSelf(Intent(Intents.ACTION_CLASH_REQUEST_STOP))
        ProxyConfig.wipe(ctx.autoproxyDir)
        if (state != State.connected && state != State.connecting) {
            state = State.disconnected
            err = null
        }
    }

    @Synchronized
    private fun onLoaded() {
        state = State.connected
        sinceAt = SystemClock.elapsedRealtime()
        err = null
        reconnectUsed = false
        Log.i("AutoProxy connected")
    }

    @Synchronized
    private fun onStopped(reason: String?) {
        val p = profile
        when {
            userStop || p == null -> {
                state = State.disconnected
                err = null
                ProxyConfig.wipe(app.autoproxyDir)
            }
            reason == null && !reconnectUsed -> {
                reconnectUsed = true
                Log.w("AutoProxy tun lost, reconnect once")
                startTun(app, p)
            }
            else -> {
                state = State.error
                err = reason ?: "tun_lost"
                ProxyConfig.wipe(app.autoproxyDir)
                Log.w("AutoProxy error: $err")
            }
        }
    }

    private fun tunUp(): Boolean = try {
        NetworkInterface.getByName("tun0") != null
    } catch (e: Exception) {
        false
    }

    /** Bo JSON `/status` + STATUS logcat (spec 3.5) — KHONG BAO GIO co pass / host day du. */
    @Synchronized
    fun statusJson(): JSONObject = JSONObject().apply {
        put("state", state.name)
        put("tun", tunUp())
        put("since_ms", if (state == State.disconnected) 0 else SystemClock.elapsedRealtime() - sinceAt)
        val p = profile
        put("host", p?.maskedHost ?: JSONObject.NULL)
        put("port", p?.port ?: JSONObject.NULL)
        put("user", p?.maskedUser ?: JSONObject.NULL)
        // 0.1.2 — loai proxy may DANG THUC SU cau hinh. Server doi chieu voi loai
        // no do duoc; lech nghia la tunnel len ma khong goi nao qua duoc. Bao
        // gio cung doc tu profile trong RAM, dung tu extra vua nhan.
        put("scheme", p?.scheme ?: JSONObject.NULL)
        put("err", err ?: JSONObject.NULL)
    }

    fun version(): String = BuildConfig.VERSION_NAME
}
