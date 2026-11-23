package com.cc.facepass.intervention.act

import com.cc.facepass.intervention.Act
import com.cc.facepass.intervention.Place
import com.cc.facepass.judgment.Judgment
import com.cc.facepass.judgment.Verdict
import com.cc.facepass.organon.Journal
import com.cc.facepass.organon.load
import com.cc.facepass.organon.onlyMethod
import com.cc.facepass.substance.Device
import com.cc.facepass.substance.Sensor
import io.github.libxposed.api.XposedInterface

/**
 * The face gate: the act that admits face to a request judged Face.
 * ColorOS ranks its face 4095, below any class an app may ask for; only the strength comparison is passed.
 */
object FaceGate : Act("face gate", Place.SYSTEM_SERVER) {

    /** PreAuthInfo.AUTHENTICATOR_OK */
    private const val OK = 1

    private class Layout(val sensor: Int, val packageName: Int, val camera: Int)

    @Volatile private var layout: Layout? = null

    private val open = ThreadLocal<Boolean>()

    override fun apply(loader: ClassLoader) {
        val sensorType = loader.load("com.android.server.biometrics.BiometricSensor")
        val statusOf = loader.load("com.android.server.biometrics.PreAuthInfo")
            .onlyMethod("getStatusForBiometricAuthenticator") { sensorType in it.parameterTypes }
        val types = statusOf.parameterTypes
        layout = Layout(
            sensor = types.indexOf(sensorType),
            packageName = types.indexOf(String::class.java).also { check(it >= 0) { "no package in $statusOf" } },
            camera = types.indexOfFirst { it.name.endsWith("CameraManager") },
        )

        val int = Int::class.javaPrimitiveType
        val atLeast = loader.load("com.android.server.biometrics.Utils")
            .onlyMethod("isAtLeastStrength") { it.parameterTypes.contentEquals(arrayOf(int, int)) }

        hook(atLeast, strength)
        hook(statusOf, status)
    }

    private val status = XposedInterface.Hooker { chain ->
        val at = layout ?: return@Hooker chain.proceed()
        val sensor = chain.getArg(at.sensor)
        val packageName = chain.getArg(at.packageName) as? String
        if (sensor == null || !Sensor(sensor).isFace || Judgment.current(packageName) != Verdict.Face) {
            return@Hooker chain.proceed()
        }

        val camera = if (at.camera >= 0) chain.getArg(at.camera) else null
        val takeover = camera != null && Device.isKeyguardLocked() && CameraTakeover.prepare(camera)
        open.set(true)
        if (takeover) CameraTakeover.engage()
        try {
            chain.proceed().also { if (it != OK) Journal.info("face refused for $packageName: status $it") }
        } finally {
            open.remove()
            if (takeover) CameraTakeover.release()
        }
    }

    private val strength = XposedInterface.Hooker { chain ->
        if (open.get() == true) true else chain.proceed()
    }
}
