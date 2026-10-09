package com.hmodule.controls

enum class DefaultQuality(val key: String, val title: String, val rank: Int) {
    HIGHEST("highest", "最高可用画质（最高 1080P）", 1080),
    FHD("1080", "超清 · 1080P", 1080),
    HD("720", "高清 · 720P", 720),
    SD("540", "标清 · 540P", 540),
    LOW("480", "流畅 · 480P", 480),
    LOWEST("360", "流畅 · 360P", 360);

    /** Use an available native resolution; unsupported choices fall back down, then to the lowest. */
    fun <T> select(supported: List<T>, rankOf: (T) -> Int): T? {
        val ranked = supported.map { it to rankOf(it) }.filter { it.second in 1..1080 }
        if (this == HIGHEST) return ranked.maxByOrNull { it.second }?.first
        return (ranked.filter { it.second <= rank }.maxByOrNull { it.second }
            ?: ranked.minByOrNull { it.second })?.first
    }

    companion object {
        const val VALUE_KEY = "guoplus_default_quality"
        fun fromKey(key: String?): DefaultQuality = when (key) {
            "2160", "1440", "1080plus" -> FHD
            else -> entries.firstOrNull { it.key == key } ?: HIGHEST
        }
    }
}
