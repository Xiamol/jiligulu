package com.jiligulu.app.ui.chat

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChatTimeLabelsTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 10, 6)
    private fun at(day: LocalDate = today, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun startsAtFirstMessageAndAddsOnlyFiveMinuteBreaks() {
        val messages = listOf(ChatItem.UserMsg(1, "hi", at(hour=9, minute=0)),
            ChatItem.GuluMsg(2, "hello", sentAt=at(hour=9, minute=1)),
            ChatItem.UserMsg(3, "next", at(hour=9, minute=5)),
            ChatItem.UserMsg(4, "later", at(hour=9, minute=10)))
        assertEquals(mapOf(1L to "09:00", 4L to "09:10"), ChatTimeLabels.separators(messages, today, zone))
    }
    @Test fun midnightUsesDifferentDayEvenInSameShortConversation() {
        val messages = listOf(ChatItem.UserMsg(1, "night", at(today.minusDays(1),23,59)),
            ChatItem.UserMsg(2, "new day", at(hour=0,minute=0)))
        assertEquals(mapOf(1L to "昨天 23:59", 2L to "00:00"), ChatTimeLabels.separators(messages,today,zone))
    }
    @Test fun labelsUseActualDatesAndKeepHoursForOldHistory() {
        assertEquals("星期日 08:04", ChatTimeLabels.format(at(today.minusDays(2),8,4),today,zone))
        assertEquals("9月6日 08:04", ChatTimeLabels.format(at(today.minusMonths(1),8,4),today,zone))
        assertEquals("2025年10月6日 08:04", ChatTimeLabels.format(at(today.minusYears(1),8,4),today,zone))
    }
    @Test fun loadingBubbleKeepsTimestampAndHiddenRowsDoNotSplitAConversation() {
        val messages = listOf(ChatItem.UserMsg(1,"hi",at(hour=9,minute=0)),
            ChatItem.GuluMsg(2,"", sentAt=at(hour=9,minute=6)),
            ChatItem.GuluMsg(3,"",loading=true,sentAt=at(hour=9,minute=7)))
        assertEquals(mapOf(1L to "09:00",3L to "09:07"),ChatTimeLabels.separators(messages,today,zone))
        val final = messages[2].let { (it as ChatItem.GuluMsg).copy(text="reply",loading=false) }
        assertEquals(messages[2].sentAt,final.sentAt)
    }
    @Test fun prependingOlderPageRetainsBoundaryIdsAndDoesNotRecreateSendTimes() {
        val first = ChatItem.UserMsg(1,"old",at(hour=8,minute=0))
        val latest = ChatItem.UserMsg(2,"new",at(hour=9,minute=0))
        val before = ChatTimeLabels.separators(listOf(latest),today,zone)
        val after = ChatTimeLabels.separators(listOf(first,latest),today,zone)
        assertEquals(before[2],after[2])
        assertEquals("08:00",after[1])
    }
}
