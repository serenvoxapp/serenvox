// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.serenvox.app.ui.theme.gradientFor

/** Circular gradient avatar with the voice's initial. */
@Composable
fun VoiceAvatar(key: String, label: String, size: Dp = 28.dp) {
    val (a, b) = gradientFor(key)
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        if (size >= 36.dp) Text(label.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp)
    }
}

/** Pill chip: small avatar + name. */
@Composable
fun VoiceChip(key: String, name: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, label = "chip")
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.clip(CircleShape).background(bg).border(1.dp, border, CircleShape).clickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceAvatar(key, name, 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Pill(text: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null, filled: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(if (filled) c.primary else c.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = if (filled) c.onPrimary else c.onSurface); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) c.onPrimary else c.onSurface)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(vertical = 6.dp), content = content)
}

@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null,
               trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, action: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}
