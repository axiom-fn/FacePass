package com.cc.facepass.intervention.act

import com.cc.facepass.intervention.Act
import com.cc.facepass.intervention.Place
import com.cc.facepass.judgment.Whitelist
import com.cc.facepass.organon.Journal
import com.cc.facepass.organon.load
import com.cc.facepass.organon.onlyMethod
import com.cc.facepass.substance.Device
import io.github.libxposed.api.XposedInterface

/**
 * The foreground recognition: the act that takes a whitelisted app on top as foreground in AuthService.
 * Reopened at once, the wallet still ranks as cached and is refused as "not foreground".
 */
object ForegroundRecognition : Act("foreground recognition", Place.SYSTEM_SERVER) {

    override fun apply(loader: ClassLoader) {
        val int = Int::class.javaPrimitiveType
        val isForeground = loader.load("com.android.server.biometrics.Utils")
            .onlyMethod("isForeground") { it.parameterTypes.contentEquals(arrayOf(int, int)) }
        hook(isForeground, foreground)
    }

    /** Utils.isForeground(callingUid, callingPid) */
    private val foreground = XposedInterface.Hooker { chain ->
        val ranked = chain.proceed()
        val uid = chain.getArg(0) as? Int
        if (ranked != false || uid == null) return@Hooker ranked
        val caller = Device.packagesOf(uid).firstOrNull { it in Whitelist }
        if (caller == null || caller != Device.topPackage()) return@Hooker ranked
        Journal.info("$caller on top, not yet ranked; taken as foreground")
        true
    }
}
