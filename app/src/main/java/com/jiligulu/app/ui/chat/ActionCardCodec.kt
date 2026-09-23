package com.jiligulu.app.ui.chat

import com.jiligulu.app.core.ai.AiOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 跳转卡的载荷编解码（R4/R5 的可点选项）。
 *
 * 只存选项数组；卡片正文走消息的 `content` 字段，与普通气泡同源，
 * 这样历史渲染不需要为它开第二条通道。
 *
 * 解码失败返回空列表：卡片退化成一条纯文本气泡，绝不因为一条坏数据把整个聊天流崩掉。
 */
object ActionCardCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(AiOption.serializer())

    fun encode(options: List<AiOption>): String = json.encodeToString(serializer, options)

    fun decode(raw: String): List<AiOption> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
}
