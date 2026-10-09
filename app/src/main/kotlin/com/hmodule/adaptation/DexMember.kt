package com.hmodule.adaptation

/** JVM descriptors include owner, member, parameters and exact result/field type. */
data class DexMember(val kind: String, val owner: String, val name: String = "",
    val parameters: List<String> = emptyList(), val type: String = "") {
    fun verify(loader: ClassLoader?, resourceExists: ((String) -> Boolean)?) {
        if (kind == "resource") {
            require(resourceExists?.invoke(name) == true) { "资源不存在或无法校验: id/$name" }
            return
        }
        require(loader != null) { "缺少宿主 ClassLoader" }
        fun load(t: String): Class<*> = when (t) {
            "V" -> Void.TYPE; "Z" -> Boolean::class.javaPrimitiveType!!; "B" -> Byte::class.javaPrimitiveType!!
            "C" -> Char::class.javaPrimitiveType!!; "S" -> Short::class.javaPrimitiveType!!
            "I" -> Int::class.javaPrimitiveType!!; "J" -> Long::class.javaPrimitiveType!!
            "F" -> Float::class.javaPrimitiveType!!; "D" -> Double::class.javaPrimitiveType!!
            else -> Class.forName(if (t.startsWith('[')) t.replace('/', '.') else t.substring(1, t.length - 1).replace('/', '.'), false, loader)
        }
        val clazz = load(owner)
        if (kind == "class") return
        if (name == "<init>") { clazz.getDeclaredConstructor(*parameters.map(::load).toTypedArray()); return }
        var current: Class<*>? = clazz
        while (current != null) {
            val c = current
            if (kind == "method") {
                val match = c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.toList() == parameters.map(::load) && it.returnType == load(type) }
                if (match != null) return
            } else {
                val match = c.declaredFields.firstOrNull { it.name == name }
                if (match != null) {
                    require(match.type == load(type)) { "宿主字段类型改变: ${clazz.name}.$name" }
                    return
                }
            }
            current = c.superclass
        }
        throw IllegalArgumentException("宿主成员不存在或类型改变: ${clazz.name}.$name")
    }

    companion object {
        fun typeOf(clazz: Class<*>): String = when (clazz) {
            Void.TYPE -> "V"; java.lang.Boolean.TYPE -> "Z"; java.lang.Byte.TYPE -> "B"; java.lang.Character.TYPE -> "C"
            java.lang.Short.TYPE -> "S"; java.lang.Integer.TYPE -> "I"; java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"; java.lang.Double.TYPE -> "D"
            else -> if (clazz.isArray) clazz.name.replace('.', '/') else objectType(clazz.name)
        }
        fun objectType(name: String) = "L${name.replace('.', '/')};"
        private val objectPattern = Regex("L[A-Za-z0-9_$/]+;")
        private fun types(text: String, allowVoid: Boolean = false): List<String> {
            val result = mutableListOf<String>(); var i = 0
            while (i < text.length) {
                val start = i
                while (i < text.length && text[i] == '[') i++
                require(i < text.length && i - start <= 32) { "无效类型签名" }
                if (text[i] == 'L') {
                    val end = text.indexOf(';', i)
                    require(end > i && objectPattern.matches(text.substring(i, end + 1))) { "无效类签名" }
                    i = end + 1
                } else {
                    require(text[i] in "ZBCSIJFD" || allowVoid && i == start && text[i] == 'V') { "无效类型签名" }
                    i++
                }
                result.add(text.substring(start, i))
            }
            return result
        }
        fun parse(kind: String, text: String): DexMember {
            if (kind == "resource") {
                require(text.matches(Regex("id/[A-Za-z0-9_]+"))) { "无效资源签名" }
                return DexMember(kind, "", text.substring(3))
            }
            if (kind == "class") {
                require(objectPattern.matches(text)) { "无效类签名" }; return DexMember(kind, text)
            }
            val split = text.split("->")
            require(split.size == 2 && objectPattern.matches(split[0])) { "无效成员所属类" }
            val part = split[1]
            if (kind == "field") {
                val f = part.split(':'); require(f.size == 2 && f[0].matches(Regex("[A-Za-z0-9_$]+"))) { "无效字段签名" }
                require(types(f[1]).size == 1) { "无效字段类型" }
                return DexMember(kind, split[0], f[0], type = f[1])
            }
            require(kind == "method")
            val open = part.indexOf('('); val close = part.indexOf(')')
            require(open > 0 && close > open) { "无效方法签名" }
            val name = part.substring(0, open)
            require(name == "<init>" || name.matches(Regex("[A-Za-z0-9_$]+"))) { "无效方法名" }
            val params = types(part.substring(open + 1, close))
            val result = types(part.substring(close + 1), true)
            require(result.size == 1 && (name != "<init>" || result.single() == "V")) { "无效返回类型" }
            return DexMember(kind, split[0], name, params, result.single())
        }
    }
}
