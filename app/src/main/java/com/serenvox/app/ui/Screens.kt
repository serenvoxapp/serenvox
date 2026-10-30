// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.serenvox.app.tts.VoiceCatalog
import com.serenvox.app.tts.KokoroPack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.serenvox.app.ThemeMode
import com.serenvox.app.library.LibraryItem
import com.serenvox.app.tts.PlayerState
import com.serenvox.app.tts.Status
import com.serenvox.app.tts.VoiceInfo
import com.serenvox.app.tts.VoiceState
import com.serenvox.app.ui.theme.ReaderStyle
import com.serenvox.app.ui.theme.gradientFor
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

data class VoiceOption(val id: String, val name: String)

// ------------------------------------------------------------------ Read

@Composable
fun ReadScreen(
    title: String,
    text: String,
    player: PlayerState,
    voices: List<VoiceOption>,
    selectedVoice: String,
    importing: Boolean,
    exportProgress: Float?,
    canExport: Boolean,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit,
    onVoice: (String) -> Unit,
    onMoreVoices: () -> Unit,
    onExport: () -> Unit,
    onSeek: (Int) -> Unit,
    importStatus: String = "Reading your document…",
) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title.ifBlank { "Read" }, if (text.isBlank()) "Listen to anything, offline" else "${text.length} characters") {
            if (text.isNotBlank() && player.status == Status.IDLE) {
                IconButton(onClick = onExport, enabled = canExport && exportProgress == null) { Icon(Icons.Outlined.IosShare, "Export audio") }
                IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, "Clear") }
            }
            Pill("Import", Icons.Outlined.UploadFile, onImport)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(voices, key = { it.id }) { v -> VoiceChip(v.id, v.name, v.id == selectedVoice) { onVoice(v.id) } }
            item { Pill("More voices", Icons.Default.Add, onMoreVoices) }
        }
        Spacer(Modifier.height(12.dp))
        exportProgress?.let {
            LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(CircleShape))
            Text("Exporting audio… ${(it * 100).roundToInt()}%", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                importing -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(importStatus, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                player.status != Status.IDLE && player.chunks.isNotEmpty() -> ReaderView(player, onSeek)
                text.isBlank() -> EmptyRead(onImport, onTextChange)
                else -> TextField(
                    text, onTextChange,
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    textStyle = ReaderStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                )
            }
        }
        player.error?.let { Text(it, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun EmptyRead(onImport: () -> Unit, onTextChange: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF9D8CFF), Color(0xFF6B4EFF))))
                .clickable(onClick = onImport).padding(24.dp)
        ) {
            Column {
                Icon(Icons.Outlined.UploadFile, null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(16.dp))
                Text("Import a document", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("PDF, Word, EPUB or text — read aloud with natural voices.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .85f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
            Text("Or type / paste text", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text("Start typing…") }, textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onTextChange(draft) }, enabled = draft.isNotBlank(), modifier = Modifier.align(Alignment.End)) { Text("Use this text") }
        }
    }
}

@Composable
private fun ReaderView(player: PlayerState, onSeek: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(player.index) { if (player.index in player.chunks.indices) list.animateScrollToItem((player.index - 1).coerceAtLeast(0)) }
    LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        itemsIndexed(player.chunks) { i, c ->
            val current = i == player.index
            val bg by animateColorAsState(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "band")
            Text(c, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).clickable { onSeek(i) }
                .padding(horizontal = 10.dp, vertical = 4.dp),
                style = ReaderStyle,
                color = when {
                    current -> MaterialTheme.colorScheme.onPrimaryContainer
                    i < player.index -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                })
        }
    }
}

// ------------------------------------------------------------------ Player

