package com.jiligulu.app.ui.littleworld

import kotlin.math.abs
import kotlin.math.max

/**
 * 展示层留的一帧棋局。
 *
 * **为什么必须由展示层自己留**：[XiangqiState] 只有 `board / lastMove / ply`，
 * 吃子时被吃的棋子已经从 board 里消失、引擎并不记录它——所以"一步之前"的局面
 * 根本无法从当前状态反推。动画是展示行为，棋局仍由 [XiangqiEngine] 维护，
 * 这里只负责把已经发生的一步**演出来**。
 */
internal data class XiangqiViewSnapshot(val board: List<Int>, val lastMove: XiangqiMove?)

/** 正在演出的一步。 */
internal class XiangqiStepAnim(
    val from: GridCell,
    val to: GridCell,
    val mover: Int,
    /** 被吃棋子；0 表示这一步不吃子。 */
    val captured: Int,
    val durationMillis: Int
)

/**
 * 走子动画的节奏与数据准备。
 *
 * 第一阶段只做**可靠的起点→终点直线移动**；马走折线、象走对角、炮越炮架属于
 * 路径差异，等基础移动在真机上确认不重影之后再补——先把可靠做出来，比一次做全更安全。
 */
internal object XiangqiMoveAnimator {

    const val MIN_DURATION_MS = 280
    const val MAX_DURATION_MS = 420
    const val PER_RANK_MS = 28

    /** 被吃棋子在碰撞后才出现，保留 [CAPTURE_FADE_MS] 缩小淡出。 */
    const val CAPTURE_FADE_MS = 100

    /** 吃子碰撞发生在移动进度的这个比例之后，前面的路上看不到被吃子。 */
    const val CAPTURE_COLLIDE_AT = 0.82f

    /** 绝杀时间线：落稳后先停一下，再进杀法演出。 */
    const val FINISH_PAUSE_MS = 80

    /** 落点闪烁与轨迹停留。 */
    const val LANDING_FLASH_MS = 320
    const val TRAIL_HOLD_MS = 1500

    /** 距离越远稍长，但夹在 280–420ms，避免长距离显得拖沓。 */
    fun durationFor(move: XiangqiMove): Int {
        val span = max(abs(move.to.x - move.from.x), abs(move.to.y - move.from.y))
        return (MIN_DURATION_MS + (span - 1) * PER_RANK_MS).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /**
     * 从前后两帧推出这一步该怎么演；推不出来就返回 null（不演）。
     *
     * 返回 null 的几种情况都应当**不播动画**而不是播一个假的：
     * - 没有 lastMove（开局、读档）
     * - 棋盘没变（悔棋、状态纠正等非落子刷新）
     * - 起点没有棋子（数据异常，宁可不演也不画一个来源不明的棋子）
     */
    fun stepFrom(previous: XiangqiViewSnapshot, next: XiangqiViewSnapshot): XiangqiStepAnim? {
        val move = next.lastMove ?: return null
        if (previous.board == next.board) return null
        val mover = previous.board[move.from.y * 9 + move.from.x]
        if (mover == 0) return null
        val captured = previous.board[move.to.y * 9 + move.to.x]
        return XiangqiStepAnim(move.from, move.to, mover, captured, durationFor(move))
    }

    /** 移动进度：起步稍快、落稳收住（FastOutSlowIn 的手写近似）。 */
    fun travel(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    /** 被吃棋子的缩放与透明度：碰撞瞬间还是满的，随后 [CAPTURE_FADE_MS] 内缩没。 */
    fun captureFade(t: Float): Float =
        ((t - CAPTURE_COLLIDE_AT) / (1f - CAPTURE_COLLIDE_AT)).coerceIn(0f, 1f)
}
