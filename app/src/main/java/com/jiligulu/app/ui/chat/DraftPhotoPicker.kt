package com.jiligulu.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.jiligulu.app.core.audio.UiCue
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.memories.MemoryFiles
import com.jiligulu.app.ui.memories.MemoryPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp

/** A claim spans the durable save, including cancellation after the UI disappears. */
internal suspend fun commitPickedDraftPhoto(oldPath: String?, copy: suspend () -> String,
    claim: (String) -> AutoCloseable?, save: suspend (String?) -> Boolean,
    release: suspend (Collection<String>) -> Unit): Boolean {
    var path: String? = null
    var held: AutoCloseable? = null
    var committed = false
    try {
        val imported = copy()
        path = imported
        held = checkNotNull(claim(imported)) { "Photo copy is unavailable" }
        currentCoroutineContext().ensureActive()
        // Write completion must remain observable even if its caller is cancelled meanwhile.
        withContext(NonCancellable) { committed = save(imported) }
        return committed
    } finally {
        withContext(NonCancellable) {
            // No unfinished writer remains before the claim is released. A new reference
            // is re-read by release; it is never inferred from the UI's local success flag.
            held?.close()
            path?.let { release(listOf(it)) }
            if (committed && !oldPath.isNullOrBlank() && oldPath != path) release(listOf(oldPath))
        }
    }
}

internal suspend fun removeDraftPhoto(oldPath: String, save: suspend (String?) -> Boolean,
    release: suspend (Collection<String>) -> Unit): Boolean {
    var committed = false
    try { withContext(NonCancellable) { committed = save(null) }; return committed }
    finally { if (committed) withContext(NonCancellable) { release(listOf(oldPath)) } }
}

/** Compact, per-draft attachment. Receipt recognition and chat requests never see these pixels. */
@Composable
internal fun DraftPhotoPicker(path: String?, enabled: Boolean, tag: String,
    onPhotoChange: suspend (String?) -> Boolean, onBusyChange: (Boolean) -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestPath by rememberUpdatedState(path)
    val latestSave by rememberUpdatedState(onPhotoChange)
    val latestBusyChange by rememberUpdatedState(onBusyChange)
    val latestEnabled by rememberUpdatedState(enabled)
    var busy by remember { mutableStateOf(false) }
    fun failed() { Toast.makeText(context, "照片没能夹好，再试一次", Toast.LENGTH_SHORT).show() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && latestEnabled && !busy) scope.launch {
            busy = true; latestBusyChange(true)
            try {
                val applied = commitPickedDraftPhoto(latestPath, copy = { MemoryFiles.importPhoto(context, uri) },
                    claim = { MemoryFiles.claimPrivateMedia(context, it) }, save = { latestSave(it) },
                    release = { MemoryFiles.releaseDraftCopies(context, it) })
                if (!applied) failed()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed() }
            finally { busy = false; latestBusyChange(false) }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
        else if (!path.isNullOrBlank()) MemoryPhoto(path, Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
            .testTag("draft-photo-thumbnail-$tag").clickable(enabled = enabled, role = Role.Button,
                onClickLabel = "替换照片", onClick = uiTap(UiCue.PAPER) {
                    runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { failed() }
                }), maxSide = 96)
        else IconButton(onClick = uiTap(UiCue.PAPER) {
            runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { failed() }
        }, enabled = enabled, modifier = Modifier.size(28.dp).testTag("draft-photo-add-$tag")) {
            Icon(Icons.Outlined.AttachFile, "夹照片", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f))
        }
        }
        if (!path.isNullOrBlank()) IconButton(onClick = uiTap(UiCue.REMOVE) {
            if (!busy && latestEnabled) scope.launch {
                val prior = latestPath?.takeIf { it.isNotBlank() } ?: return@launch
                busy = true; latestBusyChange(true)
                try {
                    if (!removeDraftPhoto(prior, { latestSave(it) }, { MemoryFiles.releaseDraftCopies(context, it) })) failed()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed() }
                finally { busy = false; latestBusyChange(false) }
            }
        }, enabled = enabled && !busy, modifier = Modifier.size(24.dp).testTag("draft-photo-remove-$tag")) {
            Icon(Icons.Outlined.Close, "移除照片", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
