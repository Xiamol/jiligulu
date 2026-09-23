package com.jiligulu.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jiligulu.app.core.ai.AiAppAction
import com.jiligulu.app.ui.components.LedgerCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Parameters are locally validated; titles/descriptions never come from model-generated claims. */
@Serializable
data class AppActionPayload(
    val version: Int = 1,
    val action: AiAppAction,
    val summary: String = "",
    /** Snapshot: a confirmation may only purge these exact records, never later additions. */
    val trashIds: List<Long> = emptyList(),
    /** An ID alone is insufficient when a bill was restored and later deleted again. */
    val trashDeletedAt: Map<Long, Long> = emptyMap(),
    val appliedCount: Int = 0
)

object AppActionCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(payload: AppActionPayload): String = json.encodeToString(AppActionPayload.serializer(), payload)
    fun decode(raw: String): AppActionPayload? = runCatching {
        json.decodeFromString(AppActionPayload.serializer(), raw)
    }.getOrNull()?.takeIf { it.version == 1 && it.action.isValid }
}

@Composable
fun AppActionConfirmationCard(card: ChatItem.AppActionCard, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val destructive = card.payload.action.kind == AiAppAction.EMPTY_TRASH
    LedgerCard(Modifier.testTag("app-action-${card.id}")) {
        Text(if (destructive) "🧺 清空前，再看一眼" else "💧 帮你调整喝水提醒", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Text(card.payload.summary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                card.status == ChatItem.AppActionCard.Status.NEEDS_RETRY -> "上次处理尚未完成，部分设置可能已经保存。继续完成后会核实结果。"
                destructive -> "彻底删除后无法恢复。只处理这张卡列出的回收站账单，草稿会保留。"
                else -> "点确认后才会生效；其他设置保持原样。"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        when (card.status) {
            ChatItem.AppActionCard.Status.EDITING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppActionPill("先等等", "app-action-cancel", onCancel)
                AppActionPill(if (destructive) "确认彻底删除" else "确认调整", "app-action-confirm", onConfirm)
            }
            ChatItem.AppActionCard.Status.SAVING -> Text("阿噜正在处理…", style = MaterialTheme.typography.labelMedium)
            ChatItem.AppActionCard.Status.DONE -> Text(
                if (destructive) "已彻底删除 ${card.payload.appliedCount} 笔账单" else "已按上面的内容调整 ♡",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
            )
            ChatItem.AppActionCard.Status.CANCELLED -> Text("已取消，没有执行这次操作", style = MaterialTheme.typography.labelMedium)
            ChatItem.AppActionCard.Status.NEEDS_RETRY -> AppActionPill("继续完成", "app-action-retry", onConfirm)
        }
    }
}

@Composable
private fun AppActionPill(label: String, tag: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
        shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}
