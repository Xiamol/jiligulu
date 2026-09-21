package com.jiligulu.app.data.repository

import android.content.Context
import com.jiligulu.app.core.ai.AiConfig
import com.jiligulu.app.core.ai.AiParseResult
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.core.ai.SvgIconValidator
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.data.local.entity.IconType
import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 确认入库时的单条草稿（UI 层可编辑后的最终态） */
data class ConfirmItem(
    val amountText: String, // 元，可编辑
    val type: BillType,
    val categoryName: String,
    val isNewCategory: Boolean,
    val iconEmoji: String,
    val iconSvg: String,
    val keywords: String,
    val detail: String,
    val note: String,
    val checked: Boolean,
    val timestamp: Long? = null
)

/**
 * AI 对话记账：prompt 组装（带全量分类列表，防近义重复建类，PRD §8 风险 6）
 * → DeepSeek 拆解 → 草稿 → 确认入库（自动建分类 + 黄金角取色）。
 */
class AiRepository(
    private val context: Context,
    private val categoryRepository: CategoryRepository,
    private val billRepository: BillRepository,
    private val userPrefs: UserPrefs,
    private val chatHistoryRepository: ChatHistoryRepository
) {
    /** Versioned with the app; the complete parsing contract lives in one asset. */
    private val promptTemplate: String by lazy {
        context.assets.open(AiConfig.PROMPT_ASSET_PATH).bufferedReader().use { it.readText() }
    }

    suspend fun effectiveApiKey(): String =
        userPrefs.apiKeyOverride.first().ifBlank { AiConfig.DEFAULT_API_KEY }

    suspend fun nicknameWithSuffix(): String {
        val nick = userPrefs.nickname.first()
        val suffix = userPrefs.nameSuffix.first()
        return if (nick.isBlank()) "" else "$nick$suffix"
    }

    suspend fun parse(input: String, requestMillis: Long, zone: ZoneId): Result<AiParseResult> {
        val categories = categoryRepository.getAll()
        val categoriesText = categories.joinToString("\n") { c ->
            "- ${c.name}（关键词：${c.keywords.ifBlank { "无" }}）"
        }
        val now = Instant.ofEpochMilli(requestMillis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE"))
        val nickname = userPrefs.nickname.first().ifBlank { "主人" }
        val prompt = promptTemplate
            .replace("{nickname}", nickname)
            .replace("{suffix}", userPrefs.nameSuffix.first())
            .replace("{categories}", categoriesText)
            .replace("{now}", now)
            .replace("{timezone}", zone.id)
            .replace("{input}", input)
        return DeepSeekClient(effectiveApiKey()).parseBill(prompt, input)
    }

    /**
     * Validate every selected draft, then commit all bills and the card status in one transaction.
     */
    suspend fun confirm(cardId: Long, items: List<ConfirmItem>, rawText: String, finalPayload: String? = null): Int =
        chatHistoryRepository.confirmDraftAtomically(cardId, finalPayload) {
            val selected = items.filter { it.checked }
            require(selected.isNotEmpty()) { "请至少选择一条账单" }
            val valid = selected.map { item ->
                item to requireNotNull(Formatters.yuanTextToFen(item.amountText)) { "请填写有效的金额" }
            }
            val existing = categoryRepository.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()
            val confirmedAt = System.currentTimeMillis()
            valid.forEach { (item, fen) ->
                val categoryName = item.categoryName.ifBlank { "未分类" }.lowercase()
                val categoryId = existing[categoryName] ?: createCategoryFromDraft(item).also { existing[categoryName] = it }
                billRepository.addFromAi(
                    amountFen = fen,
                    type = item.type,
                    categoryId = categoryId,
                    detail = item.detail,
                    note = item.note,
                    rawText = rawText,
                    timestamp = item.timestamp ?: confirmedAt
                )
            }
            valid.size
        }

    /** AI 首次提到的分类 → 自动新建：SVG 过白名单用 SVG，否则 emoji 兜底（PRD §5.3 方案 A+B） */
    private suspend fun createCategoryFromDraft(item: ConfirmItem): Long {
        val svgOk = item.isNewCategory && SvgIconValidator.isValid(item.iconSvg)
        val emoji = item.iconEmoji.ifBlank { "🫧" }
        return categoryRepository.createCategory(
            name = item.categoryName.ifBlank { "未分类" },
            iconType = if (svgOk) IconType.SVG else IconType.EMOJI,
            iconValue = emoji, // SVG 渲染接入前，emoji 兼作显示兜底
            iconSvg = if (svgOk) item.iconSvg else "",
            keywords = item.keywords,
            createdBy = CreatedBy.AI
        )
    }
}
