package com.jiligulu.app.ui.littleworld

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Readable question/answer evaluation bank; no model, key, HTTP, or counting formula. */
class LiuRenQuestionFirstTest {
    private data class Case(val label: String, val question: String, val horizon: String,
        val goalWords: List<String>, val answer: String, val reason: String, val advice: String)

    private val cases = listOf(
        Case("年度新恋情", "我今年能谈到女朋友吗？桃花如何？", "今年", listOf("恋爱", "桃花", "伴侣", "确定关系"),
            "今年并非没有桃花，有相识机会，但稳定谈成恋爱偏慢；能否确定关系还要看双方后续回应。",
            "两头留连、中间小吉，说明前后容易犹豫或反复，中途有接近人的机会；相识与确定恋爱关系不能当成同一步。",
            "多去能持续认识人的活动，聊得来后表达兴趣，观察彼此是否愿意继续接触。"),
        Case("复合", "分手两个月了，这个月能复合吗？", "这个月", listOf("复合", "恢复", "重新确认"),
            "这个月复合偏慢；可以尝试恢复联系，但有回应不等于已经和好，原来的问题仍需磨合。",
            "两头留连提示旧问题前后仍会反复，中间小吉留有恢复联系的空间；一时能聊起来，还不是重新确认关系。",
            "先想清楚分开的原因是否有改变，再尝试一次不施压的联系，看对方的实际回应。"),
        Case("普通见面", "周五约普通朋友吃饭，对方还没确认，能约成吗？", "周五", listOf("见面", "约成", "约定"),
            "周五见面有商量成的余地，但约定仍容易变化；以对方明确确认时间地点为准，保留替代安排。",
            "两头留连使邀约前后容易等待，中间小吉给一次沟通推进；商量顺利不等于周五的安排已经确定。",
            "把日期、地点和备选时段一次说清，请朋友确认后再落实预订。"),
        Case("面试", "笔试已过，明天终面能拿offer吗？", "明天", listOf("录用", "面试", "offer"),
            "明天终面有推进空间，但拿到明确录用还不算稳；正面反馈先别当成正式offer。",
            "两头留连提示终面前后的确认可能反复，中间小吉保留沟通推进；笔试已过是已有条件，下一步仍需正式通知。",
            "准备两段能说明能力的经历，问清终面后的通知方式与后续流程。"),
        Case("考试", "下周资格考试能过吗？我只复习了一半。", "下周", listOf("考试", "通过", "成绩"),
            "下周通过考试还不算稳，更受剩余准备与现场发挥牵制；先补未掌握部分，别只靠临场运气。",
            "两头留连提示准备和收尾容易反复，中间小吉留有发挥空间；仅复习一半这个条件仍在，不能跳过剩余内容。",
            "先做一套限时练习定位薄弱处，把剩下复习时间集中给最容易失分的内容。"),
        Case("失物", "昨天把耳机落在公交车，这周能找回来吗？", "这周", listOf("找回", "失物", "耳机"),
            "这周找回耳机还有线索机会，但从线索到真正拿回可能反复；别把猜测或相似物品当成已经找到。",
            "两头留连提示寻找前后仍需核实，中间小吉留有新线索；公交车是你给的最后位置，先沿它确认，不另猜方位。",
            "联系对应车辆及失物招领，核对耳机特征和遗失时段，拿到实物后再确认。"),
        Case("带聚会终点的出行", "明天高铁转公交，能准时赶上晚上的聚会吗？", "明天", listOf("赶上", "行程", "换乘", "交通"),
            "明天能否赶上晚场更取决于最后一段换乘余量；中途有接续机会，收尾仍易拖慢，先备替代交通。",
            "两头留连提示出发和抵达都容易耽搁，中间小吉保留接续余地；高铁转公交能推进，仍不等于已赶上晚场。",
            "核对到站与公交间隔，给最后一段留余量，并准备能执行的备选交通。"),
        Case("未知具体目标", "阳台番茄这个月能开花吗？已经长叶没花苞。", "这个月", listOf("番茄", "开花", "花苞"),
            "这个月番茄开花更偏向需要继续等待；已有长叶进展，但无花苞的条件尚未落实到开花，先看真实长势。",
            "两头留连提示开花这件事前后偏慢，中间小吉保留生长进展；长叶与长出花苞是不同阶段，不能直接等同。",
            "结合品种、光照和实际长势持续观察，记录花苞变化，不凭盘断定生长结果。")
    )

