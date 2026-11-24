package io.github.axiom_fn.facepass.intervention.act

import android.content.Context
import android.content.res.Resources
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Binder
import android.os.CancellationSignal
import android.os.UserHandle
import io.github.axiom_fn.facepass.intervention.Act
import io.github.axiom_fn.facepass.intervention.Place
import io.github.axiom_fn.facepass.judgment.Judgment
import io.github.axiom_fn.facepass.judgment.Verdict
import io.github.axiom_fn.facepass.judgment.Whitelist
import io.github.axiom_fn.facepass.organon.Journal
import io.github.axiom_fn.facepass.organon.load
import io.github.axiom_fn.facepass.organon.methodsNamed
import io.github.axiom_fn.facepass.organon.onlyMethod
import io.github.axiom_fn.facepass.substance.Device
import io.github.axiom_fn.facepass.substance.FingerprintReceiver
import io.github.axiom_fn.facepass.substance.Request
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The legacy bridge: the act that carries a chosen app's FingerprintManager request into BiometricPrompt,
 * where face may answer. Whichever sensor answers, the app hears fingerprint.
 *
 * Android does the same for under-display sensors (FingerprintService.authenticateWithPrompt). The prompt is
 * built on the app's own binder thread, so AuthService takes the app as its caller. A request with a key,
 * one judged otherwise, or one from a user without face goes on to the fingerprint service untouched.
 */
object LegacyBridge : Act("legacy bridge", Place.SYSTEM_SERVER) {

    private const val SERVICE = "com.android.server.biometrics.sensors.fingerprint.FingerprintService"
    private const val STUB = "android.hardware.fingerprint.IFingerprintService\$Stub"
    private const val RECEIVER = "android.hardware.fingerprint.IFingerprintServiceReceiver"
    private const val AUTH_SERVICE = "com.android.server.biometrics.AuthService"

    /** The binder is an anonymous class in AOSP; it is looked for among $1 .. $LIMIT as well. */
    private const val ANONYMOUS_LIMIT = 16

    private class Layout(val operation: Int, val receiver: Int, val options: Int, val packageName: Int, val returnsId: Boolean)

    @Volatile private var layout: Layout? = null

    /** Request ids handed back for bridged requests: far above any the fingerprint service issues. */
    private val nextRequestId = AtomicLong(1L shl 48)

    /** Bridged requests not yet answered, by request id; the app cancels with the id. */
    private val open = ConcurrentHashMap<Long, CancellationSignal>()

    /** Set on the thread opening a bridged prompt, so the one cross-user permission check is let through. */
    private val bridging = ThreadLocal<Boolean>()

    override fun apply(loader: ClassLoader) {
        val receiver = loader.load(RECEIVER)
        val binder = binderOf(loader)
        val authenticate = binder.onlyMethod("authenticate") { receiver in it.parameterTypes }
        val types = authenticate.parameterTypes
        layout = Layout(
            operation = types.indexOf(Long::class.javaPrimitiveType).also { check(it >= 0) { "no operation id in $authenticate" } },
            receiver = types.indexOf(receiver),
            options = types.indexOfFirst { it.name.endsWith("AuthenticateOptions") },
            packageName = types.indexOf(String::class.java),
            returnsId = authenticate.returnType == Long::class.javaPrimitiveType,
        )
        check(layout!!.options >= 0 || layout!!.packageName >= 0) { "no package in $authenticate" }

        hook(authenticate, request)
        binder.methodsNamed("cancelAuthentication").forEach { hook(it, cancel) }

        // A bridged prompt runs the person's own face in the foreground user for a clone's request:
        // a cross-user authentication, which AuthService admits only to a holder of USE_BIOMETRIC_INTERNAL.
        // The one check is let through for the one synchronous call we make, on our thread, and no other.
        loader.load(AUTH_SERVICE).methodsNamed("checkInternalPermission").forEach { hook(it, internalAllowed) }
        Journal.info("$name: ${binder.name}.${describe(authenticate)}")
    }

    private val internalAllowed = XposedInterface.Hooker { chain ->
        if (bridging.get() == true) null else chain.proceed()
    }

    private fun binderOf(loader: ClassLoader): Class<*> {
        val stub = loader.load(STUB)
        val service = loader.load(SERVICE)
        val anonymous = (1..ANONYMOUS_LIMIT).mapNotNull { runCatching { loader.load("$SERVICE\$$it") }.getOrNull() }
        return (service.declaredClasses.asList() + anonymous).firstOrNull { stub.isAssignableFrom(it) }
            ?: error("no ${stub.simpleName} in $SERVICE")
    }

    private fun describe(method: Method) = "${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"

