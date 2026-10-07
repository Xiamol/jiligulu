package com.jiligulu.app.core.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/** Optional profile metadata must not invalidate an otherwise correct financial draft. */
object AiMemoryUpdatesSerializer : KSerializer<List<AiMemoryUpdate>> {
    private val list = ListSerializer(AiMemoryUpdate.serializer())
    override val descriptor: SerialDescriptor = list.descriptor
    override fun serialize(encoder: Encoder, value: List<AiMemoryUpdate>) = list.serialize(encoder, value.take(16))
    override fun deserialize(decoder: Decoder): List<AiMemoryUpdate> {
        val source = decoder as? JsonDecoder ?: return list.deserialize(decoder).take(16)
        val items = source.decodeJsonElement() as? JsonArray ?: return emptyList()
        return items.take(16).mapNotNull { item -> runCatching {
            source.json.decodeFromJsonElement(AiMemoryUpdate.serializer(), item)
        }.getOrNull() }
    }
}
