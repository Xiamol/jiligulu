package com.jiligulu.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jiligulu.app.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    /** 单行表：直接观察唯一一条预算；未设置时发 null（PRD：未设预算默认隐藏余粮环） */
    @Query("SELECT * FROM budgets WHERE id = 1")
    fun observe(): Flow<BudgetEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = 1")
    suspend fun clear()
}
