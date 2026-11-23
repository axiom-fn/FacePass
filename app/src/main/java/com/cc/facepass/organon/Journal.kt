package com.cc.facepass.organon

import android.util.Log
import io.github.libxposed.api.XposedModule

/** The journal: where the module writes, under the tag FacePass. */
object Journal {

    private const val TAG = "FacePass"

    @Volatile private var module: XposedModule? = null

    fun bind(module: XposedModule) {
        this.module = module
    }

    fun info(message: String) {
        module?.log(Log.INFO, TAG, message)
    }

    fun warn(message: String, cause: Throwable? = null) {
        module?.log(Log.WARN, TAG, message, cause)
    }

    fun error(message: String, cause: Throwable) {
        module?.log(Log.ERROR, TAG, message, cause)
    }
}
