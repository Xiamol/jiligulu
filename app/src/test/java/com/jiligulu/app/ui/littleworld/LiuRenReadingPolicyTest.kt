package com.jiligulu.app.ui.littleworld

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

/** Reading composition and trust boundaries; the counting algorithm has its own golden tests. */
class LiuRenReadingPolicyTest {
    private val interview = "明天终面，笔试已过，我想问录用机会和怎样准备"
    private fun fixture(question: String = interview, digits: String = "133") = LiuRenCast(question, LiuRenMode.NUMBERS,
        Instant.parse("2024-02-10T15:30:00Z").toEpochMilli(), "Asia/Shanghai", 1, 1, 1, digits = digits)
    private fun accepted(reading: LiuRenReading, cast: LiuRenCast) =
        LiuRenReadingPolicy.decode(LiuRenReadingPolicy.encode(reading), cast)

    @Test fun interviewReadingKeepsAllThreeRolesAndTheUsersGoalAndConditions() {
        val cast = fixture()
        val reading = LiuRenReadingPolicy.local(cast)
        assertEquals(cast.question, reading.question)
        assertEquals(listOf("大安", "速喜", "小吉"), reading.stages.map { it.palace })
        assertEquals(listOf("大安" to "速喜", "速喜" to "小吉"), reading.links.map { it.from to it.to })
        assertTrue(reading.summary.contains("明天终面")); assertTrue(reading.summary.contains("笔试已过"))
        assertTrue(reading.stages[0].text.contains("准备"))
        assertTrue(reading.stages[1].text.contains("交流") || reading.stages[1].text.contains("流程"))
        assertTrue(reading.stages[2].text.contains("录用"))
        val plain = reading.plainText()
        val positions = listOf("起点 · 大安", "过程 · 速喜", "趋向 · 小吉").map(plain::indexOf)
        assertTrue(positions.all { it >= 0 }); assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        assertEquals(reading, accepted(reading, cast))
    }

    @Test fun switchingFirstAndMiddlePalacesChangesTheChainEvenWhenTheFinalPalaceIsIdentical() {
        val steadyThenFast = LiuRenReadingPolicy.local(fixture(digits = "133"))
        val fastThenSteady = LiuRenReadingPolicy.local(fixture(digits = "355"))
        assertEquals("小吉", steadyThenFast.stages.last().palace)
        assertEquals("小吉", fastThenSteady.stages.last().palace)
        assertEquals(listOf("速喜", "大安", "小吉"), fastThenSteady.stages.map { it.palace })
        assertNotEquals(steadyThenFast.summary, fastThenSteady.summary)
        assertNotEquals(steadyThenFast.stages[0], fastThenSteady.stages[0])
        assertNotEquals(steadyThenFast.stages[1], fastThenSteady.stages[1])
        assertNotEquals(steadyThenFast.links, fastThenSteady.links)
        assertEquals(fastThenSteady, accepted(fastThenSteady, fixture(digits = "355")))
    }

    @Test fun repeatedPalacesRetainThreePositionsAndTwoLinksInsteadOfBeingDeduplicated() {
        val sameCast = fixture(digits = "111")
        val repeated = LiuRenReadingPolicy.local(sameCast)
        assertEquals(listOf("大安", "大安", "大安"), repeated.stages.map { it.palace })
        assertEquals(3, repeated.stages.size); assertEquals(2, repeated.links.size)
        assertTrue(repeated.links.all { it.from == "大安" && it.to == "大安" && it.text.contains("同宫") })
        assertEquals(repeated, accepted(repeated, sameCast))
        val partial = LiuRenReadingPolicy.local(fixture(digits = "131"))
        assertEquals(listOf("大安", "速喜", "速喜"), partial.stages.map { it.palace })
        assertTrue(partial.links.last().text.contains("同宫"))
        assertNull(accepted(repeated.copy(stages = repeated.stages.distinctBy { it.palace }), sameCast))
    }

