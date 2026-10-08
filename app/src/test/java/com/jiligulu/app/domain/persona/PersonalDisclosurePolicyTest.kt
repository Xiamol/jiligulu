package com.jiligulu.app.domain.persona

import org.junit.Assert.*
import org.junit.Test

class PersonalDisclosurePolicyTest {
    private val now = 1_800_000_000_000L

    private fun user(id: Long, text: String, sentAt: Long = now - 2_000L) =
        MemoryConversationTurn(id, "USER", text, sentAt)

    private fun assistant(id: Long, text: String, sentAt: Long = now - 1_000L) =
        MemoryConversationTurn(id, "ASSISTANT", text, sentAt)

    private fun factsOf(disclosure: PersonalDisclosure) =
        disclosure.facts.associate { it.kind to it.value }

    @Test fun aGenderAnswerToTheUsersOwnQuestionIsEvidenceWithoutInventingASelfDisclosure() {
        val result = PersonalDisclosurePolicy.analyze("男生", now, listOf(
            user(1, "你知道我是男生还是女生吗？"),
            assistant(2, "阿噜还不知道，你告诉我好吗？")
        ))

        assertEquals(mapOf("gender" to "男"), factsOf(result))
        assertTrue(result.personalOnly)
        assertEquals("男生", result.facts.single().evidence)
        assertEquals(now, result.facts.single().updatedAt)
    }

    @Test fun aNumericalAgeAnswerUsesTheUserAnswerAndNeverTheAssistantGuess() {
        val result = PersonalDisclosurePolicy.analyze("19", now, listOf(
            user(1, "你猜我多少岁？"),
            assistant(2, "我猜十九二十岁，你实际多大啦？")
        ))

        assertEquals(mapOf("age" to "19岁"), factsOf(result))
        assertTrue(result.personalOnly)
        assertEquals("19", result.facts.single().evidence)
        assertEquals("19", result.rejectedAmount)
        assertFalse(result.facts.any { it.value == "20岁" })
    }

    @Test fun anAssistantCanAskForTheAgeButCannotSupplyItsValue() {
        val result = PersonalDisclosurePolicy.analyze("19", now, listOf(
            user(1, "我们聊聊吧"),
            assistant(2, "你今年几岁呀？")
        ))

        assertEquals(mapOf("age" to "19岁"), factsOf(result))
        assertEquals("19", result.facts.single().evidence)
        assertTrue(result.personalOnly)
    }

    @Test fun aNumberHasNoPersonalMeaningWithoutAnAgeQuestion() {
        val result = PersonalDisclosurePolicy.analyze("19", now)

        assertTrue(result.facts.isEmpty())
        assertFalse(result.personalOnly)
        assertEquals("19", result.billInput)
        assertNull(result.rejectedAmount)
    }

    @Test fun anAmountQuestionKeepsANumberInTheBookkeepingPath() {
        val result = PersonalDisclosurePolicy.analyze("19", now, listOf(
            user(1, "刚才吃了饭"),
            assistant(2, "这顿饭花了多少钱？")
        ))

        assertTrue(result.facts.isEmpty())
        assertFalse(result.personalOnly)
        assertEquals("19", result.billInput)
        assertNull(result.rejectedAmount)
    }

    @Test fun anAgeQuestionExpiresAfterThirtyMinutes() {
        val result = PersonalDisclosurePolicy.analyze("19", now, listOf(
            user(1, "你猜我多少岁？", now - 31 * 60_000L),
            assistant(2, "十九二十岁？", now - 31 * 60_000L + 1_000L)
        ))

        assertTrue(result.facts.isEmpty())
        assertFalse(result.personalOnly)
        assertEquals("19", result.billInput)
    }

