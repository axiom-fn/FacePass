package com.cc.facepass.organon

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/* Reflection on classes the module does not own. Fields are looked up the hierarchy: ROMs subclass freely. */

private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()

fun ClassLoader.load(name: String): Class<*> = Class.forName(name, false, this)

fun Class<*>.field(name: String): Field = fieldCache.getOrPut(this to name) {
    var type: Class<*>? = this
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name }?.let {
            it.isAccessible = true
            return@getOrPut it
        }
        type = type.superclass
    }
    throw NoSuchFieldException("$name in $this or its superclasses")
}

fun Class<*>.fieldOrNull(name: String): Field? = runCatching { field(name) }.getOrNull()

/** Declared methods so named, synthetic ones aside. */
fun Class<*>.methodsNamed(name: String): List<Method> =
    declaredMethods.filter { it.name == name && !it.isSynthetic }.onEach { it.isAccessible = true }

/** The one declared method so named that meets [predicate]; none or several is an error. */
fun Class<*>.onlyMethod(name: String, predicate: (Method) -> Boolean = { true }): Method {
    val found = methodsNamed(name).filter(predicate)
    check(found.size == 1) { "expected one $name in $this, found ${found.size}" }
    return found.single()
}

fun Any.read(name: String): Any? = javaClass.field(name).get(this)

fun Any.readInt(name: String): Int = javaClass.field(name).getInt(this)
