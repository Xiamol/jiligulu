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

    const val PROMPT_ASSET_PATH = "prompts/parse_bill.txt"
}
