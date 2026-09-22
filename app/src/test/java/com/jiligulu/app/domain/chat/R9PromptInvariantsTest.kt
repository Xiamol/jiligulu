package com.jiligulu.app.domain.chat

import android.app.Application
import android.content.Context
import com.jiligulu.app.core.ai.AiConfig
import com.jiligulu.app.core.ai.ChatTurn
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.ui.chat.CommandCardCodec
import com.jiligulu.app.ui.chat.CommandItem
import com.jiligulu.app.ui.chat.CommandKind
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import com.jiligulu.app.ui.chat.PendingDraft
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * R9 提示词结构重构的**独立验证**（QA: 严过关）。
 *
 * 与工程师自建的 [PromptStructureTest]（6 条 happy path）互补，本组用例刻意往边界与反向场景压：
 * - system 段：原始字节多次读取一致、无 BOM、无混用行尾、无占位符、无任何随请求变化的内容；
 * - 动态段：满注入下的固定顺序、分钟粒度、昵称只在动态段；
 * - 候选按需注入：闲聊/改删/恢复三分，外加一批边界输入（误判如实记录）；
 * - 草稿三态：真 SQLite 验证草稿卡（任何状态）都不作为历史行进入上下文，
 *   已确认的信息由追加的 assistant 消息承担（R6 追加式）；
 * - 硬约束：MAX_MESSAGES=60 / MAX_BILLS=30 且在 build() 真正生效；
 * - 「历史冻结」是否名存实亡：同一条消息在账本状态变化后渲染是否逐字不变（F 项核心）；
 * - 线上消息线格式：首条为 user（丢掉开头 assistant 轮）、缓存字段确实被打印。
 *
 * ⚠️ 无法验：真实 DeepSeek 的 prompt_cache_hit_tokens 数值（需联网 + 真机）。
 */
private object R9 {
    const val SYSTEM = "prompts/parse_bill_system.txt"
    const val CONTEXT = "prompts/parse_bill_context.txt"
    fun systemText(ctx: Context) = ctx.assets.open(SYSTEM).bufferedReader().use { it.readText() }
    fun contextText(ctx: Context) = ctx.assets.open(CONTEXT).bufferedReader().use { it.readText() }
    fun category(id: Long = 1, name: String = "吃饭", keywords: String = "吃,饭") =
        CategoryEntity(id = id, name = name, colorHue = 1f, colorIndex = 0, keywords = keywords)
    val zone: ZoneId get() = ZoneId.systemDefault()
    val now: Long get() = LocalDateTime.of(2026, 9, 21, 20, 0).atZone(zone).toInstant().toEpochMilli()
}

private fun newRenderer(
    ctx: Context,
    nickname: String = "路陌",
    suffix: String = "大人",
    categories: List<CategoryEntity> = listOf(R9.category()),
    context: ChatContext = ChatContext("2026-09-21 20:00（周一）", R9.zone.id, emptyList()),
    candidates: PromptRenderer.CandidateBills = PromptRenderer.CandidateBills(),
    pending: PendingDraft? = null,
    trashCandidates: List<BillEntity> = emptyList(),
    otherBills: List<BillEntity> = emptyList()
) = PromptRenderer(
    systemTemplate = R9.systemText(ctx),
    contextTemplate = R9.contextText(ctx),
    categories = categories,
    context = context,
    nickname = nickname,
    suffix = suffix,
    candidates = candidates,
    pending = pending,
    zone = R9.zone,
    trashCandidates = trashCandidates,
    otherBills = otherBills
)

private fun bill(id: Long = 1, detail: String = "牛肉面", fen: Long = 1200, at: Long = R9.now) =
    BillEntity(id = id, amountFen = fen, type = BillType.EXPENSE, categoryId = 1, detail = detail, timestamp = at)

