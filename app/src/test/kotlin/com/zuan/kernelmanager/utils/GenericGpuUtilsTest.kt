/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenericGpuUtilsTest {

    // Potongan uid_time_in_state asli dari Pixel 10 (Tensor G5, 34f00000.gpu0)
    private val sample1 = """
        uid: 198000000 253000000 300000000 396000000 448000000 512000000 576000000 633000000 691000000 748000000 806000000 864000000 921000000 998000000 1094000000
        10332: 20512 0 0 136 0 0 19 0 0 0 0 0 0 0 0
        10391: 80 0 0 538 0 0 30 0 0 0 0 0 0 0 0
        10367: 2453 0 0 24015 17647 10323 8944 4209 1330 683 394 321 1041 1138 7003
    """.trimIndent()

    private val sample2 = sample1.replace("10391: 80 0 0 538", "10391: 80 0 0 539")

    @Test
    fun sumsAllUidRowsAndSkipsHeader() {
        val expected = (20512 + 136 + 19) + (80 + 538 + 30) +
            (2453 + 24015 + 17647 + 10323 + 8944 + 4209 + 1330 + 683 + 394 + 321 + 1041 + 1138 + 7003)
        assertEquals(expected.toLong(), GenericGpuUtils.sumUidTimeInState(sample1))
    }

    @Test
    fun deltaBetweenSamplesIsBusyTime() {
        val d = GenericGpuUtils.sumUidTimeInState(sample2)!! - GenericGpuUtils.sumUidTimeInState(sample1)!!
        assertEquals(1L, d) // 1 ms aktif dalam ~1047 ms -> ~0% (GPU idle)
    }

    @Test
    fun missingOrEmptyFileGivesNull() {
        assertNull(GenericGpuUtils.sumUidTimeInState(""))
        assertNull(GenericGpuUtils.sumUidTimeInState("cat: uid_time_in_state: No such file or directory"))
        assertNull(GenericGpuUtils.sumUidTimeInState("uid: 198000000 253000000"))
    }

    @Test
    fun toMhzHandlesHzAndKhz() {
        assertEquals("1094", GenericGpuUtils.toMhz("1094000000")) // Hz (Tensor G5)
        assertEquals("198", GenericGpuUtils.toMhz("198000000"))
        assertEquals("1094", GenericGpuUtils.toMhz("1094000")) // KHz di atas 1 GHz (dulu jadi "1")
        assertEquals("150", GenericGpuUtils.toMhz("150000")) // KHz
        assertEquals("850", GenericGpuUtils.toMhz("850")) // Sudah MHz
    }
}
