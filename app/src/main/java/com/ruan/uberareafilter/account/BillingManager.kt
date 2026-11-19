package com.ruan.uberareafilter.account

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Assinatura pelo Google Play. A compra so vale depois que o NOSSO servidor confere o comprovante
 * direto com o Google (o app nunca libera sozinho). Produtos criados no Play Console com estes IDs.
 */
class BillingManager(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val onStateChanged: () -> Unit
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_MONTHLY = "plano_mensal"
        const val PRODUCT_YEARLY = "plano_anual"
    }

    private val client = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    fun buy(productId: String) {
        scope.launch {
            if (!connect()) {
                onMessage("A loja do Google não respondeu. A assinatura funciona quando o app estiver na Play Store.")
                return@launch
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId).setProductType(BillingClient.ProductType.SUBS).build()
                )
            ).build()
            val details = client.queryProductDetails(params).productDetailsList?.firstOrNull()
            val offer = details?.subscriptionOfferDetails?.firstOrNull()
            if (details == null || offer == null) {
                onMessage("Plano ainda não disponível na Play Store.")
                return@launch
            }
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details).setOfferToken(offer.offerToken).build()
                )
            ).build()
            client.launchBillingFlow(activity, flow)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) return
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            onMessage("A compra não foi concluída (${result.debugMessage.ifBlank { "erro ${result.responseCode}" }})")
            return
        }
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { p ->
            scope.launch {
                val res = AccountGate.verifyPurchase(activity, p.purchaseToken, p.products.firstOrNull().orEmpty())
                val err = res.error
                if (res.ok) {
                    if (!p.isAcknowledged) {
                        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
                    }
                    onMessage("Assinatura ativada. Obrigado!")
                } else {
                    onMessage(err ?: "Não foi possível confirmar a compra")
                }
                launch(Dispatchers.Main) { onStateChanged() }
            }
        }
    }

    fun close() = runCatching { client.endConnection() }
}