    private fun fixture(question: String, digits: String = "840") = LiuRenCast(question, LiuRenMode.NUMBERS,
        Instant.parse("2026-10-09T06:00:00Z").toEpochMilli(), "Asia/Shanghai", 8, 29, 8, digits = digits)
    private fun remote(case: Case) = buildJsonObject {
        put("answer", case.answer); put("reason", case.reason); put("advice", case.advice)
    }
    private fun decode(data: JsonObject, question: String = cases.first().question) =
        LiuRenReadingPolicy.decodeRemote(data, fixture(question))

    @Test fun theOriginal840AnnualGirlfriendQuestionIsRomanticAndNotAnInvitation() {
        val case = cases.first(); val cast = fixture(case.question)
        assertEquals(listOf("留连", "小吉", "留连"), LiuRenReadingPolicy.palaces(cast).map { it.title })
        assertEquals("恋爱与桃花", LiuRenReadingPolicy.topic(case.question).name)
        val local = LiuRenReadingPolicy.local(cast)
        assertTrue(local.summary.contains("今年"))
        assertTrue(case.goalWords.any(local.summary::contains))
        assertFalse(local.summary.contains("见面")); assertFalse(local.summary.contains("邀约"))
        assertTrue(local.reason.contains("留连") && local.reason.contains("小吉"))
        assertTrue(local.reason.contains("两头") || local.reason.contains("前后") || local.reason.contains("首尾"))
        assertNotNull(LiuRenReadingPolicy.decode(LiuRenReadingPolicy.encode(local), cast))
    }

    @Test fun allEightLocalFirstScreensAnswerTheirTargetDeadlineAndKeepAShortThreePalaceReason() {
        cases.forEach { case ->
            val cast = fixture(case.question); val reading = LiuRenReadingPolicy.local(cast)
            assertTrue(case.label + ": target deadline missing from answer", reading.summary.contains(case.horizon))
            assertTrue(case.label + ": answer is not about the goal", case.goalWords.any(reading.summary::contains))
            assertTrue(case.label, reading.summary.length in 6..140)
            assertTrue(case.label, reading.reason.length in 10..180)
            assertTrue(case.label, reading.reason.contains("留连") && reading.reason.contains("小吉"))
            assertEquals(case.question, reading.question)
            assertEquals(case.label, reading, LiuRenReadingPolicy.decode(LiuRenReadingPolicy.encode(reading), cast))
        }
        val interview = LiuRenReadingPolicy.local(fixture(cases[3].question))
        assertFalse((interview.summary + interview.reason).contains("尚未笔试"))
        val exam = LiuRenReadingPolicy.local(fixture(cases[4].question))
        assertFalse((exam.summary + exam.reason).contains("准备已经扎实"))
        val missing = LiuRenReadingPolicy.local(fixture(cases[5].question))
        assertTrue(missing.summary.contains("这周")); assertFalse(missing.summary.startsWith("昨天"))
    }

    @Test fun readableRemoteAnswersNeedOnlyThreeStringFieldsAndKeepTheLocalCourse() {
        cases.forEach { case ->
            val cast = fixture(case.question)
            val result = requireNotNull(LiuRenReadingPolicy.decodeRemote(remote(case), cast)) { case.label }
            assertEquals(case.answer, result.summary); assertEquals(case.reason, result.reason); assertEquals(case.advice, result.advice)
            assertEquals(case.question, result.question)
            val local = LiuRenReadingPolicy.local(cast)
            assertEquals(local.stages, result.stages); assertEquals(local.links, result.links)
            assertEquals(listOf("留连", "小吉", "留连"), result.stages.map { it.palace })
            assertEquals(result, LiuRenReadingPolicy.decode(LiuRenReadingPolicy.encode(result), cast))
        }
    }