    private val request = XposedInterface.Hooker { chain ->
        val at = layout ?: return@Hooker chain.proceed()
        val packageName = runCatching { packageOf(chain.getArgs(), at) }.getOrNull()
        if (packageName !in Whitelist) return@Hooker chain.proceed()

        val uid = Binder.getCallingUid()
        val requestUser = Device.userOf(uid)
        val subject = "$packageName (user $requestUser)"
        val verdict = Judgment.of(Request(packageName, requestUser, chain.getArg(at.operation) as? Long ?: 0L))
        if (verdict != Verdict.Face) {
            Journal.info("$subject: old fingerprint request, $verdict; left to fingerprint")
            return@Hooker chain.proceed()
        }

        val faceUser = faceUserFor(requestUser)
        if (faceUser == null) {
            Journal.info("$subject: old fingerprint request; no face to use, left to fingerprint")
            return@Hooker chain.proceed()
        }

        val receiver = FingerprintReceiver(chain.getArg(at.receiver) ?: return@Hooker chain.proceed())
        runCatching { bridge(packageName!!, faceUser, requestUser, receiver) }
            .onSuccess { Journal.info("$subject: old fingerprint request taken into the prompt (face user $faceUser)") }
            .map { id -> if (at.returnsId) id else null }
            .getOrElse {
                Journal.error("$subject: prompt not opened; left to fingerprint", it)
                chain.proceed()
            }
    }

    /**
     * The user whose face answers for [requestUser]: that user, unless the face service is sure it has none.
     * A clone (user 999) enrols no face of its own, so it borrows the foreground user's, as ColorOS already
     * lends a clone the foreground user's fingerprint. The clone always has the foreground copy of the app.
     */
    private fun faceUserFor(requestUser: Int): Int? {
        val own = Device.hasFace(requestUser)
        if (own != false) return requestUser
        val foreground = Device.foregroundUser()
        val foregroundFace = foreground?.let(Device::hasFace)
        if (foreground != null && foreground != requestUser && foregroundFace == true) return foreground
        Journal.info("face lookup: user $requestUser has face=$own; foreground=$foreground has face=$foregroundFace")
        return null
    }

    /** cancelAuthentication(token, opPackageName, attributionTag, requestId): ours are closed here. */
    private val cancel = XposedInterface.Hooker { chain ->
        val id = chain.getArgs().lastOrNull { it is Long } as? Long
        val signal = id?.let(open::remove) ?: return@Hooker chain.proceed()
        signal.cancel()
        null
    }

    private fun packageOf(args: List<Any?>, at: Layout): String? =
        if (at.options >= 0) args[at.options]?.let { it.javaClass.getMethod("getOpPackageName").invoke(it) as? String }
        else args[at.packageName] as? String

    /**
     * Opens the prompt as the app in [faceUser], whose face answers; the app in [receiverUser] hears the result.
     * The two users differ only for a clone. Returns the request id the app will cancel with.
     */
    private fun bridge(packageName: String, faceUser: Int, receiverUser: Int, receiver: FingerprintReceiver): Long {
        val system = Device.systemContext() ?: error("no system context")
        val app = Context::class.java
            .getMethod("createPackageContextAsUser", String::class.java, Int::class.javaPrimitiveType, UserHandle::class.java)
            .invoke(system, packageName, 0, Device.userHandle(faceUser)) as Context
        val executor = system.mainExecutor
        val id = nextRequestId.incrementAndGet()
        val signal = CancellationSignal()

        val prompt = BiometricPrompt.Builder(app)
            .setTitle(systemString("biometric_dialog_default_title") ?: app.applicationInfo.loadLabel(app.packageManager))
            .setNegativeButton(app.getString(android.R.string.cancel), executor) { _, _ ->
                open.remove(id)
                receiver.error(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)
            }
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        val subject = "$packageName (user $receiverUser)"
        open[id] = signal
        bridging.set(true)
        try {
            prompt.authenticate(signal, executor, answer(id, receiverUser, receiver, subject))
        } catch (t: Throwable) {
            open.remove(id)
            throw t
        } finally {
            bridging.remove()
        }
        return id
    }

    /** The result the app hears. Whichever sensor answered, it hears fingerprint; a face miss it never hears. */
    private fun answer(id: Long, userId: Int, receiver: FingerprintReceiver, subject: String) = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            open.remove(id)
            receiver.succeeded(userId)
        }

        // A face miss in a face-and-fingerprint prompt is not a fingerprint failure: the prompt keeps both
        // running. Passing it on would have the app count misses and fall back to its own password.
        override fun onAuthenticationFailed() = Unit

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            open.remove(id)
            Journal.info("$subject: prompt ended, $errorCode")
            receiver.error(errorCode)
        }
    }

    private fun systemString(name: String): String? {
        val resources = Resources.getSystem()
        val id = resources.getIdentifier(name, "string", "android")
        return if (id != 0) resources.getString(id) else null
    }
}
