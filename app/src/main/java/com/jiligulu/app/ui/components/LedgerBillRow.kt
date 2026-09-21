package com.jiligulu.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiligulu.app.domain.color.GoldenAnglePalette
import com.jiligulu.app.ui.theme.ExpenseGreen
import com.jiligulu.app.ui.theme.IncomeRed

/** One row for both the ledger and statistics. Its parent owns the group outline and corners. */
@Composable
fun LedgerBillRow(
    icon: String,
    colorHue: Float,
    title: String,
    subtitle: String,
    amountText: String,
    isExpense: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false
) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "查看和编辑账单", onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).background(
                    GoldenAnglePalette.colorForHue(colorHue).copy(alpha = 0.12f),
                    MaterialTheme.shapes.large
                ), contentAlignment = Alignment.Center
            ) { Text(icon, style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(amountText, style = MaterialTheme.typography.titleMedium,
                color = if (isExpense) ExpenseGreen else IncomeRed)
        }
        if (showDivider) {
            val color = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)) {
                drawLine(color, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
        }
    }
}
