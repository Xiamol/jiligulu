package com.jiligulu.app.ui.announcement

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.data.announcement.AnnouncementRepository

/** The mailbox belongs to the shared main toolbar, leaving room for the daily ledger. */
@Composable
fun MailboxHeaderButton(repository: AnnouncementRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    IconButton(enabled = !state.loading, onClick = {
        repository.open(state.entries.firstOrNull()?.id.orEmpty())
    }, modifier = Modifier.testTag("main-mailbox")) {
        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else BadgedBox(badge = {
            if (state.entries.isNotEmpty()) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary) {
                Text(if (state.entries.size > 9) "9+" else state.entries.size.toString())
            }
        }) {
            Icon(Icons.Outlined.MailOutline, "阿噜的小信箱", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
