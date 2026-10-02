package com.jiligulu.app.domain.budget

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.BudgetEntity
import com.jiligulu.app.data.local.entity.BudgetPeriod
import java.util.Calendar
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 预算实时状态（已用/剩余/超支） */
data class BudgetStatus(
    val budget: BudgetEntity,
    val periodStart: Long,
    val periodEnd: Long,
    val spentFen: Long,
    val remainFen: Long,        // 可为负（超支）
    val overspendRatio: Float   // 超支比例，未超支为 0f；如 0.32 = 超支 32%
)

/**
 * 预算引擎：纯 Kotlin 可单测。
 * 已用金额**不存库**，每次按当前周期内账单实时算——周期一过自动"重置"，
 * 天然满足 PRD「周期结束未修改则自动重置」。
 */
object BudgetEngine {

    /** 当前预算周期 [start, end) */
    fun currentPeriod(budget: BudgetEntity, now: Long = System.currentTimeMillis()): Pair<Long, Long> =
        when (budget.periodType) {
            BudgetPeriod.DAILY -> {
                val zone = ZoneId.systemDefault()
                val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                day.atStartOfDay(zone).toInstant().toEpochMilli() to
                    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            BudgetPeriod.WEEKLY -> {
                // 以预算更新日为锚，每 7 天滚动
                val zone = ZoneId.systemDefault()
                val anchor = Instant.ofEpochMilli(budget.updatedAt).atZone(zone).toLocalDate()
                val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                val elapsed = ChronoUnit.DAYS.between(anchor, day).coerceAtLeast(0)
                val cycles = elapsed / 7
                val start = anchor.plusDays(cycles * 7)
                start.atStartOfDay(zone).toInstant().toEpochMilli() to
                    start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            BudgetPeriod.MONTHLY -> {
                val anchorDay = budget.anchorDay.coerceIn(1, 28)
                val thisAnchor = monthAnchor(now, anchorDay, 0)
                if (now < thisAnchor) {
                    monthAnchor(now, anchorDay, -1) to thisAnchor
                } else {
                    thisAnchor to monthAnchor(now, anchorDay, 1)
                }
            }
        }

    /** 由周期内账单实时计算预算状态 */
    fun statusOf(
        budget: BudgetEntity,
        billsInRange: List<BillEntity>,
        now: Long = System.currentTimeMillis()
    ): BudgetStatus {
        val (start, end) = currentPeriod(budget, now)
        val spent = billsInRange
            .asSequence()
            .filter { it.type == BillType.EXPENSE && it.timestamp >= start && it.timestamp < end }
            .sumOf { it.amountFen }
        val remain = budget.amountFen - spent
        val overspend = if (spent > budget.amountFen && budget.amountFen > 0) {
            (spent - budget.amountFen).toFloat() / budget.amountFen
        } else 0f
        return BudgetStatus(budget, start, end, spent, remain, overspend)
    }

    /** now 所在月偏移 monthOffset 后的 anchorDay 号 0 点 */
    private fun monthAnchor(now: Long, anchorDay: Int, monthOffset: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, anchorDay)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
