// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.ads

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.RequestConfiguration
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
            val testIds = BuildConfig.ADS_TEST_DEVICE_IDS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (testIds.isNotEmpty()) MobileAds.setRequestConfiguration(RequestConfiguration.Builder().setTestDeviceIds(testIds).build())
            MobileAds.initialize(activity) {
                activity.runOnUiThread { _ready.value = true; loadInterstitial() }
            }
        }
    }

    /** Blank interstitial ID (ads.properties) = interstitials off entirely. */
    private val interstitialsEnabled get() = BuildConfig.INTERSTITIAL_ID.isNotBlank()
    private val startedAt = System.currentTimeMillis()

    private fun loadInterstitial() {
        if (!interstitialsEnabled || prefsAdFree()) return
        InterstitialAd.load(activity, BuildConfig.INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }
    private var adFreeNow = false
    private fun prefsAdFree() = adFreeNow

    /**
     * Called when an export has finished, before the share sheet opens: a natural break the user
     * started. Never on launch/exit, never while audio plays. At most one per 3 exports, at most
     * once every 5 minutes, and not in the first 2 minutes of a session. [then] (open the share
     * sheet) runs after the ad is closed, or immediately when no ad is shown.
     */
    fun maybeShowAfterExport(adFree: Boolean, isPlaying: Boolean, then: () -> Unit) {
        adFreeNow = adFree
        prefs.exportCount += 1
        val now = System.currentTimeMillis()
        val ad = interstitial
        if (adFree || isPlaying || !_ready.value || ad == null || prefs.exportCount % 3 != 0 ||
            now - prefs.lastInterstitialAt < 5 * 60_000 || now - startedAt < 2 * 60_000) { then(); return }
        prefs.lastInterstitialAt = now
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { then(); loadInterstitial() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { then(); loadInterstitial() }
        }
        ad.show(activity)
    }

    /** Remove-ads purchased: drop any preloaded interstitial. */
    fun setAdFree(adFree: Boolean) { adFreeNow = adFree; if (adFree) interstitial = null }
}
