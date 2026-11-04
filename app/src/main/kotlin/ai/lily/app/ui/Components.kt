package ai.lily.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---- Tone: the five meanings colour carries in this app ----

/** Petal marks what is selected, pad what is working or fine, stamen what needs the person, coral what went wrong. */
enum class Tone { Neutral, Petal, Stamen, Pad, Coral }

@Composable
@ReadOnlyComposable
fun Tone.ink(): Color = when (this) {
    Tone.Neutral -> LilyUi.colors.textDim
    Tone.Petal -> MaterialTheme.colorScheme.primary
    Tone.Stamen -> LilyUi.colors.stamen
    Tone.Pad -> LilyUi.colors.pad
    Tone.Coral -> LilyUi.colors.coral
}

@Composable
@ReadOnlyComposable
fun Tone.wash(): Color = when (this) {
    Tone.Neutral -> LilyUi.colors.sunken
    Tone.Petal -> LilyUi.colors.petalSoft
    Tone.Stamen -> LilyUi.colors.stamenSoft
    Tone.Pad -> LilyUi.colors.padSoft
    Tone.Coral -> LilyUi.colors.coralSoft
}

// ---- Structure ----

/** A full-height screen on the app background, clear of the status bar, the navigation bar and the keyboard. */
@Composable
fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        content = content,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LilyUi.colors.hairline))
}

/** The big serif title at the top of a section, with an optional line under it and actions on the right. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LilyUi.colors.textDim, modifier = Modifier.padding(top = 2.dp))
            }
        }
        actions()
    }
}

/** A named group inside a screen. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, hint: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = LilyUi.colors.textDim, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

// ---- Buttons ----

enum class ButtonKind { Primary, Tonal, Quiet, Danger }

private class ButtonLook(val fill: Color, val ink: Color, val edge: Color?)

/** A pill button, at least 48dp tall. Press sinks it slightly and shifts its colour. */
@Composable
fun LilyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme
    val look = when (kind) {
        ButtonKind.Primary -> ButtonLook(s.primary, s.onPrimary, null)
        ButtonKind.Tonal -> ButtonLook(c.petalSoft, s.primary, null)
        ButtonKind.Quiet -> ButtonLook(Color.Transparent, s.onBackground, c.hairline)
        ButtonKind.Danger -> ButtonLook(Color.Transparent, c.coral, c.coral.copy(alpha = 0.5f))
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(90), label = "press")
    Row(
        modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                val scale = 1f - 0.03f * press
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(CircleShape)
            .background(lerp(look.fill, look.ink, 0.12f * press))
            .then(if (look.edge != null) Modifier.border(1.dp, look.edge, CircleShape) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round 48dp icon button. [description] is read out by screen readers, so say what it does. */
@Composable
fun LilyIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ---- Inputs ----

/**
 * A text field with its label above it. Focus draws a petal border; [error] draws a coral one and says why underneath.
 * [secret] hides the text and adds a Show / Hide switch. [mono] is for addresses, keys and commands.
 */
@Composable
fun LilyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    secret: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val edge = when {
        error != null -> c.coral
        focused -> s.primary
        else -> c.hairline
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = s.onBackground,
        fontFamily = if (mono) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily,
    )
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (focused) s.primary else c.textDim, modifier = Modifier.padding(bottom = 6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(c.sunken)
                .border(if (focused) 1.5.dp else 1.dp, edge, shape)
                .padding(start = 14.dp, end = if (secret) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                textStyle = textStyle,
                singleLine = singleLine,
                minLines = minLines,
                cursorBrush = SolidColor(s.primary),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
                interactionSource = source,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = textStyle, color = c.textFaint)
                        }
                        inner()
                    }
                },
            )
            if (secret) {
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = if (reveal) "Hide" else "Show") { reveal = !reveal }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (reveal) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = s.primary)
                }
            }
        }
        val note = error ?: helper
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = if (error != null) c.coral else c.textDim, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

/**
 * The message box at the foot of a chat or a task list: grows to six lines, and the round send button is petal-coloured
 * only when there is something to send.
 */