// ================================================================
// A. system 段「真的」逐字节稳定
// ================================================================
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9SystemSegmentTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `system asset raw bytes are identical across repeated reads`() {
        val reads = (1..5).map { ctx.assets.open(R9.SYSTEM).use { it.readBytes() } }
        reads.forEachIndexed { i, b -> assertTrue("第 $i 次读取与首次不一致", b.contentEquals(reads[0])) }
        val bom = reads[0].size >= 3 && reads[0][0] == 0xEF.toByte() &&
            reads[0][1] == 0xBB.toByte() && reads[0][2] == 0xBF.toByte()
        assertFalse("资源不应带 UTF-8 BOM（会与编辑器显示不一致）", bom)
    }

    @Test
    fun `system asset never mixes CRLF and lone LF`() {
        val text = ctx.assets.open(R9.SYSTEM).use { it.readBytes() }.decodeToString()
        val crlf = Regex("\r\n").findAll(text).count()
        val loneLf = text.count { it == '\n' } - crlf
        val loneCr = text.count { it == '\r' } - crlf
        assertTrue(
            "行尾符混用（CRLF=$crlf 单LF=$loneLf 单CR=$loneCr）会让不同平台/检出结果不一致，破坏缓存前缀",
            (crlf == 0 || loneLf == 0) && loneCr == 0
        )
    }

    @Test
    fun `renderSystem returns the asset verbatim`() {
        assertEquals(R9.systemText(ctx), newRenderer(ctx).renderSystem())
    }

    @Test
    fun `renderSystem never varies with any request data`() {
        val base = newRenderer(ctx).renderSystem()
        val mutated = newRenderer(
            ctx,
            nickname = "完全不同的人",
            suffix = "殿下",
            categories = listOf(R9.category(id = 99, name = "饮品", keywords = "喝")),
            context = ChatContext(
                now = "1999-01-01 00:00（周五）", timeZone = "Asia/Tokyo",
                recentBills = listOf(ChatContext.BillLine("[7] x"))
            ),
            candidates = PromptRenderer.candidatesFrom(listOf(bill(id = 7)), R9.now, R9.zone),
            pending = PendingDraft("5", rawInput = "5", createdAt = R9.now)
        ).renderSystem()
        assertEquals("system 段不许随昵称/时间/分类/账本/挂起账变化", base, mutated)
    }

    @Test
    fun `system segment carries no runtime placeholder`() {
        val placeholder = Regex("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}")
        val s = R9.systemText(ctx)
        assertFalse("发现占位符: ${placeholder.find(s)?.value}", placeholder.containsMatchIn(s))
    }

    @Test
    fun `system segment carries no per-request content`() {
        val s = R9.systemText(ctx)
        assertFalse("system 不该出现昵称", s.contains("路陌"))
        assertFalse("system 不该出现昵称后缀", s.contains("大人"))
        assertFalse("system 不该写死具体日期", Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s))
        assertFalse("system 不该写死带秒时间戳", Regex("\\d{2}:\\d{2}:\\d{2}").containsMatchIn(s))
        // A3 点名要查的：JSON 输出示例里 occurred_at 是否写死了具体日期
        assertFalse(
            "JSON 示例里 occurred_at 被写死成具体日期，会污染模型行为",
            Regex("\"occurred_at\"\\s*:\\s*\"\\d").containsMatchIn(s)
        )
    }

    @Test
    fun `prompt assets are exactly the frozen system plus the dynamic context`() {
        val files = ctx.assets.list("prompts")!!.toList()
        assertTrue(files.contains("parse_bill_system.txt"))
        assertTrue(files.contains("parse_bill_context.txt"))
        assertFalse("旧模板 parse_bill.txt 应已删除", files.contains("parse_bill.txt"))
        assertEquals(AiConfig.SYSTEM_PROMPT_ASSET_PATH, R9.SYSTEM)
        assertEquals(AiConfig.CONTEXT_PROMPT_ASSET_PATH, R9.CONTEXT)
    }
}

