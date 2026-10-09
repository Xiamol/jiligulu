package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jiligulu.app.ui.components.SpringScrollColumn

/** Every fortune tab keeps the same viewport and action rail, including long readings. */
@Composable
internal fun FortunePageLayout(
    modifier: Modifier = Modifier,
    scrollBody: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("fortune-body"), contentAlignment = Alignment.TopCenter) {
            val bodyModifier = Modifier.widthIn(max = 340.dp).fillMaxSize().padding(horizontal = 12.dp)
            if (scrollBody) SpringScrollColumn(bodyModifier, verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, content = body)
            else Column(bodyModifier, horizontalAlignment = Alignment.CenterHorizontally, content = body)
        }
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth().height(88.dp).padding(horizontal = 12.dp)
            .testTag("fortune-footer"), horizontalAlignment = Alignment.CenterHorizontally, content = footer)
    }
}
