package com.jiligulu.app.ui.littleworld

/** The requester consents by asking; only the other player can approve the request. */
internal data class XiangqiUndoRequest(val revision: Int, val id: Int, val requester: XiangqiSide)

internal enum class XiangqiUndoResolution(val hint: String) {
    REJECTED("伙伴暂时不想悔棋"), TIMEOUT("悔棋请求已超时"), CANCELLED("棋局已更新，悔棋请求已取消"),
    STALE("棋局已更新，请重新发起悔棋"), BUSY("已有一个悔棋请求等待回复"), EMPTY("还没有可以退回的落子"),
}

internal enum class XiangqiUndoOffer { ACCEPTED, DUPLICATE, STALE, BUSY, EMPTY }

/** Both transports keep the same bounded, locally verified history. No peer supplies an undo target. */
internal class XiangqiUndoHistory {
    private val previous = ArrayDeque<XiangqiState>()
    private val seenIds = IntArray(2)
    private var nextLocalId = 0
    private var pendingPosition: XiangqiState? = null
    private var approvedByLocalResponder = false
    var pending: XiangqiUndoRequest? = null
        private set
    val canUndo: Boolean get() = previous.isNotEmpty() && pending == null
    fun canUndo(side: XiangqiSide): Boolean = pending == null && targetIndex(side) >= 0
    private fun targetIndex(side: XiangqiSide): Int = previous.indexOfLast {
        it.turnSide == side && it.outcome == XiangqiOutcome.PLAYING
    }
    private fun target(side: XiangqiSide): XiangqiState? = targetIndex(side).takeIf { it >= 0 }?.let { previous.elementAt(it) }
    private fun popTo(side: XiangqiSide): XiangqiState? {
        val index = targetIndex(side)
        if (index < 0) return null
        val result = previous.elementAt(index)
        while (previous.size > index) previous.removeLast()
        return result
    }

    /** Call only after ordinary snapshot validation; a fresh game starts a fresh history. */
    fun recordAdvance(current: XiangqiState, next: XiangqiState): Boolean {
        if (next == XiangqiEngine.newGame()) {
            previous.clear()
        } else {
            if (next.lastMove?.let { XiangqiEngine.play(current, it) == next } != true) return false
            if (previous.size == 512) previous.removeFirst()
            previous.addLast(current)
        }
        cancelPending()
        return true
    }

    fun beginLocal(revision: Int, game: XiangqiState, side: XiangqiSide): XiangqiUndoRequest? {
        if (!canUndo(side) || revision == Int.MAX_VALUE || nextLocalId == Int.MAX_VALUE) return null
        val request = XiangqiUndoRequest(revision, ++nextLocalId, side)
        seenIds[side.ordinal] = request.id
        bind(request, game)
        return request
    }

    fun receiveOffer(
        request: XiangqiUndoRequest,
        revision: Int,
        game: XiangqiState,
        localSide: XiangqiSide,
        host: Boolean,
    ): XiangqiUndoOffer {
        if (request.requester != localSide.opponent || request.id <= 0 || request.revision != revision ||
            revision == Int.MAX_VALUE) return XiangqiUndoOffer.STALE
        if (request == pending && game == pendingPosition) return XiangqiUndoOffer.DUPLICATE
        if (request.id <= seenIds[request.requester.ordinal]) return XiangqiUndoOffer.STALE
        seenIds[request.requester.ordinal] = request.id
        // A host offer wins a simultaneous request. The guest never rewrites the host's pending offer.
        if (pending != null && (host || pending?.requester != localSide)) return XiangqiUndoOffer.BUSY
        if (target(request.requester) == null) return XiangqiUndoOffer.EMPTY
        bind(request, game)
        return XiangqiUndoOffer.ACCEPTED
    }

    /** Mark consent only on the responding device, before sending its acceptance to the host. */
    fun consentLocally(revision: Int, game: XiangqiState, localSide: XiangqiSide): Boolean {
        val request = pending ?: return false
        if (request.requester == localSide || request.revision != revision || pendingPosition != game) return false
        approvedByLocalResponder = true
        return true
    }

    /** Host-only commit: the authenticated responding side must be the requester's opponent. */
    fun commitHost(
        request: XiangqiUndoRequest,
        responder: XiangqiSide,
        revision: Int,
        game: XiangqiState,
    ): XiangqiState? {
        if (pending != request || responder != request.requester.opponent || request.revision != revision ||
            revision == Int.MAX_VALUE || pendingPosition != game || previous.isEmpty()) return null
        val target = popTo(request.requester) ?: return null
        cancelPending()
        return target
    }

    fun acceptsGuestUndo(
        localSide: XiangqiSide,
        revision: Int,
        game: XiangqiState,
        next: XiangqiLanMessage.UndoSnapshot,
    ): Boolean {
        val request = pending ?: return false
        return next.request == request && request.revision == revision && revision < Int.MAX_VALUE &&
            pendingPosition == game && next.snapshot.revision == revision + 1 && previous.isNotEmpty() &&
            next.snapshot.game == target(request.requester) &&
            (localSide == request.requester || approvedByLocalResponder)
    }

    /** Must follow acceptsGuestUndo; stale or repeated undo commits cannot pop another position. */
    fun commitGuestUndo(
        localSide: XiangqiSide,
        revision: Int,
        game: XiangqiState,
        next: XiangqiLanMessage.UndoSnapshot,
    ): Boolean {
        if (!acceptsGuestUndo(localSide, revision, game, next)) return false
        popTo(next.request.requester) ?: return false
        cancelPending()
        return true
    }

    fun cancelIfMatches(request: XiangqiUndoRequest): Boolean {
        if (pending != request) return false
        cancelPending()
        return true
    }

    fun cancelPending() {
        pending = null
        pendingPosition = null
        approvedByLocalResponder = false
    }

    private fun bind(request: XiangqiUndoRequest, game: XiangqiState) {
        pending = request
        pendingPosition = game
        approvedByLocalResponder = false
    }
}
