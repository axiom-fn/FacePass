package io.github.axiom_fn.facepass.intervention.act

import android.os.Handler
import android.os.Looper
import io.github.axiom_fn.facepass.intervention.Act
import io.github.axiom_fn.facepass.intervention.Place
import io.github.axiom_fn.facepass.organon.Journal
import io.github.axiom_fn.facepass.organon.load
import io.github.axiom_fn.facepass.organon.methodsNamed
import io.github.axiom_fn.facepass.substance.Session
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * The early success: the act that holds a face success arriving before the prompt is shown.
 * Delivered then, SystemUI reports the dismissal as a user cancel; held, it is delivered once shown.
 */
object EarlySuccess : Act("early success", Place.SYSTEM_SERVER) {

    /** If the prompt never reports itself shown. */
    private const val FALLBACK_MS = 1_000L

    private val held: MutableMap<Any, Array<Any?>> = Collections.synchronizedMap(WeakHashMap())

    private val replaying = ThreadLocal<Boolean>()

    @Volatile private var succeeded: Method? = null

    override fun apply(loader: ClassLoader) {
        val session = loader.load(Session.CLASS)
        val animatedIn = session.methodsNamed("onDialogAnimatedIn").also { check(it.isNotEmpty()) { "no onDialogAnimatedIn" } }
        val success = session.methodsNamed("onAuthenticationSucceeded").single()
        succeeded = success

        animatedIn.forEach { hook(it, shown) }
        listOf("onDialogDismissed", "onCancelAuthSession", "onClientDied").flatMap(session::methodsNamed).forEach { hook(it, ended) }
        hook(success, early)
    }

    private val early = XposedInterface.Hooker { chain ->
        val raw = chain.getThisObject()
        if (raw == null || replaying.get() == true || Session(raw).state != Session.STARTED) {
            return@Hooker chain.proceed()
        }
        held[raw] = chain.getArgs().toTypedArray()
        Looper.myLooper()?.let { looper ->
            Handler(looper).postDelayed({ if (Session(raw).state == Session.STARTED) deliver(raw) }, FALLBACK_MS)
        }
        null
    }

    private val shown = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        val raw = chain.getThisObject() ?: return@Hooker result
        if (Session(raw).state == Session.SHOWING) deliver(raw) else held.remove(raw)
        result
    }

    private val ended = XposedInterface.Hooker { chain ->
        chain.getThisObject()?.let(held::remove)
        chain.proceed()
    }

    private fun deliver(raw: Any) {
        val args = held.remove(raw) ?: return
        replaying.set(true)
        try {
            succeeded?.invoke(raw, *args)
        } catch (t: Throwable) {
            Journal.error("$name: held success lost", t)
        } finally {
            replaying.remove()
        }
    }
}