// ================================================================
// B. 动态段结构
// ================================================================
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9ContextStructureTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun fullRenderer() = newRenderer(
        ctx,
        categories = listOf(R9.category()),
        candidates = PromptRenderer.candidatesFrom(listOf(bill()), R9.now, R9.zone),
        pending = PendingDraft("5", rawInput = "5", createdAt = R9.now),
        trashCandidates = listOf(bill(id = 9, detail = "回收站的奶")),
        otherBills = listOf(bill(id = 11, detail = "其他分类的账")),
        context = ChatContext(
            now = "2026-09-21 20:00（周一）", timeZone = R9.zone.id,
            recentBills = listOf(ChatContext.BillLine("[1] 9月21日 12:00 · 吃饭 · 牛肉面 · 12.00 元 · 支出"))
        )
    )

    @Test
    fun `dynamic block keeps the documented fixed order when every section is injected`() {
        // 「整理…恢复回收站」同时命中 {candidates}/{trashCandidates}/{otherBills}
        val out = fullRenderer().renderContext("整理一下并恢复回收站里那笔")
        val order = listOf(
            "称呼说明", "现有分类列表", "【待补充的账】", "【最近三天的账本】",
            "【候选账单】", "【回收站候选】", "【「待定」分类下的账单】",
            "当前时间：", "设备时区：", "用户这轮说："
        )
        val idx = order.map { out.indexOf(it) }
        order.forEachIndexed { i, name -> assertTrue("缺少段「$name」\n$out", idx[i] >= 0) }
        for (i in 0 until order.size - 1) {
            assertTrue("顺序错：${order[i]} 应排在 ${order[i + 1]} 之前\n$out", idx[i] < idx[i + 1])
        }
    }

    @Test
    fun `now is rendered to the minute and never carries seconds`() {
        val c = ChatContextBuilder.build(emptyList(), emptyList(), R9.now, R9.zone)
        assertTrue("now 应形如 yyyy-MM-dd HH:mm（周X）: ${c.now}",
            Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}（.+）$").containsMatchIn(c.now))
        assertFalse("now 不该带秒: ${c.now}", Regex(":\\d{2}:\\d{2}").containsMatchIn(c.now))
    }

    @Test
    fun `nickname lives only in the dynamic block never in system`() {
        val r = newRenderer(ctx, nickname = "路陌", suffix = "大人")
        assertTrue("昵称应出现在动态段", r.renderContext("你好").contains("路陌大人"))
        assertFalse("昵称不该进 system", r.renderSystem().contains("路陌"))
        assertFalse("后缀不该进 system", r.renderSystem().contains("大人"))
    }

    @Test
    fun `empty nickname falls back to persona wording without a name`() {
        val out = newRenderer(ctx, nickname = "", suffix = "").renderContext("你好")
        assertTrue("无昵称时应给出兜底说法", out.contains("阿噜"))
        assertFalse("空昵称不该渲染出「希望称他为：」", out.contains("用户希望你称他为："))
    }
}

