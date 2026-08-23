package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.awaitCancellation
import java.io.File
import java.util.*

/**
 * AutoProxy thay the ConfigurationModule: khong Room, khong profile, khong override.
 * Nap dung 1 file `<dir>/config.yaml` do app ghi truoc khi start service; loi nap -> LoadException
 * -> TunService dung voi reason (fail-loud, khong fallback).
 */
class FixedConfigModule(service: Service, private val dir: File) :
    Module<FixedConfigModule.LoadException>(service) {
    data class LoadException(val message: String)

    override suspend fun run() {
        try {
            if (!dir.resolve("config.yaml").isFile)
                throw IllegalStateException("no_config")

            Clash.load(dir).await()

            StatusProvider.currentProfile = PROFILE_NAME

            service.sendProfileLoaded(PROFILE_UUID)

            Log.d("AutoProxy fixed config loaded")
        } catch (e: Exception) {
            return enqueueEvent(LoadException(e.message ?: "Unknown"))
        }

        awaitCancellation()
    }

    companion object {
        const val PROFILE_NAME = "AutoProxy"
        val PROFILE_UUID: UUID = UUID(0L, 0L)
    }
}
