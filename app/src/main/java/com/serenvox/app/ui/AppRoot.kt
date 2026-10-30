// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.serenvox.app.BuildConfig
import com.serenvox.app.MainActivity
import com.serenvox.app.Prefs
import com.serenvox.app.R
import com.serenvox.app.SerenvoxApp
import com.serenvox.app.ThemeMode
import com.serenvox.app.tts.PlaybackService
import com.serenvox.app.tts.Status
import com.serenvox.app.tts.VoiceCatalog
import com.serenvox.app.tts.VoiceState
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private enum class Screen { MAIN, VOICES, SETTINGS, LICENCES }

@Composable
fun AppRoot(activity: MainActivity, app: SerenvoxApp) {
    val theme by app.prefs.theme.collectAsStateWithLifecycle()
    val dark = when (theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val ctx = activity
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) {
        var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
        BackHandler(screen != Screen.MAIN) { screen = if (screen == Screen.LICENCES) Screen.SETTINGS else Screen.MAIN }
        val msg by app.billing.message.collectAsStateWithLifecycle()
        LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); app.billing.clearMessage() } }
        when (screen) {
            Screen.MAIN -> MainScreen(activity, app, { screen = Screen.VOICES }, { screen = Screen.SETTINGS })
            Screen.VOICES -> VoicesScreen(app) { screen = Screen.MAIN }
            Screen.SETTINGS -> SettingsScreen(activity, app, { screen = Screen.LICENCES }) { screen = Screen.MAIN }
            Screen.LICENCES -> SimpleScreen("Open-source licences", { screen = Screen.SETTINGS }) {
                SelectionContainer { Text(Licences.text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: MainActivity, app: SerenvoxApp, openVoices: () -> Unit, openSettings: () -> Unit) {
    val prefs = app.prefs
    val player = app.player
    val state by player.state.collectAsStateWithLifecycle()
    val speed by prefs.speed.collectAsStateWithLifecycle()
    val pitch by prefs.pitch.collectAsStateWithLifecycle()
    val voice by prefs.voice.collectAsStateWithLifecycle()
    val speaker by prefs.speaker.collectAsStateWithLifecycle()
    val voiceStates by app.voices.states.collectAsStateWithLifecycle()
    val adFree by app.billing.adFree.collectAsStateWithLifecycle()
    val adsReady by activity.ads.ready.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(prefs.lastText) }
    val incoming by activity.incomingText
    LaunchedEffect(incoming) { incoming?.let { text = it; activity.incomingText.value = null } }
    LaunchedEffect(text) { prefs.lastText = text.take(100_000) }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<Float?>(null) }

    val installed = VoiceCatalog.voices.filter { voiceStates[it.id] == VoiceState.Installed }
    val activeVoice = if (voiceStates[voice] == VoiceState.Installed) voice else Prefs.SYSTEM_VOICE

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { activity.readText(it) }?.let { text = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(activity.getString(R.string.app_name)) }, actions = {
                IconButton(onClick = { importer.launch(arrayOf("text/plain")) }) { Icon(Icons.Default.FileOpen, "Import .txt") }
                IconButton(onClick = openVoices) { Icon(Icons.Default.RecordVoiceOver, "Voices") }
                IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
            })
        },
        bottomBar = { if (!adFree && adsReady) BannerAd() }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (state.status == Status.IDLE) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                    label = { Text("Type or paste text") })
            } else {
                // Highlight the sentence being spoken.
                Card(Modifier.fillMaxWidth().heightIn(min = 180.dp)) {
                    Text(buildAnnotatedString {
                        state.chunks.forEachIndexed { i, c ->
                            if (i == state.index) withStyle(SpanStyle(fontWeight = FontWeight.Bold,
                                background = MaterialTheme.colorScheme.primaryContainer)) { append(c) }
                            else append(c)
                            append(' ')
                        }
                    }, Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text(when (state.status) {
                Status.IDLE -> state.error ?: "${text.length} characters"
                Status.PREPARING -> "Loading voice…"
                else -> "Sentence ${state.index + 1} of ${state.chunks.size} · ${state.engine}"
            }, style = MaterialTheme.typography.labelMedium)

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledIconButton(onClick = {
                    when (state.status) {
                        Status.PLAYING, Status.PREPARING -> player.pause()
                        Status.PAUSED -> player.resume()
                        Status.IDLE -> {
                            player.play(text, activeVoice, speed, pitch, speaker)
                            PlaybackService.start(activity)
                        }
                    }
                }, Modifier.size(64.dp), enabled = text.isNotBlank()) {
                    Icon(if (state.status == Status.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
                }
                FilledTonalIconButton(onClick = { player.stop() }, Modifier.size(64.dp), enabled = state.status != Status.IDLE) {
                    Icon(Icons.Default.Stop, "Stop")
                }
                FilledTonalIconButton(onClick = {
                    val out = File(activity.cacheDir, "exports").apply { mkdirs() }.let { File(it, "serenvox.wav") }
                    scope.launch {
                        exporting = 0f
                        val r = runCatching { player.exportWav(text, activeVoice, speed, speaker, out) { exporting = it } }
                        exporting = null
                        r.onSuccess {
                            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", out)
                            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share audio"))
                            activity.ads.maybeShowAfterExport(adFree, player.state.value.status != Status.IDLE)
                        }.onFailure { Toast.makeText(activity, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }, Modifier.size(64.dp),
                    enabled = text.isNotBlank() && exporting == null && activeVoice != Prefs.SYSTEM_VOICE && state.status == Status.IDLE) {
                    Icon(Icons.Default.Download, "Export WAV")
                }
            }
            exporting?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()); Text("Exporting WAV… ${(it * 100).roundToInt()}%") }

            // Voice picker
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(open, { open = it }) {
                OutlinedTextField(
                    value = VoiceCatalog.byId(activeVoice)?.name ?: "System voice (Android)", onValueChange = {}, readOnly = true,
                    label = { Text("Voice") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text("System voice (Android)") }, { prefs.setVoice(Prefs.SYSTEM_VOICE); open = false })
                    installed.forEach { v -> DropdownMenuItem({ Text("${v.name} · neural") }, { prefs.setVoice(v.id); open = false }) }
                    DropdownMenuItem({ Text("Get more voices…") }, { open = false; openVoices() })
                }
            }
            if (activeVoice == Prefs.SYSTEM_VOICE) {
                TextButton(onClick = openVoices) { Text("Tip: download a free offline AI voice for smoother speech") }
            }
            if (activeVoice == "en_US-libritts-high") {
                Text("Speaker #$speaker")
                Slider(speaker.toFloat(), { prefs.setSpeaker(it.roundToInt()) }, valueRange = 0f..903f)
            }
            Text("Speed ${"%.2f".format(speed)}×")
            Slider(speed, { prefs.setSpeed(it) }, valueRange = 0.5f..2f)
            Text("Pitch ${"%.2f".format(pitch)}")
            Slider(pitch, { prefs.setPitch(it) }, valueRange = 0.5f..2f)
            Text("Speed/pitch apply from the next time you press play.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BannerAd() {
    val width = LocalConfiguration.current.screenWidthDp
    AndroidView(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), factory = { c ->
        AdView(c).apply {
            adUnitId = BuildConfig.BANNER_ID
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(c, width))
            loadAd(AdRequest.Builder().build())
        }
    })
}

@Composable
private fun VoicesScreen(app: SerenvoxApp, onBack: () -> Unit) {
    val states by app.voices.states.collectAsStateWithLifecycle()
    val current by app.prefs.voice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SimpleScreen("Voices", onBack) {
        Text("Neural voices run fully on your device and work offline after a one-time download (Wi-Fi recommended).",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        VoiceCatalog.voices.forEach { v ->
            val s = states[v.id] ?: VoiceState.NotInstalled
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(v.name + if (current == v.id) "  ✓" else "", style = MaterialTheme.typography.titleMedium)
                    Text("${v.description} · ${v.locale} · ~${v.sizeMb} MB download · ${v.licence}", style = MaterialTheme.typography.bodySmall)
                    when (s) {
                        is VoiceState.Downloading -> { LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 8.dp)) }
                        VoiceState.Installing -> { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)); Text("Installing…") }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s is VoiceState.Failed) Text("Failed: ${s.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            if (s == VoiceState.Installed) {
                                TextButton(onClick = { app.prefs.setVoice(v.id) }) { Text("Use") }
                                TextButton(onClick = {
                                    app.player.play("Hello! This is ${v.name}, an offline voice from Serenvox.", v.id, 1f, 1f, 0)
                                }) { Text("Preview") }
                                TextButton(onClick = {
                                    app.player.stop(); app.voices.delete(v)
                                    if (current == v.id) app.prefs.setVoice(Prefs.SYSTEM_VOICE)
                                }) { Text("Delete") }
                            } else {
                                TextButton(onClick = { scope.launch { app.voices.download(v); if (app.voices.isInstalled(v.id) && current == Prefs.SYSTEM_VOICE) app.prefs.setVoice(v.id) } }) { Text("Download") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(activity: MainActivity, app: SerenvoxApp, openLicences: () -> Unit, onBack: () -> Unit) {
    val theme by app.prefs.theme.collectAsStateWithLifecycle()
    val adFree by app.billing.adFree.collectAsStateWithLifecycle()
    val price by app.billing.price.collectAsStateWithLifecycle()
    SimpleScreen("Settings", onBack) {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == m, { app.prefs.setTheme(m) })
                Text(m.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Ads", style = MaterialTheme.typography.titleMedium)
        if (adFree) Text("Ads removed — thank you for supporting the app!")
        else Button(onClick = { app.billing.buy(activity) }) { Text("Remove ads" + (price?.let { " ($it)" } ?: "")) }
        OutlinedButton(onClick = { app.billing.restore() }) { Text("Restore purchase") }
        if (activity.ads.privacyOptionsRequired) {
            OutlinedButton(onClick = { activity.ads.showPrivacyOptions() }) { Text("Privacy / ad consent options") }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        TextButton(onClick = openLicences) { Text("About, licences & voice credits") }
        TextButton(onClick = {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL)))
        }) { Text("Privacy policy") }
        TextButton(onClick = {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL)))
        }) { Text("Source code (GPL-3.0)") }
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
}

// TODO: replace with the URL where store/privacy-policy.html is hosted.
const val PRIVACY_URL = "https://example.com/serenvox/privacy"
// TODO: replace with the public source repository URL (required for GPL-3 compliance).
const val SOURCE_URL = "https://github.com/OWNER/serenvox"
