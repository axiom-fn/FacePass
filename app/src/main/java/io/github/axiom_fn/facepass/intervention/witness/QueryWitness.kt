package io.github.axiom_fn.facepass.intervention.witness

import android.os.Binder
import io.github.axiom_fn.facepass.intervention.Place
import io.github.axiom_fn.facepass.intervention.Witness
import io.github.axiom_fn.facepass.judgment.Whitelist
import io.github.axiom_fn.facepass.organon.Journal
import io.github.axiom_fn.facepass.organon.load
import io.github.axiom_fn.facepass.substance.Device
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** The query witness: logs what a whitelisted app asks of AuthService, and the answer. */
object QueryWitness : Witness("query witness", Place.SYSTEM_SERVER) {

    private val packagesByUid = ConcurrentHashMap<Int, List<String>>()

    override fun apply(loader: ClassLoader) {
        loader.load("com.android.server.biometrics.AuthService\$AuthServiceImpl").declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .also { check(it.isNotEmpty()) { "no AuthService methods" } }
            .forEach { hook(it, call) }
    }

    private val call = XposedInterface.Hooker { chain ->
        val caller = whitelisted(Binder.getCallingUid())
        val result = chain.proceed()
        if (caller != null) {
            val args = chain.getArgs().joinToString(transform = ::show)
            Journal.info("$caller: ${chain.getExecutable().name}($args) = ${show(result)}")
        }
        result
    }

    /** A uid seen before system services start has no packages yet, so empty answers are not kept. */
    private fun whitelisted(uid: Int): String? {
        val packages = packagesByUid[uid] ?: Device.packagesOf(uid).also { if (it.isNotEmpty()) packagesByUid[uid] = it }
        return packages.firstOrNull { it in Whitelist }
    }

    private fun show(value: Any?): String = when (value) {
        null, is Number, is Boolean -> value.toString()
        is String -> "\"$value\""
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        else -> value.javaClass.simpleName
    }
}
