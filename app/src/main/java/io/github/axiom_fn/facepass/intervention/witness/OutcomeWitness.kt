package io.github.axiom_fn.facepass.intervention.witness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.axiom_fn.facepass.intervention.Place
import io.github.axiom_fn.facepass.intervention.Witness
import io.github.axiom_fn.facepass.judgment.RetryRule
import io.github.axiom_fn.facepass.organon.Journal
import io.github.axiom_fn.facepass.organon.load
import io.github.axiom_fn.facepass.organon.methodsNamed
import io.github.axiom_fn.facepass.substance.Device
import io.github.axiom_fn.facepass.substance.Session
import io.github.libxposed.api.XposedInterface

/** The outcome witness: records each face success for RetryRule, and forgets at screen-off. */
object OutcomeWitness : Witness("outcome witness", Place.SYSTEM_SERVER) {

    @Volatile private var listening = false

    override fun apply(loader: ClassLoader) {
        loader.load(Session.CLASS).methodsNamed("onAuthenticationSucceeded")
            .also { check(it.isNotEmpty()) { "no onAuthenticationSucceeded" } }
            .forEach { hook(it, success) }
    }

    /** onAuthenticationSucceeded(sensorId, strong, token) */
    private val success = XposedInterface.Hooker { chain ->
        val raw = chain.getThisObject()
        val sensorId = chain.getArgs().getOrNull(0) as? Int
        if (raw != null && sensorId != null) {
            val session = Session(raw)
            val packageName = session.packageName
            if (packageName != null && sensorId in session.faceSensorIds) {
                RetryRule.faceSucceeded(packageName)
                listenForScreenOff()
            }
        }
        chain.proceed()
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = RetryRule.forget()
    }

    /** At the first face success system services are surely up. */
    @Synchronized
    private fun listenForScreenOff() {
        if (listening) return
        val context = Device.systemContext() ?: return
        runCatching {
            context.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            listening = true
        }.onFailure { Journal.error("$name: screen-off not watched", it) }
    }
}
