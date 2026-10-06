package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.core.audio.UiCue
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.littleworld.Sticker
import com.jiligulu.app.data.littleworld.WaitingWish
import com.jiligulu.app.data.littleworld.Wish
import com.jiligulu.app.data.littleworld.WishDeposit
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.CompactFormField
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.rememberPageData
import com.jiligulu.app.ui.components.SpringLazyColumn
import com.jiligulu.app.ui.memories.LifePhotoField
import com.jiligulu.app.ui.memories.MemoryPhoto
import com.jiligulu.app.ui.memories.MemoryPosterButton
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun WishBookScreen(onBack: () -> Unit, onRecordWaiting: (Sticker) -> Unit) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val repository = app.container.littleWorld
    val state by rememberPageData(repository.state, LittleWorldState())
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editWish by remember { mutableStateOf<Wish?>(null) }
    var createWish by remember { mutableStateOf(false) }
    var editWaiting by remember { mutableStateOf<WaitingWish?>(null) }
    var createWaiting by remember { mutableStateOf(false) }
    var promotion by remember { mutableStateOf<WaitingWish?>(null) }
    var depositWish by remember { mutableStateOf<String?>(null) }
    var recordsWish by remember { mutableStateOf<String?>(null) }
    var deleteWish by remember { mutableStateOf<Wish?>(null) }
    var removeDeposit by remember { mutableStateOf<Pair<String, WishDeposit>?>(null) }
    var completedMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val active = remember(state.wishes) { state.wishes.filter { it.completedAt == null }.sortedBy { it.createdAt } }
    val completed = remember(state.wishes) { state.wishes.filter { it.completedAt != null }.sortedByDescending { it.completedAt } }
    val waiting = remember(state.waiting) { state.waiting.filter { !it.archived }.sortedByDescending { it.createdAt } }
    val current = active.firstOrNull { it.id == selected }
    var selectedWaiting by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMemory by rememberSaveable { mutableStateOf<String?>(null) }

    fun perform(successCue: UiCue = UiCue.CONFIRM, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block(); UiSound.play(app, successCue) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "没有保存成功，请再试一次" }
            finally { busy = false }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        val roomHeight=maxHeight
        SpringLazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=0.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                val bottles=when(tab) {
                    0->active.map {ShelfWish(it.id,it.title,wishProgress(it),"${wishPercent(it)}%")}
                    1->waiting.map {ShelfWish(it.id,it.title,0f,"先留着喜欢")}
                    else->completed.map {ShelfWish(it.id,it.title,1f,"实现啦")}
                }
                WishShelfStage(bottles,tab,onBack,{if(tab==1) createWaiting=true else createWish=true},
                    {tab=it;selected=null;selectedWaiting=null;selectedMemory=null},minHeight=roomHeight) { id ->
                    when(tab) {0->selected=id;1->selectedWaiting=id;else->selectedMemory=id}
                }
            }
            if(tab==1) {
                val archived=state.waiting.filter {it.archived}
                if(archived.isNotEmpty()) item { TextButton(onClick=uiTap(UiCue.TOGGLE) {showArchived=!showArchived}) {Text(if(showArchived) "收起已经放下的愿望" else "看看已经放下的愿望") } }
                if(showArchived) items(archived,key={"archived-${it.id}"}) {wish->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(wish.title,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick=uiTap {perform {repository.saveWaiting(wish.copy(archived=false))}}){Text("放回候场")}
                    }
                }
            }
        }
    }
    current?.let {wish->GuluDialog(wish.title,{selected=null},compact=true,dense=true,compactWidth=280.dp,busy=busy,
        confirmLabel="放颗星星",onConfirm={selected=null;depositWish=wish.id},dismissLabel="收起来") {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            StarWishJar(wishProgress(wish),Modifier.width(84.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("已装满 ${wishPercent(wish)}%",color=MaterialTheme.colorScheme.primary)
                Text("¥${Formatters.fenToYuanText(wish.savedFen)}",style=MaterialTheme.typography.titleMedium)
                Text("目标 ¥${Formatters.fenToYuanText(wish.targetFen)}",style=MaterialTheme.typography.bodySmall)
                Text("还差 ¥${Formatters.fenToYuanText((wish.targetFen-wish.savedFen).coerceAtLeast(0))}",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(wish.caption.isNotBlank()) Text(wish.caption,style=MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick=uiTap {selected=null;editWish=wish},enabled=!busy){Text("编辑愿望")}
            TextButton(onClick=uiTap(UiCue.PAPER) {selected=null;recordsWish=wish.id},enabled=!busy){Text("攒星记录")}
        }
    } }
    selectedWaiting?.let {id->waiting.firstOrNull {it.id==id}?.let {wish->GuluDialog(wish.title,{selectedWaiting=null},compact=true,dense=true,compactWidth=280.dp,busy=busy,
        confirmLabel="开始攒",onConfirm={selectedWaiting=null;promotion=wish},dismissLabel="收起来") {
        Text(if(wish.amountFen>0) "大约 ¥${Formatters.fenToYuanText(wish.amountFen)}" else "价格可以慢慢想，先留下喜欢。")
        TextButton(onClick=uiTap(UiCue.NAVIGATE) {selectedWaiting=null;onRecordWaiting(Sticker(id="waiting:${wish.id}",title=wish.title,emoji=wish.emoji,amountFen=wish.amountFen))},enabled=!busy){Text("买到了 · 记一笔")}
        Row {
            TextButton(onClick=uiTap {selectedWaiting=null;editWaiting=wish},enabled=!busy){Text("编辑")}
            TextButton(onClick=uiTap {perform {repository.archiveWaiting(wish.id);selectedWaiting=null}},enabled=!busy){Text("轻轻放下")}
        }
    } } }
    selectedMemory?.let {id->completed.firstOrNull {it.id==id}?.let {wish->GuluDialog(wish.title,{selectedMemory=null},compact=true,dense=true,compactWidth=280.dp,busy=busy,
        confirmLabel="攒星回忆",onConfirm={selectedMemory=null;recordsWish=wish.id},dismissLabel="收起来",confirmCue=UiCue.PAPER) {
        Text("${Formatters.dayLabel(wish.completedAt ?: wish.createdAt)} · 实现啦",color=MaterialTheme.colorScheme.primary)
        Text("攒下 ¥${Formatters.fenToYuanText(wish.savedFen)} · ${wish.deposits.size} 次记录",style=MaterialTheme.typography.bodySmall)
        if(wish.caption.isNotBlank()) Text(wish.caption)
        if(wish.photoPath.isNotBlank()) MemoryPhoto(wish.photoPath,Modifier.fillMaxWidth().height(180.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=uiTap {selectedMemory=null;editWish=wish}){Text("编辑")}
        }
        MemoryPosterButton(wish.title,wish.caption.ifBlank {"一颗一颗的小星星，终于装满了这个愿望。"},wish.photoPath,
            amountFen=wish.savedFen,dateMillis=wish.completedAt)
    } } }

    if (createWish || editWish != null) WishEditor(editWish, onDismiss = { createWish = false; editWish = null }, onDelete = editWish?.let { { deleteWish = it } }, busy = busy,
        shouldRetainPhotos = { busy }) { wish ->
        val wasComplete = editWish?.completedAt != null
        perform {
            repository.saveWish(wish)
            selected = wish.id
            createWish = false
            editWish = null
            if (wish.savedFen >= wish.targetFen) {
                tab = 2
                if (!wasComplete) completedMessage = wish.id to wish.title
            } else tab = 0
        }
    }
    if (createWaiting || editWaiting != null) WaitingEditor(editWaiting, { createWaiting = false; editWaiting = null }, busy = busy) { wish ->
        perform { repository.saveWaiting(wish); createWaiting = false; editWaiting = null }
    }
    promotion?.let { wish ->
        PromoteWaitingDialog(wish, { promotion = null }, busy = busy) { target ->
            perform { selected = repository.promoteWaiting(wish.id, target); promotion = null; tab = 0 }
        }
    }
    depositWish?.let { id -> state.wishes.firstOrNull { it.id == id }?.let { wish ->
        DepositDialog(wish, { depositWish = null }, busy = busy) { amount, note ->
            perform {
                repository.deposit(id, amount, note)
                depositWish = null
                if (wish.savedFen + amount >= wish.targetFen) { completedMessage = wish.id to wish.title; tab = 2 }
            }
        }
    } }
    recordsWish?.let { id -> state.wishes.firstOrNull { it.id == id }?.let { wish ->
        GuluDialog("${wish.emoji} 攒星回忆", { recordsWish = null }, compact = true, dense = true, compactWidth = 280.dp) {
            if (wish.deposits.isEmpty()) Text("还没有放入星星，从小小的一颗开始吧。")
            wish.deposits.sortedByDescending { it.createdAt }.forEach { record ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("+ ¥${Formatters.fenToYuanText(record.amountFen)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                        Text("${Formatters.dayLabel(record.createdAt)} ${Formatters.timeLabel(record.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = uiTap { removeDeposit = id to record }) { Icon(Icons.Outlined.DeleteOutline, "撤销这次攒星", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .5f))
            }
        }
    } }
    removeDeposit?.let { (id, record) -> GuluDialog("把这颗星星拿出来？", { removeDeposit = null }, "确认撤销", {
        perform(UiCue.REMOVE) { repository.removeDeposit(id, record.id); removeDeposit = null }
    }, dismissLabel = "留下", busy = busy, compact = true, dense = true, compactWidth = 280.dp) { Text("将撤销 ¥${Formatters.fenToYuanText(record.amountFen)} 的进度。如果不再达到目标，瓶子会回到正在攒。") } }
    deleteWish?.let { wish -> GuluDialog("收走这个愿望瓶？", { deleteWish = null }, "删除愿望", {
        perform(UiCue.REMOVE) { repository.deleteWish(wish.id); deleteWish = null; editWish = null; recordsWish = null }
    }, dismissLabel = "留下", busy = busy, compact = true, dense = true, compactWidth = 280.dp) { Text("「${wish.title}」和它的攒星记录都会删除。已经夹进生活纪念册的海报会保留。") } }
    completedMessage?.let { (id, title) -> GuluDialog("愿望实现啦！✨", { completedMessage = null }, "去留个纪念", {
        editWish = state.wishes.firstOrNull { it.id == id }
        completedMessage = null
    }, dismissLabel = "稍后", compact = true, dense = true, compactWidth = 280.dp) {
        StarWishJar(1f, Modifier.width(116.dp).align(Alignment.CenterHorizontally), complete = true)
        Text("「$title」已经装满星星。阿噜把它好好留进纪念册啦 ♡")
    } }
    error?.let { message -> GuluDialog("小星星还没放好", { error = null }, compact = true, dense = true, compactWidth = 280.dp) { Text(message) } }
}

@Composable
private fun WorldEmpty(title: String, subtitle: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StarWishJar(0f, Modifier.width(112.dp))
        Text(title, fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = uiTap(onClick), shape = RoundedCornerShape(14.dp)) { Text(button) }
    }
}

@Composable
private fun WaitingCard(wish: WaitingWish, onEdit: () -> Unit, onPromote: () -> Unit, onBought: () -> Unit, onArchive: () -> Unit) {
    LedgerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(wish.emoji, fontSize = 28.sp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(wish.title, style = MaterialTheme.typography.titleMedium)
                Text(if (wish.amountFen > 0) "大约 ¥${Formatters.fenToYuanText(wish.amountFen)}" else "先留下喜欢，价格可以以后再填", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = uiTap(onEdit)) { Icon(Icons.Outlined.Edit, "编辑候场愿望", tint = MaterialTheme.colorScheme.primary) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = uiTap(onPromote)) { Text("认真攒") }
            TextButton(onClick = uiTap(UiCue.NAVIGATE, onBought)) { Text("买到了 · 记一笔") }
            TextButton(onClick = uiTap(onArchive)) { Text("放下了", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun wishProgress(wish: Wish) = (wish.savedFen.toDouble() / wish.targetFen.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
private fun wishPercent(wish: Wish) = (wishProgress(wish) * 100).toInt()

internal fun exactWishAmount(text: String, optional: Boolean = false): Long? {
    if (optional && text.isBlank()) return 0L
    return runCatching { BigDecimal(text.trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }
        .getOrNull()?.takeIf { it in 1..99_999_999_999L }
}

@Composable
private fun WishEditor(wish: Wish?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, busy: Boolean = false,
    dialogTitle: String = if (wish == null) "许一个小愿望" else "给愿望添一点故事",
    shouldRetainPhotos: () -> Boolean = { false }, onSave: (Wish) -> Unit) {
    var title by remember(wish?.id) { mutableStateOf(wish?.title.orEmpty()) }
    var amount by remember(wish?.id) { mutableStateOf(wish?.targetFen?.takeIf { it > 0 }?.let(Formatters::fenToYuanText).orEmpty()) }
    var emoji by remember(wish?.id) { mutableStateOf(wish?.emoji ?: "⭐") }
    var caption by remember(wish?.id) { mutableStateOf(wish?.caption.orEmpty()) }
    var photo by remember(wish?.id) { mutableStateOf(wish?.photoPath.orEmpty()) }
    var photoBusy by remember(wish?.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GuluDialog(dialogTitle, onDismiss, "好好保存", {
        val target = exactWishAmount(amount)
        when {
            title.trim().isEmpty() -> error = "给愿望起一个名字吧"
            target == null -> error = "目标金额要大于 0，最多两位小数"
            else -> onSave((wish ?: Wish(title = title, targetFen = target)).copy(title = title.trim(), targetFen = target, emoji = emoji, caption = caption.trim(), photoPath = photo))
        }
    }, dismissLabel = "再想想", busy = busy, compact = true, dense = true, compactWidth = 280.dp,
        confirmEnabled = title.isNotBlank() && exactWishAmount(amount) != null && !photoBusy) {
        CompactFormField("愿望", title, { title = it.take(40) }, placeholder = "想实现的小事情", enabled = !busy)
        CompactFormField("目标", amount, { amount = it.take(14) }, prefix = "¥", minHeight = 50.dp,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
        if (wish != null) exactWishAmount(amount)?.let { target ->
            if (wish.completedAt == null && target <= wish.savedFen) Text("已攒够这个新目标，保存后会移进纪念区。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            else if (wish.completedAt != null && target > wish.savedFen) Text("新目标比已攒的更多，保存后会回到正在攒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        EmojiChooser(emoji, enabled = !busy) { emoji = it }
        CompactFormField("一句话", caption, { caption = it.take(300) }, placeholder = "可选", singleLine = false, enabled = !busy)
        LifePhotoField(photo, { photo = it }, enabled = !busy, onBusyChange = { photoBusy = it }, shouldRetainCopies = shouldRetainPhotos)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        onDelete?.let { TextButton(onClick = uiTap(it), enabled = !busy) { Text("删除这个愿望瓶", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun WaitingEditor(wish: WaitingWish?, onDismiss: () -> Unit, busy: Boolean = false, onSave: (WaitingWish) -> Unit) {
    var title by remember(wish?.id) { mutableStateOf(wish?.title.orEmpty()) }
    var amount by remember(wish?.id) { mutableStateOf(wish?.amountFen?.takeIf { it > 0 }?.let(Formatters::fenToYuanText).orEmpty()) }
    var emoji by remember(wish?.id) { mutableStateOf(wish?.emoji ?: "🌱") }
    var error by remember { mutableStateOf<String?>(null) }
    GuluDialog("先把喜欢留下来", onDismiss, "放进候场区", {
        val target = exactWishAmount(amount, optional = true)
        when {
            title.trim().isEmpty() -> error = "想要什么呀，写个名字吧"
            target == null -> error = "价格可以不填；填写时要大于 0，最多两位小数"
            else -> onSave((wish ?: WaitingWish(title = title)).copy(title = title.trim(), amountFen = target, emoji = emoji))
        }
    }, dismissLabel = "稍后", busy = busy, compact = true, dense = true, compactWidth = 280.dp,
        confirmEnabled = title.isNotBlank() && exactWishAmount(amount, optional = true) != null) {
        CompactFormField("想要", title, { title = it.take(40) }, placeholder = "先留下喜欢", enabled = !busy)
        CompactFormField("大约", amount, { amount = it.take(14) }, prefix = "¥", placeholder = "价格可以以后填",
            minHeight = 50.dp, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
        EmojiChooser(emoji, enabled = !busy) { emoji = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PromoteWaitingDialog(wish: WaitingWish, onDismiss: () -> Unit, busy: Boolean = false, onSave: (Long) -> Unit) {
    var amount by remember(wish.id) { mutableStateOf(wish.amountFen.takeIf { it > 0 }?.let(Formatters::fenToYuanText).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    GuluDialog("认真攒这个愿望", onDismiss, "准备星星瓶", {
        val target = exactWishAmount(amount)
        if (target == null) error = "填一个大于 0 的目标，最多两位小数" else onSave(target)
    }, dismissLabel = "再想想", busy = busy, compact = true, dense = true, compactWidth = 280.dp,
        confirmEnabled = exactWishAmount(amount) != null) {
        Text("${wish.emoji} ${wish.title}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("给它一个小目标，搬进星星瓶。", style = MaterialTheme.typography.bodySmall)
        CompactFormField("目标", amount, { amount = it.take(14) }, prefix = "¥", minHeight = 50.dp,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DepositDialog(wish: Wish, onDismiss: () -> Unit, busy: Boolean = false, onSave: (Long, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GuluDialog("给「${wish.title}」放颗星星", onDismiss, "装进瓶子", {
        val value = exactWishAmount(amount)
        if (value == null) error = "金额要大于 0，最多两位小数" else onSave(value, note)
    }, dismissLabel = "稍后", busy = busy, compact = true, dense = true, compactWidth = 280.dp,
        confirmEnabled = exactWishAmount(amount) != null) {
        Text("还差 ¥${Formatters.fenToYuanText((wish.targetFen - wish.savedFen).coerceAtLeast(0))}", color = MaterialTheme.colorScheme.primary)
        CompactFormField("这次攒", amount, { amount = it.take(14) }, prefix = "¥", minHeight = 50.dp,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
        TextButton(onClick = uiTap(UiCue.SELECT) { amount = Formatters.fenToYuanText((wish.targetFen - wish.savedFen).coerceAtLeast(0)) }, enabled = !busy) { Text("刚好装满这个愿望") }
        CompactFormField("小事", note, { note = it.take(100) }, placeholder = "可选", enabled = !busy)
        Text("只更新愿望进度，不会增加账单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun EmojiChooser(selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(listOf("⭐", "🎁", "📷", "✈️", "🏡", "📚", "🎧", "🌱", "🐱", "💻", "🧸", "🍰")) { emoji ->
            Surface(shape = RoundedCornerShape(12.dp), color = if (selected == emoji) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f), modifier = Modifier.size(44.dp).clickable(enabled = enabled, onClick = uiTap(UiCue.SELECT) { onSelect(emoji) })) {
                Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 22.sp) }
            }
        }
    }
}
