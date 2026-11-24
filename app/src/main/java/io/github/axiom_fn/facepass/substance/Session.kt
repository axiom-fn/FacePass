package io.github.axiom_fn.facepass.substance

import io.github.axiom_fn.facepass.organon.read
import io.github.axiom_fn.facepass.organon.readInt

/** A session: one verification in progress, an AuthSession of system_server. */
@JvmInline
value class Session(val raw: Any) {

    val packageName: String? get() = runCatching { raw.read("mOpPackageName") as? String }.getOrNull()

    val userId: Int get() = runCatching { raw.readInt("mUserId") }.getOrDefault(0)

    val state: Int? get() = runCatching { raw.readInt("mState") }.getOrNull()

    /** Face sensors PreAuthInfo admitted to this session. */
    val faceSensorIds: Set<Int>
        get() {
            val preAuth = raw.read("mPreAuthInfo") ?: return emptySet()
            val eligible = preAuth.read("eligibleSensors") as? List<*> ?: return emptySet()
            return eligible.filterNotNull().map(::Sensor).filter { it.isFace }.mapNotNull { it.id }.toSet()
        }

    companion object {
        const val CLASS = "com.android.server.biometrics.AuthSession"

        /** STATE_AUTH_STARTED: sensors running, prompt not yet shown. */
        const val STARTED = 2

        /** STATE_AUTH_STARTED_UI_SHOWING */
        const val SHOWING = 3
    }
}
