package com.cc.facepass.substance

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Binder

/** The device, seen from system_server. Nothing here answers before system services are up. */
object Device {

    fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as Context
    }.getOrNull()

    fun isKeyguardLocked(): Boolean =
        systemContext()?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Asked as the system; inside an app's binder call the app would see only its own tasks. */
    @Suppress("DEPRECATION")
    fun topPackage(): String? {
        val identity = Binder.clearCallingIdentity()
        try {
            return systemContext()?.getSystemService(ActivityManager::class.java)
                ?.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
        } catch (_: Exception) {
            return null
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    fun packagesOf(uid: Int): List<String> =
        systemContext()?.packageManager?.getPackagesForUid(uid)?.toList().orEmpty()
}
