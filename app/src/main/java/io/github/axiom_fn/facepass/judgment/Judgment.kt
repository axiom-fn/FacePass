package io.github.axiom_fn.facepass.judgment

import io.github.axiom_fn.facepass.substance.Request
import java.util.Collections
import java.util.WeakHashMap

/**
 * Judgment: the rules, in order, as premises; the first that concludes gives the verdict, else Face.
 * The verdict is reached once, at authenticate(), and travels with the PromptInfo to PreAuthInfo.
 */
object Judgment {

    private val rules = listOf(WhitelistRule, KeyBindingRule, RetryRule)

    private val held: MutableMap<Any, Verdict> = Collections.synchronizedMap(WeakHashMap())

    private val current = ThreadLocal<Verdict>()

    fun of(request: Request): Verdict = rules.firstNotNullOfOrNull { it.judge(request) } ?: Verdict.Face

    /** For a check with no authenticate() behind it: the whitelist alone decides. */
    fun provisional(packageName: String?): Verdict = WhitelistRule.judge(Request.of(packageName)) ?: Verdict.Face

    fun hold(promptInfo: Any, verdict: Verdict) {
        held[promptInfo] = verdict
    }

    /** Runs [block] under the verdict held for [promptInfo], which is spent by it. */
    fun <T> under(promptInfo: Any?, block: () -> T): T {
        val verdict = promptInfo?.let(held::remove) ?: return block()
        current.set(verdict)
        try {
            return block()
        } finally {
            current.remove()
        }
    }

    fun current(packageName: String?): Verdict = current.get() ?: provisional(packageName)
}
