package com.jiligulu.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jiligulu.app.data.local.dao.BillDao
import com.jiligulu.app.data.local.dao.BudgetDao
import com.jiligulu.app.data.local.dao.CategoryDao
import com.jiligulu.app.data.local.dao.ChatMessageDao
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillSource
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.BudgetEntity
import com.jiligulu.app.data.local.entity.BudgetPeriod
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.data.local.entity.IconType
import com.jiligulu.app.domain.category.CategoryDefaults
import com.jiligulu.app.domain.color.GoldenAnglePalette

/** Room 生成代码（BillDao_Impl 等，位于 data.local.dao 子包）要引用本类，必须 public */
class EnumConverters {
    @TypeConverter fun billTypeToString(v: BillType): String = v.name
    @TypeConverter fun stringToBillType(v: String): BillType = BillType.valueOf(v)
    @TypeConverter fun billSourceToString(v: BillSource): String = v.name
    @TypeConverter fun stringToBillSource(v: String): BillSource = BillSource.valueOf(v)
    @TypeConverter fun iconTypeToString(v: IconType): String = v.name
    @TypeConverter fun stringToIconType(v: String): IconType = IconType.valueOf(v)
    @TypeConverter fun createdByToString(v: CreatedBy): String = v.name
    @TypeConverter fun stringToCreatedBy(v: String): CreatedBy = CreatedBy.valueOf(v)
    @TypeConverter fun budgetPeriodToString(v: BudgetPeriod): String = v.name
    @TypeConverter fun stringToBudgetPeriod(v: String): BudgetPeriod = BudgetPeriod.valueOf(v)
}

