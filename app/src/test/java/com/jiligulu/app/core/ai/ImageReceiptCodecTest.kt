package com.jiligulu.app.core.ai
import org.junit.Assert.*
import org.junit.Test

class ImageReceiptCodecTest {
    private fun render(events: String, clock: String = "") = ImageReceiptCodec.render("""{"status_time":"$clock","events":[$events]}""")
    private fun card(side: String, status: String, amount: String = "42.37") = """{"kind":"transfer","side":"$side","status":"$status","amount":"$amount","counterparty":"朋友"}"""
    @Test fun receivedTransferPairsAreIncomeRegardlessOfReceiptCardPosition() {
        val input = render("""{"kind":"time","text":"9月17日 17:15"},""" + card("left", "已被接收") + "," + card("right", "已收款"))
        val bills = ImageReceiptCodec.parseText(input).bills
        assertEquals(1, bills.size)
        assertEquals("INCOME", bills.single().type)
        assertEquals("9月17日17:15", bills.single().timeExpression)
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
        assertEquals("日期待确认17:54", bills.single().timeExpression)
    }
    @Test fun unpaidOrderAlwaysProducesEditableAddDraft() {
        val text = render("""{"kind":"order","amount":"17.85","status":"待支付","detail":"炸鸡套餐","category":"吃饭","time":""}""", "16:41")
        val bill = ImageReceiptCodec.parseText(text.replace("17.85", "18.85")).bills.single()
        assertEquals(18.85, bill.amountYuan, .001)
        assertEquals("add", bill.action)
        assertEquals("EXPENSE", bill.type)
        assertTrue(bill.note.contains("待支付"))
        assertTrue(bill.timeExpression.contains("16:41"))
    }
    @Test fun invalidSideCannotSilentlyBecomeExpense() {
        assertTrue(runCatching { render(card("unknown", "已收款")) }.isFailure)
    }
}