@Composable
fun PlayerBar(
    player: PlayerState, speed: Float, sleepMinutesLeft: Int?, enabled: Boolean,
    onPlayPause: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, onSpeed: () -> Unit, onSleep: () -> Unit, onStop: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val progress by animateFloatAsState(player.progress, label = "p")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp))
        .background(c.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp)) {
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), trackColor = c.surfaceContainerHighest)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(if (player.chunks.isEmpty()) "Ready" else "${(player.index + 1).coerceAtMost(player.chunks.size)} / ${player.chunks.size}",
                style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(when (player.status) { Status.PREPARING -> "Loading voice…"; Status.IDLE -> ""; else -> player.engine },
                style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.clip(CircleShape).clickable(onClickLabel = "Sleep timer", onClick = onSleep)
                .then(if (sleepMinutesLeft != null) Modifier.background(c.primaryContainer) else Modifier)
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Bedtime, "Sleep timer", Modifier.size(22.dp), tint = if (sleepMinutesLeft != null) c.primary else c.onSurfaceVariant)
                if (sleepMinutesLeft != null) {
                    Spacer(Modifier.width(4.dp))
                    Text("${sleepMinutesLeft}m", style = MaterialTheme.typography.labelMedium, color = c.onPrimaryContainer, maxLines = 1, softWrap = false)
                }
            }
            IconButton(onClick = onBack, enabled = player.status != Status.IDLE) { Icon(Icons.Default.FastRewind, "Previous sentence", Modifier.size(30.dp)) }
            val playing = player.status == Status.PLAYING || player.status == Status.PREPARING
            FilledIconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(64.dp), shape = CircleShape) {
                if (player.status == Status.PREPARING) CircularProgressIndicator(Modifier.size(26.dp), color = c.onPrimary, strokeWidth = 2.5.dp)
                else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(34.dp))
            }
            IconButton(onClick = onForward, enabled = player.status != Status.IDLE) { Icon(Icons.Default.FastForward, "Next sentence", Modifier.size(30.dp)) }
            Box(Modifier.clip(CircleShape).background(c.surfaceContainerHighest).clickable(onClick = onSpeed).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${fmt(speed)}×", style = MaterialTheme.typography.labelLarge)
            }
        }
        AnimatedVisibility(player.status != Status.IDLE, enter = fadeIn(), exit = fadeOut()) {
            TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
        }
    }
}

@Composable
fun MiniPlayer(title: String, player: PlayerState, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
        .background(c.primaryContainer).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        VoiceAvatar(title, title, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title.ifBlank { "Now reading" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onPrimaryContainer)
            Text(player.chunks.getOrNull(player.index) ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onPrimaryContainer.copy(alpha = .75f))
        }
        IconButton(onClick = onPlayPause) {
            Icon(if (player.status == Status.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = c.onPrimaryContainer)
        }
    }
}

fun fmt(v: Float) = if ((v * 100).roundToInt() % 10 == 0) "%.1f".format(v) else "%.2f".format(v)

@Composable
fun SpeedSheetContent(speed: Float, pitch: Float, onSpeed: (Float) -> Unit, onPitch: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Reading speed: ${fmt(speed)}×", style = MaterialTheme.typography.titleLarge)
        Slider(speed, { onSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2.5f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> FilterChip(speed == s, { onSpeed(s) }, { Text("${fmt(s)}×") }, shape = CircleShape) }
        }
        Spacer(Modifier.height(20.dp))
        Text("Pitch: ${fmt(pitch)}", style = MaterialTheme.typography.titleMedium)
        Slider(pitch, { onPitch((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f)
    }
}

@Composable
fun SleepSheetContent(active: Boolean, onPick: (Int?) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
        Text("Pause reading after…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        listOf(5, 10, 15, 30, 45, 60).chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m -> OutlinedButton({ onPick(m) }, Modifier.weight(1f)) { Text("$m min") } }
            }
        }
        if (active) TextButton({ onPick(null) }, Modifier.fillMaxWidth()) { Text("Turn off timer") }
    }
}

// ------------------------------------------------------------------ Voices

