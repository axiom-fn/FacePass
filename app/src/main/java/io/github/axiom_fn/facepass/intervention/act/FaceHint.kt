package io.github.axiom_fn.facepass.intervention.act

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Process
import io.github.axiom_fn.facepass.R
import io.github.axiom_fn.facepass.intervention.Act
import io.github.axiom_fn.facepass.intervention.Place
import io.github.axiom_fn.facepass.judgment.Whitelist
import io.github.axiom_fn.facepass.organon.Journal
import io.github.axiom_fn.facepass.organon.load
import io.github.axiom_fn.facepass.organon.methodsNamed
import io.github.axiom_fn.facepass.substance.Device
import io.github.axiom_fn.facepass.substance.Session
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * The face hint: the act that shows "looking for your face" in Fluid Cloud while face runs.
 * A Live Update notification, posted as the module app through NotificationManagerInternal.
 */
object FaceHint : Act("face hint", Place.SYSTEM_SERVER) {

    /** Created by the module app; silent, default importance. */
    const val CHANNEL_ID = "face_hint_silent"
    private const val NOTIFICATION_ID = 4096

    /** BIOMETRIC_ERROR_CANCELED: another sensor won. */
    private const val CANCELED = 5

    private const val NOTIFICATIONS = "com.android.server.notification.NotificationManagerInternal"

    /** Face sensors of each session the hint is shown for. */
    private val showing: MutableMap<Any, Set<Int>> = Collections.synchronizedMap(WeakHashMap())

    /** Sessions whose hint has gone; it does not come back. */
    private val done: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())

    /** Reached lazily: notification service and package manager start after the hooks. */
    private class Poster(val service: Any, val enqueue: Method, val cancel: Method, val context: Context, val uid: Int)

    @Volatile private var loader: ClassLoader? = null
    @Volatile private var poster: Poster? = null

    override fun apply(loader: ClassLoader) {
        check(loader.load(NOTIFICATIONS).methods.any { it.name == "enqueueNotification" && it.parameterCount == 8 }) {
            "no enqueueNotification(8)"
        }
        this.loader = loader
        val session = loader.load(Session.CLASS)
        fun on(name: String, hooker: XposedInterface.Hooker) = session.methodsNamed(name).forEach { hook(it, hooker) }
        on("startAllPreparedSensorsExceptFingerprint", started)
        on("startAllPreparedSensors", started)
        on("onAuthenticationSucceeded", ended)
        on("onAuthenticationTimedOut", faceStopped)
        on("onErrorReceived", faceStopped)
        on("onDialogDismissed", ended)
        on("onCancelAuthSession", ended)
        on("onClientDied", ended)
    }

    private val started = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        chain.getThisObject()?.let { runCatching { show(Session(it)) }.onFailure { e -> Journal.error("$name: not shown", e) } }
        result
    }

    /** onAuthenticationTimedOut / onErrorReceived (sensorId, cookie, error, vendorCode) */
    private val faceStopped = XposedInterface.Hooker { chain ->
        val raw = chain.getThisObject()
        val sensorId = chain.getArgs().getOrNull(0) as? Int
        val error = chain.getArgs().getOrNull(2)
        if (raw != null && error != CANCELED && showing[raw]?.contains(sensorId) == true) hide(raw)
        chain.proceed()
    }

    private val ended = XposedInterface.Hooker { chain ->
        chain.getThisObject()?.let(::hide)
        chain.proceed()
    }

    private fun show(session: Session) {
        val raw = session.raw
        if (raw in showing || raw in done || session.packageName !in Whitelist) return
        val faceIds = session.faceSensorIds.ifEmpty { return }
        val poster = poster() ?: return
        val notification = Notification.Builder(poster.context, CHANNEL_ID)
            .setSmallIcon(Icon.createWithResource(poster.context.packageName, R.drawable.ic_stat_face))
            .setContentTitle(poster.context.getString(R.string.fluid_title))
            .setShortCriticalText(poster.context.getString(R.string.fluid_chip))
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        poster.enqueue.invoke(poster.service, *poster.caller(), null, NOTIFICATION_ID, notification, session.userId)
        showing[raw] = faceIds
    }

    private fun hide(raw: Any) {
        done[raw] = true
        if (showing.remove(raw) == null) return
        val poster = poster() ?: return
        runCatching { poster.cancel.invoke(poster.service, *poster.caller(), null, NOTIFICATION_ID, Session(raw).userId) }
            .onFailure { Journal.error("$name: not removed", it) }
    }

    /** (pkg, opPkg, callingUid, callingPid): as the module app itself would post. */
    private fun Poster.caller(): Array<Any> = arrayOf(context.packageName, context.packageName, uid, Process.myPid())

    @Synchronized
    private fun poster(): Poster? {
        poster?.let { return it }
        val loader = loader ?: return null
        return runCatching {
            val internal = loader.load(NOTIFICATIONS)
            val context = Device.systemContext()!!.createPackageContext(moduleInfo.packageName, Context.CONTEXT_IGNORE_SECURITY)
            Poster(
                service = loader.load("com.android.server.LocalServices")
                    .getMethod("getService", Class::class.java).invoke(null, internal)!!,
                enqueue = internal.methods.first { it.name == "enqueueNotification" && it.parameterCount == 8 },
                cancel = internal.methods.first { it.name == "cancelNotification" && it.parameterCount == 7 },
                context = context,
                // LSPosed's ApplicationInfo for the module carries no uid; the installed package does.
                uid = context.applicationInfo.uid.also { check(it > 0) { "module uid unknown" } },
            )
        }.onFailure { Journal.error("$name: notification service not ready", it) }
            .getOrNull()
            ?.also { poster = it }
    }
}
