package com.jiligulu.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A label and an editable line, sized by its content rather than a large outlined container. */
@Composable
fun CompactFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    prefix: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = 3,
    minHeight: Dp = 44.dp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
            Text(label, Modifier.width(60.dp).padding(end = 6.dp, top = if (singleLine) 0.dp else 10.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (prefix.isNotEmpty()) Text(prefix, Modifier.padding(end = 4.dp), style = MaterialTheme.typography.bodyMedium)
            BasicTextField(value, onValueChange, Modifier.weight(1f).padding(vertical = 10.dp)
                .semantics { contentDescription = label }, enabled = enabled, singleLine = singleLine,
                minLines = if (singleLine) 1 else 2, maxLines = if (singleLine) 1 else maxLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (enabled)
                    MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)),
                keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)); inner() }
                })
            trailing?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
    }
}
