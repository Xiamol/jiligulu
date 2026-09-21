package com.jiligulu.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 预算周期（PRD v0.3：1 天 / 7 天 / 月度） */
enum class BudgetPeriod { DAILY, WEEKLY, MONTHLY }

/**
 * 预算：M3 只有一个总预算（单行表，id 恒为 1）。
 * amountFen 可为负（PRD 已拍板）。
 * anchorDay：MONTHLY 时=每月几号重置（1~28，避开月末天数坑）；其余周期不用。
 * WEEKLY 以 updatedAt 所在日为锚点，每 7 天滚动重置。
 */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: Long = 1L,
    val amountFen: Long,
    val periodType: BudgetPeriod,
    val anchorDay: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)
