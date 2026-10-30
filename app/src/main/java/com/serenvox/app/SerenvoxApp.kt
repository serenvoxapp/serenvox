// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import android.app.Application
import com.serenvox.app.billing.BillingManager
import com.serenvox.app.doc.DocumentImporter
import com.serenvox.app.library.LibraryRepo
import com.serenvox.app.tts.SpeechPlayer
import com.serenvox.app.tts.VoiceManager

class SerenvoxApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var voices: VoiceManager; private set
    lateinit var player: SpeechPlayer; private set
    lateinit var billing: BillingManager; private set
    lateinit var library: LibraryRepo; private set
    lateinit var importer: DocumentImporter; private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        voices = VoiceManager(this)
        player = SpeechPlayer(this, voices)
        player.setRepeatMode(prefs.repeatMode.value)
        library = LibraryRepo(this)
        importer = DocumentImporter(this)
        billing = BillingManager(this).also { it.connect() }
    }
}
