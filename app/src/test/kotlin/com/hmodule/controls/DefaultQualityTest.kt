package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class DefaultQualityTest {
    @Test fun selectedQualityUsesOnlyVideoSupportedValuesAndFallsBackDown() {
        assertEquals(720, DefaultQuality.HD.select(listOf(360, 720, 1080)) { it })
        assertEquals(720, DefaultQuality.FHD.select(listOf(360, 720, 1081)) { it })
        assertEquals(1080, DefaultQuality.HIGHEST.select(listOf(360, 1080, 1081, 2000, 4000)) { it })
        assertNull(DefaultQuality.HIGHEST.select(listOf(1081, 2000, 4000)) { it })
        assertEquals(DefaultQuality.FHD, DefaultQuality.fromKey("1080plus"))
        assertEquals(DefaultQuality.FHD, DefaultQuality.fromKey("1440"))
        assertEquals(DefaultQuality.FHD, DefaultQuality.fromKey("2160"))
    }

    @Test fun noLowerQualityFallsBackToTheLowestAvailable() {
        assertEquals(720, DefaultQuality.LOWEST.select(listOf(1080, 720)) { it })
    }

    @Test fun highestSkipsAutomaticAndUnknownEntries() {
        assertEquals(1080, DefaultQuality.HIGHEST.select(listOf(Int.MIN_VALUE, -9990, 360, 1080)) { it })
        assertNull(DefaultQuality.HIGHEST.select(listOf(Int.MIN_VALUE, -9990)) { it })
        assertNull(DefaultQuality.HD.select(emptyList<Int>()) { it })
    }

    @Test fun eachVideoGetsItsOwnFallbackWithoutLosingThePreferredQuality() {
        val quality = DefaultQuality.FHD
        assertEquals(720, quality.select(listOf(360, 720)) { it })
        assertNull(quality.select(emptyList<Int>()) { it })
        assertEquals(1080, quality.select(listOf(360, 720, 1080)) { it })
    }

    @Test fun oldHighestSettingAndInvalidKeysDefaultToHighest() {
        assertEquals(DefaultQuality.HIGHEST, DefaultQuality.fromKey(null))
        assertEquals(DefaultQuality.HIGHEST, DefaultQuality.fromKey("unsupported-key"))
        assertEquals(DefaultQuality.HD, DefaultQuality.fromKey("720"))
    }
}
