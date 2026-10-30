package com.serenvox.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One-time "remove_ads" purchase via Google Play Billing. Entitlement is cached locally. */
class BillingManager(context: Context) : PurchasesUpdatedListener {
    private val sp = context.getSharedPreferences("billing", Context.MODE_PRIVATE)
    private val _adFree = MutableStateFlow(sp.getBoolean(KEY, false))
    val adFree: StateFlow<Boolean> = _adFree
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private var details: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun connect(onReady: () -> Unit = {}) {
        if (client.isReady) { onReady(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct(); restore(silent = true); onReady()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        )).build()
        client.queryProductDetailsAsync(params) { _, result ->
            details = result.productDetailsList.firstOrNull()
            _price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    fun buy(activity: Activity) {
        connect {
            val d = details
            if (d == null) { _message.value = "Store not available yet. Try again shortly."; queryProduct(); return@connect }
            val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
            )).build()
            activity.runOnUiThread { client.launchBillingFlow(activity, params) }
        }
    }

    fun restore(silent: Boolean = false) {
        connect {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build()) { r, purchases ->
                if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                    if (!silent) _message.value = "Couldn't reach Google Play (${r.debugMessage})"
                    return@queryPurchasesAsync
                }
                val owned = purchases.any { handle(it) }
                setAdFree(owned)
                if (!silent) _message.value = if (owned) "Purchase restored — ads removed." else "No previous purchase found."
            }
        }
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { if (handle(it)) { setAdFree(true); _message.value = "Thanks! Ads removed." } }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            else -> _message.value = "Purchase failed: ${r.debugMessage}"
        }
    }

    /** Returns true if this purchase grants the entitlement. */
    private fun handle(p: Purchase): Boolean {
        if (PRODUCT_ID !in p.products || p.purchaseState != Purchase.PurchaseState.PURCHASED) return false
        if (!p.isAcknowledged) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
        }
        return true
    }

    private fun setAdFree(v: Boolean) { _adFree.value = v; sp.edit().putBoolean(KEY, v).apply() }
    fun clearMessage() { _message.value = null }

    companion object {
        const val PRODUCT_ID = "remove_ads"
        private const val KEY = "ad_free"
    }
}