    @Test fun everyFiveElementPairHasTheCorrectDirectionAndTheDeclaredSixPalaceMapping() {
        // Independent, explicit 5 × 5 golden table. No cycle/modulo implementation is mirrored.
        val expected = mapOf(
            LiuRenElement.WOOD to listOf(LiuRenRelation.SAME, LiuRenRelation.GENERATES, LiuRenRelation.CONTROLS, LiuRenRelation.CONTROLLED_BY, LiuRenRelation.GENERATED_BY),
            LiuRenElement.FIRE to listOf(LiuRenRelation.GENERATED_BY, LiuRenRelation.SAME, LiuRenRelation.GENERATES, LiuRenRelation.CONTROLS, LiuRenRelation.CONTROLLED_BY),
            LiuRenElement.EARTH to listOf(LiuRenRelation.CONTROLLED_BY, LiuRenRelation.GENERATED_BY, LiuRenRelation.SAME, LiuRenRelation.GENERATES, LiuRenRelation.CONTROLS),
            LiuRenElement.METAL to listOf(LiuRenRelation.CONTROLS, LiuRenRelation.CONTROLLED_BY, LiuRenRelation.GENERATED_BY, LiuRenRelation.SAME, LiuRenRelation.GENERATES),
            LiuRenElement.WATER to listOf(LiuRenRelation.GENERATES, LiuRenRelation.CONTROLS, LiuRenRelation.CONTROLLED_BY, LiuRenRelation.GENERATED_BY, LiuRenRelation.SAME))
        val columns = listOf(LiuRenElement.WOOD, LiuRenElement.FIRE, LiuRenElement.EARTH, LiuRenElement.METAL, LiuRenElement.WATER)
        expected.forEach { (from, row) -> columns.forEachIndexed { index, to ->
            assertEquals("$from → $to", row[index], LiuRenReadingPolicy.relation(from, to))
        } }
        val mapping = mapOf(LiuRenPalace.DA_AN to LiuRenElement.WOOD, LiuRenPalace.LIU_LIAN to LiuRenElement.EARTH,
            LiuRenPalace.SU_XI to LiuRenElement.FIRE, LiuRenPalace.CHI_KOU to LiuRenElement.METAL,
            LiuRenPalace.XIAO_JI to LiuRenElement.WATER, LiuRenPalace.KONG_WANG to LiuRenElement.EARTH)
        mapping.forEach { (palace, element) -> assertEquals(element, LiuRenReadingPolicy.element(palace)) }
        assertEquals(LiuRenRelation.SAME, LiuRenReadingPolicy.relation(LiuRenPalace.LIU_LIAN, LiuRenPalace.KONG_WANG))
        assertEquals(LiuRenRelation.GENERATES, LiuRenReadingPolicy.relation(LiuRenPalace.DA_AN, LiuRenPalace.SU_XI))
        assertEquals(LiuRenRelation.GENERATED_BY, LiuRenReadingPolicy.relation(LiuRenPalace.SU_XI, LiuRenPalace.DA_AN))
    }

    @Test fun theSameCastGetsDifferentStageFocusesForDifferentQuestions() {
        val preparation = LiuRenReadingPolicy.local(fixture())
        val meetupCast = fixture("周五和朋友见面，对方还没确认地点，我该怎样安排？")
        val meetup = LiuRenReadingPolicy.local(meetupCast)
        assertEquals(preparation.stages.map { it.palace }, meetup.stages.map { it.palace })
        assertNotEquals(preparation.summary, meetup.summary)
        preparation.stages.zip(meetup.stages).forEach { (a, b) -> assertNotEquals(a.text, b.text) }
        assertTrue(meetup.stages[0].text.contains("邀约"))
        assertTrue(meetup.stages[1].text.contains("地点"))
        assertTrue(meetup.stages[2].text.contains("约定"))
        assertEquals(meetup, accepted(meetup, meetupCast))
    }

    @Test fun unknownTopicsStillNeedTheirOwnTargetInsteadOfBypassingQuestionGrounding() {
        val cast = fixture("阳台的小番茄能不能开花？")
        val reading = LiuRenReadingPolicy.local(cast)
        assertEquals(reading, accepted(reading, cast))
        val generic = "让念头在窗边坐一坐，照顾好自己，保持微笑慢慢等待，一切都值得温柔相待。"
        assertNull(accepted(reading.copy(stages = reading.stages.map { it.copy(text = generic) }), cast))
        assertNull(accepted(reading.copy(summary = generic, advice = generic), cast))
    }

    @Test fun relationMustMatchTheActualAdjacentPairIncludingItsDirection() {
        val cast = fixture(); val correct = LiuRenReadingPolicy.local(cast)
        assertEquals(LiuRenRelation.GENERATES, correct.links[0].relation)
        assertEquals(LiuRenRelation.CONTROLLED_BY, correct.links[1].relation)
        assertNull(accepted(correct.copy(links = correct.links.mapIndexed { i, link ->
            if (i == 0) link.copy(relation = LiuRenRelation.GENERATED_BY) else link }), cast))
        assertNull(accepted(correct.copy(links = correct.links.mapIndexed { i, link ->
            if (i == 1) link.copy(relation = LiuRenRelation.CONTROLS) else link }), cast))
        val source = Json.parseToJsonElement(LiuRenReadingPolicy.encode(correct)).jsonObject
        val links = source["links"]!!.jsonArray
        val missing = JsonObject(source + ("links" to JsonArray(listOf(JsonObject(links[0].jsonObject - "relation"), links[1]))))
        assertNull(LiuRenReadingPolicy.decode(missing, cast))
        val unknown = JsonObject(source + ("links" to JsonArray(listOf(JsonObject(links[0].jsonObject +
            ("relation" to JsonPrimitive("AUGMENT"))), links[1]))))
        assertNull(LiuRenReadingPolicy.decode(unknown, cast))
    }

