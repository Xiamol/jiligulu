package com.jiligulu.app.domain.time

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BillTimeResolverTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private fun instant(local: String, zoneId: ZoneId = zone) = LocalDateTime.parse(local).atZone(zoneId).toInstant().toEpochMilli()
    private val now = instant("2026-09-21T18:55:00")
    private fun resolve(text: String, at: Long = now) = BillTimeResolver.resolve(text, requestMillis = at, zone = zone)

    @Test fun noTimeKeepsConfirmationTimeDynamic() {
        val result = resolve("")
        assertNull(result.timestamp)
        assertFalse(result.needsReview)
    }

    @Test fun modelCannotInventTimeWhenUserDidNotMentionOne() {
        val result = BillTimeResolver.resolve("", "2025-01-01T08:00:00", now, zone)
        assertNull(result.timestamp)
        assertFalse(BillTimeResolver.hasTimeExpression("早餐12元，奶茶9元"))
    }

    @Test fun yesterdayNoonUsesYesterdayAtTwelve() {
        assertEquals(instant("2026-09-20T12:00:00"), resolve("昨天中午吃饭花了9块").timestamp)
    }

    @Test fun requestAnchorSurvivesMidnightAndCrossesYear() {
        assertEquals(instant("2025-12-31T12:00:00"), resolve("昨天中午", instant("2026-01-01T00:00:01")).timestamp)
    }

    @Test fun relativeDateCrossesLeapMonth() {
        assertEquals(instant("2024-02-29T20:00:00"), resolve("昨晚", instant("2024-03-01T08:00:00")).timestamp)
    }

    @Test fun chineseHourAndHalfAreRecognized() {
        assertEquals(instant("2026-09-20T14:30:00"), resolve("昨天下午两点半").timestamp)
    }

    @Test fun numericalDateAndClockAreRecognized() {
        assertEquals(instant("2025-12-31T21:45:00"), resolve("2025-12-31 21:45").timestamp)
    }

    @Test fun dateWithoutClockUsesVisibleNoonConvention() {
        val result = resolve("9月20日")
        assertEquals(instant("2026-09-20T12:00:00"), result.timestamp)
        assertTrue(result.hint.contains("12:00"))
    }

    @Test fun lastMonthDateCrossesYear() {
        assertEquals(instant("2025-12-03T08:00:00"), resolve("上个月3号早上", instant("2026-01-01T18:00:00")).timestamp)
    }

    @Test fun explicitLastYearUsesPreviousYear() {
        assertEquals(instant("2025-12-31T12:00:00"), resolve("去年12月31日").timestamp)
    }

    @Test fun lastWeekIsAnchoredToMonday() {
        assertEquals(instant("2026-09-16T15:00:00"), resolve("上周三下午").timestamp)
    }

    @Test fun ambiguousDateCannotSilentlyBecomeToday() {
        listOf("前几天", "上个月中午", "上周晚上", "时间待确认").forEach {
            assertTrue(it, resolve(it).needsReview)
            assertNull(resolve(it).timestamp)
        }
    }

    @Test fun impossibleDateAndTimeRequireCorrection() {
        listOf("2月30日", "昨天25点", "昨天12:99").forEach {
            assertTrue(it, resolve(it).needsReview)
            assertNull(resolve(it).timestamp)
        }
    }

    @Test fun usesDeviceZoneInsteadOfUtcDate() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val at = instant("2026-09-21T01:00:00") // Still September 20 in LA.
        val result = BillTimeResolver.resolve("昨天中午", requestMillis = at, zone = losAngeles)
        assertEquals(instant("2026-09-19T12:00:00", losAngeles), result.timestamp)
    }

    @Test fun multipleBillsKeepIndividualDateContexts() {
        val input = "昨天中午午饭9元，今天下午咖啡12元"
        val lunch = BillTimeResolver.contextForBill(input, "午饭", 2)
        val coffee = BillTimeResolver.contextForBill(input, "咖啡", 2)
        assertEquals(instant("2026-09-20T12:00:00"), resolve(lunch).timestamp)
        assertEquals(instant("2026-09-21T15:00:00"), resolve(coffee).timestamp)
    }

    @Test fun missingAssociationOfMultipleDatesRequiresReview() {
        assertEquals("时间待确认", BillTimeResolver.contextForBill("昨天9元，前天12元", "购物", 2))
    }

    @Test fun chineseDecimalAmountIsNotAClock() {
        assertFalse(BillTimeResolver.hasTimeExpression("咖啡九点五元"))
        assertFalse(BillTimeResolver.hasTimeExpression("咖啡2点5元"))
        assertFalse(BillTimeResolver.hasTimeExpression("咖啡￥2点5"))
        assertEquals(instant("2026-09-20T12:00:00"), resolve("昨天咖啡九点五元").timestamp)
    }

    @Test fun nowPhraseUsesRequestInstant() {
        assertEquals(now, resolve("刚才买了杯咖啡12元").timestamp)
    }

    @Test fun hallucinatedBreakfastTimeCannotOverrideDefaultNow() {
        val expression = BillTimeResolver.expressionForBill("早餐9元", "早餐", 1, "今天早上8点")
        assertEquals("", expression)
        assertNull(resolve(expression).timestamp)
    }

    @Test fun laterBillDateNeverAppliesToEarlierBill() {
        val input = "早餐9元，昨天下午咖啡12元"
        assertEquals("", BillTimeResolver.contextForBill(input, "早餐", 2))
        assertEquals("", BillTimeResolver.expressionForBill(input, "早餐", 2, "昨天下午"))
        assertEquals(instant("2026-09-20T15:00:00"), resolve(BillTimeResolver.contextForBill(input, "咖啡", 2)).timestamp)
    }

    @Test fun earlierDateMayScopeTheFollowingBill() {
        val input = "昨天下午咖啡12元，饼干9元"
        assertEquals(instant("2026-09-20T15:00:00"), resolve(BillTimeResolver.contextForBill(input, "饼干", 2)).timestamp)
    }

    @Test fun shortenedModelTimeCannotDropTheDate() {
        val expression = BillTimeResolver.expressionForBill("昨天中午午饭9元", "午饭", 1, "中午")
        assertEquals(instant("2026-09-20T12:00:00"), resolve(expression).timestamp)
    }

    @Test fun previousWeekSynonymIsNotMistakenForThisWeek() {
        assertEquals(instant("2026-09-15T12:00:00"), resolve("上个星期二", instant("2026-09-25T18:00:00")).timestamp)
    }

    @Test fun singleBillUsesItsOwnClauseInsteadOfAnEarlierDate() {
        val expression = BillTimeResolver.expressionForBill("昨天没花钱，今天早餐9元", "早餐", 1, "昨天")
        assertEquals(instant("2026-09-21T12:00:00"), resolve(expression).timestamp)
    }

    @Test fun singleBillWithoutReliableDateAssociationRequiresReview() {
        val expression = BillTimeResolver.contextForBill("昨天没花钱，今天花了9元", "早餐", 1)
        assertEquals("时间待确认", expression)
        assertTrue(resolve(expression).needsReview)
    }

    @Test fun monthOrYearWithoutDayRequiresReviewEvenIfModelGuessesFirstDay() {
        listOf("9月买电脑100元", "去年买电脑100元", "2025年买电脑100元", "两个月前买电脑100元", "几个月前买电脑100元").forEach { input ->
            assertTrue(input, BillTimeResolver.hasTimeExpression(input))
            val expression = BillTimeResolver.expressionForBill(input, "电脑", 1, "9月1日")
            val result = BillTimeResolver.resolve(expression, "2026-09-01T12:00:00", now, zone)
            assertTrue(input, result.needsReview)
            assertNull(input, result.timestamp)
        }
    }
}
