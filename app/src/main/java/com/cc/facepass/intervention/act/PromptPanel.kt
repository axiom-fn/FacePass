package com.cc.facepass.intervention.act

import android.content.Context
import com.cc.facepass.intervention.Act
import com.cc.facepass.intervention.Place
import com.cc.facepass.judgment.Whitelist
import com.cc.facepass.organon.Journal
import com.cc.facepass.organon.field
import com.cc.facepass.organon.load
import com.cc.facepass.organon.onlyMethod
import com.cc.facepass.organon.read
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * The prompt panel: the act that gives a whitelisted face prompt the panel ColorOS draws for fingerprint.
 *
 * Medium size from the start, since ColorOS draws nothing in the small one; no confirmation unless
 * asked for, since a medium panel otherwise demands one; fingerprint started together with face.
 * The view model may outlive the prompt, so what was set is undone before the next prompt that is not ours.
 */
object PromptPanel : Act("prompt panel", Place.SYSTEM_UI) {

    private const val VIEW_MODEL = "com.android.systemui.biometrics.ui.viewmodel.PromptViewModel"
    private const val EITHER_TEXT = "keyguard_verify_use_fingerprint_or_face"

    private class Layout(
        val config: Int,
        val viewModel: Int,
        val faceProps: Int,
        val forceMedium: Field,
        val startMode: Field,
        val pending: Any,
        val flowGet: Method,
        val flowSet: Method,
        val startFingerprint: Method,
    )

    /** What was changed on the prompt now showing; there is only ever one. */
    private class Change(val autoConfirm: Boolean, val text: String?)

    @Volatile private var layout: Layout? = null
    @Volatile private var current: Change? = null

    /** View models changed by this module, with whether their prompt asked for confirmation. */
    private val changed: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())

    override fun apply(loader: ClassLoader) {
        val config = loader.load("com.android.systemui.biometrics.AuthContainerView\$Config")
        val viewModel = loader.load(VIEW_MODEL)
        val flow = loader.load("kotlinx.coroutines.flow.MutableStateFlow")
        val container: Constructor<*> = loader.load("com.android.systemui.biometrics.AuthContainerView")
            .declaredConstructors.single { config in it.parameterTypes && viewModel in it.parameterTypes }
            .apply { isAccessible = true }
        val types = container.parameterTypes
        config.field("mOpPackageName")

        layout = Layout(
            config = types.indexOf(config),
            viewModel = types.indexOf(viewModel),
            // (config, scope, fingerprintProps, faceProps, ...)
            faceProps = types.withIndex().filter { it.value == List::class.java }.map { it.index }.getOrElse(1) { -1 },
            forceMedium = viewModel.field("_forceMediumSize"),
            startMode = viewModel.field("_fingerprintStartMode"),
            pending = loader.load("com.android.systemui.biometrics.ui.viewmodel.FingerprintStartMode").field("Pending").get(null)!!,
            flowGet = flow.getMethod("getValue"),
            flowSet = flow.getMethod("setValue", Any::class.java),
            startFingerprint = viewModel.onlyMethod("ensureFingerprintHasStarted"),
        )

        val bool = Boolean::class.javaPrimitiveType
        hook(viewModel.onlyMethod("needsExplicitConfirmation"), explicitConfirmation)
        hook(loader.load("$VIEW_MODEL\$isConfirmationRequired\$1").getDeclaredMethod("invokeSuspend", Any::class.java)
            .apply { isAccessible = true }, confirmationRequired)
        hook(viewModel.onlyMethod("showAuthenticating") { it.parameterTypes.contentEquals(arrayOf(String::class.java, bool)) }, authenticating)
        hook(container, construction)
    }

    private val construction = XposedInterface.Hooker { chain ->
        val at = layout
        if (at != null) {
            runCatching {
                val config = chain.getArg(at.config)!!
                val viewModel = chain.getArg(at.viewModel)!!
                val faceProps = if (at.faceProps >= 0) chain.getArg(at.faceProps) as? List<*> else null
                adjust(at, config, viewModel, faceProps)
            }.onFailure {
                current = null
                Journal.error("$name: prompt left unchanged", it)
            }
        }
        chain.proceed()
    }

    private fun adjust(at: Layout, config: Any, viewModel: Any, faceProps: List<*>?) {
        if (config.read("mOpPackageName") as? String in Whitelist && includesFace(config, faceProps)) {
            val askedToConfirm = runCatching { config.read("mRequireConfirmation") as Boolean }.getOrDefault(true)
            at.flowSet.invoke(at.forceMedium.get(viewModel), true)
            // Pending -> Normal: fingerprint starts with the prompt instead of after face fails.
            if (!askedToConfirm) at.startFingerprint.invoke(viewModel, false)
            changed[viewModel] = askedToConfirm
            current = Change(autoConfirm = !askedToConfirm, text = eitherText(config))
            return
        }
        current = null
        val askedToConfirm = changed.remove(viewModel) ?: return
        at.flowSet.invoke(at.forceMedium.get(viewModel), false)
        val mode = at.startMode.get(viewModel)
        if (!askedToConfirm && at.flowGet.invoke(mode) !== at.pending) at.flowSet.invoke(mode, at.pending)
    }

    /** Whether the sensors admitted to this prompt (Config.mSensorIds) include a face sensor. */
    private fun includesFace(config: Any, faceProps: List<*>?): Boolean {
        val admitted = runCatching { config.read("mSensorIds") as IntArray }.getOrNull() ?: return true
        val faceIds = faceProps?.mapNotNull { runCatching { it?.read("sensorId") as Int }.getOrNull() } ?: return true
        return admitted.any { it in faceIds }
    }

    private fun eitherText(config: Any): String? = runCatching {
        val resources = (config.read("mContext") as Context).resources
        resources.getString(resources.getIdentifier(EITHER_TEXT, "string", Place.SYSTEM_UI.process))
    }.getOrNull()

    private val explicitConfirmation = XposedInterface.Hooker { chain ->
        val viewModel = chain.getThisObject()
        if (current?.autoConfirm == true && viewModel != null && changed[viewModel] == false) false else chain.proceed()
    }

    private val confirmationRequired = XposedInterface.Hooker { chain ->
        if (current?.autoConfirm == true) false else chain.proceed()
    }

    private val authenticating = XposedInterface.Hooker { chain ->
        val text = current?.takeIf { it.autoConfirm }?.text ?: return@Hooker chain.proceed()
        chain.proceed(chain.getArgs().toTypedArray().also { it[0] = text })
    }
}
