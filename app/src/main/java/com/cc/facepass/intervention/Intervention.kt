package com.cc.facepass.intervention

import com.cc.facepass.organon.Journal
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** Where an intervention takes place. */
enum class Place(val process: String) {
    SYSTEM_SERVER("system_server"),
    SYSTEM_UI("com.android.systemui"),
}

/**
 * An intervention: what the module does inside a system process. It is either an act or a witness.
 * Installed whole or not at all; on any failure its hooks are taken back.
 */
sealed class Intervention(val name: String, val place: Place) {

    private lateinit var module: XposedModule
    private val handles = mutableListOf<XposedInterface.HookHandle>()

    fun install(module: XposedModule, loader: ClassLoader) {
        this.module = module
        handles.clear()
        try {
            apply(loader)
            Journal.info("$name: installed")
        } catch (t: Throwable) {
            handles.forEach { runCatching { it.unhook() } }
            handles.clear()
            Journal.error("$name: not installed", t)
        }
    }

    protected abstract fun apply(loader: ClassLoader)

    protected fun hook(target: Executable, hooker: XposedInterface.Hooker) {
        handles += module.hook(target).setId(name).intercept(hooker)
    }

    /** For a target known only from a runtime object. */
    @Synchronized
    protected fun hookLate(target: Executable, hooker: XposedInterface.Hooker): Boolean =
        runCatching { hook(target, hooker) }
            .onFailure { Journal.error("$name: late hook failed", it) }
            .isSuccess

    protected val moduleInfo get() = module.moduleApplicationInfo
}

/** An act: an intervention that changes what the system does. */
abstract class Act(name: String, place: Place) : Intervention(name, place)

/** A witness: an intervention that only records. Every call it sees returns what it would have. */
abstract class Witness(name: String, place: Place) : Intervention(name, place)
