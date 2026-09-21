package com.jiligulu.app.core.ai

/**
 * AI 生成 SVG 图标的白名单校验（PRD §8 风险 5）：
 * 只允许静态简笔画标签，禁脚本/外链/事件属性。校验失败一律走 emoji 兜底。
 */
object SvgIconValidator {

    private val ALLOWED_TAGS = listOf("svg", "path", "circle", "line", "rect", "polyline", "polygon", "ellipse", "g")

    private val FORBIDDEN_PATTERNS = listOf(
        "<script", "<foreignobject", "<image", "<use", "<animate", "<set",
        "javascript:", "http://", "https://", "xlink", "data:"
    )

    private val EVENT_ATTR = Regex("""on[a-z]+\s*=""", RegexOption.IGNORE_CASE)
    private val TAG_PATTERN = Regex("""</?([a-zA-Z][a-zA-Z0-9]*)""")

    fun isValid(svg: String): Boolean {
        if (svg.isBlank() || svg.length > 4096) return false
        val lower = svg.lowercase()
        if (!lower.contains("<svg")) return false
        if (FORBIDDEN_PATTERNS.any { lower.contains(it) }) return false
        if (EVENT_ATTR.containsMatchIn(svg)) return false
        // 所有出现的标签都必须在白名单内
        val tags = TAG_PATTERN.findAll(svg).map { it.groupValues[1].lowercase() }.toSet()
        return tags.isNotEmpty() && tags.all { it in ALLOWED_TAGS }
    }
}