    @Test fun aNewBookkeepingTopicEndsTheOldAgeQuestion() {
        val result = PersonalDisclosurePolicy.analyze("19", now, listOf(
            user(1, "你猜我多少岁？", now - 10_000L),
            assistant(2, "你今年多大？", now - 9_000L),
            user(3, "先记一下今天吃饭", now - 2_000L),
            assistant(4, "这顿饭花了多少钱？")
        ))

        assertTrue(result.facts.isEmpty())
        assertFalse(result.personalOnly)
        assertNull(result.rejectedAmount)
    }

    @Test fun anExplicitAgeCorrectionCannotRemainAPendingAmount() {
        listOf("19岁，笨蛋阿噜", "没有19的账单，我说的是19岁").forEach { input ->
            val result = PersonalDisclosurePolicy.analyze(input, now, listOf(
                user(1, "19"),
                assistant(2, "19元是花在哪儿呀？")
            ))

            assertEquals(input, mapOf("age" to "19岁"), factsOf(result))
            assertTrue(input, result.personalOnly)
            assertEquals(input, "19", result.rejectedAmount)
            assertTrue(input, result.facts.single().evidence in input)
        }
    }

    @Test fun ageAndARealBillInOneTurnKeepSeparateMeaningsForTheirNumbers() {
        val input = "我19岁，吃饭10元"
        val result = PersonalDisclosurePolicy.analyze(input, now)

        assertEquals(mapOf("age" to "19岁"), factsOf(result))
        assertFalse(result.personalOnly)
        assertEquals("吃饭10元", result.billInput.trim())
        assertEquals("19", result.rejectedAmount)
        assertTrue(result.facts.single().evidence in input)
    }

    @Test fun ordinaryFoodAndPurchaseAmountsAreNotPersonalFacts() {
        listOf("吃饭，10", "我买19元", "吃饭10元").forEach { input ->
            val result = PersonalDisclosurePolicy.analyze(input, now)

            assertTrue(input, result.facts.isEmpty())
            assertFalse(input, result.personalOnly)
            assertEquals(input, input, result.billInput)
            assertNull(input, result.rejectedAmount)
        }
    }

    @Test fun aDirectBirthdayHasAMonthAndDayWithoutAnInventedYear() {
        val input = "生日2月28"
        val result = PersonalDisclosurePolicy.analyze(input, now)

        assertEquals(mapOf("birthday" to "2月28日"), factsOf(result))
        assertTrue(result.personalOnly)
        assertTrue(result.facts.single().evidence in input)
    }

    @Test fun aBareMonthAndDayNeedsABirthdayQuestion() {
        val withoutQuestion = PersonalDisclosurePolicy.analyze("2月28", now)
        assertTrue(withoutQuestion.facts.isEmpty())
        assertFalse(withoutQuestion.personalOnly)

        val answer = PersonalDisclosurePolicy.analyze("2月28", now, listOf(
            user(1, "你还不知道我的生日吧"),
            assistant(2, "你的生日是几月几号呀？")
        ))
        assertEquals(mapOf("birthday" to "2月28日"), factsOf(answer))
        assertEquals("2月28", answer.facts.single().evidence)
        assertTrue(answer.personalOnly)
    }

    @Test fun impossibleBirthdayDatesCannotBecomeFacts() {
        listOf("生日2月30", "生日13月1日", "生日4月31日").forEach { input ->
            assertTrue(input, PersonalDisclosurePolicy.analyze(input, now).facts.isEmpty())
        }
    }

    @Test fun schoolGradeAndOccupationOnlyContainWhatTheUserActuallyStates() {
        val input = "我的学校是星河大学，我的年级是大二，我的职业是程序员"
        val result = PersonalDisclosurePolicy.analyze(input, now)

        assertEquals(mapOf("school" to "星河大学", "grade" to "大二", "occupation" to "程序员"),
            factsOf(result))
        assertTrue(result.personalOnly)
        assertTrue(result.facts.all { it.evidence in input })
    }