@Database(
    entities = [BillEntity::class, CategoryEntity::class, BudgetEntity::class, ChatMessageEntity::class],
    version = 6,
    exportSchema = true
)
@TypeConverters(EnumConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun billDao(): BillDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN iconSvg TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS budgets (id INTEGER NOT NULL, amountFen INTEGER NOT NULL, periodType TEXT NOT NULL, anchorDay INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, rawInput TEXT NOT NULL, draftPayload TEXT NOT NULL, status TEXT NOT NULL, savedCount INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 软删除：null 表示正常账单。已有账单全部视为正常，历史数据不受影响。
                db.execSQL("ALTER TABLE bills ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            }
        }

        /**
         * v5 → v6（R1/R2）：分类中文化 + 合并重复 + 正式补齐内置「待定」收纳箱。
         *
         * 前置事实：分类表**没有唯一约束**（[CategoryDao.findByName] 是精确 `LIMIT 1` 查询）。
         * 因此全流程**不许依赖数据库报错兜底**，凡是「先改名再删重复」这类写法都会在
         * **不报任何错**的情况下留下两条同名记录——静默坏数据，日后极难排查。
         * 所以顺序被钉死为：**先并账单 → 再删重复行 → 最后改名**（见 [renameCategoryMergingDuplicates]）。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Step 0：本次唯一 DDL。加列后所有既有分类 deletable=1（可删），
                // 稍后 Step 2 再把收纳箱「待定」置回 0。
                db.execSQL("ALTER TABLE categories ADD COLUMN deletable INTEGER NOT NULL DEFAULT 1")

                // Step 1：把旧英文种子分类中文化，并合并 AI 已经建过的同名中文分类。
                renameCategoryMergingDuplicates(db, legacyName = "eating", localizedName = "吃饭")
                renameCategoryMergingDuplicates(db, legacyName = "drinking", localizedName = "饮品")

                // Step 2：内置「待定」补齐 / 收敛为一条且不可删。
                ensureVacuumCategory(db)

                // Step 3：R8 图标兜底清理——旧的泡泡占位改为空串（渲染层走首字徽章）。
                db.execSQL("UPDATE categories SET iconValue = '' WHERE iconValue = '🫧'")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
        )

        /** 当前 schema 版本，供迁移测试断言用（避免测试里散落魔法数字）。 */
        const val SCHEMA_VERSION = 6

        /** v0.6 内置种子：吃饭 / 饮品（可删）+ 待定（收纳箱，不可删）。 */
        private val DEFAULT_CATEGORIES = listOf(
            CategorySeed(
                name = "吃饭",
                icon = "🍚",
                keywords = "吃,饭,面,粉,早餐,午饭,晚饭,外卖,火锅,干锅,小炒,食堂,餐,烧烤"
            ),
            CategorySeed(
                name = "饮品",
                icon = "🥤",
                keywords = "喝,水,奶茶,咖啡,饮料,可乐,茶,酒,矿泉水,果汁"
            ),
            CategorySeed(
                name = CategoryDefaults.VACUUM_NAME,
                icon = CategoryDefaults.FALLBACK_EMOJI,
                keywords = CategoryDefaults.VACUUM_KEYWORDS,
                deletable = false
            )
        )

        fun build(context: Context, name: String = "jiligulu.db"): AppDatabase =
            builder(context, name)
                // A missing future migration must fail visibly; never erase the user's ledger.
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(object : Callback() {
                    override fun onCreate(connection: SupportSQLiteDatabase) {
                        super.onCreate(connection)
                        // Runs inside SQLite's creation transaction, before the first DAO read.
                        // No coroutine may race database assignment or a user's first entry.
                        seedDefaultCategories(connection)
                    }
                })
                .build()

        /**
         * 只装了 schema、不挂迁移与种子回调的构建器。
         *
         * 给「迁移写对了没有」这类测试用：迁移测试要自己准备历史库、自己决定建哪些表，
         * 这里的 onCreate 回调反而会干扰断言。生产路径请一律走 [build]。
         */
        fun builder(context: Context, name: String): RoomDatabase.Builder<AppDatabase> =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)

        /** 默认分类种子。生产路径在 [build] 的 onCreate 里调；测试可单独复用。 */
        fun seedDefaultCategories(connection: SupportSQLiteDatabase) {
            DEFAULT_CATEGORIES.forEachIndexed { index, seed ->
                connection.execSQL(
                    "INSERT INTO categories (name, iconType, iconValue, iconSvg, colorHue, colorIndex, createdBy, keywords, deletable) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(seed.name, IconType.EMOJI.name, seed.icon, "",
                        GoldenAnglePalette.hueFor(index), index, CreatedBy.DEFAULT.name, seed.keywords,
                        if (seed.deletable) 1 else 0)
                )
            }
        }
    }
}

private data class CategorySeed(
    val name: String,
    val icon: String,
    val keywords: String,
    val deletable: Boolean = true
)

/**
 * Step 1 的核心动作：把旧英文种子分类改名成中文，并合并 AI 已建过的同名中文重复行。
 *
 * **顺序是承重的**：先并账单 → 再删重复行 → 最后才改名。
 * 若反过来先改名，无唯一约束的分类表会安静地留下两条同名记录，数据库不会报任何错。
 *
 * 三种边界都用 Kotlin 判定，不指望 SQL 报错：
 * - 仅英文种子行存在 → 直接改名；
 * - 仅中文行存在（老库种子早被改过名）→ 以中文行为主，改名同值、幂等；
 * - 两者并存且有 N 条中文重复 → 全部并进主行后删除重复行，再改名。
 */
private fun renameCategoryMergingDuplicates(
    db: SupportSQLiteDatabase,
    legacyName: String,
    localizedName: String
) {
    val legacySeedId = db.queryFirstId("SELECT MIN(id) FROM categories WHERE name = ?", arrayOf(legacyName))
    val localizedId = db.queryFirstId("SELECT MIN(id) FROM categories WHERE name = ?", arrayOf(localizedName))
    // 两行都不存在 → 这个库里根本没有对应分类（例如只有无关自定义分类），无需处理。
    val mainId = legacySeedId ?: localizedId ?: return

    // 重复行必须在删除前一次性查出，避免「边删边查」时集合发生位移。
    val duplicateIds = db.queryIds(
        "SELECT id FROM categories WHERE name = ? AND id <> ?",
        arrayOf(localizedName, mainId)
    )
    reassignBillsThenDeleteCategories(db, mainId, duplicateIds)

    // 只有「保留下来的主行还是旧英文名」时才需要改名；中文行本来就是终态。
    if (legacySeedId != null) {
        db.execSQL("UPDATE categories SET name = ? WHERE id = ?", arrayOf(localizedName, mainId))
    }
}

