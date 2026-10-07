package com.jiligulu.app.data.repository

import android.app.Application
import com.jiligulu.app.core.ai.AiLedgerQuery
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.domain.chat.LedgerLookup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LedgerLookupRepositoryTest {
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun db(): AppDatabase {
        val name = "lookup-${names.size}.db"; names += name
        return AppDatabase.build(context, name).also { opened += it }
    }
    @After fun close() { opened.forEach { it.close() }; names.forEach(context::deleteDatabase) }

    @Test fun specificOldDateSearchUsesAllHistoryAndExactCalendarBoundaries() = runBlocking {
        val db = db(); val category = db.categoryDao().findByName("水果")!!.id
        suspend fun add(time: Long, text: String) = db.billDao().insert(BillEntity(amountFen = 100,
            type = BillType.EXPENSE, categoryId = category, detail = text, timestamp = time))
        add(at("2025-01-08") - 1, "边界之前")
        val first = add(at("2025-01-08"), "旧日苹果")
        val last = add(at("2025-01-09") - 1, "旧日香蕉")
        add(at("2025-01-09"), "边界之后")
        repeat(220) { add(at("2026-10-07") + it, "新账单") }
        val found = LedgerLookupRepository(db).search(LedgerLookup.from(
            AiLedgerQuery(startDate = "2025-01-08", endDate = "2025-01-09"), zone))
        assertEquals(setOf(first, last), found.bills.map { it.id }.toSet())
        assertEquals(2, found.count)
        assertEquals(200L, found.groups.sumOf { it.amountFen })
    }

    @Test fun summaryIncludesEveryMatchWhileDetailIsBoundedAndTrashIsExcluded() = runBlocking {
        val db = db(); val fruit = db.categoryDao().findByName("水果")!!.id
        repeat(90) { db.billDao().insert(BillEntity(amountFen = 100, type = BillType.EXPENSE,
            categoryId = fruit, detail = "苹果", timestamp = at("2025-01-08") + it)) }
        db.billDao().insert(BillEntity(amountFen = 9000, type = BillType.INCOME,
            categoryId = fruit, detail = "水果退款", timestamp = at("2025-01-08")))
        val trashed = db.billDao().insert(BillEntity(amountFen = 99999, type = BillType.EXPENSE,
            categoryId = fruit, detail = "不该计入", timestamp = at("2025-01-08")))
        db.billDao().moveToTrash(trashed, 1)
        val found = LedgerLookupRepository(db).search(LedgerLookup())
        assertEquals(91, found.count)
        assertEquals(LedgerLookup.DETAIL_LIMIT, found.bills.size)
        assertEquals(9000L, found.groups.single { it.type == "EXPENSE" }.amountFen)
        assertEquals(9000L, found.groups.single { it.type == "INCOME" }.amountFen)
        assertTrue(found.render(db.categoryDao().findAllOnce(), zone).contains("\"detail_truncated\":true"))
    }

    @Test fun keywordAndCategorySearchTreatsSqlCharactersLiterallyAndDoesNotMatchUnrelatedRawInput() = runBlocking {
        val db = db(); val fruit = db.categoryDao().findByName("水果")!!.id
        val other = db.categoryDao().findByName("饮品")!!.id
        val wanted = db.billDao().insert(BillEntity(amountFen = 450, type = BillType.EXPENSE,
            categoryId = fruit, detail = "O'Reilly苹果 100%", timestamp = at("2024-02-01")))
        db.billDao().insert(BillEntity(amountFen = 500, type = BillType.EXPENSE,
            categoryId = other, detail = "咖啡", rawText = "O'Reilly苹果 100%", timestamp = at("2024-02-01")))
        val repo = LedgerLookupRepository(db)
        assertEquals(listOf(wanted), repo.search(LedgerLookup(keywords = listOf("100%"),
            categories = listOf("水果"), type = "EXPENSE")).bills.map { it.id })
        assertEquals(1, repo.search(LedgerLookup(keywords = listOf("O'Reilly"))).count)
        assertEquals(0, repo.search(LedgerLookup(keywords = listOf("' OR 1=1 --"))).count)
        assertEquals(0, repo.search(LedgerLookup(keywords = listOf("不存在"))).count)
    }

    @Test fun invalidDatesAndTypesAreRejectedRatherThanBroadeningTheSearch() {
        listOf(AiLedgerQuery(startDate = "2026-02-30"), AiLedgerQuery(startDate = "昨天"),
            AiLedgerQuery(startDate = "2026-10-07", endDate = "2026-10-06"),
            AiLedgerQuery(type = "DELETE"), AiLedgerQuery(keywords = listOf("\n秘密"))).forEach { query ->
            try { LedgerLookup.from(query, zone); fail("Invalid lookup must not search all history") }
            catch (_: Exception) { }
        }
    }

    @Test fun dstCalendarDayUsesTheActualLocalBoundaryRatherThanTwentyFourHours() {
        val dst = ZoneId.of("America/New_York")
        val query = LedgerLookup.from(AiLedgerQuery(startDate = "2026-03-08", endDate = "2026-03-09"), dst)
        assertEquals(23 * 3600000L, query.endMillis!! - query.startMillis!!)
    }

    @Test fun displayLabelsAndLegacyNamesResolveEveryMatchingCategoryIdWithoutRenamingRows() = runBlocking {
        val db = db()
        val chinese = db.categoryDao().findByName("吃饭")!!.id
        val legacy = CategoryRepository(db.categoryDao()).createCategory("eating")
        val drinking = db.categoryDao().findByName("饮品")!!.id
        val chineseBill = db.billDao().insert(BillEntity(amountFen = 100, type = BillType.EXPENSE,
            categoryId = chinese, detail = "午饭", timestamp = 123))
        val legacyBill = db.billDao().insert(BillEntity(amountFen = 200, type = BillType.EXPENSE,
            categoryId = legacy, detail = "晚饭", timestamp = 456))
        db.billDao().insert(BillEntity(amountFen = 300, type = BillType.EXPENSE,
            categoryId = drinking, detail = "咖啡", timestamp = 789))
        val repo = LedgerLookupRepository(db)
        val expected = setOf(chineseBill, legacyBill)
        assertEquals(expected, repo.search(LedgerLookup(categories = listOf("吃饭"))).bills.map { it.id }.toSet())
        assertEquals(expected, repo.search(LedgerLookup(categories = listOf("EATING"))).bills.map { it.id }.toSet())
        assertEquals(expected, repo.search(LedgerLookup(keywords = listOf("吃饭"))).bills.map { it.id }.toSet())
        assertEquals(0, repo.search(LedgerLookup(categories = listOf("不存在的分类"))).count)
        assertEquals("eating", db.categoryDao().findByName("eating")!!.name)
    }
}
