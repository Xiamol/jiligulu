package com.jiligulu.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.jiligulu.app.ui.theme.LocalSkinMaterial
import com.jiligulu.app.ui.theme.skinPaperSurface

/** Shared ledger/dialog paper adopts the selected skin's material and printed edges. */
@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val material = LocalSkinMaterial.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = material.shapes.large,
        color = material.paper
    ) {
        Column(Modifier.skinPaperSurface(material).padding(contentPadding), content = content)
    }
}
