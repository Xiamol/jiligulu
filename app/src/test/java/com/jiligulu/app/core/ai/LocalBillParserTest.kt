package com.jiligulu.app.core.ai

import com.jiligulu.app.domain.time.BillTimeResolver
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalBillParserTest {
    @Test fun datesAndHoursCannotBecomeAmounts() {
        val bill = LocalBillParser.parse("9月21日中午12点吃饭花了9块").single()
        assertEquals(9.0, bill.amountYuan, 0.001)
        assertTrue(LocalBillParser.parse("9月21日中午12点吃饭").isEmpty())
    }

    @Test fun chineseMoneyWithExplicitUnitsIsRecognized() {
        assertEquals(9.0, LocalBillParser.parse("昨天中午吃饭九块").single().amountYuan, 0.001)
        assertEquals(25.0, LocalBillParser.parse("奶茶二十五元").single().amountYuan, 0.001)
        assertEquals(9.5, LocalBillParser.parse("咖啡九点五元").single().amountYuan, 0.001)
        assertEquals(9.5, LocalBillParser.parse("咖啡9块5毛").single().amountYuan, 0.001)
        assertEquals(9.5, LocalBillParser.parse("咖啡九块五").single().amountYuan, 0.001)
        assertEquals(2.5, LocalBillParser.parse("咖啡2点5元").single().amountYuan, 0.001)
    }

    @Test fun commonAmountOnlyShortcutRemainsUsable() {
        assertEquals(12.0, LocalBillParser.parse("早餐 12").single().amountYuan, 0.001)
        assertEquals(18.0, LocalBillParser.parse("咖啡 ￥18").single().amountYuan, 0.001)
    }

    @Test fun twoBillsHaveTwoAmountsAndDates() {
        val drafts = LocalBillParser.parse("昨天中午午饭9元，今天下午咖啡12元")
        assertEquals(listOf(9.0, 12.0), drafts.map { it.amountYuan })
        val zone = ZoneId.of("Asia/Taipei")
        val now = LocalDateTime.parse("2026-09-21T18:00:00").atZone(zone).toInstant().toEpochMilli()
        val actual = drafts.map { BillTimeResolver.resolve(it.timeExpression, requestMillis = now, zone = zone).timestamp }
        val expected = listOf("2026-09-20T12:00:00", "2026-09-21T15:00:00")
            .map { LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli() }
        assertEquals(expected, actual)
    }

    @Test fun ambiguousTotalsDoNotBecomeExtraBills() {
        assertTrue(LocalBillParser.parse("午饭12元实付9元").isEmpty())
        assertTrue(LocalBillParser.parse("早餐5元，午饭12元实付9元").isEmpty())
    }

    @Test fun negativeAndMalformedAmountsAreNotTruncatedIntoPositiveAmounts() {
        assertTrue(LocalBillParser.parse("咖啡-9元").isEmpty())
        assertTrue(LocalBillParser.parse("咖啡12.345元").isEmpty())
    }

    @Test fun distinctDaysCanSeparateBillsWithoutPunctuation() {
        val drafts = LocalBillParser.parse("昨天午饭9元今天咖啡12元")
        assertEquals(listOf(9.0, 12.0), drafts.map { it.amountYuan })
        assertTrue(drafts[0].timeExpression.startsWith("昨天"))
        assertTrue(drafts[1].timeExpression.startsWith("今天"))
    }
}
