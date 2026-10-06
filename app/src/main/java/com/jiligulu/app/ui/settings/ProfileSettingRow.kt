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
import com.jiligulu.app.ui.components.uiTap

enum class ProfileSettingField(val title:String) { NAME("名字"), SUFFIX("称呼后缀"), API_KEY("自定义 API Key") }

@Composable
internal fun ProfileSettingRow(title:String, summary:String, enabled:Boolean, onClick:()->Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clickable(enabled=enabled,role=Role.Button,onClick=uiTap(com.jiligulu.app.core.audio.UiCue.NAVIGATE, onClick))
            .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,style=MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
            Text(summary,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1,
                overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign=androidx.compose.ui.text.style.TextAlign.End)
            Icon(Icons.Outlined.ChevronRight,null,Modifier.padding(start=6.dp).size(18.dp),tint=MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.38f))
    }
}
