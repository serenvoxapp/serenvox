// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.serenvox.app.BuildConfig
import com.serenvox.app.MainActivity
import com.serenvox.app.Prefs
import com.serenvox.app.SerenvoxApp
import com.serenvox.app.ThemeMode
import com.serenvox.app.doc.DocumentImporter
import com.serenvox.app.tts.PlaybackService
import com.serenvox.app.tts.Status
import com.serenvox.app.tts.VoiceCatalog
import com.serenvox.app.tts.VoiceState
import com.serenvox.app.ui.theme.SerenvoxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

enum class Tab(val label: String, val icon: ImageVector) {
    READ("Read", Icons.Outlined.AutoStories), VOICES("Voices", Icons.Outlined.RecordVoiceOver),
    LIBRARY("Library", Icons.AutoMirrored.Outlined.LibraryBooks), SETTINGS("Settings", Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(activity: MainActivity, app: SerenvoxApp) {
    val prefs = app.prefs
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val dynamic by prefs.dynamicColor.collectAsStateWithLifecycle()
    val dark = when (theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    SerenvoxTheme(dark, dynamic) {
        val onboarded by prefs.onboarded.collectAsStateWithLifecycle()
        val voiceStates by app.voices.states.collectAsStateWithLifecycle()
        val engineInstalled by app.voices.engineInstalled.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        if (!onboarded) {
            var chosen by rememberSaveable { mutableStateOf(VoiceCatalog.voices.first().id) }
            LaunchedEffect(voiceStates[chosen]) {
                if (voiceStates[chosen] == VoiceState.Installed) { prefs.setVoice(chosen); prefs.setOnboarded() }
            }
            val samples = remember { com.serenvox.app.tts.SamplePlayer(activity) }
            val previewing by samples.playing.collectAsStateWithLifecycle()
            DisposableEffect(Unit) { onDispose { samples.stop() } }
            LaunchedEffect(chosen) { samples.stop() }
            Surface { OnboardingScreen(VoiceCatalog.voices, voiceStates, chosen, { chosen = it },
                { samples.stop(); val v = VoiceCatalog.byId(chosen)!!; scope.launch { app.voices.download(v) } },
                { samples.stop(); prefs.setOnboarded() }, previewing, { samples.toggle(it) }, engineInstalled,
                { VoiceCatalog.byId(chosen)?.let { app.voices.cancelDownload(it) } }) }
            return@SerenvoxTheme
        }
        MainShell(activity, app)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(activity: MainActivity, app: SerenvoxApp) {
    val prefs = app.prefs; val player = app.player
    val state by player.state.collectAsStateWithLifecycle()
    val speed by prefs.speed.collectAsStateWithLifecycle()
    val pitch by prefs.pitch.collectAsStateWithLifecycle()
    val voice by prefs.voice.collectAsStateWithLifecycle()
    val speaker by prefs.speaker.collectAsStateWithLifecycle()
    val voiceStates by app.voices.states.collectAsStateWithLifecycle()
    val engineInstalled by app.voices.engineInstalled.collectAsStateWithLifecycle()
    val adFree by app.billing.adFree.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(adFree) { activity.ads.setAdFree(adFree) }
    val adsReady by activity.ads.ready.collectAsStateWithLifecycle()
    val library by app.library.items.collectAsStateWithLifecycle()
    val sleepEnd by player.sleepEndsAt.collectAsStateWithLifecycle()
    val repeatMode by player.repeatMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(Tab.READ) }
    var licences by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(prefs.lastText) }
    var title by rememberSaveable { mutableStateOf(prefs.currentTitle) }
    var docId by rememberSaveable { mutableStateOf(prefs.currentDoc) }
    var importing by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf("Reading your document…") }
    var importError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<Float?>(null) }
    var speedSheet by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepEnd) { while (sleepEnd != null) { now = System.currentTimeMillis(); delay(15_000) } }
    val sleepLeft = sleepEnd?.let { (((it - now) + 59_999) / 60_000).toInt().coerceAtLeast(1) }

