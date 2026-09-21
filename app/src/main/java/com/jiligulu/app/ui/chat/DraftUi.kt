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
    val timeHint: String = "未提及时间，确认入账时记录此刻"
) {
    val isValid: Boolean get() = Formatters.yuanTextToFen(amountText) != null && !timeNeedsReview
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
