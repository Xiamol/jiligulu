package com.jiligulu.app.core.ai

/** DeepSeek 接入配置。Key 优先级：设置页自定义 > 构建期注入的内置 Key */
object AiConfig {
    const val BASE_URL = "https://api.deepseek.com/chat/completions"
    const val MODEL = "deepseek-flash" // V4.1-Flash，原生多模态（M5 识屏复用同一模型）

    /**
     * 内置默认 Key —— 不写在源码里，构建期由 local.properties 的 DEEPSEEK_API_KEY 注入
     * （见 app/build.gradle.kts 的 buildConfigField；local.properties 已被 .gitignore 排除）。
     * 未配置时为空串，此时需在设置页手填 Key 才能使用 AI。
     */
    val DEFAULT_API_KEY: String = com.jiligulu.app.BuildConfig.DEEPSEEK_API_KEY

    /**
     * R9：逐字不变的固定 system message。
     *
     * 它是 DeepSeek 前缀缓存的地基，**不含任何占位符**——昵称、时间、账本、分类一律不进这里，
     * 否则改一个称呼就会打穿整段前缀，缓存命中率归零。
     */
    const val SYSTEM_PROMPT_ASSET_PATH = "prompts/parse_bill_system.txt"

    /**
     * R9：动态上下文模板，占位符顺序固定
     * （称呼 → 分类 → 待补充 → 账本 → 候选 → 回收站候选 → 待定账单 → 时间 → 时区 → 输入）。
     */
    const val CONTEXT_PROMPT_ASSET_PATH = "prompts/parse_bill_context.txt"
}
