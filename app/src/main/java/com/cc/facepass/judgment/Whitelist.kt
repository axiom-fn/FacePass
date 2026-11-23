package com.cc.facepass.judgment

import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule

/**
 * The whitelist: packages the user allowed to use face. Written by the settings screen to LSPosed
 * remote preferences, read by the hooked processes; unreadable means empty.
 */
object Whitelist {

    const val PREFS_GROUP = "whitelist"
    const val KEY_PACKAGES = "face_packages"

    @Volatile private var prefs: SharedPreferences? = null

    fun bind(module: XposedModule) {
        prefs = runCatching { module.getRemotePreferences(PREFS_GROUP) }.getOrNull()
    }

    operator fun contains(packageName: String?): Boolean =
        packageName != null && prefs?.getStringSet(KEY_PACKAGES, null)?.contains(packageName) == true
}
