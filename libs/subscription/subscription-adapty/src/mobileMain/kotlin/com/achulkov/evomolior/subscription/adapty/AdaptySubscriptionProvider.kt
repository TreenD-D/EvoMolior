package com.achulkov.evomolior.subscription.adapty

import com.adapty.kmp.Adapty
import com.adapty.kmp.OnProfileUpdatedListener
import com.adapty.kmp.models.AdaptyConfig
import com.adapty.kmp.models.AdaptyLogLevel
import com.adapty.kmp.models.AdaptyPaywallFetchPolicy
import com.adapty.kmp.models.AdaptyPaywallProduct
import com.adapty.kmp.models.AdaptyPeriodUnit
import com.adapty.kmp.models.AdaptyPrice
import com.adapty.kmp.models.AdaptyProfileParameters
import com.adapty.kmp.models.AdaptyPurchaseResult
import com.adapty.kmp.models.AdaptyResult
import com.adapty.kmp.models.exceptionOrNull
import com.adapty.kmp.models.fold
import com.adapty.kmp.models.getOrNull
import com.achulkov.evomolior.subscription.api.GrantedAccess
import com.achulkov.evomolior.subscription.api.Price
import com.achulkov.evomolior.subscription.api.PurchasePackage
import com.achulkov.evomolior.subscription.api.PurchasePackageId
import com.achulkov.evomolior.subscription.api.SubscriptionProvider
import com.achulkov.evomolior.subscription.api.SubscriptionProviderUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.time.Duration.Companion.minutes

internal class AdaptySubscriptionProvider : SubscriptionProvider {

    private val paywallProductsCache = mutableMapOf<PurchasePackageId, AdaptyPaywallProduct>()

    override val currentSubscriptionProviderUserFlow: Flow<SubscriptionProviderUser?> =
        callbackFlow {
            val listener = OnProfileUpdatedListener { adaptyProfile ->
                trySend(adaptyProfile.asSubscriptionProviderUser())
            }
            Adapty.setOnProfileUpdatedListener(listener)
            awaitClose {
                Adapty.setOnProfileUpdatedListener(null)
            }
        }


    override suspend fun initialize(apiKey: String): Result<Unit> {
        val adaptyResult = Adapty.activate(
            configuration = AdaptyConfig.Builder(apiKey = apiKey)
                .withActivateUI(true)
                .build()
        )
        return adaptyResult.asResult(onSuccess = {})
    }

    override suspend fun setLogEnabled(enabled: Boolean) {
        val adaptyLogLevel = when (enabled) {
            true -> AdaptyLogLevel.VERBOSE
            false -> AdaptyLogLevel.ERROR
        }
        Adapty.setLogLevel(adaptyLogLevel)
    }

    override suspend fun login(userId: String): Result<Unit> {

        return Adapty.identify(userId).asResult {}
    }

    override suspend fun logout(): Result<Unit> {
        return Adapty.logout().asResult {}
    }

    override suspend fun setCustomAttributes(attributes: Map<String, Any?>) {
        val builder = AdaptyProfileParameters.Builder()
        attributes.forEach { (key, value) ->
            when (value) {
                null -> builder.withRemovedCustomAttribute(key)
                is String -> builder.withCustomAttribute(key, value)
                is Double -> builder.withCustomAttribute(key, value)
                else -> builder.withCustomAttribute(key, value.toString())
            }
        }
        Adapty.updateProfile(builder.build())
    }

    override suspend fun getUser(): Result<SubscriptionProviderUser> {
        return Adapty.getProfile().asResult { it.asSubscriptionProviderUser() }
    }

    override suspend fun purchase(purchasePackageId: PurchasePackageId): Result<SubscriptionProviderUser> {
        val packageToBuy = paywallProductsCache[purchasePackageId]

        if (packageToBuy == null) return Result.failure(Exception("Package is not found in Adapty paywall cache. Make sure, you called getPurchasePackages first"))
        val purchaseResult = Adapty.makePurchase(packageToBuy)

        return when (purchaseResult) {
            is AdaptyResult.Error -> Result.failure(purchaseResult.error)
            is AdaptyResult.Success<AdaptyPurchaseResult> -> {
                when (val successfulPurchaseResult = purchaseResult.value) {
                    is AdaptyPurchaseResult.Success -> {

                        Result.success(successfulPurchaseResult.profile.asSubscriptionProviderUser())
                    }

                    AdaptyPurchaseResult.Pending -> {
                        Result.failure(Exception("Purchase is pending"))
                    }

                    AdaptyPurchaseResult.UserCanceled -> {
                        Result.failure(Exception("Purchase was canceled"))
                    }
                }
            }
        }
    }