// ================================================================
// C. 候选段「按需注入」
// ================================================================
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9CandidateInjectionTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun fullRenderer() = newRenderer(
        ctx,
        candidates = PromptRenderer.candidatesFrom(listOf(bill()), R9.now, R9.zone),
        trashCandidates = listOf(bill(id = 9, detail = "回收站的奶")),
        otherBills = listOf(bill(id = 11, detail = "其他分类的账"))
    )

    @Test
    fun `chit chat never injects candidates or trash`() {
        val r = fullRenderer()
        listOf("今天天气不错", "给我唱首歌", "我最近好穷").forEach { input ->
            val out = r.renderContext(input)
            assertFalse("「$input」不该注入候选段", out.contains("【候选账单】"))
            assertFalse("「$input」不该注入回收站段", out.contains("【回收站候选】"))
        }
    }

    @Test
    fun `edit and delete inputs do inject candidates`() {
        val r = fullRenderer()
        listOf("把昨天那笔改成 12", "删掉中午那杯奶茶").forEach { input ->
            assertTrue("「$input」应注入候选段", r.renderContext(input).contains("【候选账单】"))
        }
    }

    @Test
    fun `restore inputs inject trash candidates`() {
        val r = fullRenderer()
        listOf("恢复午饭那笔", "把回收站里的东西捞回来").forEach { input ->
            assertTrue("「$input」应注入回收站段", r.renderContext(input).contains("【回收站候选】"))
        }
    }

    @Test
    fun `boundary inputs are classified exactly as the current keyword rules do`() {
        // ⚠️ 以下带「误判」注释的是经验规则的已知误判，语义上不理想，但代价可接受（见报告）。
        assertTrue(ChatIntent.needsCandidates("这笔账我不是要删但想看看")) // 误判：只是想看，含「删」
        assertTrue(ChatIntent.needsCandidates("改天再聊"))               // 误判：与记账无关，含「改」
        assertTrue(ChatIntent.needsCandidates("我今天感觉不对"))          // 误判：情绪表达，含「不对」
        assertTrue(ChatIntent.needsOtherBills("整理一下我的房间"))         // 误判：整理房间≠整理账单
        assertFalse(ChatIntent.needsCandidates("午饭那条其实花了30"))     // 漏判：其实是改账，但无关键词
        assertFalse(ChatIntent.needsCandidates("😀🎉💰"))                 // pure emoji
        assertFalse(ChatIntent.needsCandidates(""))                      // 空串
        assertFalse(ChatIntent.needsCandidates("   \n  "))               // 纯空白
        assertFalse(ChatIntent.needsTrash("今天天气不错"))
        assertFalse(ChatIntent.needsOtherBills("今天天气不错"))
    }

    @Test
    fun `regex metacharacters in input never throw and are not misread as commands`() {
        val dirty = listOf(".*+?[]{}()\\^$|", "([{", "a|b", "\\d+元", "改*删+")
        dirty.forEach { input ->
            // 只要三处预判都不抛异常即可（input 是主语，不是 pattern）
            ChatIntent.needsCandidates(input)
            ChatIntent.needsTrash(input)
            ChatIntent.needsOtherBills(input)
        }
        assertFalse(ChatIntent.needsCandidates(".*+?[]{}()"))
        assertTrue("「改*删+」含关键词，应命中", ChatIntent.needsCandidates("改*删+"))
        // 含换行/超长的动态段渲染必须稳定不崩
        val long = "改" + "超长文本".repeat(6000)
        val out = newRenderer(ctx).renderContext(long)
        assertTrue("超长输入必须原样出现在末段", out.length > long.length)
        assertTrue(newRenderer(ctx).renderContext("第一行\n第二行").contains("第一行\n第二行"))
    }

    @Test
    fun `degenerate category and bill data never crash rendering`() {
        // 空分类表
        assertTrue(newRenderer(ctx, categories = emptyList()).renderContext("你好").contains("现有分类列表"))
        // 名称为空串的分类
        val blankCat = newRenderer(ctx, categories = listOf(R9.category(id = 1, name = "")))
            .renderContext("你好")
        assertTrue("空名称分类应显示「未分类」", blankCat.contains("未分类"))
        // 账单指向不存在的分类 → 行内分类回退「未分类」，不崩
        val orphans = newRenderer(
            ctx,
            categories = emptyList(),
            candidates = PromptRenderer.candidatesFrom(listOf(bill(id = 3, detail = "孤儿账")), R9.now, R9.zone)
        ).renderContext("改一下那笔")
        assertTrue(orphans.contains("孤儿账"))
        assertTrue(orphans.contains("未分类"))
    }
}

