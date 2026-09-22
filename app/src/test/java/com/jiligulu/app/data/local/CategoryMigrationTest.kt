package com.jiligulu.app.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.domain.category.CategoryDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v5 → v6 迁移（R1/R2）专门用例，跑真 SQLite。
 *
 * 迁移里有几处**顺序敏感**的动作（先并账单、再删重复分类、最后改名），
 * 「分类表没有唯一约束」意味着写错顺序时数据库**不会报任何错**，只会安静地留下坏数据。
 * 所以这里用真库把「无重复分类名 / 无英文名 / 账单总数不变 / 回收站账单也换挂点」逐条钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CategoryMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableSetOf<String>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    /** 仅有旧英文种子 → 迁移后为中文 + 内置「其他」，账单总数不变、挂点跟着改名走。 */
    @Test
    fun englishSeedsBecomeChineseAndVacuumIsAdded() = runBlocking {
        createV5("mig-english.db").use { db ->
            db.insertCategory(1, "eating", keywords = "吃,饭")
            db.insertCategory(2, "drinking", keywords = "喝,水")
            db.insertBill(1, 1)
            db.insertBill(2, 2)
        }
        val migrated = openMigrated("mig-english.db")
        val categories = migrated.categoryDao().findAllOnce()

        assertEquals(setOf("吃饭", "饮品", "其他"), categories.map { it.name }.toSet())
        assertTrue(categories.none { it.name == "eating" || it.name == "drinking" })

        val vacuum = categories.single { it.name == CategoryDefaults.VACUUM_NAME }
        assertFalse("收纳箱不可删", vacuum.deletable)
        // 吃饭 / 饮品仍然可删。
        assertTrue(categories.filter { it.name == "吃饭" || it.name == "饮品" }.all { it.deletable })

        // 挂点随改名迁移：id 1/2 还在，只是名字换了。
        val live = migrated.billDao().observeAll().first()
        assertEquals(2, live.size)
        assertEquals(1L, live.single { it.id == 1L }.categoryId)
        assertEquals(2L, live.single { it.id == 2L }.categoryId)
        assertNotNull(categories.firstOrNull { it.id == 1L && it.name == "吃饭" })
        assertNotNull(categories.firstOrNull { it.id == 2L && it.name == "饮品" })

        // deletable 列确实落进了物理表（不只是实体映射）。
        assertTrue(rawColumns("mig-english.db", "categories").contains("deletable"))
    }

    /** 同时存在英文种子与 AI 建的「吃饭」→ 并账单、删重复、改名，且**无重名**。 */
    @Test
    fun englishSeedAndChineseDuplicateMergeIntoOne() = runBlocking {
        createV5("mig-dup.db").use { db ->
            db.insertCategory(1, "eating", keywords = "吃")
            db.insertCategory(5, "吃饭", createdBy = "AI")
            db.insertBill(1, 1)                       // 挂在种子行
            db.insertBill(2, 5)                       // 活账单，挂在重复行
            db.insertBill(3, 5, deletedAt = 500L)     // 回收站账单，也挂在重复行
        }
        val migrated = openMigrated("mig-dup.db")
        val categories = migrated.categoryDao().findAllOnce()

        assertEquals(1, categories.count { it.name == "吃饭" })
        assertTrue(categories.none { it.name == "eating" })
        // 无重复分类名（无唯一约束，靠迁移自己保证）。
        assertEquals(categories.size, categories.map { it.name }.toSet().size)

        val live = migrated.billDao().observeAll().first()
        val trash = migrated.billDao().getTrash()
        // 活账单与回收站账单都并进保留下来的种子行（回收站也换挂点，避免悬空）。
        assertEquals(setOf(1L), live.map { it.categoryId }.toSet())
        assertEquals(listOf(3L), trash.map { it.id })
        assertEquals(setOf(1L), trash.map { it.categoryId }.toSet())
        // 账单总数不变（防丢数据）。
        assertEquals(3, live.size + trash.size)
    }

    /** 仅中文行存在（老库种子早被改过名）→ 幂等、不报错、id 不变。 */
    @Test
    fun renamedSeedAloneIsIdempotent() = runBlocking {
        createV5("mig-renamed.db").use { db ->
            db.insertCategory(3, "吃饭")
            db.insertBill(1, 3)
        }
        val migrated = openMigrated("mig-renamed.db")
        val categories = migrated.categoryDao().findAllOnce()

        assertEquals(1, categories.count { it.name == "吃饭" })
        assertEquals(3L, categories.single { it.name == "吃饭" }.id)
        assertEquals(setOf("吃饭", "其他"), categories.map { it.name }.toSet())
        assertEquals(listOf(3L), migrated.billDao().observeAll().first().map { it.categoryId })
    }

    /** 「其他」已存在且有多条 → 收敛为一条、id 取 MIN、归 DEFAULT、不可删，账单并过来。 */
    @Test
    fun existingVacuumRowsConvergeToOneNonDeletable() = runBlocking {
        createV5("mig-vacuum.db").use { db ->
            db.insertCategory(2, CategoryDefaults.VACUUM_NAME, createdBy = "AI")
            db.insertCategory(7, CategoryDefaults.VACUUM_NAME, createdBy = "AI")
            db.insertBill(1, 7)
        }
        val migrated = openMigrated("mig-vacuum.db")
        val categories = migrated.categoryDao().findAllOnce()

        assertEquals(1, categories.count { it.name == CategoryDefaults.VACUUM_NAME })
        val vacuum = categories.single { it.name == CategoryDefaults.VACUUM_NAME }
        assertEquals(2L, vacuum.id)
        assertEquals(CreatedBy.DEFAULT, vacuum.createdBy)
        assertFalse(vacuum.deletable)
        assertEquals(listOf(2L), migrated.billDao().observeAll().first().map { it.categoryId })
    }

    // ---------- helpers ----------

    /** 用打包的 v5 schema 建一个历史库，再交给测试塞入自定义分类 / 账单。 */
    private fun createV5(name: String): SQLiteDatabase {
        names += name
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val resource = javaClass.classLoader!!.getResourceAsStream(
            "com.jiligulu.app.data.local.AppDatabase/5.json"
        )
        assertNotNull("Room schema v5 must be packaged as a test resource", resource)
        val schema = JSONObject(resource!!.bufferedReader().use { it.readText() })
            .getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.getJSONArray("indices")
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = 5
        return db
    }

    /** 打开历史库并触发真实迁移；[AppDatabase.builder] 不挂种子回调，避免干扰断言。 */
    private fun openMigrated(name: String): AppDatabase {
        names += name
        val db = AppDatabase.builder(context, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        opened += db
        // 逼 Room 立刻迁移 + 校验 schema（校验不通过会抛 IllegalStateException）。
        runBlocking { db.categoryDao().findAllOnce() }
        return db
    }

    private fun rawColumns(name: String, table: String): List<String> =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY
        ).use { raw ->
            raw.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        }

    private fun SQLiteDatabase.insertCategory(
        id: Long,
        name: String,
        icon: String = "🍚",
        createdBy: String = "DEFAULT",
        keywords: String = ""
    ) {
        execSQL(
            "INSERT INTO categories (id, name, iconType, iconValue, iconSvg, colorHue, colorIndex, createdBy, keywords) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(id, name, "EMOJI", icon, "", 0f, 0, createdBy, keywords)
        )
    }

    private fun SQLiteDatabase.insertBill(id: Long, categoryId: Long, deletedAt: Long? = null) {
        execSQL(
            "INSERT INTO bills (id, amountFen, type, categoryId, detail, note, photoUri, timestamp, source, rawText, deletedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(id, 100L, "EXPENSE", categoryId, "明细", "", null, 1_000L, "MANUAL", "", deletedAt)
        )
    }
}