    LaunchedEffect(text) { prefs.lastText = text.take(500_000) }
    LaunchedEffect(title, docId) { prefs.currentTitle = title; prefs.currentDoc = docId }
    LaunchedEffect(state.index, docId) { docId?.let { if (state.status != Status.IDLE) app.library.setPosition(it, state.index) } }
    val msg by app.billing.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) { msg?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show(); app.billing.clearMessage() } }

    fun importUri(uri: Uri, mime: String?) {
        scope.launch {
            importing = true; tab = Tab.READ; player.stop()
            importStatus = "Reading your document…"
            runCatching { app.importer.import(uri, mime) { s -> scope.launch { importStatus = s } } }
                .onSuccess { d ->
                    val item = app.library.add(d.title, d.kind, d.text)
                    text = d.text; title = d.title; docId = item.id
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.e("SerenvoxImport", "Import failed", it)
                    importError = it.message ?: "Import failed (${it.javaClass.simpleName})." }
            importing = false
        }
    }
    importError?.let { m ->
        AlertDialog(onDismissRequest = { importError = null },
            title = { Text("Couldn't import") }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } })
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importUri(it, null) } }
    val launchImport = { picker.launch(DocumentImporter.MIME_TYPES) }

    val incomingText by activity.incomingText
    LaunchedEffect(incomingText) { incomingText?.let { text = it; title = "Shared text"; docId = null; tab = Tab.READ; activity.incomingText.value = null } }
    val incomingUri by activity.incomingUri
    LaunchedEffect(incomingUri) { incomingUri?.let { (u, m) -> activity.incomingUri.value = null; importUri(u, m) } }

    val activeVoice = if (voiceStates[voice] == VoiceState.Installed) voice else Prefs.SYSTEM_VOICE
    val voiceOptions = listOf(VoiceOption(Prefs.SYSTEM_VOICE, "System")) +
        VoiceCatalog.voices.filter { voiceStates[it.id] == VoiceState.Installed }.map { VoiceOption(it.id, it.name.substringBefore(" (")) }

    fun playPause() {
        when (state.status) {
            Status.PLAYING, Status.PREPARING -> player.pause()
            Status.PAUSED -> player.resume()
            Status.IDLE -> if (text.isNotBlank()) {
                val from = docId?.let { id -> library.firstOrNull { it.id == id }?.position } ?: 0
                player.play(text, activeVoice, speed, pitch, speaker, from)
                PlaybackService.start(activity)
            }
        }
    }

    BackHandler(licences || tab != Tab.READ) { if (licences) licences = false else tab = Tab.READ }

    if (licences) { LicencesPage { licences = false }; return }

    Scaffold(
        bottomBar = {
            Column {
                if (tab != Tab.READ && state.status != Status.IDLE) MiniPlayer(title, state, { tab = Tab.READ }, ::playPause)
                if (!adFree && adsReady) { BannerAd(); androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(tab == t, { tab = t }, { Icon(t.icon, null) }, label = { Text(t.label) })
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).statusBarsPadding().fillMaxSize()) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    Tab.READ -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            ReadScreen(title, text, state, voiceOptions, activeVoice, importing, importStatus = importStatus, exportProgress = exporting,
                                canExport = activeVoice != Prefs.SYSTEM_VOICE,
                                onTextChange = { text = it; if (docId != null) { docId = null; title = "" } },
                                onImport = launchImport,
                                onClear = { text = ""; title = ""; docId = null },
                                onVoice = { player.stop(); prefs.setVoice(it) },
                                onMoreVoices = { tab = Tab.VOICES },
                                onExport = {
                                    val out = File(activity.cacheDir, "exports").apply { mkdirs() }.let { File(it, "serenvox.wav") }
                                    scope.launch {
                                        exporting = 0f
                                        val r = runCatching { player.exportWav(text, activeVoice, speed, speaker, out) { exporting = it } }
                                        exporting = null
                                        r.onSuccess {
                                            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", out)
                                            activity.ads.maybeShowAfterExport(adFree, player.state.value.status != Status.IDLE) {
                                                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                                    type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }, "Share audio"))
                                            }
                                        }.onFailure { Toast.makeText(activity, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                                    }
                                },
                                onSeek = { player.seekTo(it) })
                        }
                        PlayerBar(state, speed, sleepLeft, text.isNotBlank() && !importing, ::playPause,
                            { player.skip(-1) }, { player.skip(1) }, { speedSheet = true }, { sleepSheet = true }, { player.stop() },
                            repeatMode = repeatMode, onRepeat = { repeatMode.next().let { prefs.setRepeatMode(it); player.setRepeatMode(it) } })
                    }
                    Tab.VOICES -> {
                      val samples = remember { com.serenvox.app.tts.SamplePlayer(activity) }
                      val previewing by samples.playing.collectAsStateWithLifecycle()
                      DisposableEffect(Unit) { onDispose { samples.stop() } }
                      LaunchedEffect(activeVoice) { samples.stop() }
                      VoicesScreen(VoiceCatalog.voices, voiceStates, activeVoice, speaker,
                        onDownload = { v -> scope.launch { app.voices.download(v) } },
                        onCancel = { v -> app.voices.cancelDownload(v) },
                        onUse = { player.stop(); prefs.setVoice(it) },
                        onSample = { id -> samples.stop()
                            val name = VoiceCatalog.byId(id)?.name?.substringBefore(" (") ?: "your system voice"
                            player.play("Hi, I'm $name. I can read your documents aloud, right here on your phone.", id, speed, pitch, speaker)
                        },
                        onDelete = { v -> player.stop(); app.voices.delete(v); if (prefs.voice.value == v.id) prefs.setVoice(Prefs.SYSTEM_VOICE) },
                        onSpeaker = { prefs.setSpeaker(it) },
                        previewing = previewing, onPreview = { player.stop(); samples.toggle(it) }, engineInstalled = engineInstalled)
                    }
                    Tab.LIBRARY -> LibraryScreen(library,
                        onOpen = { item ->
                            app.library.text(item.id)?.let { player.stop(); text = it; title = item.title; docId = item.id; app.library.touch(item.id); tab = Tab.READ }
                        },
                        onDelete = { app.library.delete(it.id); if (docId == it.id) docId = null },
                        onImport = launchImport)
                    Tab.SETTINGS -> {
                        val theme by prefs.theme.collectAsStateWithLifecycle()
                        val dyn by prefs.dynamicColor.collectAsStateWithLifecycle()
                        val price by app.billing.price.collectAsStateWithLifecycle()
                        SettingsScreen(theme, dyn, Build.VERSION.SDK_INT >= 31, adFree, price, activity.ads.privacyOptionsRequired,
                            (if (BuildConfig.TEST_BUILD) BuildConfig.VERSION_NAME + " · Test build" else BuildConfig.VERSION_NAME), prefs::setTheme, prefs::setDynamicColor, { app.billing.buy(activity) }, { app.billing.restore() },
                            { activity.ads.showPrivacyOptions() }, { licences = true },
                            { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) },
                            { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) },
                            onTerms = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL))) },
                            onSupport = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))) })
                    }
                }
            }
        }
    }

    if (speedSheet) ModalBottomSheet({ speedSheet = false }) {
        SpeedSheetContent(speed, pitch, { prefs.setSpeed(it); player.updateParams(it, pitch) }, { prefs.setPitch(it); player.updateParams(speed, it) })
    }
    if (sleepSheet) ModalBottomSheet({ sleepSheet = false }) {
        SleepSheetContent(sleepEnd != null) { player.setSleepTimer(it); sleepSheet = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicencesPage(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Licences") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(Licences.text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BannerAd() {
    val width = LocalConfiguration.current.screenWidthDp
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.runtime.remember(width) {
        AdView(ctx).apply {
            adUnitId = BuildConfig.BANNER_ID
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, width))
            loadAd(AdRequest.Builder().build())
        }
    }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(view, owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) { androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> view.pause(); androidx.lifecycle.Lifecycle.Event.ON_RESUME -> view.resume(); else -> {} }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); view.destroy() }
    }
    // Reserve the anchored-banner height so the bar does not jump when the ad fills.
    AndroidView(modifier = Modifier.fillMaxWidth().height(view.adSize?.height?.dp ?: 50.dp), factory = { view })
}

// TODO: replace with the URL where store/privacy-policy.html is hosted.
const val PRIVACY_URL = "https://serenvox.com/privacy/"
const val TERMS_URL = "https://serenvox.com/terms/"
const val SUPPORT_URL = "https://serenvox.com/support/"
const val WEBSITE_URL = "https://serenvox.com/"
// TODO: replace with the public source repository URL (required for GPL-3 compliance).
const val SOURCE_URL = "https://github.com/serenvoxapp/serenvox"
