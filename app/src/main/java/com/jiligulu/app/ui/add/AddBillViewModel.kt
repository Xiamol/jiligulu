package com.jiligulu.app.ui.add

import android.net.Uri
import com.jiligulu.app.ui.memories.MemoryFiles
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.repository.CategoryReclassification
import com.jiligulu.app.core.ai.PendingCategoryClassifier
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryAdminRepository
import com.jiligulu.app.data.repository.CategoryDeletionResult
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.core.ai.AiBillDraft
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.core.ai.ManualCategoryClassifier
import com.jiligulu.app.domain.category.CategoryEngine
import com.jiligulu.app.domain.category.CategoryDefaults
import com.jiligulu.app.domain.category.CategorySuggestions
import com.jiligulu.app.core.ai.AiParseResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddBillSaveState(
    val isSaving: Boolean = false,
    val error: String? = null
)

internal fun manualCategoryInputKey(detail: String, note: String, type: BillType): String {
    val title = detail.trim()
    val comment = note.trim()
    return "${type.name}|${title.length}:$title|${comment.length}:$comment"
}

data class ManualCategoryPreview(
    val inputKey: String = "",
    val name: String = "",
    val categoryId: Long? = null,
    val proposal: AiBillDraft? = null,
    val resolving: Boolean = false,
    val error: String? = null
)

data class ManualPhotoState(val path: String = "", val importing: Boolean = false, val error: String? = null)

data class PendingReclassificationState(
    val open: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val total: Int = 0, val processed: Int = 0, val proposals: List<CategoryReclassification> = emptyList(),
    val bills: List<BillEntity> = emptyList(),
    val error: String? = null, val result: String? = null
)