    @Test fun anExplicitCampusAndYearKeepTheirExactDeclaredInstitutionAndGrade() {
        val input = "我的学校是星河大学宜宾校区，我是大二的学生"
        val result = PersonalDisclosurePolicy.analyze(input, now)
        assertEquals(mapOf("school" to "星河大学宜宾校区", "grade" to "大二"), factsOf(result))
        assertTrue(result.personalOnly)
        assertTrue(result.facts.all { it.evidence in input })
    }

    @Test fun aFormalOccupationIsStoredAsTheUsersFullTitleRatherThanAnInferredCategory() {
        val input = "我的职业是软件公司的研发工程师"
        val result = PersonalDisclosurePolicy.analyze(input, now)
        assertEquals(mapOf("occupation" to "软件公司的研发工程师"), factsOf(result))
        assertEquals(input, result.facts.single().evidence)
        assertTrue(result.personalOnly)
    }

    @Test fun anAcknowledgementDoesNotConfirmTheAssistantsInventedSchoolOrGrade() {
        val result = PersonalDisclosurePolicy.analyze("嗯", now, listOf(
            user(1, "我19岁"),
            assistant(2, "我猜你在星河大学读大二，应该还是学生吧？")
        ))

        assertTrue(result.facts.isEmpty())
        assertFalse(result.personalOnly)
    }

    @Test fun terseFieldsAboutAnotherPersonNeverBecomeTheUsersOwnGenderOrAge() {
        listOf("给弟弟买衣服，男生，19元", "弟弟的年龄，19岁", "给妹妹过生日，生日2月28",
            "朋友的资料，女生，生日2月28", "送同事礼物，男生，19元", "我朋友19岁，生日2月28").forEach { input ->
            assertTrue(input, PersonalDisclosurePolicy.analyze(input, now).facts.isEmpty())
        }
    }

    @Test fun aThirdPersonTopicCannotBorrowAnEarlierPersonalQuestionSlot() {
        val history = listOf(assistant(1, "你今年几岁？"))
        assertTrue(PersonalDisclosurePolicy.analyze("弟弟的年龄，19岁", now, history).facts.isEmpty())
        assertTrue(PersonalDisclosurePolicy.analyze("小明的年龄，19岁", now, history).facts.isEmpty())
        val own = PersonalDisclosurePolicy.analyze("我19岁，吃饭19元", now, history)
        assertEquals(mapOf("age" to "19岁"), factsOf(own))
        assertFalse(own.personalOnly)
        assertEquals("吃饭19元", own.billInput)
    }

    @Test fun aThirdPersonConversationBlocksLaterOmittedSubjectsAcrossMessageBoundaries() {
        val previous = listOf(user(1, "我弟弟的资料"), assistant(2, "你弟弟是男生还是女生？"))
        listOf("男生", "19岁", "生日2月28").forEach { input ->
            assertTrue(input, PersonalDisclosurePolicy.analyze(input, now, previous).facts.isEmpty())
        }
        assertTrue(PersonalDisclosurePolicy.analyze("男生", now, listOf(assistant(1, "你的朋友是男生还是女生？"))).facts.isEmpty())
        assertTrue(PersonalDisclosurePolicy.analyze("教师", now, listOf(assistant(1, "你的妈妈从事什么工作？"))).facts.isEmpty())
    }

    @Test fun anExplicitSelfStatementCanChangeTheTopicBackFromAnotherPerson() {
        val previous = listOf(user(1, "我弟弟的资料"), assistant(2, "你弟弟是男生还是女生？"))
        assertEquals(mapOf("age" to "19岁"), factsOf(PersonalDisclosurePolicy.analyze("我19岁", now, previous)))
        assertEquals(mapOf("birthday" to "2月28日"), factsOf(PersonalDisclosurePolicy.analyze("我的生日2月28", now, previous)))
        assertEquals(mapOf("age" to "19岁"), factsOf(PersonalDisclosurePolicy.analyze("19", now,
            previous + assistant(3, "现在说说你，你今年几岁？"))))
    }

