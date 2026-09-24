package com.jiligulu.app.ui.components

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A composed page owns its background subscription; disposing the page cancels all its work. */
@Composable
fun <T> rememberPageData(source: Flow<T>, initial: T): State<T> {
    val scope = remember(source) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val shared = remember(source) { source.stateIn(scope, SharingStarted.WhileSubscribed(0), initial) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    return shared.collectAsStateWithLifecycle()
}
