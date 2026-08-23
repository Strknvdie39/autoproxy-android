package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Cong dieu khien duy nhat tu ADB (spec D' 3.3):
 *   am broadcast -n vn.autoplatform.proxy/.ControlReceiver -a vn.autoplatform.proxy.action.CONNECT
 *      --es host H --ei port P --es user U --es pass S [--ei local_port 17890]
 *   ... DISCONNECT | STATUS
 * STATUS in 1 dong JSON (da che) ra logcat tag `AutoProxy`. Pass KHONG BAO GIO ra log.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra("host")?.trim().orEmpty()
                val port = intent.getIntExtra("port", 0)
                val user = intent.getStringExtra("user").orEmpty()
                val pass = intent.getStringExtra("pass").orEmpty()
                val localPort = intent.getIntExtra("local_port", AutoProxy.DEFAULT_LOCAL_PORT)

                if (host.isEmpty() || port !in 1..65535 || localPort !in 1024..65535) {
                    Log.w(AutoProxy.TAG, "CONNECT rejected: bad_args")
                    return
                }

                AutoProxy.connect(context, ProxyProfile(host, port, user, pass, localPort))
            }
            ACTION_DISCONNECT -> AutoProxy.disconnect(context)
            ACTION_STATUS -> Unit
            else -> return
        }

        Log.i(AutoProxy.TAG, AutoProxy.statusJson().toString())
    }

    companion object {
        const val ACTION_CONNECT = "vn.autoplatform.proxy.action.CONNECT"
        const val ACTION_DISCONNECT = "vn.autoplatform.proxy.action.DISCONNECT"
        const val ACTION_STATUS = "vn.autoplatform.proxy.action.STATUS"
    }
}