    @Test fun unrelatedQuestionsWrongStageOrderMissingStagesAndNonadjacentLinksAreRejected() {
        val cast = fixture(); val correct = LiuRenReadingPolicy.local(cast)
        assertNull(accepted(correct.copy(question = "完全不相干的另一问"), cast))
        assertNull(accepted(correct.copy(question = ""), cast))
        assertNull(accepted(correct.copy(stages = correct.stages.take(2)), cast))
        assertNull(accepted(correct.copy(stages = correct.stages.reversed()), cast))
        assertNull(accepted(correct.copy(stages = correct.stages.mapIndexed { i, stage -> if (i == 1) stage.copy(palace = "赤口") else stage }), cast))
        assertNull(accepted(correct.copy(links = correct.links.take(1)), cast))
        assertNull(accepted(correct.copy(links = correct.links.mapIndexed { i, link -> if (i == 0) link.copy(to = "小吉") else link }), cast))
    }

    @Test fun mentioningTheCorrectPalacesWithoutAnsweringTheQuestionIsNotAValidReading() {
        val cast = fixture(); val correct = LiuRenReadingPolicy.local(cast)
        val generic = "让念头在窗边坐一坐，照顾好自己，保持微笑慢慢等待，一切都值得温柔相待。"
        assertNull(accepted(correct.copy(stages = correct.stages.map { it.copy(text = generic) }), cast))
        assertNull(accepted(correct.copy(summary = generic), cast))
        assertNull(accepted(correct.copy(advice = generic), cast))
        assertNull(accepted(correct.copy(stages = correct.stages.mapIndexed { i, stage -> if (i == 1) stage.copy(text = generic) else stage }), cast))
    }

    @Test fun outcomeGuaranteesInAnyPartAreRejectedWhileCautiousNegationIsAllowed() {
        val cast = fixture(); val correct = LiuRenReadingPolicy.local(cast)
        listOf("保证拿到录用", "一定会成功", "百分之百拿到录用", "注定被录用", "肯定会通过面试").forEach { promise ->
            assertNull(promise, accepted(correct.copy(summary = correct.summary + promise), cast))
        }
        assertNull(accepted(correct.copy(stages = correct.stages.mapIndexed { i, stage ->
            if (i == 0) stage.copy(text = stage.text + "阿噜包你通过面试。") else stage }), cast))
        assertNull(accepted(correct.copy(links = correct.links.mapIndexed { i, link ->
            if (i == 0) link.copy(text = link.text + "你必定被录用。") else link }), cast))
        listOf("但不能保证录用结果。", "阿噜不保证录用结果。", "这不意味着一定会通过面试。", "不一定会成功，录用还要看实际反馈。").forEach { caution ->
            val cautious = correct.copy(advice = correct.advice + caution)
            assertEquals(caution, cautious, accepted(cautious, cast))
        }
    }

    @Test fun sensitiveQuestionsRemainNonpredictiveEvenWhenTheUserAsksForAProhibitedOutcome() {
        listOf("手术能治愈吗？", "这只股票会涨吗？", "这场诉讼能胜诉吗？", "手术后能恢复工作吗？").forEach { question ->
            val cast = fixture(question)
            val reading = LiuRenReadingPolicy.local(cast)
            assertTrue(question, reading.summary.contains("不能"))
            assertTrue(question, reading.advice.contains("专业"))
            assertEquals(question, reading, accepted(reading, cast))
        }
        val cast = fixture("我想了解病情，应当核实什么？")
        val correct = LiuRenReadingPolicy.local(cast)
        assertNull(accepted(correct.copy(summary = correct.summary + "不用就医，会痊愈。"), cast))
        val money = fixture("我的股票投资该核实什么？")
        val finance = LiuRenReadingPolicy.local(money)
        assertNull(accepted(finance.copy(advice = finance.advice + "照此投资能盈利。"), money))
        val legal = fixture("这场诉讼应当核实什么？")
        val lawsuit = LiuRenReadingPolicy.local(legal)
        assertNull(accepted(lawsuit.copy(advice = lawsuit.advice + "按照盘结果你会胜诉。"), legal))
    }

    @Test fun malformedAndIncompleteReadingDataCannotBecomeAStoredRemoteResult() {
        val cast = fixture(); val correct = LiuRenReadingPolicy.local(cast)
        assertNull(LiuRenReadingPolicy.decode("", cast))
        assertNull(LiuRenReadingPolicy.decode("not-json", cast))
        assertNull(LiuRenReadingPolicy.decode("{}", cast))
        assertNull(LiuRenReadingPolicy.decode("[]", cast))
        assertNull(accepted(correct.copy(summary = "小吉"), cast))
        assertNull(accepted(correct.copy(stages = correct.stages.map { it.copy(text = "顺利") }), cast))
        assertNull(accepted(correct.copy(advice = "加油"), cast))
        val source = Json.parseToJsonElement(LiuRenReadingPolicy.encode(correct)).jsonObject
        val oversized = JsonObject(source + ("extra" to JsonPrimitive("x".repeat(LiuRenReadingPolicy.MAX_STORED_CHARS))))
        assertNull(LiuRenReadingPolicy.decode(oversized, cast))
        assertEquals(correct, LiuRenReadingPolicy.decode(source, cast))
    }
}
