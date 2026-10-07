package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/** Painted props are already the button. A press must not paint a second rectangle over them. */
@Composable
internal fun Modifier.sceneClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    onClick = onClick
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.sceneCombinedClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = combinedClickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    onLongClickLabel = onLongClickLabel,
    onLongClick = onLongClick,
    onClick = onClick
)
