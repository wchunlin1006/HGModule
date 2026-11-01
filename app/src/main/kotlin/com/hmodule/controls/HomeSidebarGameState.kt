package com.hmodule.controls

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** A reactive presentation mask over the homepage sidebar's dedicated game state. */
class HomeSidebarGameState(
    private val stateInterface: Class<*>,
    private val enabledState: Any,
    private val emptyGames: Any,
) {
    private val getValue = stateInterface.getMethod("getValue")
    private val setValue = stateInterface.getMethod("setValue", Any::class.java)

    fun setEnabled(enabled: Boolean) { setValue.invoke(enabledState, enabled) }

    fun wrap(nativeState: Any): Any = Proxy.newProxyInstance(stateInterface.classLoader, arrayOf(stateInterface)) { proxy, method, args ->
        when {
            method.name == "getValue" && method.parameterCount == 0 -> {
                // Read both states so Compose observes native updates and configuration changes.
                val hidden = getValue.invoke(enabledState) == true
                val native = getValue.invoke(nativeState)
                if (hidden) emptyGames else native
            }
            method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args?.get(0)
            method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
            method.declaringClass == Any::class.java && method.name == "toString" -> "HomeSidebarGameState"
            else -> try { method.invoke(nativeState, *(args ?: emptyArray())) }
                catch (e: InvocationTargetException) { throw e.targetException }
        }
    }
}
