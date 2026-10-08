package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LiuRenRewriteUsageTest {
    private val today = LocalDate.of(2026, 10, 9)
    private val startAt = Instant.parse("2026-10-09T09:00:00Z").toEpochMilli()
    private fun preferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("liuren-rewrite-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun cast(question: String = "明天面试能顺利吗？", at: Long = startAt, digits: String = "364") =
        LiuRenCast(question, LiuRenMode.NUMBERS, at, "Asia/Shanghai", 8, 29, 10, digits = digits)

    @Test fun receiptImmediatelyChangesOnlyThisCastAndReopensWithoutConsumingAnotherStamp() {
        val prefs = preferences(); val usage = FortuneRewriteUsage(prefs); val cast = cast()
        val original = cast.result
        assertTrue(usage.liuRenState(cast, today).available)
        assertTrue(usage.rewriteLiuRen(cast, today, startAt + 10))
        val applied = usage.liuRenState(cast, today)
        assertTrue(applied.applied)
        assertFalse(applied.available)
        assertEquals("阿噜盖过章啦 ♡", applied.buttonLabel)
        assertEquals(cast.question, applied.receipt!!.question)
        assertTrue(applied.receipt.conclusion.contains("面试"))
        assertTrue(applied.receipt.action.contains("自我介绍"))
        assertEquals(original, cast.result)
        assertFalse(usage.rewriteLiuRen(cast, today, startAt + 20))
        val reopened = FortuneRewriteUsage(prefs)
        assertEquals(applied, reopened.liuRenState(cast.copy(), today))
        assertFalse(reopened.rewriteLiuRen(cast, today.plusDays(1), startAt + 100))
        assertEquals(applied, reopened.liuRenState(cast, today.plusDays(1)))
    }

    @Test fun otherQuestionsAndNewCastInstantsCannotBorrowAnExistingReceipt() {
        val usage = FortuneRewriteUsage(preferences()); val first = cast()
        assertTrue(usage.rewriteLiuRen(first, today))
        val differentQuestion = cast("这周能和朋友见面吗？")
        val recast = first.copy(capturedAtMillis = first.capturedAtMillis + 1)
        for (other in listOf(differentQuestion, recast, first.copy(digits = "223"))) {
            val state = usage.liuRenState(other, today)
            assertFalse(state.applied)
            assertFalse(state.available)
            assertNull(state.receipt)
            assertEquals("今天的章已用过啦", state.buttonLabel)
            assertFalse(usage.rewriteLiuRen(other, today))
            assertTrue(usage.liuRenState(other, today.plusDays(1)).available)
        }
        assertTrue(usage.rewriteLiuRen(differentQuestion, today.plusDays(1)))
        assertTrue(usage.liuRenState(first, today).applied)
        assertTrue(usage.liuRenState(differentQuestion, today.plusDays(1)).applied)
        assertNotEquals(LiuRenRewrite.key(first), LiuRenRewrite.key(recast))
    }

    @Test fun horoscopeAndLiuRenDailyQuotasRemainSeparateInBothDirections() {
        val usage = FortuneRewriteUsage(preferences())
        assertTrue(usage.mark(FortuneRewriteKind.HOROSCOPE, today))
        assertTrue(usage.rewriteLiuRen(cast(), today))
        assertFalse(usage.mark(FortuneRewriteKind.HOROSCOPE, today))
        val tomorrow = today.plusDays(1)
        assertTrue(usage.rewriteLiuRen(cast(at = startAt + 1), tomorrow))
        assertTrue(usage.mark(FortuneRewriteKind.HOROSCOPE, tomorrow))
        assertFalse(usage.rewriteLiuRen(cast(at = startAt + 2), tomorrow))
    }

    @Test fun legacyDailyUsageDoesNotInventOwnershipAndCorruptReceiptsDoNotRestoreSpentQuota() {
        val prefs = preferences()
        prefs.edit().putString(FortuneRewriteKind.LIU_REN.preferenceKey, today.toString())
            .putString(FortuneRewriteUsage.LIU_REN_RECEIPTS_KEY, "not JSON").apply()
        val usage = FortuneRewriteUsage(prefs)
        assertEquals(LiuRenRewriteState(receipt = null, available = false), usage.liuRenState(cast(), today))
        assertFalse(usage.rewriteLiuRen(cast(), today))
        assertTrue(usage.liuRenState(cast(), today.plusDays(1)).available)
    }

    @Test fun twoOwnersCanSpendOnlyOneDailyStampEvenForDifferentCasts() {
        val prefs = preferences(); val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(cast(), cast("这周旅行能成行吗？")).map { cast ->
                executor.submit<Boolean> { gate.await(); FortuneRewriteUsage(prefs).rewriteLiuRen(cast, today) }
            }
            gate.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(FortuneRewriteUsage(prefs).used(FortuneRewriteKind.LIU_REN, today))
        } finally { executor.shutdownNow() }
    }

    @Test fun receiptsAreBoundedAndRetainTheLatestCourseAcrossReopening() {
        val prefs = preferences(); val usage = FortuneRewriteUsage(prefs)
        repeat(18) { index ->
            assertTrue(usage.rewriteLiuRen(cast(at = startAt + index), today.plusDays(index.toLong())))
        }
        val raw = prefs.getString(FortuneRewriteUsage.LIU_REN_RECEIPTS_KEY, "").orEmpty()
        assertTrue(raw.length < 30_000)
        assertEquals(16, Regex("\"castKey\"").findAll(raw).count())
        val reopened = FortuneRewriteUsage(prefs)
        assertTrue(reopened.liuRenState(cast(at = startAt + 17), today.plusDays(17)).applied)
        assertFalse(reopened.liuRenState(cast(), today.plusDays(17)).applied)
    }

    @Test fun encouragementProvidesDifferentActionsForTheActualQuestionAndKeepsOriginalCourse() {
        val interview = cast("面试前可以怎么准备？")
        val meeting = cast("这周能约朋友见面吗？")
        val lost = cast("我丢了耳机，还能找回吗？")
        val original = listOf(interview.result, meeting.result, lost.result)
        val receipts = listOf(interview, meeting, lost).map { LiuRenRewrite.receipt(it, today.toString(), startAt) }
        assertTrue(receipts[0].action.contains("面试"))
        assertTrue(receipts[1].action.contains("日期"))
        assertTrue(receipts[2].action.contains("失物招领"))
        assertEquals(3, receipts.map { it.action }.distinct().size)
        assertEquals(original, listOf(interview.result, meeting.result, lost.result))
        val concern = LiuRenRewrite.receipt(cast("检查结果会不会是疾病？"), today.toString(), startAt)
        assertTrue(concern.action.contains("专业人士"))
        assertFalse(concern.conclusion.contains("一定"))
    }
}
