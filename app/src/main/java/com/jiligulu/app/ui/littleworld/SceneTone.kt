package com.jiligulu.app.ui.littleworld

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/** Restrained painted color; ledger amounts, text, categories and icons keep semantic colors. */
internal val MutedSceneColorFilter=ColorFilter.colorMatrix(ColorMatrix().apply {setToSaturation(.78f)})
