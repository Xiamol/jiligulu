package com.jiligulu.app.ui.chat

import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

@Serializable
data class DraftUi(
    val amountText: String = "",
    @Serializable(with = BillTypeSerializer::class) val type: BillType = BillType.EXPENSE,
    val categoryName: String = "未分类",
    val isNewCategory: Boolean = false,
    val iconEmoji: String = "",
    val iconSvg: String = "",
    val keywords: String = "",
    val detail: String = "",
    val note: String = "",
    val checked: Boolean = true,
    /** null means the instant of confirmation, rather than the time the screen opened. */
    val timestamp: Long? = null,
    val timeNeedsReview: Boolean = false,
    val timeHint: String = "未提及时间，确认入账时记录此刻",
    val photoUri: String? = null
) {
    /** Receipt metadata remains stored, while only a human note or actionable warning is shown. */
    val displayNote: String get() = draftNoteForDisplay(note)
    /** A visible suggested timestamp can be accepted by confirming the whole draft. */
    val requiresTimeInput: Boolean get() = timeNeedsReview && timestamp == null
    val isValid: Boolean get() = Formatters.yuanTextToFen(amountText) != null && !requiresTimeInput
}

internal fun draftNoteForDisplay(note: String): String {
    if (!note.trimStart().startsWith("图片状态：")) return note
    val completed = setOf("支付成功", "已支付", "已完成", "交易成功", "转账成功", "已收款", "确认收款", "收款成功", "已存入零钱", "制作中", "配送中", "状态待确认", "请核对", "可修改")
    return note.trimStart().removePrefix("图片状态：").split('，', ',', '；', ';', '\n').map(String::trim).mapNotNull { part ->
        when {
            part.isBlank() || part in completed || part.startsWith("支付方式：") ||
                part.startsWith("图中无相关账单时间") || part.startsWith("暂按系统时间") ||
                part == "日期或时间需核对" -> null
            part.startsWith("金额来自关联聊天推定") -> "核对金额"
            else -> part.removePrefix("备注：")
        }
    }.distinct().joinToString(" · ")
}

internal fun chatMessageDisplayText(text: String): String {
    val codec = com.jiligulu.app.core.ai.ImageReceiptCodec
    if (!codec.isImageText(text)) return text
    val rows = text.trim().removePrefix(codec.HEADER).lines().count { it.isNotBlank() && !it.startsWith("备注：") }
    return if (rows > 0) "账单图片 · $rows 笔" else "账单图片"
}

object BillTypeSerializer : KSerializer<BillType> {
    override val descriptor = PrimitiveSerialDescriptor("BillType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BillType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): BillType = BillType.valueOf(decoder.decodeString())
}

/** Versioned payloads allow fields to be added without losing editable chat drafts. */
object DraftHistoryCodec {
    @Serializable
    private data class Payload(val version: Int = 1, val drafts: List<DraftUi>)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(drafts: List<DraftUi>): String = json.encodeToString(Payload.serializer(), Payload(drafts = drafts))
    fun decode(payload: String): List<DraftUi> = json.decodeFromString(Payload.serializer(), payload).drafts
}
