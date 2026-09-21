package com.jiligulu.app.domain.persona

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 台词库条目（assets/quips.json 数据驱动，加台词不改代码 —— PRD §3.5）。
 * type: save 记账接话 / greet 问候 / water 喝水 / poem 古诗词 / weird 怪话
 * text: {n} 为称呼占位符
 * weight: 加权随机权重
 * timeRange: [起, 止) 一天内分钟数（0-1439），null = 全天可出
 */
@Serializable
data class QuipEntry(
    val type: String,
    val text: String,
    val weight: Int = 10,
    val timeRange: List<Int>? = null
)

@Serializable
private data class QuipFile(
    val version: Int = 1,
    val quips: List<QuipEntry>
)

/** 台词库单例：App 启动后首次使用时从 assets 读一次，常驻内存（几十条，量小） */
class QuipLibrary internal constructor(private val entries: List<QuipEntry>) {

    fun ofType(type: String): List<QuipEntry> = entries.filter { it.type == type }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        @Volatile
        private var instance: QuipLibrary? = null

        fun get(context: Context): QuipLibrary =
            instance ?: synchronized(this) {
                instance ?: run {
                    val text = context.applicationContext.assets
                        .open("quips.json").bufferedReader().use { it.readText() }
                    val file = json.decodeFromString(QuipFile.serializer(), text)
                    QuipLibrary(file.quips).also { instance = it }
                }
            }
    }
}