@Composable
fun LilyComposer(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    sendLabel: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme
    val canSend = enabled && value.isNotBlank()
    val shape = RoundedCornerShape(26.dp)
    val style = MaterialTheme.typography.bodyLarge.copy(color = s.onBackground)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.hairline, shape)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            textStyle = style,
            maxLines = 6,
            cursorBrush = SolidColor(s.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(hint, style = style, color = c.textFaint)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) s.primary else c.sunken)
                .clickable(enabled = canSend, role = Role.Button, onClickLabel = sendLabel, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Send, contentDescription = sendLabel, tint = if (canSend) s.onPrimary else c.textFaint, modifier = Modifier.size(20.dp))
        }
    }
}

// ---- Selection ----

class TabSpec(val label: String, val badge: Int = 0)

/** Two or three views of one screen. The chosen one lifts onto a raised pill; a stamen count says how many need the person. */
@Composable
fun SegmentedTabs(tabs: List<TabSpec>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LilyUi.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(CircleShape)
            .background(c.sunken)
            .padding(4.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val on = index == selected
            val lift by animateFloatAsState(if (on) 1f else 0f, tween(160), label = "tab")
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(c.raised.copy(alpha = lift))
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onBackground else c.textDim,
                )
                if (tab.badge > 0) {
                    Spacer(Modifier.width(8.dp))
                    CountBadge(tab.badge)
                }
            }
        }
    }
}

/** A small stamen count: how many things are waiting for the person. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = LilyUi.colors
    Box(
        modifier.heightIn(min = 16.dp).widthIn(min = 16.dp).clip(CircleShape).background(c.stamen).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), style = MaterialTheme.typography.labelSmall, color = c.onStamen)
    }
}

/** A choice among a few: one chip is selected. Used for roles, routers and templates. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (selected) c.petalSoft else c.sunken)
            .border(1.dp, if (selected) s.primary else c.hairline, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) s.primary else s.onBackground, maxLines = 1)
    }
}

/** A switch tinted to the palette. Keep a row around it at least 48dp tall. */
@Composable
fun LilySwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = s.onPrimary,
            checkedTrackColor = s.primary,
            checkedBorderColor = s.primary,
            uncheckedThumbColor = c.textDim,
            uncheckedTrackColor = c.sunken,
            uncheckedBorderColor = c.hairline,
        ),
    )
}

// ---- Rows and status ----

/** A list row on the page itself: no card, a 64dp touch target, hairlines are drawn by the list. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LilyUi.colors.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).clip(CircleShape).background(tone.ink()))
}

/** A short status in a tinted pill, such as "3 models" or "Needs you". */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tone.ink(),
        maxLines = 1,
        modifier = modifier.clip(CircleShape).background(tone.wash()).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A message inside a screen: what happened and, when there is one, the action that follows from it. */
@Composable
fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.wash()).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone, Modifier.padding(end = 12.dp), size = 8)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = tone.ink())
            }
        }
    }
}

/** An empty or failed screen: what is missing, why, and the one thing to do about it. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    art: @Composable () -> Unit = { LilyMark(size = 64.dp) },
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        art()
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LilyUi.colors.textDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            LilyButton(actionLabel, onAction)
        }
    }
}

// ---- Sheets and dialogs ----

/**
 * A bottom sheet for editing one thing: it rises over the screen, scrolls when it is tall, and clears the keyboard.
 * Edits that take more than a glance go here; a dialog is kept for yes/no questions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LilySheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LilyUi.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.raised,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.hairline))
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
            content()
        }
    }
}

/** A yes/no question. A destructive answer is drawn in coral so it cannot be tapped by habit. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    val c = LilyUi.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.raised,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = c.textDim,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { LilyButton(confirmLabel, onConfirm, kind = if (danger) ButtonKind.Danger else ButtonKind.Primary) },
        dismissButton = { LilyButton("Cancel", onDismiss, kind = ButtonKind.Quiet) },
    )
}
