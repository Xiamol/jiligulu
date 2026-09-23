package com.jiligulu.app.core.ai

/** Accepts a small static SVG vocabulary. Nothing here fetches resources or evaluates XML entities. */
object SvgIconValidator {
    private val allowedTags = setOf("svg", "path", "circle", "line", "rect", "polyline", "polygon", "ellipse", "g")
    private val forbidden = listOf("javascript:", "http://", "https://", "xlink", "data:", "url(")
    private val tagPattern = Regex("""<(/?)([A-Za-z][A-Za-z0-9]*)([^<>]*)>""")
    private val attributePattern = Regex("""\s+([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*("[^"]*"|'[^']*')""")
    private const val NAMESPACE = "http://www.w3.org/2000/svg"

    fun isValid(svg: String): Boolean {
        if (svg.isBlank() || svg.length > 4096 || '&' in svg || "<!" in svg || "<?" in svg) return false
        val stack = ArrayDeque<String>()
        var end = 0
        var roots = 0
        for (match in tagPattern.findAll(svg)) {
            if (svg.substring(end, match.range.first).isNotBlank()) return false
            end = match.range.last + 1
            val closing = match.groupValues[1].isNotEmpty()
            val tag = match.groupValues[2]
            if (tag !in allowedTags) return false
            val rawAttributes = match.groupValues[3]
            if (closing) {
                if (rawAttributes.isNotBlank() || stack.removeLastOrNull() != tag) return false
                continue
            }
            val isRoot = stack.isEmpty()
            if (isRoot && (++roots != 1 || tag != "svg")) return false
            if (!isRoot && tag == "svg") return false
            val selfClosing = rawAttributes.endsWith('/')
            val attributes = if (selfClosing) rawAttributes.dropLast(1) else rawAttributes
            var attrEnd = 0
            val names = mutableSetOf<String>()
            for (attribute in attributePattern.findAll(attributes)) {
                if (attribute.range.first != attrEnd) return false
                attrEnd = attribute.range.last + 1
                val name = attribute.groupValues[1]
                val value = attribute.groupValues[2].drop(1).dropLast(1)
                if (!names.add(name) || name.startsWith("on", ignoreCase = true) || ':' in name ||
                    name.contains("xlink", ignoreCase = true) || name.equals("href", true) || name.equals("style", true)) return false
                if (name == "xmlns") {
                    if (!isRoot || value != NAMESPACE) return false
                } else if (forbidden.any { value.lowercase().contains(it) }) return false
            }
            if (attributes.substring(attrEnd).isNotBlank()) return false
            if (!selfClosing) stack.addLast(tag)
        }
        return roots == 1 && stack.isEmpty() && svg.substring(end).isBlank()
    }
}
