// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ads

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.serenvox.app.BuildConfig
import com.serenvox.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles UMP consent (GDPR / US state privacy) and AdMob. Ads are only initialised and
 * requested after consent info says ads may be requested.
 */
class AdsManager(private val activity: Activity, private val prefs: Prefs) {
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
    private val initStarted = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private var interstitial: InterstitialAd? = null

    val privacyOptionsRequired: Boolean
        get() = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun gatherConsent(adFree: Boolean) {
        if (adFree) return
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { if (consent.canRequestAds()) initAds() }
        }, { if (consent.canRequestAds()) initAds() })
        if (consent.canRequestAds()) initAds()
    }

    fun showPrivacyOptions() = UserMessagingPlatform.showPrivacyOptionsForm(activity) {}

    private fun initAds() {
        if (!initStarted.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(activity) {
                activity.runOnUiThread { _ready.value = true; loadInterstitial() }
            }
        }
    }

    private fun loadInterstitial() {
        InterstitialAd.load(activity, BuildConfig.INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }

    /**
     * Called after a successful export (never during playback). Shows at most one
     * interstitial every 3rd export and no more than once per 5 minutes.
     */
    fun maybeShowAfterExport(adFree: Boolean, isPlaying: Boolean) {
        prefs.exportCount += 1
        if (adFree || isPlaying || !_ready.value) return
        val now = System.currentTimeMillis()
        if (prefs.exportCount % 3 != 0 || now - prefs.lastInterstitialAt < 5 * 60_000) return
        val ad = interstitial ?: return
        prefs.lastInterstitialAt = now
        interstitial = null
        ad.show(activity)
        loadInterstitial()
    }
}
