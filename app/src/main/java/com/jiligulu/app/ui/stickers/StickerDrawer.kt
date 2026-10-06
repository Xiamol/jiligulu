package com.jiligulu.app.ui.stickers

import com.jiligulu.app.ui.littleworld.StickerPaperArtwork
import com.jiligulu.app.ui.littleworld.StickerWallArtwork
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import com.jiligulu.app.ui.littleworld.StickerIllustration
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.littleworld.Sticker
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.ui.calculator.CalculatorDialog
import com.jiligulu.app.ui.components.rememberPageData
import com.jiligulu.app.ui.components.SpringLazyGrid
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.components.CompactFormField
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.theme.IncomeGreen
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opens only on request; the ledger's daily bills do not move to make room for stickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerDrawer(onDismiss: () -> Unit, onPick: (Sticker) -> Unit) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val repository = app.container.littleWorld
    val loadedState by rememberPageData<LittleWorldState?>(repository.state, null)
    val state = loadedState ?: LittleWorldState(stickers = emptyList())
    val categories by rememberPageData(app.container.categoryRepository.categories, emptyList())
    val scope = rememberCoroutineScope()
    var managing by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Sticker?>(null) }
    var pendingDelete by remember { mutableStateOf<Sticker?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .42f).dp.coerceIn(220.dp, 340.dp)
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val gridState=rememberLazyGridState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("阿噜的贴纸墙", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(if (managing) "点贴纸编辑 · 可以挪位置、换图案" else "轻轻揭下一张，带去记一笔 ♡",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = uiTap { managing = !managing }) { Text(if (managing) "布置好了" else "布置") }
                IconButton(onClick = uiTap(onDismiss)) { Icon(Icons.Default.Close, "关闭贴纸墙") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Box(Modifier.fillMaxWidth().height(maxHeight).clip(RoundedCornerShape(18.dp))) {
                StickerWallArtwork(Modifier.matchParentSize())
            if (loadedState == null) {
                // Do not compose the permanent trailing key before the real stickers exist:
                // LazyGrid would retain "new-sticker" as its anchor and open at the bottom.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else SpringLazyGrid(columns = 3, state=gridState, modifier = Modifier.fillMaxSize(), contentPadding=PaddingValues(horizontal=10.dp,vertical=18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.stickers, key = { _, item -> item.id }) { index, sticker ->
                    StickerPaper(sticker, index, managing,
                        onClick = uiTap { if (managing) { error = null; editing = sticker } else onPick(sticker) },
                        onEdit = { error = null; editing = sticker })
                }
                item(key = "new-sticker") {
                    Surface(onClick = uiTap { editing = Sticker(); error = null },
                        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f)),
                        modifier = Modifier.fillMaxWidth().height(128.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.Add, "新增贴纸", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                            Text("自己做一张", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            }
            Text("金额、时间都能再改，确认保存才会入账。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    editing?.let { sticker ->
        val index = state.stickers.indexOfFirst { it.id == sticker.id }
        StickerEditor(
            sticker = sticker, categories = categories, saving = saving, error = error,
            onDismiss = { if (!saving) editing = null },
            onSave = { next ->
                saving = true
                scope.launch {
                    try { repository.saveSticker(next); editing = null; error = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "贴纸还没收好，再试一次吧～" }
                    finally { saving = false }
                }
            },
            onDelete = if (index >= 0) ({ editing = null; pendingDelete = sticker }) else null,
            onMoveUp = if (index > 0) ({ scope.launch {
                try { repository.moveSticker(sticker.id, -1); error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "贴纸没挪好，稍后再试吧" }
            } }) else null,
            onMoveDown = if (index >= 0 && index < state.stickers.lastIndex) ({ scope.launch {
                try { repository.moveSticker(sticker.id, 1); error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "贴纸没挪好，稍后再试吧" }
            } }) else null
        )
    }
    pendingDelete?.let { sticker ->
        GuluDialog("揭走「${sticker.title}」？", { pendingDelete = null }, confirmLabel = "移除", dismissLabel = "留着",
            compact = true, dense = true, compactWidth = 260.dp, onConfirm = {
                pendingDelete = null
                scope.launch {
                    try { repository.deleteSticker(sticker.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "贴纸没揭下来，稍后再试吧" }
                }
            }) { Text("已经记好的账单还在哦。", style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerPaper(sticker: Sticker, index: Int, managing: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    val seed=sticker.id.hashCode().ushr(1)
    val angle=listOf(-7f,4.5f,-5f,6.5f,-3.5f,7.5f,-6f,3f)[seed%8]
    val shift=listOf(-3f,4f,0f,6f,-1f)[(seed/8)%5]
    val scale=listOf(.94f,1f,.97f)[(seed/40)%3]
    val tint=listOf(Color(0xFFFFF2D0),Color(0xFFE7F3E8),Color(0xFFF0E5FA),Color(0xFFFFE8E9),Color(0xFFE4EDF9))[(seed/3)%5]
    Box(Modifier.fillMaxWidth().height(144.dp).graphicsLayer {
        rotationZ=angle;translationY=shift;scaleX=scale;scaleY=scale
    }.combinedClickable(onClick=uiTap(onClick),onLongClick=uiTap(onEdit))) {
        StickerPaperArtwork(Modifier.matchParentSize(),tint)
        Column(Modifier.fillMaxWidth().padding(horizontal=9.dp).padding(top=23.dp,bottom=10.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
            StickerIllustration(sticker.emoji,Modifier.size(58.dp))
            Text(sticker.title,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis,
                color=Color(0xFF4C405B))
            Text(if(managing)"✎ 编辑" else "¥${Formatters.fenToYuanText(sticker.amountFen)}",
                style=MaterialTheme.typography.labelMedium,color=Color(0xFF8170AC),maxLines=1)
        }
    }
}
@Composable
private fun StickerEditor(sticker: Sticker, categories: List<CategoryEntity>, saving: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (Sticker) -> Unit, onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?) {
    var title by rememberSaveable(sticker.id) { mutableStateOf(sticker.title) }
    var amount by rememberSaveable(sticker.id) { mutableStateOf(if (sticker.amountFen > 0) Formatters.fenToYuanText(sticker.amountFen) else "") }
    var emoji by rememberSaveable(sticker.id) { mutableStateOf(sticker.emoji) }
    var type by rememberSaveable(sticker.id) { mutableStateOf(sticker.type) }
    var categoryId by rememberSaveable(sticker.id) { mutableStateOf(sticker.categoryId) }
    var calculator by rememberSaveable { mutableStateOf(false) }
    val fen = remember(amount) { Formatters.yuanTextToFen(amount)?.takeIf { it <= 99_999_999_999L } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !saving, dismissOnClickOutside = !saving)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        Surface(Modifier.padding(16.dp).widthIn(max = 300.dp).fillMaxWidth().imePadding(), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .74f).dp)
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (sticker.title.isBlank()) "做一张自己的贴纸 ✿" else "给小贴纸换个样子", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal,
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                SpringScrollColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                CompactFormField("名字", title, { if (it.length <= 24) title = it }, placeholder = "早餐的小面包", enabled = !saving)
                CompactFormField("金额", amount, { if (it.matches(Regex("\\d{0,9}(\\.\\d{0,2})?"))) amount = it },
                    prefix = "¥", minHeight = 50.dp, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailing = { IconButton(onClick = uiTap { calculator = true }, enabled = !saving, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Calculate, "打开小算盘", Modifier.size(20.dp)) } })
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("收支", Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = uiTap { type = if (type == "EXPENSE") "INCOME" else "EXPENSE" }, enabled = !saving) {
                        Text(if (type == "EXPENSE") "支出" else "收入", color = if (type == "EXPENSE") ExpenseCoral else IncomeGreen)
                        Icon(Icons.Default.SwapHoriz, "切换收支", Modifier.padding(start = 4.dp).size(16.dp))
                    }
                }
                Text("挑一个小图案", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("🍳", "🍚", "🍜", "🥐", "☕", "🧋", "🥬", "🍊", "🚇", "🚕", "🚌", "🛒", "🐱", "🐶", "🎮", "📚", "💰", "⭐").chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { icon ->
                            Surface(onClick = uiTap { emoji = icon }, enabled = !saving, shape = RoundedCornerShape(12.dp),
                                color = if (emoji == icon) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                modifier = Modifier.weight(1f).height(44.dp)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    StickerIllustration(icon, Modifier.size(28.dp), fallbackFontSize = 20.sp)
                                }
                            }
                        }
                    }
                }
                }
                Text("分类", style = MaterialTheme.typography.labelLarge)
                SpringScrollColumn(Modifier.heightIn(max = 138.dp), handOffOnRepeat = true, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Surface(onClick = uiTap { categoryId = -1 }, enabled = !saving, shape = RoundedCornerShape(12.dp),
                        color = if (categoryId <= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("按名字推荐，记账时再确认", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(10.dp))
                    }
                    categories.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { category ->
                                Surface(onClick = uiTap { categoryId = category.id }, enabled = !saving,
                                    color = if (categoryId == category.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                                    Text(CategoryLabels.displayName(category.name), style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 10.dp))
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (onDelete != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = uiTap { onMoveUp?.invoke() }, enabled = onMoveUp != null && !saving) { Text("↑ 前移") }
                    TextButton(onClick = uiTap { onMoveDown?.invoke() }, enabled = onMoveDown != null && !saving) { Text("↓ 后移") }
                    TextButton(onClick = uiTap(onDelete), enabled = !saving) { Text("移除", color = MaterialTheme.colorScheme.error) }
                }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = uiTap(onDismiss), enabled = !saving, modifier = Modifier.weight(1f)) { Text("先收起") }
                    Button(onClick = uiTap { fen?.let { onSave(sticker.copy(title = title.trim(), amountFen = it, emoji = emoji, type = type, categoryId = categoryId)) } },
                        enabled = title.isNotBlank() && fen != null && !saving, modifier = Modifier.weight(1.5f), shape = RoundedCornerShape(16.dp)) {
                        Text(if (saving) "收好中…" else "贴上墙")
                    }
                }
            }
        }
    }
    if (calculator) CalculatorDialog(amount, onDismiss = { calculator = false }, onUse = { amount = it; calculator = false })
}
