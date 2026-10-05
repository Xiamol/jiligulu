package com.jiligulu.app.ui.littleworld

import android.os.SystemClock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun ColumnScope.SecretSnakeGame(state: SnakeState, running: Boolean, started: Boolean, boardSize: Dp,
    onDirection: (SnakeDirection) -> Unit, onToggle: () -> Unit, onRestart: () -> Unit) {
    val latestDirection by rememberUpdatedState(onDirection)
    val status = when {
        state.won -> "小蛇把星星全收好啦！"
        state.gameOver -> "碰到啦，再陪小蛇走一圈吧"
        running -> "吃星星得分，小心墙壁和自己的尾巴"
        started -> "小蛇休息中，继续时会从这里出发"
        else -> "点开始，再用方向键或滑动转弯"
    }
    Row(Modifier.width(boardSize), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("✦ ${state.score}", color = Color(0xFF96805A), style = MaterialTheme.typography.titleLarge)
        Text(if (running) "小蛇出发啦" else if (state.gameOver) "本局结束" else "休息一下", style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF887F86))
    }
    Spacer(Modifier.height(24.dp))
    Canvas(Modifier.size(boardSize).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0F1DF))
        .semantics { contentDescription = "贪吃蛇棋盘，收好${state.score}颗星星。可以滑动改变方向。" }
        .pointerInput(Unit) {
            var drag = Offset.Zero
            detectDragGestures(onDragStart = { drag = Offset.Zero }, onDragEnd = {
                if (drag.getDistance() >= 12.dp.toPx()) latestDirection(
                    if (abs(drag.x) > abs(drag.y)) {
                        if (drag.x > 0) SnakeDirection.RIGHT else SnakeDirection.LEFT
                    } else if (drag.y > 0) SnakeDirection.DOWN else SnakeDirection.UP)
            }, onDragCancel = { drag = Offset.Zero }) { change, amount -> change.consume(); drag += amount }
        }) {
        val cellW = size.width / state.width
        val cellH = size.height / state.height
        repeat(state.width + 1) { x -> drawLine(Color(0xFF638867).copy(alpha = .09f),
            Offset(x * cellW, 0f), Offset(x * cellW, size.height), 1.dp.toPx()) }
        repeat(state.height + 1) { y -> drawLine(Color(0xFF638867).copy(alpha = .09f),
            Offset(0f, y * cellH), Offset(size.width, y * cellH), 1.dp.toPx()) }
        state.food?.let { food ->
            val center = Offset((food.x + .5f) * cellW, (food.y + .5f) * cellH)
            val radius = cellW * .43f
            val star = Path()
            repeat(10) { i ->
                val angle = -Math.PI / 2 + i * Math.PI / 5
                val r = if (i % 2 == 0) radius else radius * .48f
                val point = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
                if (i == 0) star.moveTo(point.x, point.y) else star.lineTo(point.x, point.y)
            }
            star.close(); drawPath(star, Color(0xFFE9AD43))
        }
        state.body.forEachIndexed { index, cell ->
            val inset = if (index == 0) .06f else .11f
            drawRoundRect(if (index == 0) Color(0xFF446D49) else Color(0xFF76A16E),
                Offset((cell.x + inset) * cellW, (cell.y + inset) * cellH),
                Size(cellW * (1 - inset * 2), cellH * (1 - inset * 2)), CornerRadius(cellW * .23f))
        }
        val head = state.body.first()
        val center = Offset((head.x + .5f) * cellW, (head.y + .5f) * cellH)
        val forward = Offset(state.direction.dx.toFloat(), state.direction.dy.toFloat()) * (cellW * .17f)
        val across = Offset(-state.direction.dy.toFloat(), state.direction.dx.toFloat()) * (cellW * .20f)
        listOf(center + forward + across, center + forward - across).forEach { eye ->
            drawCircle(Color(0xFFFFF8DF), cellW * .105f, eye)
            drawCircle(Color(0xFF334039), cellW * .049f, eye + forward * .2f)
        }
    }
    Text(status, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF887F86))
    Spacer(Modifier.height(12.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SnakeDirectionButton("↑", "向上", onClick = { onDirection(SnakeDirection.UP) }, enabled = !state.gameOver)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SnakeDirectionButton("←", "向左", onClick = { onDirection(SnakeDirection.LEFT) }, enabled = !state.gameOver)
            SnakeDirectionButton("↓", "向下", onClick = { onDirection(SnakeDirection.DOWN) }, enabled = !state.gameOver)
            SnakeDirectionButton("→", "向右", onClick = { onDirection(SnakeDirection.RIGHT) }, enabled = !state.gameOver)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = onToggle, enabled = !state.gameOver,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.width(120.dp)) { Text(if (running) "暂停" else if (started) "继续" else "开始") }
        TextButton(onClick = onRestart) { Text("重新开始") }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SnakeDirectionButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(width = 60.dp, height = 46.dp)
        .semantics { contentDescription = label }, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(14.dp)) {
        Text(symbol, fontSize = 25.sp)
    }
}

