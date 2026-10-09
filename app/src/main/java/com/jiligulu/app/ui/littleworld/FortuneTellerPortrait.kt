package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.jiligulu.app.R

/** One sampled, shared portrait; the costume belongs to the fortune pages. */
@Composable
internal fun FortuneTellerPortrait(size: Dp, modifier: Modifier = Modifier) {
    val portrait = rememberWorldArtwork(R.drawable.gulu_fortune_teller_v1)
    Box(modifier.size(size).semantics { contentDescription = "阿噜小先生" }) {
        portrait?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}