    override suspend fun restorePurchase(): Result<SubscriptionProviderUser> {
        return Adapty.restorePurchases().fold(
            onSuccess = { adaptyProfile ->
                val isActiveAccessLevelsEmpty =
                    adaptyProfile.accessLevels.filter { it.value.isActive }.isEmpty()
                if (isActiveAccessLevelsEmpty) Result.failure(Exception("Restore failed. No active subscription found"))
                else Result.success(adaptyProfile.asSubscriptionProviderUser())
            },
            onError = { error -> Result.failure(error) }
        )
    }

    override suspend fun getPurchasePackages(placementId: String?): Result<List<PurchasePackage>> {
        val currentPlacementId = placementId ?: adaptyDefaultPlacementId
        val paywallResult = Adapty.getPaywall(
            placementId = currentPlacementId,
            fetchPolicy = AdaptyPaywallFetchPolicy.ReturnCacheDataIfNotExpiredElseLoad(5.minutes.inWholeMilliseconds)
        )

        val paywall = paywallResult.getOrNull()

        if (paywall == null) {
            return Result.failure(
                paywallResult.exceptionOrNull()
                    ?: Exception("Paywall is not found for placementId: $currentPlacementId")
            )
        }

        val paywallProductsResult = Adapty.getPaywallProducts(paywall)
        val paywallProducts = paywallProductsResult.getOrNull()

        if (paywallProducts == null) {
            return Result.failure(
                paywallProductsResult.exceptionOrNull()
                    ?: Exception("Paywall products are not found for paywall: ${paywall.name}")
            )
        }

        paywallProducts.forEach { paywallProduct ->
            paywallProductsCache[PurchasePackageId(paywallProduct.vendorProductId)] = paywallProduct
        }

        val purchasePackages = paywallProducts.map { paywallProduct ->
            paywallProduct.asPurchasePackage()
        }

        return Result.success(purchasePackages)
    }

    override suspend fun getGrantedAccessesWithDetails(placements: List<String>): Result<List<GrantedAccess>> {
        val adaptyProfile = Adapty.getProfile().getOrNull()
        val activeAccessLevels =
            adaptyProfile?.accessLevels?.filter { it.value.isActive }?.values ?: emptyList()

        val availablePaywallPlacements = placements.takeIf { it.isNotEmpty() } ?: listOf(adaptyDefaultPlacementId)

        val grantedAccessWithDetails = activeAccessLevels.map { accessLevel ->

            val allPaywalls = availablePaywallPlacements.mapNotNull {
                Adapty.getPaywall(
                    placementId = it,
                    fetchPolicy = AdaptyPaywallFetchPolicy.ReturnCacheDataIfNotExpiredElseLoad(5.minutes.inWholeMilliseconds)
                ).getOrNull()
            }
            val allPaywallProducts = allPaywalls.flatMap {
                Adapty.getPaywallProducts(it).getOrNull() ?: emptyList()
            }

            val paywallProduct = allPaywallProducts.first {
                val basePlanIdOfProduct = it.subscription?.basePlanId
                it.vendorProductId == accessLevel.vendorProductId ||
                        (basePlanIdOfProduct != null && accessLevel.vendorProductId.contains(
                            basePlanIdOfProduct
                        ))
            }

            GrantedAccess(
                id = accessLevel.id,
                productIdentifier = accessLevel.vendorProductId,
                expirationDateMillis = accessLevel.expiresAt.asTimeInMilliseconds(),
                willRenew = accessLevel.willRenew,
                isLifetime = accessLevel.isLifetime,
                details = GrantedAccess.Details(
                    title = paywallProduct.asPurchasePackage().title.substringBefore("("),
                    price = paywallProduct.price.asGrantedAccessPrice(),
                    durationType = when (paywallProduct.subscription?.period?.unit) {
                        AdaptyPeriodUnit.YEAR -> GrantedAccess.DurationType.YEARLY
                        AdaptyPeriodUnit.MONTH -> GrantedAccess.DurationType.MONTHLY
                        AdaptyPeriodUnit.WEEK -> GrantedAccess.DurationType.WEEKLY
                        else -> {
                            if (accessLevel.isLifetime) GrantedAccess.DurationType.LIFETIME
                            else GrantedAccess.DurationType.UNKNOWN
                        }
                    }
                )
            )
        }

        return Result.success(grantedAccessWithDetails)

    }

    private fun AdaptyPaywallProduct.asPurchasePackage(): PurchasePackage {
        return PurchasePackage(
            id = PurchasePackageId(this.vendorProductId),
            title = this.localizedTitle,
            description = localizedDescription,
            price = this.price.asGrantedAccessPrice()
        )
    }

    private fun AdaptyPrice.asGrantedAccessPrice(): Price {
        return Price(
            amount = amount,
            currencyCodeOrSymbol = currencyCode ?: currencySymbol,
            localizedString = localizedString
        )
    }

    private inline fun <T, R> AdaptyResult<T>.asResult(onSuccess: (T) -> R): Result<R> = this.fold(
        onSuccess = { resultData -> Result.success(onSuccess(resultData)) },
        onError = { error -> Result.failure(error) }
    )

}