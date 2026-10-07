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

    data class Preset(val name: String, val icon: String, val keywords: String, val deletable: Boolean = true)

    val supplementalPresets = listOf(
        Preset("水果", "builtin_fruit", "水果,苹果,香蕉,橘子,橙子,草莓,葡萄,西瓜,芒果,蓝莓,榴莲,桃子,梨"),
        Preset("蔬菜", "builtin_vegetable", "蔬菜,青菜,白菜,番茄,土豆,黄瓜,买菜"),
        Preset("通讯", "builtin_phone", "话费,流量,宽带,手机费,通讯"),
        Preset("运动", "builtin_sport", "健身,球馆,游泳,运动,瑜伽"),
        Preset("旅行", "builtin_travel", "旅行,旅游,景点,门票,民宿,酒店"),
        Preset("礼物", "builtin_present", "礼物,礼品,送礼,鲜花,生日礼物")
    )

    /** Curated on a fresh install only; upgrades never recreate a category the user has removed. */
    val presets = listOf(
        Preset("吃饭", "builtin_food", "早餐,午餐,午饭,晚餐,晚饭,吃饭,面条,牛肉面,米饭,外卖,食堂,火锅,烧烤,炒饭"),
        Preset("饮品", "builtin_drink", "奶茶,咖啡,饮料,可乐,矿泉水,果汁,牛奶,茶饮"),
        Preset("零食", "builtin_snack", "零食,薯片,饼干,糖果,蛋糕,巧克力,冰淇淋,面包"),
        Preset("交通", "builtin_bus", "地铁,公交,打车,出租车,高铁,车票,油费,停车,交通"),
        Preset("购物", "builtin_shopping", "衣服,鞋子,裤子,化妆品,护肤品,购物"),
        Preset("日用品", "builtin_daily", "纸巾,洗发水,沐浴露,牙膏,洗衣液,日用品"),
        Preset("住房", "builtin_home", "房租,租金,水电,电费,水费,物业,住宿"),
        Preset("数码", "builtin_digital", "手机,电脑,耳机,数码,软件,订阅,API,Gemini,ChatGPT"),
        Preset("学习", "builtin_study", "教材,书本,学费,课程,买书,文具,学习"),
        Preset("医疗", "builtin_health", "医院,看病,药品,买药,体检,医疗"),
        Preset("娱乐", "builtin_play", "电影,演唱会,游戏,音乐,游乐园,娱乐"),
        Preset("生活服务", "builtin_service", "理发,洗衣,快递,维修,清洁,生活服务"),
        Preset("宠物", "builtin_pet", "猫粮,狗粮,宠物,猫砂,兽医"),
        Preset("工资", "builtin_salary", "工资,薪水,奖金,薪资"),
        Preset("生活费", "builtin_living", "生活费,零花钱"),
        Preset("红包", "builtin_gift", "红包,压岁钱"),
        Preset("转账", "builtin_transfer", "转账,收款,汇款"),
        *supplementalPresets.toTypedArray(),
        Preset(VACUUM_NAME, "builtin_pending", VACUUM_KEYWORDS, deletable = false)
    )

    /** 收纳箱判据：唯一不可删的分类。 */
    fun isVacuum(category: CategoryEntity): Boolean = !category.deletable
}
