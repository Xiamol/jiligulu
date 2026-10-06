package com.jiligulu.app.core.ai

import com.jiligulu.app.data.local.entity.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualCategoryClassifierTest {
    private val category = CategoryEntity(id = 5, name = "学习", iconValue = "builtin_study",
        keywords = "书本,课程", colorHue = 100f, colorIndex = 1)

    @Test fun classifierReusesTheCatalogAndCannotChooseAnExtraTarget() {
        val result = AiParseResult(bills = listOf(AiBillDraft(targetId = 1, category = "学习", amountYuan = 9999.0)))
        val suggestion = ManualCategoryClassifier.suggestion(result, listOf(category))!!
        assertEquals(category.name, suggestion.category)
        assertEquals(category.iconValue, suggestion.iconEmoji)
        assertEquals(false, suggestion.isNewCategory)
        assertNull(ManualCategoryClassifier.suggestion(result.copy(bills = result.bills + AiBillDraft(targetId = 2)), listOf(category)))
    }

    @Test fun paymentChannelAndMissingTargetAreNotAcceptedAsCategories() {
        assertNull(ManualCategoryClassifier.suggestion(AiParseResult(bills = listOf(AiBillDraft(targetId = 1, category = "零钱通支付"))), emptyList()))
        assertNull(ManualCategoryClassifier.suggestion(AiParseResult(bills = listOf(AiBillDraft(category = "书本"))), emptyList()))
    }

    @Test fun appropriateNewCategoryRemainsAProposal() {
        val suggestion = ManualCategoryClassifier.suggestion(AiParseResult(bills = listOf(
            AiBillDraft(targetId = 1, category = "摄影", iconEmoji = "📷", keywords = "镜头,拍摄"))), listOf(category))!!
        assertEquals("摄影", suggestion.category)
        assertEquals(true, suggestion.isNewCategory)
    }
}