/**
 * Step 2：保证内置「待定」**恰好存在一条**且不可删。
 *
 * - 不存在 → 直接插入（createdBy=DEFAULT、deletable=0、黄金角取色、图标留空走徽章）；
 * - 已存在（AI 建过，可能多条）→ 取 MIN(id) 为主行，其余先并账单再删行，最后把主行归正。
 */
private fun ensureVacuumCategory(db: SupportSQLiteDatabase) {
    val canonicalId = db.queryFirstId(
        "SELECT MIN(id) FROM categories WHERE name = ?",
        arrayOf(CategoryDefaults.VACUUM_NAME)
    )
    if (canonicalId == null) {
        val index = db.queryCount("SELECT COUNT(*) FROM categories")
        db.execSQL(
            "INSERT INTO categories (name, iconType, iconValue, iconSvg, colorHue, colorIndex, createdBy, keywords, deletable) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
            arrayOf(CategoryDefaults.VACUUM_NAME, IconType.EMOJI.name, CategoryDefaults.FALLBACK_EMOJI, "",
                GoldenAnglePalette.hueFor(index), index, CreatedBy.DEFAULT.name, CategoryDefaults.VACUUM_KEYWORDS)
        )
        return
    }
    val duplicateIds = db.queryIds(
        "SELECT id FROM categories WHERE name = ? AND id <> ?",
        arrayOf(CategoryDefaults.VACUUM_NAME, canonicalId)
    )
    reassignBillsThenDeleteCategories(db, canonicalId, duplicateIds)
    // 归正主行：来源归 DEFAULT，且锁死为不可删——它现在是收纳箱，不是普通分类。
    db.execSQL(
        "UPDATE categories SET createdBy = ?, deletable = 0 WHERE id = ?",
        arrayOf(CreatedBy.DEFAULT.name, canonicalId)
    )
}

/**
 * 把 [duplicateIds] 下的账单改挂到 [mainId]，然后删掉这些重复分类。
 *
 * **故意不过滤 `deletedAt`**：迁移期删的是**分类行本身**，不是软删除账单。
 * 回收站里的账单若仍指向即将消失的分类，等它被恢复时就会挂到一个不存在的分类上（悬空）。
 * 注意这与**运行期**删分类的规则相反——运行期只转活账单，回收站账单保持原挂点，
 * 等恢复时再由 `restoreToLive` 兜底到「待定」。
 */
private fun reassignBillsThenDeleteCategories(
    db: SupportSQLiteDatabase,
    mainId: Long,
    duplicateIds: List<Long>
) {
    if (duplicateIds.isEmpty()) return
    val placeholders = duplicateIds.joinToString(",") { "?" }
    val duplicateArgs = duplicateIds.map { it.toString() }.toTypedArray()
    db.execSQL(
        "UPDATE bills SET categoryId = ? WHERE categoryId IN ($placeholders)",
        arrayOf(mainId.toString(), *duplicateArgs)
    )
    db.execSQL("DELETE FROM categories WHERE id IN ($placeholders)", duplicateArgs)
}

/** 聚合查询取单个 id；无匹配行时 SQLite 返回 NULL，这里归一为 null。 */
private fun SupportSQLiteDatabase.queryFirstId(sql: String, args: Array<out Any?>): Long? =
    query(sql, args).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }

/** 取一组 id。 */
private fun SupportSQLiteDatabase.queryIds(sql: String, args: Array<out Any?>): List<Long> =
    query(sql, args).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

/** 取单个整数值。 */
private fun SupportSQLiteDatabase.queryCount(sql: String): Int =
    query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
