package `in`.maitri.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One option in a single-choice list: a full-width row, radio on the left, then the label and a one-line description.
 * The selected row has a filled accent radio and the card background. The whole row is the touch target.
 */
@Composable
internal fun MaitriRadioRow(selected: Boolean, label: String, description: String? = null, enabled: Boolean = true,
                            onClick: () -> Unit) {
    val p = maitriPalette()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale().clip(RoundedCornerShape(14.dp))
        .background(if (selected) p.card else androidx.compose.ui.graphics.Color.Transparent)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MaitriRadio(selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) p.primary else p.secondary, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            if (description != null) Text(description, color = p.secondary, fontSize = 12.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** The radio circle from screen 10: an outlined ring, filled with an accent dot when selected. */
@Composable
internal fun MaitriRadio(selected: Boolean) {
    val p = maitriPalette()
    Box(Modifier.size(22.dp).border(BorderStroke(2.dp, if (selected) p.accent else p.secondary), CircleShape),
        contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(11.dp).background(p.accent, CircleShape))
    }
}

/** A Maitri dialog holding a single-choice list: palette surface, Maitri type, an accent "Done"-style action. */
@Composable
internal fun MaitriChoiceDialog(title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
                                intro: String? = null, footer: @Composable ColumnScope.() -> Unit = {},
                                options: @Composable ColumnScope.() -> Unit) {
    val p = maitriPalette()
    AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = onDismiss, containerColor = p.surface, shape = RoundedCornerShape(28.dp),
        titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (intro != null) Text(intro, color = p.secondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp), content = options)
                footer()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        })
}
