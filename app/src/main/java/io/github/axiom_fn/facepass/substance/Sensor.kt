package io.github.axiom_fn.facepass.substance

import io.github.axiom_fn.facepass.organon.readInt

/** A sensor: one BiometricSensor of BiometricService, known by its id and its kind. */
@JvmInline
value class Sensor(val raw: Any) {

    val id: Int? get() = runCatching { raw.readInt("id") }.getOrNull()

    val modality: Modality? get() = Modality.of(runCatching { raw.readInt("modality") }.getOrNull())

    val isFace: Boolean get() = modality == Modality.FACE
}