// ================================================================
// D. 草稿不进上下文（真 SQLite）
// ================================================================
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9DraftContextTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableSetOf<String>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        names.forEach { ctx.deleteDatabase(it) }
    }

    private fun db(name: String) = AppDatabase.build(ctx, name).also { opened += it; names += name }

    /** R9/T02b：历史 = 只挑 USER/ASSISTANT 原文（草稿卡与指令卡都不在其中）。 */
    private fun historyLineFor(message: ChatMessageEntity): String = runBlocking {
        val h = ChatHistoryRepository(db("r9-draft-${message.kind}-${message.status}-${message.id}.db"))
        h.insert(message)
        AiRepository.chatTurnsFor(h.getAll(), R9.now)
            .joinToString("\n") { "${it.role}:${it.content}" }
    }

    private fun draft(status: String) = ChatMessageEntity(
        kind = "DRAFT", status = status, createdAt = R9.now,
        draftPayload = DraftHistoryCodec.encode(
            listOf(DraftUi(amountText = "12", detail = "牛肉面", categoryName = "吃饭"))
        )
    )

    @Test
    fun `draft cards never enter the model history whatever their status`() {
        // v0.6 定稿（R6 追加式）：历史只挑 USER/ASSISTANT 原文；草稿卡（kind=DRAFT）任何状态都不进。
        // 「已确认」这件事由 ChatViewModel 追加的 assistant 消息承担——见下一条用例。
        listOf("EDITING", "DISMISSED", "DELETED", "CONFIRMED", "").forEach { status ->
            assertFalse("$status 草稿卡不该作为历史行进入上下文", historyLineFor(draft(status)).contains("牛肉面"))
        }
    }

    @Test
    fun `a confirmed draft reaches the model through the appended assistant message`() {
        // 追加式铁律：状态变化只追加新消息、永不回改历史。
        // 确认入账后 ChatViewModel 会 append「已入库 N 条！」+ 接话，那条 ASSISTANT 原文就是模型看到的东西。
        val appended = ChatMessageEntity(
            kind = "ASSISTANT", content = "已入库 1 条！牛肉面 12 元记好啦", createdAt = R9.now
        )
        assertTrue("追加的 assistant 消息应进历史", historyLineFor(appended).contains("牛肉面"))
    }

    @Test
    fun `a command card never enters the model history its outcome is appended instead`() {
        val cmd = ChatMessageEntity(
            kind = "COMMAND", status = "EDITING", createdAt = R9.now,
            draftPayload = CommandCardCodec.encode(
                CommandKind.UPDATE,
                listOf(CommandItem(billId = 1, title = "牛肉面", newAmountText = "15"))
            )
        )
        assertFalse("指令卡不该作为历史行进入上下文", historyLineFor(cmd).contains("牛肉面"))
    }
}

// ================================================================
// E + F. 硬约束 & 历史「冻结」是否名存实亡
// ================================================================
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9HistoryFreezeTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableSetOf<String>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        names.forEach { ctx.deleteDatabase(it) }
    }

    private fun db(name: String) = AppDatabase.build(ctx, name).also { opened += it; names += name }

    @Test
    fun `hard constraints stay at 60 messages and 30 bills`() {
        assertEquals("历史窗口不得收敛（不许引入 12 轮裁切）", 60, ChatContextBuilder.MAX_MESSAGES)
        assertEquals("账本条数从 60 收敛到 30", 30, ChatContextBuilder.MAX_BILLS)
    }

    @Test
    fun `history caps at 60 messages and the ledger block at 30 bills`() = runBlocking {
        val h = ChatHistoryRepository(db("r9-caps.db"))
        // 交替 USER/ASSISTANT：让第 70 条（i=69）落在 assistant 上——
        // chatTurnsFor 会裁掉「末尾未配对的 user」，若全插 USER 就只剩 59 条，测的就不是上限本身了。
        repeat(70) { i ->
            val kind = if (i % 2 == 0) "USER" else "ASSISTANT"
            h.insert(ChatMessageEntity(kind = kind, content = "m$i", createdAt = R9.now - 60_000 + i))
        }
        val turns = AiRepository.chatTurnsFor(h.getAll(), R9.now)
        assertEquals(60, turns.size)

        val bills = (1..40).map { bill(id = it.toLong(), detail = "b$it") }
        val ctx = ChatContextBuilder.build(bills, emptyList(), R9.now, R9.zone)
        assertEquals(30, ctx.recentBills.size)
    }

    /**
     * F 项核心：历史行是否「只依赖消息自身」。
     *
     * v0.6 起历史由 [AiRepository.chatTurnsFor] 从消息表直接取原文（USER/ASSISTANT），
     * 不再经过任何摘要渲染；本条锁定：同一份消息表取的两次历史逐字一致，
     * 且与账本/分类/时间推进无关——否则前缀缓存会被反复重写，R9 就白做了。
     */
    @Test
    fun `history is a pure function of stored messages and never drifts with ledger state`() = runBlocking {
        val h = ChatHistoryRepository(db("r9-freeze.db"))
        h.insert(ChatMessageEntity(kind = "USER", content = "昨天中午吃饭 9 元", createdAt = R9.now - 3_000))
        h.insert(ChatMessageEntity(kind = "ASSISTANT", content = "记好啦～", createdAt = R9.now - 2_000))
        h.insert(
            ChatMessageEntity(
                kind = "DRAFT", status = "CONFIRMED", createdAt = R9.now - 1_000,
                draftPayload = DraftHistoryCodec.encode(
                    listOf(DraftUi(amountText = "9", detail = "午饭", categoryName = "吃饭"))
                )
            )
        )

        val first = AiRepository.chatTurnsFor(h.getAll(), R9.now)
        // 时间推进一小时（仍在 24h 窗口内）：历史必须逐字不变。
        val second = AiRepository.chatTurnsFor(h.getAll(), R9.now + 3_600_000L)

        assertEquals("历史在时间推进后必须逐字不变（冻结）", first, second)
        assertEquals("历史只含 USER/ASSISTANT 原文，草稿卡不进", 2, first.size)
        assertEquals("昨天中午吃饭 9 元", first.first().content)
    }

    @Test
    fun `empty history renders without crashing`() {
        val c = ChatContextBuilder.build(emptyList(), emptyList(), R9.now, R9.zone)
        assertTrue(c.recentBills.isEmpty())
        assertTrue(newRenderer(ctx, context = c).renderContext("你好").contains("最近三天还没有记过账"))
    }
}

