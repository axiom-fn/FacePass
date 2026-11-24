package io.github.axiom_fn.facepass.substance

/** The kinds of biometric sensor; a secondary substance. Codes are BiometricAuthenticator.TYPE_*. */
enum class Modality(val code: Int) {
    FINGERPRINT(2),
    FACE(8);

    companion object {
        fun of(code: Int?): Modality? = entries.firstOrNull { it.code == code }
    }
}
