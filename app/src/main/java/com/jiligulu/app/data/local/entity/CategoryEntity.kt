package com.jiligulu.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class IconType { SVG, EMOJI, BUILTIN }

enum class CreatedBy { DEFAULT, AI, USER }

/**
 * 分类 —— 对应 PRD §7 Category。
 * v0.6 起内置种子为「吃饭 / 饮品 / 其他」；AI 自动新建分类复用同一结构。
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconType: IconType = IconType.EMOJI,
    /** EMOJI 存 emoji 字符；BUILTIN 存资源名；SVG 类型时为兜底 emoji。空串 = 首字圆徽章（R8） */
    val iconValue: String = "",
    /** AI 生成的简笔画 SVG（iconType=SVG 时有值，已过白名单校验）；M3 加渲染 */
    val iconSvg: String = "",
    /** 黄金角色相（PRD §5.4）：颜色入库固定，全 App 统一 */
    val colorHue: Float,
    /** 取色序号，超过 12 个分类时用于明度交替 */
    val colorIndex: Int,
    val createdBy: CreatedBy = CreatedBy.DEFAULT,
    /** 本地关键词分类规则（fallback 引擎用），逗号分隔 */
    val keywords: String = "",
    /**
     * 能否删除。与 [createdBy] 正交——`createdBy` 表达「谁建的」，本列表达「能不能删」。
     *
     * 为什么必须单独有一列：种子分类「吃饭」「饮品」与收纳箱「其他」的 `createdBy` 都是
     * [CreatedBy.DEFAULT]，但前者**允许**删除、后者**不允许**。同一个枚举取值被两种语义占用，
     * 分类表里也没有别的字段能区分，所以只能显式记下来。`false` 目前只保留给「其他」。
     */
    val deletable: Boolean = true
)
