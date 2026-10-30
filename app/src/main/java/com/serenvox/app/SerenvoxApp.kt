package com.serenvox.app

import android.app.Application
import com.serenvox.app.billing.BillingManager
import com.serenvox.app.tts.SpeechPlayer
import com.serenvox.app.tts.VoiceManager

class SerenvoxApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var voices: VoiceManager; private set
    lateinit var player: SpeechPlayer; private set
    lateinit var billing: BillingManager; private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        voices = VoiceManager(this)
        player = SpeechPlayer(this, voices)
        billing = BillingManager(this).also { it.connect() }
    }
}
