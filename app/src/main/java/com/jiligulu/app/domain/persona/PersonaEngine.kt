package com.jiligulu.app.domain.persona

import java.util.Calendar
import kotlin.random.Random

/**
 * 人格文案选择与展示节奏。
 *
 * 职责：
 * - 按事件从台词库加权抽台词（时段过滤）；
 * - 记账接话节流；常驻文案展示满一个刷新间隔后才允许自动替换；
 * - 免打扰时段判断（支持跨午夜）。
 *
 * 调用约定：
 * - 问候「同时段每天一次」的去重由调用方（DataStore 记日期+时段）负责；
 * - 喝水提醒的免打扰先查 inQuietHours 再调 nextWaterQuip。
 */
class PersonaEngine(private val library: QuipLibrary) {

    private var lastSaveQuipAt: Long? = null
    private var lastBubbleAt: Long? = null
    private var lastIdleAt: Long? = null
    private var companionShownAt: Long? = null

    // ---------- 时间工具 ----------

    fun minuteOfDay(now: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 今天日期串（yyyyMMdd），用于「每日一次」去重 */
    fun dayKey(now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 问候时段槽位：morning/noon/afternoon/evening/night（0-5 点不问候） */
    fun greetSlot(now: Long): String = when (minuteOfDay(now)) {
        in 300 until 660 -> "morning"
        in 660 until 840 -> "noon"
        in 840 until 1080 -> "afternoon"
        in 1080 until 1380 -> "evening"
        else -> "night"
    }

    /**
     * 免打扰时段判断，支持跨午夜。
     * start == end 视为「未设置免打扰」。
     */
    fun inQuietHours(now: Long, quietStartMin: Int, quietEndMin: Int): Boolean {
        if (quietStartMin == quietEndMin) return false
        val m = minuteOfDay(now)
        return if (quietStartMin < quietEndMin) {
            m in quietStartMin until quietEndMin
        } else {
            m >= quietStartMin || m < quietEndMin
        }
    }

    // ---------- 抽取 ----------

    private fun matchesTime(entry: QuipEntry, now: Long): Boolean {
        val range = entry.timeRange ?: return true
        val m = minuteOfDay(now)
        return m >= range[0] && m < range[1]
    }

    private fun weightedPick(entries: List<QuipEntry>): QuipEntry? {
        if (entries.isEmpty()) return null
        val total = entries.sumOf { it.weight.coerceAtLeast(1) }
        var roll = Random.nextInt(total)
        entries.forEach {
            roll -= it.weight.coerceAtLeast(1)
            if (roll < 0) return it
        }
        return entries.last()
    }

    private fun pickIdle(nicknameWithSuffix: String, previousText: String?): String? {
        val candidates = library.ofType(TYPE_POEM) + library.ofType(TYPE_WEIRD)
        // 比较最终展示文本，重复条目或不同模板渲染成同一句时也不能连抽。
        val different = candidates.filter { render(it.text, nicknameWithSuffix) != previousText }
        val pick = weightedPick(different.ifEmpty { candidates }) ?: return null
        return render(pick.text, nicknameWithSuffix)
    }

    /** {n} 占位替换：有称呼→「，XX大人」，无称呼→空串 */
    fun render(template: String, nicknameWithSuffix: String): String {
        val n = if (nicknameWithSuffix.isBlank()) "" else "，$nicknameWithSuffix"
        return template.replace("{n}", n)
    }

    // ---------- 事件 API ----------

    /** 记账接话：5 秒一条节流（PRD §3.2），命中节流返回 null */
    fun nextSaveQuip(now: Long, nicknameWithSuffix: String): String? {
        if (lastSaveQuipAt?.let { now - it < SAVE_THROTTLE_MS } == true) return null
        val pick = weightedPick(library.ofType(TYPE_SAVE)) ?: return null
        lastSaveQuipAt = now
        return render(pick.text, nicknameWithSuffix)
    }

    /** 按时段问候：过气泡节流；时段槽去重由调用方做 */
    fun nextGreeting(now: Long, nicknameWithSuffix: String): String? {
        if (!canShowBubble(now)) return null
        val candidates = library.ofType(TYPE_GREET).filter { matchesTime(it, now) }
        val pick = weightedPick(candidates) ?: return null
        markBubbleShown(now)
        return render(pick.text, nicknameWithSuffix)
    }

    /** 提醒优先于问候；不能因为刚问候过就丢掉本次喝水提醒。 */
    fun nextWaterQuip(now: Long, nicknameWithSuffix: String): String? {
        val pick = weightedPick(library.ofType(TYPE_WATER)) ?: return null
        markBubbleShown(now)
        return render(pick.text, nicknameWithSuffix)
    }

    /** 自动换句使用单调时钟；当前文案（尤其喝水提醒）必须先展示满 10 秒。 */
    fun nextIdleQuip(now: Long, nicknameWithSuffix: String, previousText: String? = null): String? {
        if (millisUntilIdleRefresh(now) > 0L) return null
        val text = pickIdle(nicknameWithSuffix, previousText) ?: return null
        lastIdleAt = now
        return text
    }

    /** 桌宠点击互动：免节流立即来一条（点击必须有即时反馈） */
    fun idleQuipNow(nicknameWithSuffix: String, previousText: String? = null): String? =
        pickIdle(nicknameWithSuffix, previousText)

    /** 与日期无关的展示间隔，调用方统一传 elapsedRealtime，避免修改系统时间影响刷新。 */
    fun markCompanionShown(now: Long) {
        companionShownAt = now
    }

    /** 返回实际剩余等待时间，避免错过一次固定 tick 后再多等完整的 10 秒。 */
    fun millisUntilIdleRefresh(now: Long): Long {
        fun remaining(lastAt: Long?): Long =
            lastAt?.let { (IDLE_INTERVAL_MS - (now - it)).coerceIn(0L, IDLE_INTERVAL_MS) } ?: 0L
        return maxOf(remaining(companionShownAt), remaining(lastIdleAt))
    }

    /** 后台系统通知专用：不过气泡节流（通知有系统级频控，不该被 UI 节流误伤） */
    fun nextWaterQuipForNotification(nicknameWithSuffix: String): String {
        val pick = weightedPick(library.ofType(TYPE_WATER))
        return pick?.let { render(it.text, nicknameWithSuffix) } ?: "喝水时间到，阿噜！"
    }

    // ---------- 问候节流（不阻止更重要的喝水提醒或主动点击） ----------

    fun canShowBubble(now: Long): Boolean =
        lastBubbleAt?.let { now - it >= BUBBLE_THROTTLE_MS } ?: true

    fun markBubbleShown(now: Long) {
        lastBubbleAt = now
    }

    companion object {
        const val TYPE_SAVE = "save"
        const val TYPE_GREET = "greet"
        const val TYPE_WATER = "water"
        const val TYPE_POEM = "poem"
        const val TYPE_WEIRD = "weird"

        const val SAVE_THROTTLE_MS = 5_000L
        const val BUBBLE_THROTTLE_MS = 30_000L
        const val IDLE_INTERVAL_MS = 10_000L
        const val IDLE_THROTTLE_MS = IDLE_INTERVAL_MS
    }
}
