package com.jiligulu.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BillType { EXPENSE, INCOME }

/** 账单来源：M1 只有 MANUAL；AI_CHAT(M2)、SCREEN(M5) 预留 */
enum class BillSource { MANUAL, AI_CHAT, SCREEN }

/**
 * 账单 —— 对应 PRD §7 Bill。
 * 金额用"分"存储（Long），避免浮点误差。
 */
@Entity(
    tableName = "bills",
    indices = [Index("categoryId"), Index("timestamp")]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountFen: Long,
    val type: BillType,
    val categoryId: Long,
    /** 细则：面/粉/火锅…（大分类之下的具体名目，PRD §3.2 要求绝不丢失） */
    val detail: String = "",
    val note: String = "",
    /** 照片附件（M2 拍照功能预留，M1 恒为 null） */
    val photoUri: String? = null,
    val timestamp: Long,
    val source: BillSource = BillSource.MANUAL,
    /** AI 原始输入（M2 预留） */
    val rawText: String = "",
    /**
     * 软删除标记：null = 正常账单，非 null = 已移入回收站的时刻。
     * 所有面向用户的查询都必须过滤 `deletedAt IS NULL`，否则回收站里的账单会重新冒出来。
     */
    val deletedAt: Long? = null
)
