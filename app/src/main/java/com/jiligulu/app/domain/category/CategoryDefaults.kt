package com.jiligulu.app.domain.category

import com.jiligulu.app.data.local.entity.CategoryEntity

/**
 * 内置分类常量（数据 / 领域 / UI 三层共享），把散落的魔法串收敛到一处。
 *
 * 「其他」是本次新增的**收纳箱分类**：分类被删除后其账单会转移到这里，所以它本身不可删除。
 * 是否可删只认 [CategoryEntity.deletable]，不要用 `name == "其他"` 这类字符串判据——
 * 分类表没有唯一约束，一旦出现第二条同名列，字符串判据就会失真。
 */
object CategoryDefaults {
    /** 收纳箱分类名。全仓唯一来源，别处不得硬编码「其他」字面量。 */
    const val VACUUM_NAME = "其他"

    /** 收纳箱的本地关键词，供 fallback 归类引擎把「记不清」类表述归到这里。 */
    const val VACUUM_KEYWORDS = "其他,杂项,未分类,记不清,忘了,说不清"

    /** 图标兜底（R8）：空串 → 渲染首字圆徽章，替代旧的 🫧 占位。 */
    const val FALLBACK_EMOJI = ""

    /** 收纳箱判据：唯一不可删的分类。 */
    fun isVacuum(category: CategoryEntity): Boolean = !category.deletable
}
