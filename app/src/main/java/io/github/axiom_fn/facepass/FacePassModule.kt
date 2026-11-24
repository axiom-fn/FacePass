package io.github.axiom_fn.facepass

import android.app.Application
import io.github.axiom_fn.facepass.intervention.Intervention
import io.github.axiom_fn.facepass.intervention.Place
import io.github.axiom_fn.facepass.intervention.act.CameraTakeover
import io.github.axiom_fn.facepass.intervention.act.EarlySuccess
import io.github.axiom_fn.facepass.intervention.act.FaceGate
import io.github.axiom_fn.facepass.intervention.act.FaceHint
import io.github.axiom_fn.facepass.intervention.act.ForegroundRecognition
import io.github.axiom_fn.facepass.intervention.act.LegacyBridge
import io.github.axiom_fn.facepass.intervention.act.PromptPanel
import io.github.axiom_fn.facepass.intervention.witness.OutcomeWitness
import io.github.axiom_fn.facepass.intervention.witness.PowerKeyWitness
import io.github.axiom_fn.facepass.intervention.witness.QueryWitness
import io.github.axiom_fn.facepass.intervention.witness.RequestIntake
import io.github.axiom_fn.facepass.judgment.Whitelist
import io.github.axiom_fn.facepass.organon.Journal
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** FacePass: ColorOS face for chosen apps through BiometricPrompt. It works in system processes only. */
class FacePassModule : XposedModule() {

    private val interventions: List<Intervention> = listOf(
        RequestIntake, OutcomeWitness, PowerKeyWitness, QueryWitness,
        ForegroundRecognition, FaceGate, CameraTakeover, FaceHint, EarlySuccess, LegacyBridge,
        PromptPanel,
    )

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        install(Place.SYSTEM_SERVER, param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName == Place.SYSTEM_UI.process && param.isFirstPackage) install(Place.SYSTEM_UI, param.classLoader)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam) = true

    /** All old hooks go, then everything is installed again from this instance. */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val loader = param.oldHookHandles.firstOrNull()?.executable?.declaringClass?.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: return
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        val place = if (Application.getProcessName() == Place.SYSTEM_UI.process) Place.SYSTEM_UI else Place.SYSTEM_SERVER
        install(place, loader)
    }

    private fun install(place: Place, loader: ClassLoader) {
        Journal.bind(this)
        Whitelist.bind(this)
        interventions.filter { it.place == place }.forEach { it.install(this, loader) }
    }
}
