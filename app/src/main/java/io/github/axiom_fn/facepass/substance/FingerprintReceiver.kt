package io.github.axiom_fn.facepass.substance

import io.github.axiom_fn.facepass.organon.Journal

/**
 * The fingerprint receiver: where an app that listens through FingerprintManager hears its answer.
 * Seen from system_server it is a binder proxy (IFingerprintServiceReceiver); every call is one-way.
 */
@JvmInline
value class FingerprintReceiver(val raw: Any) {

    /** The app is told a fingerprint matched, as Android tells it for an under-display sensor's prompt. */
    fun succeeded(userId: Int) = call("onAuthenticationSucceeded", unnamedFingerprint(), userId, true)

    fun failed() = call("onAuthenticationFailed")

    fun error(code: Int) = when (code) {
        NEGATIVE_BUTTON -> call("onError", USER_CANCELED, 0)
        in KNOWN -> call("onError", code, 0)
        else -> call("onError", VENDOR, code)
    }

    private fun call(name: String, vararg args: Any) {
        runCatching {
            raw.javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(raw, *args)
        }.onFailure { Journal.error("fingerprint receiver: $name not delivered", it) }
    }

    private companion object {
        /** Codes BiometricPrompt and FingerprintManager share. */
        val KNOWN = 1..15
        const val VENDOR = 8
        const val USER_CANCELED = 10
        const val NEGATIVE_BUTTON = 13

        /** Fingerprint("", 0, 0): no particular finger, as Android sends from a prompt. */
        fun unnamedFingerprint(): Any = Class.forName("android.hardware.fingerprint.Fingerprint")
            .getConstructor(CharSequence::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .newInstance("", 0, 0L)
    }
}
