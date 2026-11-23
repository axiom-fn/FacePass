package com.cc.facepass.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import com.cc.facepass.R
import com.cc.facepass.intervention.act.FaceHint
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** The module app: makes the face hint's channel and listens, once, for the LSPosed service. */
class FacePassApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createFaceHintChannel()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                mainHandler.post {
                    FacePassApp.service = service
                    listeners.toList().forEach { it(service) }
                }
            }

            override fun onServiceDied(service: XposedService) {
                mainHandler.post {
                    if (FacePassApp.service === service) FacePassApp.service = null
                    listeners.toList().forEach { it(null) }
                }
            }
        })
    }

    /** Default importance, which Fluid Cloud needs; silent, since it shows at every scan. */
    private fun createFaceHintChannel() {
        val channel = NotificationChannel(
            FaceHint.CHANNEL_ID,
            getString(R.string.fluid_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val listeners = mutableListOf<(XposedService?) -> Unit>()

        /** Null while the module is not enabled. Main thread only. */
        var service: XposedService? = null
            private set

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners += listener
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners -= listener
        }
    }
}
