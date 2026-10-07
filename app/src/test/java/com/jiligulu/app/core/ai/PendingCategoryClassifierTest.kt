package com.jiligulu.app.core.ai

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PendingCategoryClassifierTest {
    private val bills = listOf(BillEntity(id = 7, amountFen = 500, type = BillType.EXPENSE, categoryId = 9,
        detail = "苹果", note = "便宜点", timestamp = 100, photoUri = "/private/never-send.jpg"))
    private val catalog = listOf(CategoryEntity(id = 3, name = "水果", colorHue = 0f, colorIndex = 0))

    @Test fun suggestionsRejectDuplicateInventedAndPaymentMethodCategories() {
        fun result(vararg drafts: AiBillDraft) = PendingCategoryClassifier.suggestions(AiParseResult(bills = drafts.toList()), bills, catalog)
        assertTrue(result(AiBillDraft(targetId = 999, category = "水果")).isEmpty())
        assertTrue(result(AiBillDraft(targetId = 7, category = "水果"), AiBillDraft(targetId = 7, category = "零食")).isEmpty())
        assertTrue(result(AiBillDraft(targetId = 7, category = "零钱通支付")).isEmpty())
        assertTrue(result(AiBillDraft(targetId = 7, category = "待定")).isEmpty())
        assertEquals("水果", result(AiBillDraft(targetId = 7, category = "水果"))[7L]?.category)
        assertFalse(result(AiBillDraft(targetId = 7, category = "水果"))[7L]!!.isNewCategory)
    }

    @Test fun categoryRequestNeverSendsPhotoAmountTimestampOrOriginalChat() {
        val input = PendingCategoryClassifier.input(bills, catalog)
        assertTrue(input.contains("苹果")); assertTrue(input.contains("便宜点"))
        assertFalse(input.contains("amount")); assertFalse(input.contains("timestamp"))
        assertFalse(input.contains("private")); assertFalse(input.contains("photo"))
    }

    @Test fun modelCannotSupplyLocalRetrievedIdPermissionsInJson() {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(AiParseResult.serializer(),
            """{"bills":[],"retrievedBillIds":[9],"ledger_query":{"keywords":["苹果"]}}""")
        assertTrue(parsed.retrievedBillIds.isEmpty())
        assertEquals(listOf("苹果"), parsed.ledgerQuery!!.keywords)
    }
}