    @Test fun anExplicitAmountQuestionConsumesTheEarlierUserAgeQuestion() {
        val previous = listOf(user(1, "你猜我多少岁？"), assistant(2, "先记这顿饭吧，花了多少钱？"))
        val result = PersonalDisclosurePolicy.analyze("19", now, previous)
        assertTrue(result.facts.isEmpty())
        assertFalse(result.personalOnly)
        assertNull(result.rejectedAmount)
        assertEquals(mapOf("age" to "19岁"), factsOf(PersonalDisclosurePolicy.analyze("19", now,
            listOf(user(1, "你猜我多少岁？"), assistant(2, "阿噜还不知道，想说就告诉我吧")))))
    }

    @Test fun selfQuestionsQuotesOtherPeopleOcrAndHypothesesCannotSupplyPersonalFacts() {
        listOf(
            "我是男生吗？",
            "我弟弟19岁，生日2月28",
            "他说：“我19岁，我是男生”",
            "【图片记账】我19岁，我是男生",
            "截图里写着：我的学校是星河大学",
            "如果我19岁，我就上大二",
            "假设我的生日是2月28日"
        ).forEach { input ->
            assertTrue(input, PersonalDisclosurePolicy.analyze(input, now).facts.isEmpty())
        }
    }

    @Test fun anAssistantPromiseAndAnUnansweredGuessAreNotHistoryFacts() {
        val facts = PersonalDisclosurePolicy.historyFacts(listOf(
            user(1, "你猜我多少岁？"),
            assistant(2, "我猜你19岁，在星河大学上大二；记住你是男生啦。")
        ))

        assertTrue(facts.isEmpty())
    }

    @Test fun historicalAnswersRetainTheirOwnUserTimestampAndExactEvidence() {
        val ageAnswerAt = now - 86_400_000L
        val genderAnswerAt = ageAnswerAt + 60_000L
        val birthdayAnswerAt = ageAnswerAt + 120_000L
        val turns = listOf(
            user(1, "你猜我多少岁？", ageAnswerAt - 2_000L),
            assistant(2, "我猜十九二十岁，你实际多大？", ageAnswerAt - 1_000L),
            user(3, "19", ageAnswerAt),
            assistant(4, "你是男生还是女生呀？", genderAnswerAt - 1_000L),
            user(5, "男生", genderAnswerAt),
            assistant(6, "你的生日是几月几日？", birthdayAnswerAt - 1_000L),
            user(7, "2月28", birthdayAnswerAt),
            assistant(8, "记住了，你在星河大学读大二。", birthdayAnswerAt + 1_000L)
        )

        val facts = PersonalDisclosurePolicy.historyFacts(turns).associateBy { it.kind }
        assertEquals(setOf("age", "gender", "birthday"), facts.keys)
        assertEquals("19岁", facts.getValue("age").value)
        assertEquals("19", facts.getValue("age").evidence)
        assertEquals(ageAnswerAt, facts.getValue("age").updatedAt)
        assertEquals("男", facts.getValue("gender").value)
        assertEquals("男生", facts.getValue("gender").evidence)
        assertEquals(genderAnswerAt, facts.getValue("gender").updatedAt)
        assertEquals("2月28日", facts.getValue("birthday").value)
        assertEquals("2月28", facts.getValue("birthday").evidence)
        assertEquals(birthdayAnswerAt, facts.getValue("birthday").updatedAt)
    }

    @Test fun replayingTheSameHistoryProducesTheSameFactsAndDoesNotRefreshDates() {
        val turns = listOf(
            user(1, "我是男生，我今年19岁", now - 86_400_000L),
            assistant(2, "阿噜记住啦，你在星河大学读大二。", now - 86_399_000L)
        )

        val first = PersonalDisclosurePolicy.historyFacts(turns)
        assertEquals(setOf("gender", "age"), first.map { it.kind }.toSet())
        assertEquals(first, PersonalDisclosurePolicy.historyFacts(turns))
        assertTrue(first.all { it.updatedAt == turns.first().sentAt })
    }
}
