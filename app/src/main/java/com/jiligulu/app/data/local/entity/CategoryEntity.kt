package com.jiligulu.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class IconType { SVG, EMOJI, BUILTIN }

enum class CreatedBy { DEFAULT, AI, USER }

/**
 * 分类 —— 对应 PRD §7 Category。
 * M1 只有默认的 eating / drinking；AI 自动新建分类(M2)复用同一结构。
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconType: IconType = IconType.EMOJI,
    /** EMOJI 存 emoji 字符；BUILTIN 存资源名；SVG 类型时为兜底 emoji */
    val iconValue: String = "",
    /** AI 生成的简笔画 SVG（iconType=SVG 时有值，已过白名单校验）；M3 加渲染 */
    val iconSvg: String = "",
    /** 黄金角色相（PRD §5.4）：颜色入库固定，全 App 统一 */
    val colorHue: Float,
    /** 取色序号，超过 12 个分类时用于明度交替 */
    val colorIndex: Int,
    val createdBy: CreatedBy = CreatedBy.DEFAULT,
    /** 本地关键词分类规则（fallback 引擎用），逗号分隔 */
    val keywords: String = ""
)
