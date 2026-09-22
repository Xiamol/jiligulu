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
    version = 5,
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

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        /** 当前 schema 版本，供迁移测试断言用（避免测试里散落魔法数字）。 */
        const val SCHEMA_VERSION = 5

        /** PRD §3.2：内置默认分类只有每日必需 eating / drinking */
        private val DEFAULT_CATEGORIES = listOf(
            CategorySeed(
                name = "eating",
                icon = "🍚",
                keywords = "吃,饭,面,粉,早餐,午饭,晚饭,外卖,火锅,干锅,小炒,食堂,餐,烧烤"
            ),
            CategorySeed(
                name = "drinking",
                icon = "🥤",
                keywords = "喝,水,奶茶,咖啡,饮料,可乐,茶,酒,矿泉水,果汁"
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
                    "INSERT INTO categories (name, iconType, iconValue, iconSvg, colorHue, colorIndex, createdBy, keywords) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(seed.name, IconType.EMOJI.name, seed.icon, "",
                        GoldenAnglePalette.hueFor(index), index, CreatedBy.DEFAULT.name, seed.keywords)
                )
            }
        }
    }
}

private data class CategorySeed(val name: String, val icon: String, val keywords: String)
