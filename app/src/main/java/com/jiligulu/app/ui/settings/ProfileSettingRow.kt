package com.jiligulu.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ProfileSettingField(val title:String) { NAME("名字"), SUFFIX("称呼后缀"), API_KEY("自定义 API Key") }

@Composable
internal fun ProfileSettingRow(title:String, summary:String, enabled:Boolean, onClick:()->Unit) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(enabled=enabled,role=Role.Button,onClick=onClick)
            .padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,style=MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
            Text(summary,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1,
                overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign=androidx.compose.ui.text.style.TextAlign.End)
            Icon(Icons.Outlined.ChevronRight,null,Modifier.padding(start=6.dp).size(18.dp),tint=MaterialTheme.colorScheme.primary)
        }
    }
}