@Composable
fun VoicesScreen(
    voices: List<VoiceInfo>, states: Map<String, VoiceState>, selected: String, speaker: Int,
    onDownload: (VoiceInfo) -> Unit, onUse: (String) -> Unit, onSample: (String) -> Unit, onDelete: (VoiceInfo) -> Unit,
    onSpeaker: (Int) -> Unit,
    previewing: String? = null, onPreview: (String) -> Unit = {},
    engineInstalled: Boolean = false,
    onCancel: (VoiceInfo) -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Voices", "Natural AI voices that run fully on your phone") }
        item {
            VoiceCard("system", "System voice", "Android's built-in voice · always available", null, VoiceState.Installed,
                selected == "system", { }, { onUse("system") }, { onSample("system") }, null, null, false, {})
        }
        items(voices, key = { it.id }) { v ->
            val idx = voices.indexOf(v)
            if (idx == 0 || voices[idx - 1].engine != v.engine) SectionHeader(
                if (v.isKokoro) "Natural · recommended" else "Compact · smaller and faster",
                if (v.isKokoro) (if (engineInstalled) "Engine installed · each extra voice is 0.5 MB"
                    else "First voice ${KokoroPack.ENGINE_MB} MB (shared engine) · each extra voice 0.5 MB") else "Lighter voices for older phones or less storage")
            VoiceCard(v.id, v.name, "${v.description} · ${v.locale}",
                if (v.isKokoro) "${v.licence} · ${KokoroPack.sizeLabel(engineInstalled)}" else "${v.licence} · ${v.sizeMb} MB",
                states[v.id] ?: VoiceState.NotInstalled, selected == v.id,
                { onDownload(v) }, { onUse(v.id) }, { onSample(v.id) }, { onDelete(v) },
                VoiceCatalog.sampleKey(v.id, speaker), previewing == VoiceCatalog.sampleKey(v.id, speaker), onPreview, { onCancel(v) })
            if (v.id == VoiceCatalog.LIBRITTS) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Featured", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VoiceCatalog.featuredSpeakers.forEach { sp ->
                        val key = VoiceCatalog.sampleKey(v.id, sp)!!
                        val on = previewing == key
                        FilterChip(selected = on || (selected == v.id && speaker == sp),
                            onClick = { onSpeaker(sp); onPreview(key) }, shape = CircleShape,
                            leadingIcon = { if (on) PlayingBars(Modifier.size(16.dp)) else Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)) },
                            label = { Text("#$sp") })
                    }
                }
            }
            if (v.id == VoiceCatalog.LIBRITTS && selected == v.id) {
                Column(Modifier.padding(horizontal = 32.dp)) {
                    Text("Speaker #$speaker of 904", style = MaterialTheme.typography.labelLarge)
                    Slider(speaker.toFloat(), { onSpeaker(it.roundToInt()) }, valueRange = 0f..903f)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VoiceCard(
    id: String, name: String, desc: String, meta: String?, state: VoiceState, selected: Boolean,
    onDownload: () -> Unit, onUse: () -> Unit, onSample: () -> Unit, onDelete: (() -> Unit)?,
    sampleKey: String?, previewing: Boolean, onPreview: (String) -> Unit, onCancel: () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))
        .background(if (selected) c.primaryContainer else c.surfaceContainer).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoiceAvatar(id, name, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    if (selected) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.CheckCircle, "Selected", Modifier.size(18.dp), tint = c.primary) }
                }
                Text(desc, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                if (meta != null) Text(meta, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            if (sampleKey != null) PreviewButton(previewing) { onPreview(sampleKey) }
            else if (state == VoiceState.Installed) FilledTonalIconButton(onClick = onSample) { Icon(Icons.Default.PlayArrow, "Play sample") }
        }
        Spacer(Modifier.height(12.dp))
        when (state) {
            is VoiceState.Downloading -> DownloadProgress(state, onCancel)
            VoiceState.Installing -> { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)); Text("Installing…", style = MaterialTheme.typography.labelSmall) }
            VoiceState.Installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!selected) Button(onClick = onUse, shape = CircleShape) { Text("Use voice") }
                if (onDelete != null) OutlinedButton(onClick = onDelete, shape = CircleShape) { Text("Remove") }
            }
            else -> Column {
                if (state is VoiceState.Failed) Text(state.message, color = c.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onDownload, shape = CircleShape) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text("Download") }
            }
        }
    }
}

