package com.jiligulu.app.data.repository

import com.jiligulu.app.data.local.dao.BillDao
import com.jiligulu.app.data.local.dao.BudgetDao
import com.jiligulu.app.data.local.entity.BudgetEntity
import com.jiligulu.app.data.local.entity.BudgetPeriod
import com.jiligulu.app.domain.budget.BudgetEngine
import com.jiligulu.app.domain.budget.BudgetStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 预算仓库：设置/清除预算 + 实时状态（周期内账单 → BudgetEngine 计算） */
class BudgetRepository(
    private val budgetDao: BudgetDao,
    private val billDao: BillDao
) {

    /** 预算实时状态；未设预算发 null（PRD：默认隐藏余粮环） */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatus(): Flow<BudgetStatus?> =
        budgetDao.observe().flatMapLatest { budget ->
            if (budget == null) {
                flowOf(null)
            } else {
                // 只查当前周期的账单，范围最小化
                val (start, end) = BudgetEngine.currentPeriod(budget)
                billDao.observeBetween(start, end).map { bills ->
                    BudgetEngine.statusOf(budget, bills)
                }
            }
        }

    suspend fun setBudget(amountFen: Long, period: BudgetPeriod, anchorDay: Int = 1) {
        budgetDao.upsert(
            BudgetEntity(
                amountFen = amountFen,
                periodType = period,
                anchorDay = anchorDay.coerceIn(1, 28)
            )
        )
    }

    suspend fun clearBudget() = budgetDao.clear()
}
