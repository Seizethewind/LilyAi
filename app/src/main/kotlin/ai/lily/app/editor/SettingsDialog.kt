package ai.lily.app.editor

import ai.lily.app.lib.editor.BuiltinFont
import ai.lily.app.lib.editor.FontChoice
import ai.lily.app.lib.editor.LineSpacing
import ai.lily.app.lib.editor.TAB_SIZE_CHOICES
import ai.lily.app.lib.editor.tabSizeLabel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Theme, font, text size, spacing, indent and the on/off switches. Every change is applied and saved straight away. */
@Composable
fun SettingsDialog(state: EditorState, onDismiss: () -> Unit) {
    val s = state.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Editor settings") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                SectionTitle("Theme")
                for (t in EDITOR_THEMES) {
                    ChoiceRow(t.name, selected = t.id == s.theme) { state.updateSettings(s.copy(theme = t.id)) }
                }

                SectionTitle("Font")
                for (b in BuiltinFont.values()) {
                    val id = FontChoice.Builtin(b).id
                    ChoiceRow(b.label, selected = s.font == id) { state.updateSettings(s.copy(font = id)) }
                }
                val added = remember(state.fontsVersion) { state.fonts.list() }
                for (name in added) {
                    val id = FontChoice.Custom(name).id
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChoiceRow(name, selected = s.font == id, modifier = Modifier.weight(1f)) { state.updateSettings(s.copy(font = id)) }
                        TextButton(onClick = { state.removeFont(name) }) { Text("\u2715") }
                    }
                }
                TextButton(onClick = { state.importFont?.invoke() }, enabled = state.importFont != null) { Text("Add a font file\u2026") }
                state.fontProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Text(
                    "The quick brown fox 0O 1lI {}[]()",
                    fontFamily = state.fontFamily,
                    fontSize = state.fontSize.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (s.font != FontChoice.DEFAULT_ID) {
                    Text(
                        "Code lines up best in a monospace font. Add your own .ttf or .otf file; none are shipped with the app.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionTitle("Text size")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { state.updateFontSize(state.fontSize - 1) }) { Text("\u2212") }
                    Text("${state.fontSize}")
                    TextButton(onClick = { state.updateFontSize(state.fontSize + 1) }) { Text("+") }
                }

                SectionTitle("Line spacing")
                for (sp in LineSpacing.values()) {
                    ChoiceRow(sp.label, selected = s.spacing == sp) { state.updateSettings(s.copy(spacing = sp)) }
                }

                SectionTitle("Indent")
                for (size in TAB_SIZE_CHOICES) {
                    ChoiceRow(tabSizeLabel(size), selected = s.tabSize == size) { state.updateSettings(s.copy(tabSize = size)) }
                }

                SectionTitle("Behaviour")
                SwitchRow("Word wrap (hides line numbers)", s.wordWrap) { state.updateSettings(s.copy(wordWrap = it)) }
                SwitchRow("Line numbers", s.lineNumbers) { state.updateSettings(s.copy(lineNumbers = it)) }
                SwitchRow("Close brackets and quotes", s.autoPair) { state.updateSettings(s.copy(autoPair = it)) }
                SwitchRow("Auto save after typing stops", s.autoSave) { state.updateSettings(s.copy(autoSave = it)) }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Text(
        text = (if (selected) "\u2713  " else "     ") + label,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
