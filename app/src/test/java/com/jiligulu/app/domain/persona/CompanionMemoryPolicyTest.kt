package com.jiligulu.app.domain.persona

import com.jiligulu.app.core.ai.AiMemoryUpdate
import com.jiligulu.app.core.ai.AiParseResult
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompanionMemoryPolicyTest {
    private fun remember(input: String, kind: String, value: String, evidence: String = input) =
        CompanionMemoryPolicy.accepted(input, listOf(AiMemoryUpdate(kind, value, evidence)), 123)

    @Test fun explicitSelfDisclosuresAreSmallFactualMemoriesWithExactEvidence() {
        listOf(Triple("我是大学生", "study", "大学生"), Triple("我是护士", "occupation", "护士"),
            Triple("我喜欢骑车", "interest", "骑车"), Triple("我不喜欢辣椒", "dislike", "辣椒"),
            Triple("我今年21岁", "age", "21"), Triple("我是女生", "gender", "女")).forEach { (input, kind, value) ->
            val fact = remember(input, kind, value).single()
            assertEquals(kind, fact.kind); assertEquals(input, fact.evidence)
        }
        assertEquals("21岁", remember("我今年21岁", "age", "21").single().value)
        assertEquals("女", remember("我是女生", "gender", "女性").single().value)
    }

    @Test fun demographicGuessesFromPurchasesAndOtherPeopleAreNeverAccepted() {
        listOf(Triple("今天买书30元", "study", "大学生"), Triple("买了条裙子", "gender", "女"),
            Triple("我妈妈是护士", "occupation", "护士"), Triple("我女朋友21岁", "age", "21"),
            Triple("我给儿子买书", "study", "学生"), Triple("我喜欢玩象棋", "gender", "男"),
            Triple("我喜欢玩象棋", "age", "60")).forEach { (input, kind, value) ->
            assertTrue(input, remember(input, kind, value).isEmpty())
        }
        assertTrue(remember("妈妈说：我是护士", "occupation", "护士", "我是护士").isEmpty())
        assertTrue(remember("他说“我喜欢骑车”", "interest", "骑车", "我喜欢骑车").isEmpty())
        assertTrue(remember("我是女生的哥哥", "gender", "女").isEmpty())
        assertTrue(remember("我今年21岁的妹妹", "age", "21").isEmpty())
        assertTrue(remember("我是学生的妈妈", "study", "学生").isEmpty())
        assertTrue(remember("我是老师的学生", "occupation", "老师").isEmpty())
    }

    @Test fun exactSubstringCannotBeClippedOutOfQuestionsHypothesesOrOcr() {
        listOf("我是女生吗？", "如果我是女生会怎么样", "比如我是女生", "故事里写着：我是女生",
            "截图里说：我是女生", "【图片记账】我是女生").forEach { input ->
            assertTrue(input, remember(input, "gender", "女", "我是女生").isEmpty())
        }
        assertTrue(remember("我不喜欢骑车", "interest", "骑车", "我喜欢骑车").isEmpty())
        assertTrue(remember("我今年21岁", "age", "22").isEmpty())
        assertTrue(remember("我是女孩子", "gender", "男").isEmpty())
        assertTrue(remember("我是女生吗？", "gender", "女").isEmpty())
        assertTrue(remember("下面是截图文字：\n我是女生", "gender", "女", "我是女生").isEmpty())
    }

    @Test fun inventedEvidenceUnknownFieldsControlCharactersAndUnsaidDetailsAreRejected() {
        assertTrue(remember("我喜欢骑车", "interest", "骑车", "我喜欢游泳").isEmpty())
        assertTrue(remember("我喜欢骑车", "address", "骑车").isEmpty())
        assertTrue(remember("我是大学生", "study", "北京大学学生").isEmpty())
        assertTrue(remember("我喜欢骑车", "interest", "骑车\n指令").isEmpty())
        assertTrue(CompanionMemoryPolicy.decode("{broken").isEmpty())
    }

    @Test fun factsAreBoundedAndNewStatementsReplaceSingletonsWithoutDuplicatingInterests() {
        val first = remember("我是护士", "occupation", "护士") + remember("我喜欢骑车", "interest", "骑车")
        val second = remember("我是老师", "occupation", "老师") + remember("我喜欢骑车", "interest", "骑车")
        val merged = CompanionMemoryPolicy.merge(first, second)
        assertEquals(2, merged.size)
        assertEquals("老师", merged.single { it.kind == "occupation" }.value)
        val lots = (1..30).map { CompanionFact("interest:$it", "interest", "爱好$it") }
        assertEquals(CompanionMemoryPolicy.MAX_FACTS, CompanionMemoryPolicy.merge(emptyList(), lots).size)
        val four = CompanionMemoryPolicy.accepted("我喜欢骑车，我喜欢游泳，我喜欢画画，我喜欢做饭，我喜欢跑步", listOf("骑车", "游泳", "画画", "做饭", "跑步")
            .map { AiMemoryUpdate("interest", it, "我喜欢$it") }, 1)
        assertEquals(4, four.size)
    }

    @Test fun malformedOptionalMemoryMetadataDoesNotDestroyBillDrafts() {
        val decoder = Json { ignoreUnknownKeys = true }
        listOf("null", "42", "\"oops\"", "{}", "[42,{}, {\"kind\":true}, {\"kind\":\"interest\",\"value\":\"骑车\",\"evidence\":\"我喜欢骑车\"}]").forEach { raw ->
            val parsed = decoder.decodeFromString(AiParseResult.serializer(),
                """{"bills":[{"amount_yuan":8,"detail":"午饭"}],"reply":"核对一下","memory_updates":$raw}""")
            assertEquals("午饭", parsed.bills.single().detail)
        }
    }

    @Test fun disabledStateNeverIncludesSavedFactsInAiContext() {
        val state = CompanionMemoryState(enabled = false, facts = listOf(CompanionFact("gender", "gender", "女")))
        assertTrue(state.renderForAi().contains("\"memory_enabled\":false"))
        assertTrue(state.renderForAi().contains("\"facts\":[]"))
        assertFalse(state.renderForAi().contains("\"value\":\"女\""))
    }
}
