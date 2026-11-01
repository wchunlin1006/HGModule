package com.hmodule.controls

import java.lang.reflect.Field
import java.util.WeakHashMap

/** Filters sidebar presentation data without removing the host's source/cache entries. */
class SidebarGameCenter(private val gameCellType: Int) {
    private data class Fields(val type: Field?, val title: Field?)
    private val fields = mutableMapOf<Class<*>, Fields>()
    private val controllers = WeakHashMap<Any, Boolean>()

    @Synchronized fun remember(controller: Any) { controllers[controller] = true }
    @Synchronized fun controllers(): List<Any> = controllers.keys.toList()

    fun filter(items: List<*>, enabled: Boolean): List<*> {
        if (!enabled) return items
        val filtered = items.filterNot { item ->
            if (item == null) false else {
                val mapping = synchronized(fields) {
                    fields.getOrPut(item.javaClass) {
                        fun field(vararg names: String): Field? = names.firstNotNullOfOrNull { name ->
                            try { item.javaClass.getField(name) } catch (_: NoSuchFieldException) { null }
                        }
                        Fields(field("cellType", "a"), field("cellName", "b"))
                    }
                }
                (mapping.type?.get(item) as? Number)?.toInt() == gameCellType ||
                    (mapping.title?.get(item) as? String)?.trim() == "游戏中心"
            }
        }
        return if (filtered.size == items.size) items else filtered
    }
}
