package com.jiligulu.app.core.ai
import org.junit.Assert.*
import org.junit.Test

class ImageReceiptCodecTest {
    private val zone = java.time.ZoneId.of("Asia/Taipei")
    private val at = java.time.LocalDateTime.parse("2026-09-25T00:34:00").atZone(zone).toInstant().toEpochMilli()
    private fun render(events: String, clock: String = "") = ImageReceiptCodec.render("""{"status_time":"$clock","events":[$events]}""", at, zone)
    private fun card(side: String, status: String, amount: String = "42.37") = """{"kind":"transfer","side":"$side","status":"$status","amount":"$amount","counterparty":"朋友"}"""
    @Test fun receivedTransferPairsAreIncomeRegardlessOfReceiptCardPosition() {
        val input = render("""{"kind":"time","text":"9月17日 17:15"},""" + card("left", "已被接收") + "," + card("right", "已收款"))
        val bills = ImageReceiptCodec.parseText(input).bills
        assertEquals(1, bills.size)
        assertEquals("INCOME", bills.single().type)
        assertEquals("2026-09-17 17:15", bills.single().timeExpression)
    }
    @Test fun sentTransferAndTheirReceiptAreOneExpense() {
        val bills = ImageReceiptCodec.parseText(render(card("right", "已被接收") + "," + card("left", "已收款"))).bills
        assertEquals(1, bills.size)
        assertEquals("EXPENSE", bills.single().type)
    }
    @Test fun twoSameAmountTransfersRemainTwoTransactions() {
        val pair = card("left", "已被接收") + "," + card("right", "已收款")
        assertEquals(2, ImageReceiptCodec.parseText(render("$pair,$pair")).bills.size)
    }
    @Test fun nearestClockClearsOlderChatDate() {
        val bills = ImageReceiptCodec.parseText(render("""{"kind":"time","text":"9月20日00:00"},{"kind":"time","text":"17:54"},""" + card("left", "已被接收", ".57") + "," + card("right", "已收款", ".57"))).bills
        assertEquals(1, bills.size)
        assertEquals("INCOME", bills.single().type)
        assertEquals("2026-09-25 17:54", bills.single().timeExpression)
    }
    @Test fun unpaidOrderAlwaysProducesEditableAddDraft() {
        val text = render("""{"kind":"order","amount":"17.85","status":"待支付","detail":"炸鸡套餐","category":"吃饭","time":""}""", "16:41")
        val bill = ImageReceiptCodec.parseText(text.replace("17.85", "18.85")).bills.single()
        assertEquals(18.85, bill.amountYuan, .001)
        assertEquals("add", bill.action)
        assertEquals("EXPENSE", bill.type)
        assertTrue(bill.note.contains("待支付"))
        assertEquals("2026-09-25 00:34", bill.timeExpression)
    }
    @Test fun invalidSideCannotSilentlyBecomeExpense() {
        assertTrue(runCatching { render(card("unknown", "已收款")) }.isFailure)
    }
    @Test fun transferClassificationIsNotInOcrTextAndIsCreatedOnlyWhenMissing() {
        val text = render(card("left", "已被接收") + "," + card("right", "已收款"))
        assertFalse(text.contains("分类"))
        val parsed = ImageReceiptCodec.parseText(text)
        val fresh = ImageReceiptCodec.classify(parsed, emptyList()).bills.single()
        assertEquals("转账", fresh.category)
        assertTrue(fresh.isNewCategory)
        val existing = com.jiligulu.app.data.local.entity.CategoryEntity(id = 5, name = "转账", colorHue = 30f, colorIndex = 1)
        assertFalse(ImageReceiptCodec.classify(parsed, listOf(existing)).bills.single().isNewCategory)
        val legacy = text.replace("；已收款", "；分类其他；已收款")
        assertEquals("转账", ImageReceiptCodec.parseText(legacy).bills.single().category)
    }
    @Test fun orderCategoryUsesCurrentLedgerKeywordsAndMainItem() {
        val text = render("""{"kind":"order","amount":"17.85","status":"待支付","detail":"炸鸡套餐+可乐","category":"其他"}""")
        val food = com.jiligulu.app.data.local.entity.CategoryEntity(name = "吃饭", keywords = "餐", colorHue = 30f, colorIndex = 1)
        val drink = com.jiligulu.app.data.local.entity.CategoryEntity(name = "饮品", keywords = "可乐", colorHue = 60f, colorIndex = 2)
        assertEquals("吃饭", ImageReceiptCodec.classify(ImageReceiptCodec.parseText(text), listOf(food, drink)).bills.single().category)
        assertFalse(text.contains("分类其他"))
    }
    @Test fun receivedRedPacketBecomesIncomeAndUsesOrCreatesRedPacketCategory() {
        val text = render("""{"kind":"transaction","direction":"INCOME","amount":"50.00","counterparty":"家人","detail":"家人的红包","status":"已存入零钱","time":""}""", "00:06")
        val parsed = ImageReceiptCodec.parseText(text)
        val bill = ImageReceiptCodec.classify(parsed, emptyList()).bills.single()
        assertEquals("INCOME", bill.type); assertEquals(50.0, bill.amountYuan, .001)
        assertEquals("红包", bill.category); assertTrue(bill.isNewCategory)
        assertEquals("2026-09-25 00:34", bill.timeExpression)
        val existing = com.jiligulu.app.data.local.entity.CategoryEntity(name = "红包", colorHue = 0f, colorIndex = 1)
        assertFalse(ImageReceiptCodec.classify(parsed, listOf(existing)).bills.single().isNewCategory)
    }
    @Test fun refundAndSentRedPacketKeepTheirDirectionsWithoutNewKindWhitelist() {
        val refund = render("""{"kind":"refund","direction":"INCOME","amount":"17.85","detail":"订单退款","status":"已到账"}""")
        assertEquals("INCOME", ImageReceiptCodec.parseText(refund).bills.single().type)
        val sent = render("""{"kind":"red_packet","direction":"EXPENSE","amount":"50","detail":"发给家人的红包","status":"已发出"}""")
        assertEquals("EXPENSE", ImageReceiptCodec.parseText(sent).bills.single().type)
    }
    @Test fun relatedRedPacketViewsBecomeOneBillAndExplicitReceiptTimeWins() {
        val text = render("""{"kind":"transaction","transaction_id":"packet-1","direction":"INCOME","amount":"200","counterparty":"家人","detail":"红包说明","time":"昨天12:35","status":"已发送","amount_source":"chat_context"},
            {"kind":"transaction","transaction_id":"packet-1","direction":"INCOME","amount":"200","counterparty":"家人","detail":"领取家人的红包","time":"昨天12:43","status":"已领取","amount_source":"chat_context"}""")
        val bill = ImageReceiptCodec.parseText(text).bills.single()
        assertEquals(200.0, bill.amountYuan, .001)
        assertEquals("INCOME", bill.type)
        assertEquals("2026-09-24 12:43", bill.timeExpression)
        assertTrue(bill.note.contains("聊天推定"))
    }
    @Test fun independentSameAmountTransactionsAreNotDeduplicated() {
        val a = """{"kind":"transaction","transaction_id":"one","direction":"INCOME","amount":"200","detail":"红包","status":"已领取"}"""
        assertEquals(2, ImageReceiptCodec.parseText(render(a + "," + a.replace("one", "two"))).bills.size)
    }
    @Test fun absentTimeUsesFrozenSystemTimeEvenWithoutStatusBar() {
        val bill = ImageReceiptCodec.parseText(render("""{"kind":"transaction","direction":"INCOME","amount":"50","detail":"红包","time":"","status":"已领取"}""")).bills.single()
        assertEquals("2026-09-25 00:34", bill.timeExpression)
        assertTrue(bill.note.contains("系统时间"))
    }
}