@Composable
internal fun ColumnScope.SecretGomokuGame(state: GomokuState, paused: Boolean, boardSize: Dp,
    onMove: (Int, Int) -> Unit, onToggle: () -> Unit, onRestart: () -> Unit,
    helpBusy: Boolean = false, onSecretHelp: () -> Unit = {}) {
    val latestMove by rememberUpdatedState(onMove)
    val canMove = !paused && !helpBusy && state.currentPlayer == 1 && state.outcome == GomokuOutcome.PLAYING
    val taps = remember(state.board, state.currentPlayer, paused, helpBusy) { HiddenGameHelpTapSequence() }
    val latestHelp by rememberUpdatedState(onSecretHelp)
    val wood = remember { Brush.linearGradient(listOf(Color(0xFFF2DFB9), Color(0xFFE5C79A))) }
    val status = when (state.outcome) {
        GomokuOutcome.HUMAN_WON -> "你连成五颗啦，阿噜给你鼓掌 ♡"
        GomokuOutcome.CPU_WON -> "阿噜连成五颗了，再来一局？"
        GomokuOutcome.DRAW -> "棋盘坐满啦，这一局平手 ♡"
        GomokuOutcome.PLAYING -> if (paused) "棋局已暂停，棋子都替你留着" else if (state.currentPlayer == 1) "轮到你了 · 你执黑棋" else "阿噜在想下一步…"
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
    // The header takes 56 dp; offset half of it so the board itself centers on the page.
    val boardTop = ((maxHeight - boardSize) / 2 - 28.dp).coerceAtLeast(60.dp)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.height((boardTop - 60.dp).coerceAtLeast(0.dp)))
    Row(Modifier.width(boardSize).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        GameSeat("你", Color(0xFF4C4950), active = state.currentPlayer == 1 && !paused)
        Spacer(Modifier.weight(1f))
        GameSeat("阿噜", Color(0xFFF9F5EA), active = state.currentPlayer == 2 && !paused)
    }
    Spacer(Modifier.height(24.dp))
    Canvas(Modifier.size(boardSize).shadow(3.dp, RoundedCornerShape(13.dp), clip = false)
        .clip(RoundedCornerShape(13.dp)).background(wood).drawWithCache {
            val grains = List(24) { band ->
                val x = size.width * (band + .4f) / 24f
                Offset(x, 0f) to Offset(x + 4.dp.toPx(), size.height)
            }
            onDrawBehind {
                grains.forEachIndexed { index, (start, end) ->
                    drawLine(Color(0xFFB79361).copy(alpha = if (index % 3 == 0) .055f else .023f), start, end,
                        (1 + index % 3).dp.toPx())
                }
                drawRoundRect(Color(0xFFBD9966).copy(alpha = .6f), cornerRadius = CornerRadius(13.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
        .semantics { contentDescription = "${state.size}路五子棋棋盘，你执黑棋，阿噜执白棋。$status" }
        .pointerInput(canMove, state.size) {
            if (canMove) detectTapGestures { offset ->
                val padding = size.width * .047f
                val step = (size.width - padding * 2) / (state.size - 1)
                val x = ((offset.x - padding) / step).roundToInt()
                val y = ((offset.y - padding) / step).roundToInt()
                if (x in 0 until state.size && y in 0 until state.size) latestMove(x, y)
            }
        }) {
        val padding = size.width * .047f
        val step = (size.width - padding * 2) / (state.size - 1)
        val edge = size.width - padding
        repeat(state.size) { line ->
            val p = padding + line * step
            drawLine(Color(0xFF886844), Offset(padding, p), Offset(edge, p), 1.dp.toPx())
            drawLine(Color(0xFF886844), Offset(p, padding), Offset(p, edge), 1.dp.toPx())
        }
        val farStar = state.size - 4
        listOf(GridCell(3, 3), GridCell(state.size / 2, state.size / 2), GridCell(farStar, 3), GridCell(3, farStar), GridCell(farStar, farStar))
            .filter { it.x < state.size && it.y < state.size }.forEach { dot ->
                drawCircle(Color(0xFF886844), step * .09f, Offset(padding + dot.x * step, padding + dot.y * step))
            }
        state.board.forEachIndexed { i, stone ->
            if (stone != 0) {
                val center = Offset(padding + (i % state.size) * step, padding + (i / state.size) * step)
                drawCircle(Color(0xFF715034).copy(alpha = .25f), step * .44f, center + Offset(1.dp.toPx(), 2.dp.toPx()))
                drawCircle(if (stone == 1) Color(0xFF3D3C43) else Color(0xFFFFFBEF), step * .43f, center)
                drawCircle(if (stone == 1) Color.White.copy(alpha = .20f) else Color.White, step * .12f,
                    center - Offset(step * .14f, step * .14f))
            }
        }
        state.lastMove?.let { move ->
            drawCircle(Color(0xFFE6A149), step * .16f, Offset(padding + move.x * step, padding + move.y * step), style = Stroke(1.5.dp.toPx()))
        }
    }
    Text(status, modifier = Modifier.padding(top = 14.dp).pointerInput(taps, canMove) {
        detectTapGestures { if (taps.tap(SystemClock.elapsedRealtime(), canMove)) latestHelp() }
    }, color = Color(0xFF766A7F), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.width(boardSize).padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GameIconTool(if (paused) Icons.Outlined.PlayCircleOutline else Icons.Outlined.PauseCircleOutline,
            if (paused) "继续" else "暂停", onToggle, Modifier.weight(1f), enabled = state.outcome == GomokuOutcome.PLAYING)
        GameIconTool(Icons.Outlined.Refresh, "重开", onRestart, Modifier.weight(1f))
    }
    Text("黑棋先行 · 连成五子获胜", Modifier.padding(top = 4.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C8D98))
    }
    }
}

@Composable
internal fun GameSeat(name: String, stoneColor: Color, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(17.dp)) {
            drawCircle(Color(0xFFAE9B88).copy(alpha = .16f), center = center + Offset(0f, 1.dp.toPx()))
            drawCircle(stoneColor, radius = size.width * .44f)
            drawCircle(Color(0xFFA58E74).copy(alpha = .3f), radius = size.width * .44f, style = Stroke(.7.dp.toPx()))
        }
        Text(name, style = MaterialTheme.typography.labelLarge, color = if (active) Color(0xFF655171) else Color(0xFF9B8F97))
        if (active) Canvas(Modifier.size(4.dp)) { drawCircle(Color(0xFFB29BC4)) }
    }
}
