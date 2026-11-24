package io.github.axiom_fn.facepass.intervention.witness

import io.github.axiom_fn.facepass.intervention.Place
import io.github.axiom_fn.facepass.intervention.Witness
import io.github.axiom_fn.facepass.judgment.Judgment
import io.github.axiom_fn.facepass.judgment.Verdict
import io.github.axiom_fn.facepass.organon.Journal
import io.github.axiom_fn.facepass.organon.load
import io.github.axiom_fn.facepass.organon.methodsNamed
import io.github.axiom_fn.facepass.organon.onlyMethod
import io.github.axiom_fn.facepass.substance.Request
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/**
 * The intake: the witness that brings each request before judgment as it enters BiometricService,
 * and holds the verdict over PreAuthInfo when the request's eligibility is reckoned.
 */
object RequestIntake : Witness("request intake", Place.SYSTEM_SERVER) {

    private class Layout(val operation: Int, val promptInfo: Int, val packageName: Int, val user: Int, val createPromptInfo: Int)

    @Volatile private var layout: Layout? = null

    override fun apply(loader: ClassLoader) {
        val promptInfo = loader.load("android.hardware.biometrics.PromptInfo")
        val long = Long::class.javaPrimitiveType
        val authenticate = loader.load("com.android.server.biometrics.BiometricService\$BiometricServiceWrapper")
            .onlyMethod("authenticate") { promptInfo in it.parameterTypes && long in it.parameterTypes }
        val creates = loader.load("com.android.server.biometrics.PreAuthInfo").methodsNamed("create")
            .filter { Modifier.isStatic(it.modifiers) && promptInfo in it.parameterTypes }
            .also { check(it.isNotEmpty()) { "no PreAuthInfo.create(PromptInfo)" } }

        val types = authenticate.parameterTypes
        val createPromptInfo = creates.first().parameterTypes.indexOf(promptInfo)
        layout = Layout(
            operation = types.indexOf(long),
            promptInfo = types.indexOf(promptInfo),
            packageName = types.indexOf(String::class.java),
            user = types.indexOf(Int::class.javaPrimitiveType),
            createPromptInfo = createPromptInfo,
        )

        creates.filter { it.parameterTypes.indexOf(promptInfo) == createPromptInfo }.forEach { hook(it, eligibility) }
        hook(authenticate, admission)
    }

    private val admission = XposedInterface.Hooker { chain ->
        layout?.let { at ->
            runCatching {
                val request = Request(
                    packageName = chain.getArg(at.packageName) as? String,
                    userId = if (at.user >= 0) chain.getArg(at.user) as? Int else null,
                    operationId = chain.getArg(at.operation) as? Long ?: 0L,
                )
                val verdict = Judgment.of(request)
                chain.getArg(at.promptInfo)?.let { Judgment.hold(it, verdict) }
                if (verdict != Verdict.Stock) Journal.info("${request.packageName}: $verdict")
            }.onFailure { Journal.error("$name: request not judged", it) }
        }
        chain.proceed()
    }

    private val eligibility = XposedInterface.Hooker { chain ->
        val promptInfo = layout?.let { chain.getArgs().getOrNull(it.createPromptInfo) }
        Judgment.under(promptInfo) { chain.proceed() }
    }
}
