package io.github.axiom_fn.facepass.judgment

import android.os.SystemClock
import io.github.axiom_fn.facepass.substance.Request
import java.util.concurrent.ConcurrentHashMap

/** Apps the user has not chosen are left alone. */
object WhitelistRule : Rule {
    override fun judge(request: Request): Verdict? =
        if (request.packageName in Whitelist) null else Verdict.Stock
}

/** A key-bound request needs a strong biometric, and ColorOS face is not one. */
object KeyBindingRule : Rule {
    override fun judge(request: Request): Verdict? =
        if (request.bindsKey) Verdict.FingerprintOnly("key-bound") else null
}

/**
 * An app asking again within seconds of a face success could not use it; a time-bound key, usually.
 * Each record serves one judgment. The power key or a screen-off clears them all.
 */
object RetryRule : Rule {

    private const val WINDOW_MS = 5_000L

    private val faceSuccessAt = ConcurrentHashMap<String, Long>()

    fun faceSucceeded(packageName: String) {
        faceSuccessAt[packageName] = SystemClock.elapsedRealtime()
    }

    fun forget() = faceSuccessAt.clear()

    override fun judge(request: Request): Verdict? {
        val at = request.packageName?.let(faceSuccessAt::remove) ?: return null
        val elapsed = SystemClock.elapsedRealtime() - at
        return if (elapsed < WINDOW_MS) Verdict.FingerprintOnly("asked again after ${elapsed} ms") else null
    }
}