// ------------------------------------------------------------------ Library

@Composable
fun LibraryScreen(items: List<LibraryItem>, onOpen: (LibraryItem) -> Unit, onDelete: (LibraryItem) -> Unit, onImport: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue < 1.5f }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Library", "${items.size} document${if (items.size == 1) "" else "s"} on this device") { Pill("Import", Icons.Outlined.UploadFile, onImport) }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
                Text("Documents you import appear here so you can pick up where you left off.", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item -> LibraryCard(item, dark, { onOpen(item) }, { onDelete(item) }) }
        }
    }
}

@Composable
private fun LibraryCard(item: LibraryItem, dark: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val (a, b) = gradientFor(item.title)
    val brush = if (dark) Brush.linearGradient(listOf(a, b)) else Brush.linearGradient(listOf(a.copy(alpha = .22f), b.copy(alpha = .38f)))
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    Column(Modifier.height(190.dp).clip(RoundedCornerShape(24.dp)).background(brush).clickable(onClick = onOpen).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).background(fg.copy(alpha = .14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(item.kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.Delete, "Delete", Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onDelete), tint = fg.copy(alpha = .7f))
        }
        Spacer(Modifier.height(12.dp))
        Text(item.title, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(item.preview, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = .75f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${(item.chars / 1000).coerceAtLeast(1)}k chars · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.openedAt))}",
            style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = .75f), maxLines = 1)
    }
}

// ------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen(
    theme: ThemeMode, dynamicColor: Boolean, dynamicAvailable: Boolean, adFree: Boolean, price: String?,
    privacyOptions: Boolean, version: String,
    onTheme: (ThemeMode) -> Unit, onDynamic: (Boolean) -> Unit, onBuy: () -> Unit, onRestore: () -> Unit,
    onPrivacyOptions: () -> Unit, onLicences: () -> Unit, onPrivacy: () -> Unit, onSource: () -> Unit,
    onTerms: () -> Unit = {}, onSupport: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Settings")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!adFree) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF9D8CFF), Color(0xFF6B4EFF)))).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Go ad-free", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text("One-time purchase. Supports development.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .85f))
                    }
                    Button(onClick = onBuy, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4A2FD0))) {
                        Text(price ?: "Remove ads")
                    }
                }
            }
            Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            SectionCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(theme == m, { onTheme(m) }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                                Text(when (m) { ThemeMode.SYSTEM -> "Auto"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" })
                            }
                        }
                    }
                }
                if (dynamicAvailable) SettingRow(Icons.Outlined.Palette, "Dynamic colour", "Use colours from your wallpaper") {
                    Switch(dynamicColor, onDynamic)
                }
            }
            Text("Purchases & privacy", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            SectionCard {
                if (adFree) SettingRow(Icons.Outlined.Verified, "Ads removed", "Thank you for supporting Serenvox!")
                SettingRow(Icons.Outlined.Restore, "Restore purchase", onClick = onRestore)
                if (privacyOptions) SettingRow(Icons.Outlined.PrivacyTip, "Ad consent options", onClick = onPrivacyOptions)
                SettingRow(Icons.Outlined.Policy, "Privacy policy", onClick = onPrivacy)
                SettingRow(Icons.Outlined.Description, "Terms of use", onClick = onTerms)
                SettingRow(Icons.Outlined.HelpOutline, "Help & support", onClick = onSupport)
            }
            Text("About", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            SectionCard {
                SettingRow(Icons.AutoMirrored.Filled.Article, "Licences & voice credits", "GPL-3.0-or-later", onClick = onLicences)
                SettingRow(Icons.Outlined.Code, "Source code", onClick = onSource)
                SettingRow(Icons.Outlined.Info, "Version", version)
            }
        }
    }
}

// ------------------------------------------------------------------ Onboarding

