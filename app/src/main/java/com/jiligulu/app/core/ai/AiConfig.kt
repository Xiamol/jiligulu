package com.jiligulu.app.core.ai

/** DeepSeek 接入配置；共享默认密钥仅保存在服务端。 */
object AiConfig {
    const val BASE_URL = "https://api.deepseek.com/chat/completions"
    const val MODEL = "deepseek-flash" // V4.1-Flash，原生多模态（M5 识屏复用同一模型）
    const val DEEPSEEK_PRO_MODEL = "deepseek-v4-pro"

    const val DEFAULT_SERVICE_URL = "https://jiligulu-default-ds.creamy-hinny-4798.chatgpt.site/v1/chat/completions"

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
