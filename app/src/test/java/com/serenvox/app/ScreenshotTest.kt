// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.serenvox.app.library.LibraryItem
import com.serenvox.app.tts.PlayerState
import com.serenvox.app.tts.Status
import com.serenvox.app.tts.VoiceCatalog
import com.serenvox.app.tts.VoiceState
import com.serenvox.app.ui.*
import com.serenvox.app.ui.theme.SerenvoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = System.getProperty("screenshots.dir") ?: "build/screenshots"

    private val sample = "The sun had barely risen over the harbour when Mira opened the old notebook. " +
        "Its pages smelled of salt and cedar. She read the first line aloud, and the words seemed to hang in the air. " +
        "Somewhere a gull called. The tide was turning, and with it, everything she thought she knew. " +
        "She turned the page and kept reading, slower now, as if the story might change if she hurried."
    private val chunks = com.serenvox.app.tts.Chunker.split(sample)
    private val voices = listOf(VoiceOption("system", "System"), VoiceOption("en_US-ljspeech-medium", "Linda"),
        VoiceOption("en_GB-cori-medium", "Cori"), VoiceOption("en_US-norman-medium", "Norman"))
    private val playing = PlayerState(Status.PLAYING, chunks, 2, "Linda (US)")
    private val states = VoiceCatalog.natural.associate { it.id to (VoiceState.Installed as VoiceState) } + mapOf("en_US-ljspeech-medium" to VoiceState.Installed, "en_GB-cori-medium" to VoiceState.Installed,
        "en_US-norman-medium" to VoiceState.Downloading(0.42f))
    private val now = 1_793_000_000_000L
    private val lib = listOf(
        LibraryItem("1", "The Harbour Notebook", "pdf", 182_000, now, now, 3, sample),
        LibraryItem("2", "Quarterly report Q3", "docx", 24_000, now, now - 86_400_000, 0, "Revenue grew steadily across all regions this quarter, led by…"),
        LibraryItem("3", "Pride and Prejudice", "epub", 690_000, now, now - 3 * 86_400_000, 0, "It is a truth universally acknowledged, that a single man in possession of a good fortune…"),
        LibraryItem("4", "Meeting notes", "txt", 3_000, now, now - 9 * 86_400_000, 0, "Action items: finalise the onboarding flow, review voice licences…"),
    )

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent { SerenvoxTheme(dark) { Surface(Modifier.fillMaxSize()) { content() } } }
        rule.onRoot().captureRoboImage("$dir/${name}_${if (dark) "dark" else "light"}.png")
    }


    @Composable private fun readPlaying() = Column {
        Box(Modifier.weight(1f)) {
            ReadScreen("The Harbour Notebook", sample, playing, voices, "en_US-ljspeech-medium", false, null, true,
                {}, {}, {}, {}, {}, {}, {})
        }
        PlayerBar(playing, 1.25f, 15, true, {}, {}, {}, {}, {}, {})
    }
    @Composable private fun readEmpty() = Column {
        Box(Modifier.weight(1f)) { ReadScreen("", "", PlayerState(), voices, "system", false, null, false, {}, {}, {}, {}, {}, {}, {}) }
        PlayerBar(PlayerState(), 1f, null, false, {}, {}, {}, {}, {}, {})
    }
    @Composable private fun voicesScreen() = VoicesScreen(VoiceCatalog.voices, states, "kokoro-heart", 20, {}, {}, {}, {}, {}, previewing = "kokoro-emma", engineInstalled = true)
    @Composable private fun library() = LibraryScreen(lib, {}, {}, {})
    @Composable private fun settings() = SettingsScreen(com.serenvox.app.ThemeMode.SYSTEM, false, true, false, "US$2.99", true, "1.2.2",
        {}, {}, {}, {}, {}, {}, {}, {})
    @Composable private fun onboarding() = OnboardingScreen(VoiceCatalog.voices, emptyMap(), "kokoro-heart", {}, {}, {}, previewing = "kokoro-michael")
    @Composable private fun speedSheet() = Column { Box(Modifier.weight(1f)) {}; SpeedSheetContent(1.25f, 1f, {}, {}); SleepSheetContent(true) {} }

    @Test fun read_light() = shot("1_read_player", false) { readPlaying() }
    @Test fun read_dark() = shot("1_read_player", true) { readPlaying() }
    @Test fun empty_light() = shot("2_read_empty", false) { readEmpty() }
    @Test fun empty_dark() = shot("2_read_empty", true) { readEmpty() }
    @Test fun voices_light() = shot("3_voices", false) { voicesScreen() }
    @Test fun voices_dark() = shot("3_voices", true) { voicesScreen() }
    @Test fun library_light() = shot("4_library", false) { library() }
    @Test fun library_dark() = shot("4_library", true) { library() }
    @Test fun settings_light() = shot("5_settings", false) { settings() }
    @Test fun settings_dark() = shot("5_settings", true) { settings() }
    @Test fun onboarding_light() = shot("6_onboarding", false) { onboarding() }
    @Test fun onboarding_dark() = shot("6_onboarding", true) { onboarding() }
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun onboardingTall_light() = shot("8_onboarding_previews_small_screen", false) { onboarding() }
    @Test fun sheets_light() = shot("7_speed_sleep_sheets", false) { speedSheet() }
    @Test fun sheets_dark() = shot("7_speed_sleep_sheets", true) { speedSheet() }
}
