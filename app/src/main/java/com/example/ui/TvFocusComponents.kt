package com.example.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ui.theme.BroadcastCyan
import com.example.ui.theme.BroadcastCyanDeep
import com.example.ui.theme.DividerSlate
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HighlightSlate
import com.example.ui.theme.MidnightPanel
import com.example.ui.theme.OnCyanDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

fun isTvConfirmKey(nativeKeyCode: Int): Boolean {
    return nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        nativeKeyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        nativeKeyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
        nativeKeyCode == AndroidKeyEvent.KEYCODE_SPACE
}

/**
 * Clean Android TV D-Pad Focusable Container.
 * Uses a single FocusTargetNode (avoiding duplicate .focusable + .clickable focus traps),
 * subtle resting surfaces without heavy clutter borders, and highlights the focused element
 * with a crisp 2dp TV focus ring + smooth scale zoom + elevated zIndex.
 */
@Composable
fun TvFocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusScale: Float = 1.03f,
    shape: Shape = RoundedCornerShape(10.dp),
    containerColor: Color = MidnightPanel,
    selectedContainerColor: Color = HighlightSlate,
    focusedContainerColor: Color = HighlightSlate,
    borderColor: Color = Color.Transparent,
    focusedBorderColor: Color = BroadcastCyan,
    focusedBorderWidth: Dp = 2.dp,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.82f),
        label = "tv_focus_scale"
    )
    val animatedBg by animateColorAsState(
        targetValue = when {
            isFocused -> focusedContainerColor
            selected -> selectedContainerColor
            else -> containerColor
        },
        label = "tv_focus_bg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = when {
            isFocused -> focusedBorderColor
            selected -> BroadcastCyan.copy(alpha = 0.35f)
            else -> borderColor
        },
        label = "tv_focus_border"
    )
    val borderStroke = when {
        isFocused -> BorderStroke(focusedBorderWidth, animatedBorder)
        selected -> BorderStroke(1.dp, animatedBorder)
        borderColor != Color.Transparent -> BorderStroke(0.75.dp, animatedBorder)
        else -> null
    }

    Surface(
        shape = shape,
        color = animatedBg,
        border = borderStroke,
        shadowElevation = if (isFocused) 10.dp else 0.dp,
        modifier = modifier
            .zIndex(if (isFocused) 4f else 0f)
            .scale(scale)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .onFocusChanged { focusState ->
                val nowFocused = focusState.isFocused
                isFocused = nowFocused
                if (nowFocused) onFocused?.invoke()
            }
            .onKeyEvent { keyEvent ->
                if (enabled &&
                    keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.nativeKeyEvent.repeatCount == 0 &&
                    isTvConfirmKey(keyEvent.nativeKeyEvent.keyCode)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled)
            .pointerInput(enabled, onClick) {
                if (enabled) {
                    detectTapGestures(onTap = { onClick() })
                }
            }
            .semantics(mergeDescendants = true) {
                if (enabled) {
                    onClick {
                        onClick()
                        true
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            content = { content(isFocused) }
        )
    }
}

@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    isDanger: Boolean = false,
    enabled: Boolean = true,
    testTag: String = ""
) {
    val baseContainer = when {
        isDanger -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
        isPrimary -> BroadcastCyanDeep
        else -> ElevatedSlate
    }
    val focusedContainer = when {
        isDanger -> MaterialTheme.colorScheme.error
        isPrimary -> BroadcastCyan
        else -> HighlightSlate
    }
    val focusedBorder = Color.White.copy(alpha = 0.9f)

    TvFocusableSurface(
        onClick = onClick,
        enabled = enabled,
        focusScale = 1.04f,
        shape = RoundedCornerShape(10.dp),
        containerColor = baseContainer,
        focusedContainerColor = focusedContainer,
        borderColor = DividerSlate.copy(alpha = 0.5f),
        focusedBorderColor = focusedBorder,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = if (testTag.isNotBlank()) modifier.testTag(testTag) else modifier
    ) { isFocused ->
        val contentColor = when {
            isFocused && isPrimary -> OnCyanDark
            isFocused && isDanger -> TextPrimary
            isFocused -> TextPrimary
            isPrimary -> TextPrimary
            else -> TextSecondary
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = text.ifBlank { "Action" },
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                if (text.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = if (isFocused || isPrimary) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TvCheckboxRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    testTag: String = ""
) {
    TvFocusableSurface(
        onClick = onToggle,
        selected = checked,
        focusScale = 1.02f,
        shape = RoundedCornerShape(10.dp),
        containerColor = MidnightPanel,
        selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.24f),
        focusedContainerColor = HighlightSlate,
        borderColor = DividerSlate.copy(alpha = 0.35f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier)
    ) { isFocused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (checked) "Checked $title" else "Unchecked $title",
                tint = when {
                    checked -> BroadcastCyan
                    isFocused -> TextPrimary
                    else -> TextMuted
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isFocused || checked) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!badgeText.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (checked) BroadcastCyan.copy(alpha = 0.18f) else ElevatedSlate)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (checked) BroadcastCyan else TextMuted
                    )
                }
            }
        }
    }
}

@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    testTag: String = ""
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) BroadcastCyan else TextSecondary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isFocused) HighlightSlate else MidnightPanel)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) BroadcastCyan else DividerSlate,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                cursorBrush = SolidColor(BroadcastCyan),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused }
                    .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextMuted
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

@Composable
fun TvBadge(
    text: String,
    containerColor: Color = ElevatedSlate,
    contentColor: Color = BroadcastCyan,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}