@Composable
fun OnboardingScreen(
    voices: List<VoiceInfo>, states: Map<String, VoiceState>, chosen: String,
    onChoose: (String) -> Unit, onDownload: () -> Unit, onSkip: () -> Unit,
    previewing: String? = null, onPreview: (String) -> Unit = {},
    engineInstalled: Boolean = false,
    onCancel: () -> Unit = {},
) {
    val state = states[chosen]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF9D8CFF), Color(0xFF5A3FD6)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Serenvox", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("Listen to documents, books and articles with natural AI voices — private and fully offline.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Text("Pick your first voice", style = MaterialTheme.typography.titleMedium)
        Text("Tap ▶ to hear a voice before you download it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val shown = VoiceCatalog.onboarding.mapNotNull { id -> voices.firstOrNull { it.id == id } }
        shown.forEachIndexed { i, v ->
            if (i == 0 || shown[i - 1].engine != v.engine) Text(
                if (v.isKokoro) "Natural · ${KokoroPack.ENGINE_MB} MB for your first voice, 0.5 MB for each extra" else "Compact · smaller and faster",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            val sel = v.id == chosen
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(20.dp))
                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .clickable { onChoose(v.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                VoiceAvatar(v.id, v.name, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (v.isKokoro) "${v.description} · ${KokoroPack.sizeLabel(engineInstalled)}" else "${v.description} · ${v.sizeMb} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VoiceCatalog.sampleKey(v.id)?.let { k -> PreviewButton(previewing == k) { onPreview(k) } }
                RadioButton(sel, { onChoose(v.id) })
            }
        }
        Spacer(Modifier.height(20.dp))
        when (state) {
            is VoiceState.Downloading -> DownloadProgress(state, onCancel)
            VoiceState.Installing -> { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)); Text("Installing…", style = MaterialTheme.typography.labelMedium) }
            else -> {
                if (state is VoiceState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDownload, Modifier.fillMaxWidth().height(56.dp), shape = CircleShape) { Text("Download voice & start") }
            }
        }
        TextButton(onClick = onSkip, Modifier.fillMaxWidth()) { Text("Skip for now — use the system voice") }
        Text("Voices are a one-time download (Wi-Fi recommended) and then work without internet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ Voice preview

/** Round play/stop button for an offline voice preview, with animated bars while playing. */
@Composable
fun PreviewButton(playing: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ring")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (playing) Box(Modifier.size(40.dp).graphicsLayer { scaleX = ring; scaleY = ring }.clip(CircleShape).background(c.primary.copy(alpha = 0.18f)))
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp).semantics { contentDescription = if (playing) "Stop preview" else "Play preview" },
            colors = if (playing) IconButtonDefaults.filledTonalIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary)
                     else IconButtonDefaults.filledTonalIconButtonColors()) {
            if (playing) PlayingBars(Modifier.size(18.dp), c.onPrimary) else Icon(Icons.Default.PlayArrow, null)
        }
    }
}

/** Three bouncing equaliser bars. */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "bars")
    val hs = listOf(0, 180, 360).map { d ->
        t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420, delayMillis = 0), RepeatMode.Reverse, initialStartOffset = StartOffset(d)), label = "b$d")
    }
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        hs.forEach { h -> Box(Modifier.width(3.dp).fillMaxHeight(h.value).clip(CircleShape).background(color)) }
    }
}

/** Progress bar + status for a background voice download, with the "you can leave" note and Cancel. */
@Composable
fun DownloadProgress(state: VoiceState.Downloading, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth().clip(CircleShape))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val pct = (state.progress * 100).roundToInt()
                Text(when {
                    state.waiting -> "Waiting for connection… $pct%"
                    state.totalBytes > 0 -> "Downloading… $pct% · ${state.doneBytes / 1_000_000} of ${state.totalBytes / 1_000_000} MB"
                    else -> "Downloading… $pct%"
                }, style = MaterialTheme.typography.labelMedium)
                Text("You can leave the app; the download continues in the background.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
