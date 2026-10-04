package com.jiligulu.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.domain.color.GoldenAnglePalette
import com.jiligulu.app.ui.components.BillDateTimeField
import com.jiligulu.app.ui.components.CategoryBadge
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.SpringLazyGrid
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.theme.IncomeGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The important fields stay in the draft; the full category catalogue lives in a picker. */
@Composable
internal fun DraftEditorRow(
    draft: DraftUi,
    categories: List<CategoryEntity>,
    enabled: Boolean,
    tag: String,
    onUpdate: ((DraftUi) -> DraftUi) -> Unit
) {
    var showCategories by rememberSaveable(tag) { mutableStateOf(false) }
    var showTime by rememberSaveable(tag) { mutableStateOf(false) }
    var showNote by rememberSaveable(tag) { mutableStateOf(false) }
    val category = remember(categories, draft.categoryName) {
        categories.firstOrNull { it.name.equals(draft.categoryName, true) }
    }
    val moneyColor = if (draft.type == BillType.EXPENSE) ExpenseCoral else IncomeGreen
    val timeLabel = remember(draft.timestamp) {
        draft.timestamp?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M/d HH:mm")) }
            ?: if (draft.requiresTimeInput) "时间待补充" else "此刻"
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Checkbox(draft.checked, onCheckedChange = { checked -> onUpdate { it.copy(checked = checked) } },
                enabled = enabled, modifier = Modifier.size(30.dp).testTag("draft-check-$tag"))
            BasicTextField(draft.detail, onValueChange = { value -> onUpdate { it.copy(detail = value) } },
                modifier = Modifier.weight(1f).heightIn(min = 38.dp).testTag("draft-detail-$tag"),
                singleLine = true, enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { field ->
                    Box(Modifier.padding(vertical = 8.dp)) {
                        if (draft.detail.isBlank()) Text("这笔是什么？", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
                        field()
                    }
                })
            BasicTextField(value = draft.amountText,
                onValueChange = { value -> onUpdate { it.copy(amountText = value.filter { c -> c.isDigit() || c == '.' }) } },
                modifier = Modifier.width(104.dp).testTag("draft-amount-$tag"),
                singleLine = true, enabled = enabled,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = moneyColor, fontWeight = FontWeight.SemiBold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { field ->
                    Surface(color = moneyColor.copy(alpha = .05f), shape = RoundedCornerShape(11.dp)) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (draft.type == BillType.EXPENSE) "−¥" else "+¥", style = MaterialTheme.typography.labelMedium,
                                color = moneyColor, modifier = Modifier.padding(end = 3.dp))
                            Box(Modifier.weight(1f)) {
                                if (draft.amountText.isBlank()) Text("金额", style = MaterialTheme.typography.bodyMedium, color = moneyColor)
                                field()
                            }
                        }
                    }
                })
        }
        Row(Modifier.padding(start = 37.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.widthIn(max = 94.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .055f),
                shape = RoundedCornerShape(9.dp)) {
                Row(Modifier.clickable(enabled = enabled) { showCategories = true }
                    .testTag("draft-category-$tag").padding(horizontal = 7.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    CategoryBadge(draft.categoryName, category?.iconValue ?: draft.iconEmoji, size = 16.dp,
                        tint = category?.let { GoldenAnglePalette.colorForHue(it.colorHue) } ?: MaterialTheme.colorScheme.primary)
                    Text(draft.categoryName + if (draft.isNewCategory && category == null) "·新" else "",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.weight(1f).clickable(enabled = enabled) { showTime = true }.testTag("draft-time-$tag")
                .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(timeLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = if (draft.requiresTimeInput) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.widthIn(min = 40.dp).clickable(enabled = enabled, role = Role.Button,
                onClickLabel = if (draft.type == BillType.EXPENSE) "切换为收入" else "切换为支出") {
                    onUpdate { it.copy(type = if (it.type == BillType.EXPENSE) BillType.INCOME else BillType.EXPENSE) }
                }.padding(vertical = 7.dp, horizontal = 6.dp).testTag("draft-type-$tag"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(if (draft.type == BillType.EXPENSE) "支出" else "收入", color = moneyColor,
                    style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Outlined.EditNote, if (draft.note.isBlank()) "添加备注" else "编辑备注",
                Modifier.size(26.dp).clickable(enabled = enabled) { showNote = !showNote }.padding(5.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f))
        }
        if (draft.note.isNotBlank() && !showNote) Text(draft.note, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 37.dp))
        if (draft.requiresTimeInput || draft.timeNeedsReview) {
            Text(if (draft.requiresTimeInput) "补好时间就可以入账啦" else "时间供你核对 · 确认入账即接受",
                style = MaterialTheme.typography.labelSmall,
                color = if (draft.requiresTimeInput) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showNote) {
            OutlinedTextField(draft.note, onValueChange = { value -> onUpdate { it.copy(note = value) } },
                modifier = Modifier.fillMaxWidth().testTag("draft-note-$tag"), placeholder = { Text("留一句小备注 ♡") },
                enabled = enabled, maxLines = 3, shape = RoundedCornerShape(14.dp))
        }
    }
    if (showTime) GuluDialog("账单时间", { showTime = false }, confirmLabel = "好啦", compact = true) {
        BillDateTimeField(draft.timestamp, onTimestampChange = { timestamp ->
            onUpdate { it.copy(timestamp = timestamp, timeNeedsReview = false,
                timeHint = if (timestamp == null) "确认入账时记录此刻" else "已手动调整时间") }
        }, enabled = enabled, allowCurrentTime = !draft.requiresTimeInput)
        Text(draft.timeHint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showCategories) DraftCategoryPicker(draft, categories, tag, onDismiss = { showCategories = false },
        onSelect = { selected ->
            onUpdate { it.copy(categoryName = selected.name, iconEmoji = selected.iconValue,
                iconSvg = selected.iconSvg, isNewCategory = false) }
            showCategories = false
        })
}

@Composable
private fun DraftCategoryPicker(
    draft: DraftUi, categories: List<CategoryEntity>, tag: String,
    onDismiss: () -> Unit, onSelect: (CategoryEntity) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(categories, query) { categories.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    GuluDialog("分类小抽屉", onDismiss, confirmLabel = "好啦", compact = true) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("draft-category-search-$tag"),
            placeholder = { Text("找一个分类…") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        if (draft.isNewCategory && categories.none { it.name.equals(draft.categoryName, true) }) {
            Text("阿噜建议：${draft.categoryName} · 新分类", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        if (filtered.isEmpty()) {
            Text("没有找到这个分类，换个词试试 ♡", style = MaterialTheme.typography.bodySmall)
        } else SpringLazyGrid(columns = 3, modifier = Modifier.fillMaxWidth()
            .height(minOf(4, (filtered.size + 2) / 3).times(60).dp).testTag("draft-category-grid-$tag"),
            handOffOnRepeat = true, verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
            items(filtered, key = { it.id }) { category ->
                val selected = draft.categoryName.equals(category.name, true)
                Surface(Modifier.fillMaxWidth().clickable { onSelect(category) }
                    .testTag("draft-category-option-${category.id}"), shape = RoundedCornerShape(13.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .11f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(.7.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .3f)
                        else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(vertical = 7.dp, horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CategoryBadge(category.name, category.iconValue, size = 22.dp,
                            tint = GoldenAnglePalette.colorForHue(category.colorHue))
                        Text(category.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
