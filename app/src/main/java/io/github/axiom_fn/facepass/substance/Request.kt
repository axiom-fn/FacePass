package io.github.axiom_fn.facepass.substance

/** A request: one app's demand to be verified. One that names a Keystore operation binds a key. */
data class Request(
    val packageName: String?,
    val userId: Int?,
    val operationId: Long,
) {
    val bindsKey: Boolean get() = operationId != 0L

    companion object {
        /** Known by its package alone, as in canAuthenticate. */
        fun of(packageName: String?) = Request(packageName, userId = null, operationId = 0L)
    }
}
