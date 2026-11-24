package io.github.axiom_fn.facepass.substance

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

    /** UserHandle.PER_USER_RANGE: a uid is user * range + app id. */
    private const val PER_USER_RANGE = 100_000

    fun userOf(uid: Int): Int = uid / PER_USER_RANGE

    /** The user in the foreground; an app clone (user 999) runs beside it, never as it. */
    fun foregroundUser(): Int? {
        // Asked as the system: reading the current user needs a cross-user permission the caller lacks.
        val identity = Binder.clearCallingIdentity()
        try {
            return ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        } catch (_: Exception) {
            return null
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** UserHandle.of(userId); the constructor is hidden, so it is reached by name. */
    fun userHandle(userId: Int): android.os.UserHandle =
        android.os.UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
            .invoke(null, userId) as android.os.UserHandle

    /** Whether [userId] has face enrolled; null when the face service cannot say. */
    fun hasFace(userId: Int): Boolean? {
        val identity = Binder.clearCallingIdentity()
        try {
            val face = systemContext()?.getSystemService("face") ?: return null
            return face.javaClass.getMethod("hasEnrolledTemplates", Int::class.javaPrimitiveType).invoke(face, userId) as Boolean
        } catch (_: Exception) {
            return null
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
