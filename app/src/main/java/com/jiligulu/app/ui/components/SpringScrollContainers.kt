package com.jiligulu.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp

/** Page lists keep the user's scroll anchor; editor fields retain the normal keyboard behavior. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpringLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    manualScrollOnly: Boolean = true,
    handOffOnRepeat: Boolean = false,
    onTopPull: ((Float)->Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val gate = remember { EdgeSpringState() }
    val originalSpec = LocalBringIntoViewSpec.current
    val fixedSpec = remember { object : BringIntoViewSpec { override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f } }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null,
        LocalBringIntoViewSpec provides if (manualScrollOnly) fixedSpec else originalSpec) {
        Box(modifier.clipToBounds()) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()
                .edgeSpring({ state.canScrollBackward }, { state.canScrollForward }, handOffOnRepeat = handOffOnRepeat, state = gate, onTopPull = onTopPull),
                contentPadding = contentPadding, verticalArrangement = verticalArrangement, content = content)
            Box(Modifier.matchParentSize()) { LedgerScrollBar(state, Modifier.align(Alignment.CenterEnd), forceVisible = gate.visible) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpringLazyGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    handOffOnRepeat: Boolean = false,
    content: LazyGridScope.() -> Unit
) {
    val gate = remember { EdgeSpringState() }
    val fixedSpec = remember { object : BringIntoViewSpec { override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f } }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null, LocalBringIntoViewSpec provides fixedSpec) {
        Box(modifier.clipToBounds()) {
            LazyVerticalGrid(columns = GridCells.Fixed(columns.coerceAtLeast(1)), state = state,
                modifier = Modifier.fillMaxSize().edgeSpring({ state.canScrollBackward }, { state.canScrollForward }, handOffOnRepeat = handOffOnRepeat, state = gate),
                contentPadding = contentPadding, verticalArrangement = verticalArrangement, horizontalArrangement = horizontalArrangement, content = content)
            Box(Modifier.matchParentSize()) { LedgerScrollBar(state, columns, Modifier.align(Alignment.CenterEnd), forceVisible = gate.visible) }
        }
    }
}

/** Suitable for bounded dialog bodies. Put weight/height on the wrapper, not the inner column. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpringScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    handOffOnRepeat: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val gate = remember { EdgeSpringState() }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(modifier.clipToBounds()) {
            // Let a nested picker consume first; only its leftover boundary motion rebounds
            // this dialog body. Pre-consumption here would freeze a child grid's scrolling.
            Column(Modifier.fillMaxWidth().edgeSpring({ state.canScrollBackward }, { state.canScrollForward }, handOffOnRepeat = handOffOnRepeat, interceptPre = false, state = gate)
                .verticalScroll(state).padding(end = 8.dp), verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment, content = content)
            // An overlay must not ask for the maximum height before the short dialog body is
            // measured; doing so stretches a one-line note into a screen-sized empty dialog.
            Box(Modifier.matchParentSize()) { LedgerScrollBar(state, Modifier.align(Alignment.CenterEnd), forceVisible = gate.visible) }
        }
    }
}
