package com.jiligulu.app.data.repository

import android.app.Application
import android.content.Context
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.domain.category.CategoryDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * R2 删除分类 + 转归属的原子性，跑真 SQLite。
 *
 * 重点不是「顺路成功」，而是两条边界：回收站账单**保持原挂点**（运行期规则），
 * 以及「转归属成功但删行失败」时**整体回滚**——半截的账本比报错更可怕。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CategoryAdminRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableSetOf<String>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    private fun open(name: String): AppDatabase {
        names += name
        return AppDatabase.build(context, name).also { opened += it }
    }

    @Test
    fun deletingACategoryReassignsItsLiveBillsToVacuum() = runBlocking {
        val db = open("cat-admin.db")
        val categories = CategoryRepository(db.categoryDao())
        val catId = categories.createCategory("夜宵", createdBy = CreatedBy.USER)
        val bills = BillRepository(db.billDao())
        bills.addManual(100, BillType.EXPENSE, catId, "烤串", "")
        bills.addManual(200, BillType.EXPENSE, catId, "啤酒", "")
        val trashed = bills.addManual(300, BillType.EXPENSE, catId, "扔掉的", "")
        db.billDao().moveToTrash(trashed, 1_000L)

        val admin = CategoryAdminRepository(db)
        val vacuumId = admin.vacuumId()
        val result = admin.deleteCategoryAndReassign(catId)

        assertTrue(result is CategoryDeletionResult.Deleted)
        assertEquals(2, (result as CategoryDeletionResult.Deleted).reassigned)

        // 活账单全部改挂「待定」，账单总数不变。
        assertEquals(setOf(vacuumId), db.billDao().observeAll().first().map { it.categoryId }.toSet())
        assertEquals(3, db.billDao().observeAll().first().size + db.billDao().getTrash().size)
        // 分类已删除。
        assertNull(db.categoryDao().findByName("夜宵"))
        // 运行期规则：回收站账单保持原 categoryId（恢复时再兜底到「待定」）。
        assertEquals(catId, db.billDao().getTrash().single().categoryId)
    }

    @Test
    fun deletingTheVacuumIsRefusedAndLeavesTheDatabaseUnchanged() = runBlocking {
        val db = open("cat-vacuum.db")
        val admin = CategoryAdminRepository(db)
        val vacuumId = admin.vacuumId()
        val bill = BillRepository(db.billDao())
            .addManual(500, BillType.EXPENSE, vacuumId, "归到收纳箱", "")
        val categoriesBefore = db.categoryDao().count()

        val result = admin.deleteCategoryAndReassign(vacuumId)

        assertTrue(result is CategoryDeletionResult.Refused)
        assertEquals(categoriesBefore, db.categoryDao().count())
        assertNotNull(db.categoryDao().findByName(CategoryDefaults.VACUUM_NAME))
        assertEquals(vacuumId, db.billDao().getById(bill)?.categoryId)
    }

    @Test
    fun aFailureBetweenReassignAndDeleteRollsBothBack() = runBlocking {
        val db = open("cat-rollback.db")
        val categories = CategoryRepository(db.categoryDao())
        val catId = categories.createCategory("会失败", createdBy = CreatedBy.USER)
        val bill = BillRepository(db.billDao())
            .addManual(700, BillType.EXPENSE, catId, "别动我", "")

        // 在「转归属」与「删行」之间注入异常，验证单事务整体回滚。
        val admin = CategoryAdminRepository(db) { throw IllegalStateException("注入的失败") }
        try {
            admin.deleteCategoryAndReassign(catId)
            fail("注入的异常必须冒泡以触发回滚")
        } catch (_: IllegalStateException) {
            // expected
        }

        assertNotNull("分类必须回滚回来", db.categoryDao().findByName("会失败"))
        assertEquals("账单归属必须回滚", catId, db.billDao().getById(bill)?.categoryId)
    }
}
