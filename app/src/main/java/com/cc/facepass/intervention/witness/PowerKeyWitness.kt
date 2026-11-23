package com.cc.facepass.intervention.witness

import android.view.KeyEvent
import com.cc.facepass.intervention.Place
import com.cc.facepass.intervention.Witness
import com.cc.facepass.judgment.RetryRule
import com.cc.facepass.organon.load
import com.cc.facepass.organon.onlyMethod
import io.github.libxposed.api.XposedInterface

/**
 * The power-key witness: a press of the power key clears RetryRule.
 * A request after it is the user's own, as when the wallet is opened again; an app retrying touches no key.
 */
object PowerKeyWitness : Witness("power-key witness", Place.SYSTEM_SERVER) {

    override fun apply(loader: ClassLoader) {
        hook(
            loader.load("com.android.server.policy.PhoneWindowManager")
                .onlyMethod("interceptKeyBeforeQueueing") { it.parameterTypes.firstOrNull() == KeyEvent::class.java },
            keyDown,
        )
    }

    private val keyDown = XposedInterface.Hooker { chain ->
        val event = chain.getArgs().getOrNull(0) as? KeyEvent
        if (event?.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) RetryRule.forget()
        chain.proceed()
    }
}