class AddBillViewModel(
    private val billRepository: BillRepository,
    private val categoryRepository: CategoryRepository,
    private val categoryAdminRepository: CategoryAdminRepository,
    private val remoteCategory: suspend (String, BillType, List<CategoryEntity>) -> AiBillDraft? = { _, _, _ -> null },
    private val importPhotoFile: suspend (Uri) -> String = { error("Photo importer unavailable") },
    private val deletePhotoFile: (String) -> Unit = {},
    private val remotePendingCategories: suspend (List<BillEntity>, List<CategoryEntity>) -> Map<Long, AiBillDraft> = { _, _ -> emptyMap() }
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _saveState = MutableStateFlow(AddBillSaveState())
    val saveState = _saveState.asStateFlow()

    private val savedEvents = Channel<Unit>(Channel.BUFFERED)
    val saved = savedEvents.receiveAsFlow()

    private val _photo = MutableStateFlow(ManualPhotoState())
    val photo = _photo.asStateFlow()
    private var photoJob: Job? = null
    private var photoEpoch = 0
    private var photoCommitted = false
    private var saveJob: Job? = null
    private var cleared = false

    fun importPhoto(uri: Uri) {
        if (_saveState.value.isSaving) return
        photoJob?.cancel()
        val epoch = ++photoEpoch
        _photo.value = _photo.value.copy(importing = true, error = null)
        photoJob = viewModelScope.launch {
            var imported: String? = null
            try {
                // Finish the bounded file copy, then clean it if this screen/request was cancelled.
                val path = withContext(NonCancellable) { importPhotoFile(uri) }
                imported = path
                currentCoroutineContext().ensureActive()
                if (epoch == photoEpoch) {
                    _photo.value.path.takeIf { it.isNotBlank() }?.let(deletePhotoFile)
                    _photo.value = ManualPhotoState(path = path)
                    imported = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == photoEpoch) _photo.value = _photo.value.copy(error = "照片没能夹好，再选一次试试")
            } finally {
                imported?.let(deletePhotoFile)
                if (epoch == photoEpoch) _photo.value = _photo.value.copy(importing = false)
            }
        }
    }

    fun removePhoto() {
        if (_saveState.value.isSaving) return
        photoEpoch++
        photoJob?.cancel()
        _photo.value.path.takeIf { it.isNotBlank() }?.let(deletePhotoFile)
        _photo.value = ManualPhotoState()
    }

    override fun onCleared() {
        cleared = true
        photoEpoch++
        photoJob?.cancel()
        cancelCategoryPreview()
        reclassifyJob?.cancel()
        if (!photoCommitted && (!_saveState.value.isSaving || saveJob?.isCompleted == true)) cleanUncommittedPhoto()
        super.onCleared()
    }

    private fun cleanUncommittedPhoto() {
        if (photoCommitted) return
        val path = _photo.value.path
        _photo.value = _photo.value.copy(path = "", importing = false)
        if (path.isNotBlank()) deletePhotoFile(path)
    }

    private val _categoryPreview = MutableStateFlow(ManualCategoryPreview())
    val categoryPreview = _categoryPreview.asStateFlow()
    private var categoryJob: Job? = null
    private val previewCache = linkedMapOf<String, AiBillDraft>()

    fun cancelCategoryPreview() { categoryJob?.cancel(); categoryJob = null }

    /** A semantic preview is ready before confirmation; changing input cancels stale requests. */
    fun prepareCategory(detail: String, note: String, type: BillType, enabled: Boolean) {
        cancelCategoryPreview()
        if (!enabled || detail.isBlank()) { _categoryPreview.value = ManualCategoryPreview(); return }
        val text = listOf(detail.trim(), note.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
        val inputKey = manualCategoryInputKey(detail, note, type)
        val catalog = categories.value
        val local = PendingCategoryClassifier.localSuggestion(text, type, catalog)
        if (local != null) { publishCategory(local, catalog, inputKey); return }
        val key = inputKey + "|" + catalog.hashCode()
        previewCache[key]?.let { publishCategory(it, catalog, inputKey); return }
        _categoryPreview.value = ManualCategoryPreview(inputKey = inputKey, resolving = true)
        categoryJob = viewModelScope.launch {
            try {
                delay(650)
                val proposed = remoteCategory(text, type, catalog)
                currentCoroutineContext().ensureActive()
                if (proposed == null) _categoryPreview.value = ManualCategoryPreview(inputKey = inputKey, error = "还没找到合适分类，选一个也可以")
                else {
                    previewCache[key] = proposed
                    if (previewCache.size > 24) previewCache.remove(previewCache.keys.first())
                    publishCategory(proposed, catalog, inputKey)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _categoryPreview.value = ManualCategoryPreview(inputKey = inputKey, error = "自动分类暂时没连上，可手动选择") }
        }
    }

    private fun publishCategory(proposed: AiBillDraft, catalog: List<CategoryEntity>, inputKey: String) {
        val checked = ManualCategoryClassifier.suggestion(AiParseResult(bills = listOf(proposed.copy(targetId = 1))), catalog)
        if (checked == null) {
            _categoryPreview.value = ManualCategoryPreview(inputKey = inputKey, error = "还没找到合适分类，选一个也可以")
            return
        }
        val existing = CategorySuggestions.existing(checked.category, catalog)
        _categoryPreview.value = ManualCategoryPreview(inputKey = inputKey, name = checked.category,
            categoryId = existing?.id, proposal = checked.takeIf { existing == null })
    }

    /**
     * 该分类下的活账单条数。
     *
     * 删除弹窗要如实告诉用户「会挪走几笔」——先查数再问，而不是让用户自己数。
     * 只数活账单（回收站里的不计入、也不改归属）。
     */
    suspend fun liveBillCount(categoryId: Long): Int = billRepository.countLiveByCategory(categoryId)

    /**
     * 删除分类：活账单整体转挂「待定」+ 删分类行，一个事务。
     *
     * 返回 sealed 而不是布尔：[CategoryDeletionResult.Refused]（如收纳箱不可删）与
     * [CategoryDeletionResult.Deleted]（附带挪走条数，用于文案）要给用户不同反馈。
     */
    suspend fun deleteCategory(categoryId: Long): CategoryDeletionResult =
        categoryAdminRepository.deleteCategoryAndReassign(categoryId)

    private val _reclassification = MutableStateFlow(PendingReclassificationState())
    val reclassification = _reclassification.asStateFlow()
    private var reclassifyJob: Job? = null
    private var reclassificationEpoch = 0L

    fun closeReclassification() {
        if (_reclassification.value.saving) return
        reclassificationEpoch++
        reclassifyJob?.cancel()
        _reclassification.value = PendingReclassificationState()
    }

    fun preparePendingReclassification() {
        if (_reclassification.value.loading || _reclassification.value.saving) return
        reclassifyJob?.cancel()
        val epoch = ++reclassificationEpoch
        _reclassification.value = _reclassification.value.copy(open = true, loading = true,
            processed = 0, error = null, result = null)
        reclassifyJob = viewModelScope.launch {
            try {
                val pending = categoryAdminRepository.pendingBills()
                val catalog = categoryRepository.getAll()
                currentCoroutineContext().ensureActive()
                if (epoch != reclassificationEpoch) return@launch
                val suggested = linkedMapOf<Long, CategoryReclassification>()
                pending.forEach { bill -> PendingCategoryClassifier.localSuggestion(bill, catalog)?.let { draft ->
                    suggested[bill.id] = CategoryReclassification(bill, draft, CategorySuggestions.existing(draft.category, catalog)?.id)
                } }
                // All bills are visible immediately, including those without a confident suggestion.
                _reclassification.value = _reclassification.value.copy(total = pending.size, bills = pending,
                    proposals = suggested.values.toList())
                // Remote semantics may refine a local match or suggest an appropriate new name.
                pending.chunked(PendingCategoryClassifier.BATCH_SIZE).forEach { batch ->
                    val remote = remotePendingCategories(batch, catalog)
                    currentCoroutineContext().ensureActive()
                    if (epoch != reclassificationEpoch) return@launch
                    batch.forEach { bill -> remote[bill.id]?.let { raw ->
                        val draft = ManualCategoryClassifier.suggestion(AiParseResult(bills = listOf(raw.copy(targetId = 1))), catalog)
                            ?: return@let
                        val existing = CategorySuggestions.existing(draft.category, catalog)
                        if (draft.category != CategoryDefaults.VACUUM_NAME && (existing == null || existing.deletable))
                            suggested[bill.id] = CategoryReclassification(bill, draft.copy(targetId = bill.id), existing?.id)
                    } }
                    _reclassification.value = _reclassification.value.copy(proposals = suggested.values.toList(),
                        processed = _reclassification.value.processed + batch.size)
                }
                _reclassification.value = _reclassification.value.copy(loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch != reclassificationEpoch) return@launch
                _reclassification.value = _reclassification.value.copy(loading = false,
                    error = "联网建议暂未完成，可确认已有建议")
            }
        }
    }

    fun confirmPendingReclassification(ids: Set<Long>) {
        val state = _reclassification.value
        if (state.saving || !state.open) return
        val chosen = state.proposals.filter { it.original.id in ids }
        if (chosen.isEmpty()) return
        reclassifyJob?.cancel()
        reclassificationEpoch++
        _reclassification.value = state.copy(loading = false, saving = true, error = null)
        reclassifyJob = viewModelScope.launch {
            try {
                val result = categoryAdminRepository.applyReclassification(chosen)
                _reclassification.value = PendingReclassificationState(open = true,
                    result = "${result.moved} 笔重新分好类啦" + if (result.skipped > 0) "，${result.skipped} 笔已变化，先保留原样" else " ♡")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _reclassification.value = state.copy(loading = false, error = "没有改动账本，请再试一次") }
        }
    }

    fun save(
        amountFen: Long,
        type: BillType,
        categoryId: Long,
        detail: String,
        note: String,
        timestamp: Long? = null,
        proposedCategory: AiBillDraft? = null,
        autoCategorized: Boolean = false
    ) {
        val previous = _saveState.value
        if (previous.isSaving || _photo.value.importing) return
        if (amountFen <= 0 || (categoryId <= 0 && proposedCategory == null)) {
            _saveState.value = AddBillSaveState(error = "请填写有效金额并选择分类。")
            return
        }
        if (autoCategorized) {
            val preview = _categoryPreview.value
            val ready = preview.inputKey == manualCategoryInputKey(detail, note, type) &&
                preview.name.isNotBlank() && !preview.resolving &&
                (if (categoryId > 0) preview.categoryId == categoryId else
                    preview.proposal != null && preview.proposal == proposedCategory)
            if (!ready) {
                _saveState.value = AddBillSaveState(error = "分类还在更新，等阿噜选好再确认")
                return
            }
        }
        // Claim the save synchronously, before launching: fast repeated taps cannot insert twice.
        if (!_saveState.compareAndSet(previous, AddBillSaveState(isSaving = true))) return
        saveJob = viewModelScope.launch {
            try {
                val finalCategoryId = if (categoryId > 0) categoryId else proposedCategory?.let { proposal ->
                    categoryRepository.createCategory(proposal.category, iconValue = proposal.iconEmoji, keywords = proposal.keywords)
                } ?: error("Missing category")
                withContext(NonCancellable) {
                    billRepository.addManual(
                        amountFen = amountFen, type = type, categoryId = finalCategoryId,
                        detail = detail, note = note, timestamp = timestamp ?: System.currentTimeMillis(),
                        photoUri = _photo.value.path.takeIf { it.isNotBlank() }
                    )
                    photoCommitted = true
                }
                // Stay locked until navigation completes; the event survives a screen recreation.
                savedEvents.send(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _saveState.value = AddBillSaveState(error = "保存失败，账单尚未保存，请重试。")
            } finally {
                if (cleared && !photoCommitted) cleanUncommittedPhoto()
            }
        }.also { job ->
            job.invokeOnCompletion { if (cleared && !photoCommitted) cleanUncommittedPhoto() }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                AddBillViewModel(
                    app.container.billRepository,
                    app.container.categoryRepository,
                    app.container.categoryAdminRepository,
                    remoteCategory = { text, type, catalog ->
                        val client = DeepSeekClient(app.container.aiRepository.effectiveApiKey(), onUsage = app.container.aiUsage::record)
                        val result = client.parseBill(ManualCategoryClassifier.PROMPT,
                            ManualCategoryClassifier.input(text, type, catalog)).getOrThrow()
                        ManualCategoryClassifier.suggestion(result, catalog)
                    },
                    importPhotoFile = { uri -> MemoryFiles.importPhoto(app, uri) },
                    deletePhotoFile = { path -> MemoryFiles.deleteImportedPhoto(app, path) },
                    remotePendingCategories = { bills, catalog ->
                        val client = DeepSeekClient(app.container.aiRepository.effectiveApiKey(), onUsage = app.container.aiUsage::record)
                        val result = client.parseBill(PendingCategoryClassifier.PROMPT,
                            PendingCategoryClassifier.input(bills, catalog)).getOrThrow()
                        PendingCategoryClassifier.suggestions(result, bills, catalog)
                    }
                )
            }
        }
    }
}
