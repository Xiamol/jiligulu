package com.jiligulu.app.ui.chat

import com.jiligulu.app.data.local.entity.BillType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次改账/删账涉及的一条账单。
 *
 * [before] / [after] 都渲染成给人看的文本，而不是各自存一套字段——
 * 卡片只需要展示，落库时值已经在解析阶段算好了。少一层结构就少一层自己跟自己对不上的机会。
 */
@Serializable
data class CommandItem(
    val billId: Long,
    val title: String,
    val categoryName: String = "",
    val iconEmoji: String = "",
    val isExpense: Boolean = true,
    /** 改动前的一行摘要，如「9月21日 12:30 · 12 元 · 吃饭」。 */
    val before: String = "",
    /** 改动后的一行摘要；删除时为空。 */
    val after: String = "",
    val checked: Boolean = true,
    /** 改账时真正要写进去的值。 */
    val newAmountText: String = "",
    @Serializable(with = BillTypeSerializer::class) val newType: BillType = BillType.EXPENSE,
    val newDetail: String = "",
    val newNote: String = "",
    val newTimestamp: Long = 0,
    val newCategoryName: String = ""
) {
    val hasChanges: Boolean get() = before != after || newAmountText.isNotBlank()
}

/** 指令卡能干的三种事。 */
/** 指令卡的动作类型。RESTORE（R4）的卡片 UI 渲染归 T04，这里只声明枚举值。 */
enum class CommandKind { UPDATE, DELETE, RESTORE }

/** 「变更已提交」在 [CommandCardPayload.confirmFailed] 之外的所有情况下的含义。 */
@Serializable
data class CommandCardPayload(
    val version: Int = 1,
    val kind: String = "update",
    val items: List<CommandItem> = emptyList(),
    val alreadyApplied: Int = 0,
    val confirmFailed: Boolean = false
)

/** 指令卡载荷编解码。和草稿一样带 version，以后加字段不至于读不出旧卡。 */
object CommandCardCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(kind: CommandKind, items: List<CommandItem>): String =
        json.encodeToString(CommandCardPayload.serializer(), CommandCardPayload(kind = kind.name, items = items))

    fun encode(payload: CommandCardPayload): String =
        json.encodeToString(CommandCardPayload.serializer(), payload)

    fun decode(raw: String): CommandCardPayload? =
        runCatching { json.decodeFromString(CommandCardPayload.serializer(), raw) }.getOrNull()

    /** 卡上还没提交的条目数——加载历史时用它决定按钮显示「确认(2)」还是「已处理」。 */
    fun pendingCount(payload: CommandCardPayload): Int =
        payload.items.count { it.checked } - payload.alreadyApplied
}

/**
 * 挂起中的「待补充」账（用户只说了金额、没说名目）。
 *
 * 存进 `chat_messages.draftPayload`，不用新开表也不用迁移：
 * 这就是一条临时草稿，生命周期到下一条消息为止。
 */
@Serializable
data class PendingDraft(
    val amountText: String,
    val detail: String = "",
    val type: String = "EXPENSE",
    /** 用户原话，追问时回显用。 */
    val rawInput: String = "",
    val createdAt: Long = 0
)

/**
 * 用户这轮输入如果是纯数字，本地也能检出「话没说完」。
 *
 * 模型那一侧同样有 pending 判断，但这条本地兜底有用：
 * 模型没连上（降级到本地规则）时，「5」不会被 `LocalBillParser` 拼成一条无名的账扔进草稿卡，
 * 挂起逻辑是一致的。
 */
object PendingInputDetector {
    /** 只有金额、没有别的字：「5」「25块」「¥88.5」「三十」。 */
    private val amountOnly = Regex("^\\s*(?:[¥￥]\\s*)?\\d{1,7}(?:\\.\\d{1,2})?\\s*(?:块钱|块|元|毛|角)?\\s*$")

    fun looksIncomplete(input: String): Boolean = amountOnly.matches(input)

    /** 从纯金额输入里抽出要挂起的金额文本；抽不出（如「三十」）就返回 null，交给模型。 */
    fun amountOf(input: String): String? =
        Regex("\\d{1,7}(?:\\.\\d{1,2})?").find(input)?.value
}
