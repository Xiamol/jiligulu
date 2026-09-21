package com.jiligulu.app.core.ai

/** DeepSeek 接入配置。Key 优先级：设置页自定义 > 内置默认 */
object AiConfig {
    const val BASE_URL = "https://api.deepseek.com/chat/completions"
    const val MODEL = "deepseek-flash" // V4.1-Flash，原生多模态（M5 识屏复用同一模型）

    /** 内置默认 Key —— 纯自用，别连源码一起公开分享（PRD §8 风险 3） */
    const val DEFAULT_API_KEY = "sk-4c3c19c834b9457ba1aaea5678f91c63"

    const val PROMPT_ASSET_PATH = "prompts/parse_bill.txt"
}
