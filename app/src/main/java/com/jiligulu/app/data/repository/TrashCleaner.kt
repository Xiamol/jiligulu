package com.jiligulu.app.data.repository

import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 回收站过期清理。
 *
 * 刻意**不做** WorkManager 后台任务：清理不是时间敏感的事，
 * 每次冷启动顺手扫一遍就够，还省掉一个后台任务和它的电池开销。
 * 用户在设置里选「永不自动清除」时直接跳过。
 */
object TrashCleaner {
    /** 清理失败不阻断启动；协程取消仍交还生命周期处理。 */
    suspend fun purgeExpired(bills: BillRepository, prefs: UserPrefs): Int =
        try {
            val days = prefs.trashRetentionDays.first()
            if (days <= 0) 0 else bills.purgeExpired(days)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
}
