package com.jiligulu.app.ui.add

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jiligulu.app.R
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.littleworld.LittleWorldArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A transparent scrapbook vignette fills spare space without enlarging any editor. */
@Composable
internal fun ManualEntryDecoration(modifier: Modifier = Modifier, enabled: Boolean, onPhoto: () -> Unit) {
    val resources = LocalContext.current.resources
    val art by produceState(LittleWorldArtwork.cachedImage(R.drawable.manual_life_scrapbook_v1), resources) {
        value = withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources, R.drawable.manual_life_scrapbook_v1) }
    }
    Column(modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = "夹一张生活照片", onClick = uiTap(com.jiligulu.app.core.audio.UiCue.PAPER, onPhoto)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            art?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Text("一笔小账，也是一页生活 ♡", Modifier.padding(bottom = 6.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
    }
}
