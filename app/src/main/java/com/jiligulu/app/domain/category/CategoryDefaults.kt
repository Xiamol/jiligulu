package com.jiligulu.app.domain.category

import com.jiligulu.app.data.local.entity.CategoryEntity

/**
 * 内置分类常量（数据 / 领域 / UI 三层共享），把散落的魔法串收敛到一处。
 *
 * 「待定」是本次新增的**收纳箱分类**：分类被删除后其账单会转移到这里，所以它本身不可删除。
 * 是否可删只认 [CategoryEntity.deletable]，不要用 `name == "待定"` 这类字符串判据——
 * 分类表没有唯一约束，一旦出现第二条同名列，字符串判据就会失真。
 *
 * 命名由来（coder 拍板）：统计图本就有一个「其他」虚拟合并片（StatsViewModel.OTHER_KEY），
 * 收纳箱若也叫「其他」，饼图会同时出现两个「其他」无法分辨。故收纳箱单独叫「待定」。
 */
object CategoryDefaults {
    /** 收纳箱分类名。全仓唯一来源，别处不得硬编码「待定」字面量。 */
    const val VACUUM_NAME = "待定"

    /** 收纳箱的本地关键词：「其他」仍作为别名保留——用户/模型说「其他」时也能归到这里。 */
    const val VACUUM_KEYWORDS = "其他,杂项,未分类,记不清,忘了,说不清"

    /** 图标兜底（R8）：空串 → 渲染首字圆徽章，替代旧的 🫧 占位。 */
    const val FALLBACK_EMOJI = ""

    /** 收纳箱判据：唯一不可删的分类。 */
    fun isVacuum(category: CategoryEntity): Boolean = !category.deletable
}