    @Test fun ordinaryFriendsRomanticPartnersReconciliationAndTravelDestinationsAreDistinctGoals() {
        assertEquals("恋爱与桃花", LiuRenReadingPolicy.topic("我今年能谈到女朋友吗？桃花如何？").name)
        assertEquals("恢复这段感情", LiuRenReadingPolicy.topic("我和前女友这个月还能复合吗？").name)
        assertEquals("这次见面安排", LiuRenReadingPolicy.topic("周五约普通朋友吃饭能约成吗？").name)
        assertEquals("这次出行", LiuRenReadingPolicy.topic("明天坐高铁去见普通朋友，能赶上晚上的聚会吗？").name)
        assertEquals("这次面试", LiuRenReadingPolicy.topic("我的女朋友明天面试能拿offer吗？").name)
        assertEquals("这次考试", LiuRenReadingPolicy.topic("女朋友下周的考试能通过吗？").name)
    }

    @Test fun naturalRomanticWordingAndAThreeStepReasonDoNotNeedRepeatedStageKeywordsOrEnums() {
        val natural = buildJsonObject {
            put("answer", "今年有机会建立伴侣关系，但进度偏慢；先看双方是否愿意持续接触，再判断能否稳定下来。")
            put("reason", "起初可能犹豫，中途有接近人的进展，收尾仍容易反复；一时有回应和真正走到一起是两回事。")
            put("advice", "去能反复接触同一群人的活动，聊得来后表达好感，再观察彼此是否愿意继续。")
        }
        val result = requireNotNull(decode(natural))
        assertEquals(listOf("留连", "小吉", "留连"), result.stages.map { it.palace })
        assertEquals(listOf(LiuRenRelation.CONTROLS, LiuRenRelation.CONTROLLED_BY), result.links.map { it.relation })
        assertFalse(result.reason.contains("留连")); assertFalse(result.reason.contains("小吉"))
    }

    @Test fun mismatchedGoalsEmptyComfortFinalPalaceOnlyAndGuaranteedOutcomesAreRejected() {
        val case = cases.first()
        val bland = "保持微笑，照顾好自己，先做一小步，阿噜会陪着你慢慢等待。"
        assertNull(decode(remote(case) + ("answer" to JsonPrimitive(bland))))
        assertNull(decode(remote(case) + ("reason" to JsonPrimitive("末宫留连说明今年要慢一点，这是关于恋爱的全部解释。"))))
        val mismatched = remote(case) + ("answer" to JsonPrimitive("今年和普通朋友约饭有商量成的余地，把时间地点说清就好。")) +
            ("reason" to JsonPrimitive("两头留连、中间小吉，商量地点前后仍有等待，中途能推进一次吃饭邀约。"))
        assertNull(decode(JsonObject(mismatched))) // A relevant advice line cannot rescue the wrong main answer.
        assertNull(decode(remote(case) + ("answer" to JsonPrimitive("今年肯定会谈到女朋友，桃花会变成稳定恋爱，阿噜保证你成功。"))))
        assertNull(decode(remote(case) + ("reason" to JsonPrimitive("大安到速喜再到小吉，说明感情会从稳定转向确定。"))))
    }

    @Test fun aMalformedRemoteFieldCannotBecomeTextAndExtraActionsCannotRewriteComputedDetails() {
        val case = cases.first(); val good = remote(case)
        listOf("answer", "reason", "advice").forEach { field ->
            assertNull(field, decode(JsonObject(good - field)))
            assertNull(field, decode(JsonObject(good + (field to JsonPrimitive("")))))
        }
        assertNull(decode(JsonObject(good + ("answer" to JsonPrimitive(1234567890)))))
        assertNull(decode(JsonObject(good + ("reason" to JsonPrimitive(123456789012345)))))
        assertNull(decode(JsonObject(good + ("advice" to JsonPrimitive(1234567890)))))
        val extras = JsonObject(good + ("question" to JsonPrimitive("改成另一问")) +
            ("stages" to JsonPrimitive("大安速喜小吉")) + ("navigate" to JsonPrimitive("settings")))
        val result = requireNotNull(decode(extras))
        assertEquals(case.question, result.question)
        assertEquals(listOf("留连", "小吉", "留连"), result.stages.map { it.palace })
    }
}

private operator fun JsonObject.plus(entry: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
    JsonObject(this.toMap() + entry)