// ================================================================
// 反向：线上 messages 组装 & 缓存字段埋点
// ================================================================
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class R9WireFormatTest {
    private val okBody = """
        {"choices":[{"message":{"content":"{\"bills\":[],\"reply\":\"hi\"}"}}],
         "usage":{"prompt_cache_hit_tokens":1234,"prompt_cache_miss_tokens":5678}}
    """.trimIndent()
    private val noUsageBody = """{"choices":[{"message":{"content":"{\"bills\":[],\"reply\":\"ok\"}"}}]}"""

    /** 用拦截器截获发往 DeepSeek 的请求体，返回 (capturedRequestJson, success)。 */
    private fun invoke(history: List<ChatTurn>, responseBody: String): Pair<String, Boolean> {
        val captured = AtomicReference("")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val buffer = Buffer()
            req.body?.writeTo(buffer)
            captured.set(buffer.readUtf8())
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = runBlocking {
            DeepSeekClient("test-key", client).parseBill("SYS-PROMPT", "USER-CONTEXT", history)
        }
        return captured.get() to result.isSuccess
    }

    @Test
    fun `messages start with the frozen system then a user turn, dropping leading assistant turns`() {
        val history = listOf(
            ChatTurn("assistant", "被切走 user 轮后的残响"),
            ChatTurn("user", "早上好"),
            ChatTurn("assistant", "好呀")
        )
        val (raw, ok) = invoke(history, okBody)
        assertTrue("解析应成功", ok)
        val messages = Json.parseToJsonElement(raw).jsonObject["messages"]!!.jsonArray
        assertEquals("首条必须是 system", "system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("SYS-PROMPT", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("system 之后首条必须是 user（开头 assistant 全丢）",
            "user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("早上好", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("末条必须是本轮 user 输入",
            "USER-CONTEXT", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an all-assistant history is dropped entirely`() {
        val (raw, ok) = invoke(listOf(ChatTurn("assistant", "a"), ChatTurn("assistant", "b")), okBody)
        assertTrue(ok)
        val messages = Json.parseToJsonElement(raw).jsonObject["messages"]!!.jsonArray
        assertEquals("只剩 system + 本轮 user", 2, messages.size)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cache hit and miss tokens are logged when the usage block is present`() {
        ShadowLog.clear()
        invoke(emptyList(), okBody)
        val logged = ShadowLog.getLogs().filter { it.tag == "DeepSeekClient" }.map { it.msg }
        assertTrue("应打印命中/未命中 token，实际日志：$logged",
            logged.any { it.contains("prompt cache: hit=1234 miss=5678") })
    }

    @Test
    fun `a response without a usage block still parses`() {
        val (_, ok) = invoke(emptyList(), noUsageBody)
        assertTrue("缺 usage 不该影响解析", ok)
    }
}
