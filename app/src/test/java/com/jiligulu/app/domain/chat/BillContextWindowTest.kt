package com.jiligulu.app.domain.chat

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.prefs.BillContextWindow
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillContextWindowTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Tokyo")
    private val category = CategoryEntity(id = 1, name = "餐饮", colorHue = 1f, colorIndex = 0)
    private fun bill(id: Long, daysAgo: Int, detail: String = "午饭", note: String = "") =
        BillEntity(id = id, amountFen = 4327, type = BillType.EXPENSE, categoryId = 1,
            detail = detail, note = note, timestamp = now - daysAgo * 86_400_000L)

    @Test fun theOldFourArgumentApiKeepsThreeDaysAndThirtyBills() {
        val context = ChatContextBuilder.build((0L..40L).map { bill(it, if (it == 40L) 4 else 2) }, listOf(category), now, zone)
        assertEquals(30, context.recentBills.size)
        assertFalse(context.recentBills.any { it.label.startsWith("[40]") })
        assertTrue(context.billsNotice.contains("最近3天"))
        assertTrue(context.billsNotice.contains("最多30笔"))
    }

    @Test fun theSelectedWindowUsesNewestBillsAndAllDoesNotApplyATimeCutoff() {
        val bills = listOf(bill(1, 2), bill(2, 5), bill(3, 20), bill(4, 70), bill(5, 365)).reversed()
        val expectedCounts = listOf(1, 2, 3, 4, 5)
        BillContextWindow.entries.forEachIndexed { index, window ->
            val context = ChatContextBuilder.build(bills, listOf(category), now, zone, window.windowDays, window.maxBillCount)
            assertEquals(expectedCounts[index], context.recentBills.size)
            assertTrue(context.recentBills.first().label.startsWith("[1]"))
        }
        val capped = ChatContextBuilder.build(bills, listOf(category), now, zone, null, 2)
        assertEquals(listOf("[1]", "[2]"), capped.recentBills.map { it.label.substringBefore(" ") })
        assertTrue(capped.billsNotice.contains("截取"))
        assertEquals(5, bills.size)
    }

    @Test fun longDetailsKeepTheirMoneyAndTypeAndThePromptExplainsTheCharacterLimit() {
        val longDetail = "面".repeat(10_000)
        val longNote = "注".repeat(10_000)
        val context = ChatContextBuilder.build((1L..1000L).map { bill(it, 0, longDetail, longNote) },
            listOf(category), now, zone, null, 1000)
        assertTrue(context.recentBills.size in 1..999)
        assertTrue(context.recentBills.sumOf { it.label.length + 1 } <= ChatContextBuilder.MAX_BILL_CONTEXT_CHARS)
        assertTrue(context.recentBills.all { it.label.contains("43.27 元 · 支出") && it.label.contains("已截取") })
        val prompt = PromptRenderer("不变的system", "【本轮账本上下文】\n{bills}", listOf(category), context,
            "", "", PromptRenderer.CandidateBills(), null, zone).renderContext("聊聊")
        assertTrue(prompt.contains("全部时间范围"))
        assertTrue(prompt.contains("不代表完整账本"))
        assertTrue(prompt.contains("精确查询仍以全账本为准"))
    }

    @Test fun anEmptySelectedRangeNeverClaimsTheFullLedgerIsEmpty() {
        val context = ChatContextBuilder.build(listOf(bill(1, 20)), listOf(category), now, zone, 7, 100)
        val renderer = PromptRenderer("原样", "{bills}", listOf(category), context, "", "",
            PromptRenderer.CandidateBills(), null, zone)
        assertTrue(renderer.renderContext("聊聊").contains("此范围暂无账单"))
        assertTrue(renderer.renderContext("聊聊").contains("最近7天"))
        assertEquals("原样", renderer.renderSystem())
    }
}
