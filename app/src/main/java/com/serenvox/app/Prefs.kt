// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Tiny persisted settings store exposed as StateFlows. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(ThemeMode.valueOf(sp.getString("theme", "SYSTEM")!!))
    val theme: StateFlow<ThemeMode> = _theme
    private val _speed = MutableStateFlow(sp.getFloat("speed", 1f))
    val speed: StateFlow<Float> = _speed
    private val _pitch = MutableStateFlow(sp.getFloat("pitch", 1f))
    val pitch: StateFlow<Float> = _pitch
    /** Neural voice id, or "system" for Android's built-in TTS. */
    private val _voice = MutableStateFlow(sp.getString("voice", SYSTEM_VOICE)!!)
    val voice: StateFlow<String> = _voice
    private val _speaker = MutableStateFlow(sp.getInt("speaker", 0))
    val speaker: StateFlow<Int> = _speaker

    var exportCount: Int
        get() = sp.getInt("exports", 0)
        set(v) { sp.edit().putInt("exports", v).apply() }
    var lastInterstitialAt: Long
        get() = sp.getLong("lastInterstitial", 0)
        set(v) { sp.edit().putLong("lastInterstitial", v).apply() }
    var lastText: String
        get() = sp.getString("lastText", "") ?: ""
        set(v) { sp.edit().putString("lastText", v).apply() }

    fun setTheme(t: ThemeMode) { _theme.value = t; sp.edit().putString("theme", t.name).apply() }
    fun setSpeed(v: Float) { _speed.value = v; sp.edit().putFloat("speed", v).apply() }
    fun setPitch(v: Float) { _pitch.value = v; sp.edit().putFloat("pitch", v).apply() }
    fun setVoice(id: String) { _voice.value = id; sp.edit().putString("voice", id).apply() }
    fun setSpeaker(v: Int) { _speaker.value = v; sp.edit().putInt("speaker", v).apply() }

    companion object { const val SYSTEM_VOICE = "system" }
}
