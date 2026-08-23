package com.github.kr328.clash

import android.app.Application
import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import java.io.File
import java.io.FileOutputStream

/**
 * AutoProxy: 1 tien trinh duy nhat (service manifest da bo `:background`) de AutoProxy state,
 * ControlReceiver, LocalHttpServer va TunService chia chung bo nho.
 */
@Suppress("unused")
class MainApplication : Application() {
    private val clashDir: File
        get() = filesDir.resolve("clash")

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        extractGeoFiles()

        AutoProxy.init(this)

        Log.d("AutoProxy process started")
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime

        for (name in listOf("geoip.metadb", "geosite.dat", "ASN.mmdb", "BundleMRS.7z")) {
            val file = File(clashDir, name)
            if (file.exists() && file.lastModified() < updateDate) {
                file.delete()
            }
            if (!file.exists()) {
                FileOutputStream(file).use {
                    assets.open(name).copyTo(it)
                }
            }
        }
    }
}
