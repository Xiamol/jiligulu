package com.jiligulu.app.ui.littleworld

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class XiaoLiuRenTest {
    private val date = LocalDate.of(2026, 10, 8)
    @Test fun inclusiveCountingMatchesThePublishedSixthMonthFifthDayChenExample() {
        val result = XiaoLiuRen.forInput(XiaoLiuRenInput(date, 6, 5, 5))
        assertEquals(LiuRenPalace.KONG_WANG, result.month)
        assertEquals(LiuRenPalace.CHI_KOU, result.day)
        assertEquals(LiuRenPalace.LIU_LIAN, result.hour)
        val second = XiaoLiuRen.forInput(XiaoLiuRenInput(date, 8, 4, 8))
        assertEquals(LiuRenPalace.LIU_LIAN, second.month)
        assertEquals(LiuRenPalace.XIAO_JI, second.day)
        assertEquals(LiuRenPalace.KONG_WANG, second.hour)
    }
    @Test fun countingStartsAtOneAndLeapMonthUsesTheSameMonthNumber() {
        val input = XiaoLiuRenInput(date, 1, 1, 1)
        assertEquals(XiaoLiuRenResult(LiuRenPalace.DA_AN, LiuRenPalace.DA_AN, LiuRenPalace.DA_AN), XiaoLiuRen.forInput(input))
        assertEquals(XiaoLiuRen.forInput(input), XiaoLiuRen.forInput(input.copy(leapMonth = true)))
        assertEquals(XiaoLiuRen.forInput(input), XiaoLiuRen.forInput(input.copy(lunarDay = 7)))
        assertEquals(XiaoLiuRen.forInput(input), XiaoLiuRen.forInput(input.copy(shichen = 7)))
    }
    @Test fun bothHalvesOfZiAndEveryTwoHourBoundaryAreMappedWithoutAnHourZero() {
        assertEquals(1, XiaoLiuRen.shichen(23)); assertEquals(1, XiaoLiuRen.shichen(0))
        assertEquals(2, XiaoLiuRen.shichen(1)); assertEquals(2, XiaoLiuRen.shichen(2))
        assertEquals(12, XiaoLiuRen.shichen(21)); assertEquals(12, XiaoLiuRen.shichen(22))
        assertTrue((0..23).all { XiaoLiuRen.shichen(it) in 1..12 })
        assertTrue(runCatching { XiaoLiuRen.shichen(24) }.isFailure)
        assertTrue(runCatching { XiaoLiuRenInput(date, 0, 1, 1) }.isFailure)
        assertTrue(runCatching { XiaoLiuRenInput(date, 1, 31, 1) }.isFailure)
    }
    @Test fun threeReportedDigitsUseInclusiveStartsAndAnExplicitTenForZero() {
        assertEquals(listOf(1, 3, 7), XiaoLiuRen.digitCounts("137"))
        assertEquals(XiaoLiuRenResult(LiuRenPalace.DA_AN, LiuRenPalace.SU_XI, LiuRenPalace.SU_XI),
            XiaoLiuRen.forCounts(requireNotNull(XiaoLiuRen.digitCounts("137"))))
        assertEquals(listOf(10, 1, 2), XiaoLiuRen.digitCounts("012"))
        assertEquals(XiaoLiuRenResult(LiuRenPalace.CHI_KOU, LiuRenPalace.CHI_KOU, LiuRenPalace.XIAO_JI),
            XiaoLiuRen.forCounts(requireNotNull(XiaoLiuRen.digitCounts("012"))))
        assertEquals(LiuRenPalace.CHI_KOU, XiaoLiuRen.forCounts(listOf(10, 10, 10)).hour)
        listOf("", "12", "1234", "1 3", "１２３", "一二三", "-12", "137\n").forEach { assertNull(it, XiaoLiuRen.digitCounts(it)) }
        assertTrue(runCatching { XiaoLiuRen.forCounts(listOf(1, 0, 3)) }.isFailure)
        assertTrue(runCatching { XiaoLiuRen.forCounts(listOf(1, 3)) }.isFailure)
    }
}
