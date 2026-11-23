package com.cc.facepass.intervention.act

import com.cc.facepass.intervention.Act
import com.cc.facepass.intervention.Place
import com.cc.facepass.organon.Journal
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * The camera takeover: the act that frees the camera for an admitted face check on a locked phone.
 * The keyguard's own face unlock holds the camera while nobody looks; the app's request interrupts it.
 */
object CameraTakeover : Act("camera takeover", Place.SYSTEM_SERVER) {

    private val engaged = ThreadLocal<Boolean>()

    @Volatile private var placed = false

    /** The camera manager's class varies by ROM, so the hook waits for a live instance. */
    override fun apply(loader: ClassLoader) = Unit

    @Synchronized
    fun prepare(cameraManager: Any): Boolean {
        if (placed) return true
        val busy = generateSequence<Class<*>>(cameraManager.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "isAnyCameraUnavailable" && it.parameterCount == 0 }
            ?.apply { isAccessible = true }
        if (busy == null) {
            Journal.warn("$name: no isAnyCameraUnavailable on ${cameraManager.javaClass.name}")
            return false
        }
        placed = hookLate(busy, unavailable)
        return placed
    }

    fun engage() = engaged.set(true)

    fun release() = engaged.remove()

    private val unavailable = XposedInterface.Hooker { chain ->
        if (engaged.get() != true) return@Hooker chain.proceed()
        if (chain.proceed() == true) Journal.info("camera held by keyguard; taken over")
        false
    }
}
