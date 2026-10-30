// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Plays the bundled, pre-rendered voice preview clips (assets/samples). One at a time. */
class SamplePlayer(private val context: Context) {
    private var mp: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)
    /** Asset key of the clip currently playing, or null. */
    val playing: StateFlow<String?> = _playing

    fun toggle(key: String) { if (_playing.value == key) stop() else play(key) }

    fun play(key: String) {
        stop()
        runCatching {
            context.assets.openFd("samples/$key.m4a").use { fd ->
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                p.setOnCompletionListener { if (mp === it) stop() }
                p.setOnErrorListener { _, _, _ -> stop(); true }
                p.prepare(); p.start()
                mp = p; _playing.value = key
            }
        }.onFailure { stop() }
    }

    fun stop() {
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null; _playing.value = null
    }
}
