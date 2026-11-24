package io.github.axiom_fn.facepass.judgment

/** A verdict: what judgment concludes about one request. */
sealed interface Verdict {

    /** Face may be offered alongside fingerprint. */
    data object Face : Verdict

    /** Face is withheld. */
    data class FingerprintOnly(val reason: String) : Verdict

    /** Left to the system. */
    data object Stock : Verdict
}
