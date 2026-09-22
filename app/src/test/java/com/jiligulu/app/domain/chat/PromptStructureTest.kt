package com.jiligulu.app.domain.chat

import android.app.Application
import android.content.Context
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R9 提示词结构的验收。
 *
 * 用户抱怨的核心是「上下文缓存命中率是 0，十几次请求烧了 9 万 token」。根因是旧模板把每次都在变的
 * `{now}` 放到了第 3 行，而 DeepSeek 缓存按前缀匹配——前缀在那儿就断了。这组用例锁死三件事：
 * 1. system 段逐字不变（缓存地基，改昵称/改时间都不许打穿它）；
 * 2. 动态段顺序固定，且候选「按需注入」；
 * 3. 草稿默认不进上下文、时间降到分钟——这两条都是「别每轮自己砸前缀」的一部分。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PromptStructureTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: Long = LocalDateTime.of(2026, 9, 21, 20, 0).atZone(zone).toInstant().toEpochMilli()

    // ---------- system 段 ----------

    @Test
    fun `the system segment is byte stable no matter how the request changes`() {
        val first = renderer().renderSystem()
        val again = renderer().renderSystem()
        assertEquals("同一套动态数据，两次渲染的 system 段必须逐字一致", first, again)

        // 换昵称、换时间、换分类——只要 system 段里混进了任何一个，这里就会红。
        val shifted = renderer(
            nickname = "换个人",
            categories = listOf(category(id = 7, name = "饮品")),
            context = ctx(nowText = "2025-01-01 08:08")
        ).renderSystem()
        assertEquals("昵称/时间/分类变化都不得影响 system 段", first, shifted)
    }

    @Test
    fun `the system segment never carries a dynamic placeholder`() {
        val system = renderer().renderSystem()
        // 占位符形如 {word}。输出格式里的 JSON 示例（{"..."}）是静态格式说明、不随请求变化，
        // 因此只把「花括号里是标识符」判定为占位符——这才是真正会污染缓存前缀的东西。
        val placeholder = Regex("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}")
        assertFalse(
            "system 段串进了占位符：${placeholder.find(system)?.value}",
            placeholder.containsMatchIn(system)
        )
    }

    // ---------- 动态段顺序 ----------

    @Test
    fun `the dynamic block keeps a fixed order`() {
        val rendered = renderer().renderContext("午饭 20 块")
        val category = rendered.indexOf("现有分类列表")
        val bills = rendered.indexOf("【最近三天的账本】")
        val time = rendered.indexOf("当前时间：")
        val input = rendered.indexOf("用户这轮说：")

        assertTrue("四个段落都要在", category >= 0 && bills >= 0 && time >= 0 && input >= 0)
        assertTrue("分类段必须在账本段之前", category < bills)
        assertTrue("账本段必须在时间之前", bills < time)
        assertTrue("用户这轮说必须在最后", time < input)
    }

    @Test
    fun `the current input shows up in the dynamic block`() {
        // 本轮输入只从 `用户这轮说：{input}` 出现——history 由 chatTurnsFor 负责不重复携带同一句。
        val rendered = renderer().renderContext("晚饭 30 块")
        assertTrue("用户这轮说段要带上原话", rendered.contains("用户这轮说：晚饭 30 块"))
        assertEquals("本轮输入在动态段里只出现一次", 1, Regex("晚饭 30 块").findAll(rendered).count())
    }

    // ---------- 候选按需注入 ----------

    @Test
    fun `candidates are injected only when the input looks like an edit`() {
        val withCandidates = renderer(
            candidates = PromptRenderer.candidatesFrom(listOf(bill(detail = "牛肉面")), now, zone)
        )
        val edit = withCandidates.renderContext("改一下刚才那笔")
        val chat = withCandidates.renderContext("今天天气不错")

        assertTrue("疑似改账的输入应带上候选段", edit.contains("【候选账单】"))
        assertFalse("纯闲聊不该带候选段，省 token", chat.contains("【候选账单】"))
    }

    // ---------- 草稿默认不进上下文 ----------

    @Test
    fun `draft cards never enter the model history whatever their status`() {
        // v0.6 定稿（R6 追加式）：历史只挑 USER/ASSISTANT 原文；草稿卡（kind=DRAFT）任何状态都不进。
        // 「已确认」这件事由 ChatViewModel 追加的 assistant 消息承担——历史冻结，永不回改草稿卡。
        listOf("CONFIRMED", "EDITING", "DISMISSED", "DELETED").forEach { status ->
            assertFalse("$status 草稿卡不该作为历史行进入上下文", contextForStatus(status).contains("牛肉面"))
        }
    }

    // ---------- 时间粒度 ----------

    @Test
    fun `the current time is rendered to the minute without seconds`() {
        val ctx = ChatContextBuilder.build(emptyList(), emptyList(), now, zone)
        assertTrue(
            "时间应形如 yyyy-MM-dd HH:mm",
            Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").containsMatchIn(ctx.now)
        )
        assertFalse("时间不该带秒", Regex(":\\d{2}:\\d{2}").containsMatchIn(ctx.now))
    }

    // ---------- 装置 ----------

    private fun contextForStatus(status: String): String {
        val message = ChatMessageEntity(
            kind = "DRAFT",
            status = status,
            createdAt = now,
            draftPayload = DraftHistoryCodec.encode(
                listOf(DraftUi(amountText = "12", detail = "牛肉面", categoryName = "吃饭"))
            )
        )
        // R9/T02b：草稿是否进模型上下文，现在由「历史消息 → chatTurnsFor 只挑 USER/ASSISTANT 原文」
        // 这条链路决定（草稿卡是 DRAFT kind，本就不在挑选范围内）。
        return AiRepository.chatTurnsFor(listOf(message), now)
            .joinToString("\n") { "${it.role}：${it.content}" }
    }

    private fun renderer(
        nickname: String = "路陌",
        categories: List<CategoryEntity> = listOf(category()),
        candidates: PromptRenderer.CandidateBills = PromptRenderer.CandidateBills(),
        context: ChatContext = ctx()
    ) = PromptRenderer(
        systemTemplate = asset(AiPrompts.SYSTEM),
        contextTemplate = asset(AiPrompts.CONTEXT),
        categories = categories,
        context = context,
        nickname = nickname,
        suffix = "大人",
        candidates = candidates,
        pending = null,
        zone = zone
    )

    private fun asset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun category(id: Long = 1, name: String = "吃饭", keywords: String = "吃,饭") =
        CategoryEntity(id = id, name = name, colorHue = 1f, colorIndex = 0, keywords = keywords)

    private fun bill(detail: String) = BillEntity(
        id = 1, amountFen = 1200, type = BillType.EXPENSE, categoryId = 1,
        detail = detail, timestamp = LocalDateTime.of(2026, 9, 21, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
    )

    private fun ctx(nowText: String = "2026-09-21 20:00（周一）") =
        ChatContext(now = nowText, timeZone = zone.id, recentBills = emptyList())

    private object AiPrompts {
        const val SYSTEM = "prompts/parse_bill_system.txt"
        const val CONTEXT = "prompts/parse_bill_context.txt"
    }
}
