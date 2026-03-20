package com.achulkov.evomolior.data.repository

import com.achulkov.evomolior.data.BackgroundExecutor
import com.achulkov.evomolior.data.source.preferences.UserPreferences
import com.achulkov.evomolior.domain.model.Subscription
import com.achulkov.evomolior.subscription.api.GrantedAccess
import com.achulkov.evomolior.subscription.api.PurchasePackage
import com.achulkov.evomolior.subscription.api.PurchasePackageId
import com.achulkov.evomolior.subscription.api.SubscriptionProvider
import com.achulkov.evomolior.subscription.api.SubscriptionProviderUser
import com.achulkov.evomolior.subscription.api.hasAccess
import com.achulkov.evomolior.util.ApplicationScope
import com.achulkov.evomolior.util.Constants
import com.achulkov.evomolior.util.Constants.PAYWALL_PREMIUM_ACCESS
import com.achulkov.evomolior.util.analytics.Analytics
import com.achulkov.evomolior.util.logging.AppLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRepository(
    private val applicationScope: ApplicationScope,
    private val subscriptionProvider: SubscriptionProvider,
    private val userPreferences: UserPreferences,
    private val analytics: Analytics,
    private val backgroundExecutor: BackgroundExecutor = BackgroundExecutor.IO
) {

    val currentSubscriptionFlow: Flow<Subscription?> =
        subscriptionProvider.currentSubscriptionProviderUserFlow
            .flatMapLatest { subscriptionProviderUser ->
                flow {
                    if (subscriptionProviderUser == null) {
                        emit(null)
                    } else {
                        //Initial fast emit value without extra details
                        emit(subscriptionProviderUser.asPremiumSubscription(retrieveExtraDetails = false))
                        emit(subscriptionProviderUser.asPremiumSubscription(retrieveExtraDetails = true))
                    }
                }

            }
            .onStart {
                emit(
                    subscriptionProvider.getUser().getOrNull()
                        ?.asPremiumSubscription(retrieveExtraDetails = false)
                )
            }
            .catch { error ->
                AppLogger.e("Error occurred while getting current subscription", error)
            }
            .flowOn(backgroundExecutor.scope)
            .shareIn(applicationScope, SharingStarted.Lazily, 1)


    var currentPlacementId: String? = null

    suspend fun login(userId: String) {
        subscriptionProvider.login(userId).onFailure {
            AppLogger.e("Error occurred while logging in for subscription provider", it)
        }
    }

    suspend fun logOut() {
        subscriptionProvider.logout().onFailure {
            AppLogger.e("Error occurred while logging out for subscription provider", it)
        }
    }

    suspend fun hasPremiumAccess(): Boolean = hasAccess(PAYWALL_PREMIUM_ACCESS)

    suspend fun hasAccess(key: String): Boolean = subscriptionProvider.hasAccess(key = key)


    fun onPaywallDismissed() = applicationScope.launch {
        currentPlacementId = null
        val nbTimesPaywallIsDismissed =
            userPreferences.getInt(UserPreferences.KEY_NB_PAYWALL_DISMISSED, 0) ?: 0
        val newNbTimesPaywallIsDismissed = nbTimesPaywallIsDismissed + 1
        userPreferences.putInt(
            UserPreferences.KEY_NB_PAYWALL_DISMISSED,
            newNbTimesPaywallIsDismissed
        )
        analytics.logEvent(Analytics.EVENT_PAYWALL_DISMISSED)
        subscriptionProvider.setCustomAttributes(
            mapOf(Analytics.PARAM_NB_PAYWALL_DISMISSED to newNbTimesPaywallIsDismissed)
        )
    }

    suspend fun getPackageList(placementId: String? = null): Result<List<PurchasePackage>> =
        subscriptionProvider.getPurchasePackages(placementId = placementId)
            .map { purchasePackages ->
                purchasePackages.sortedBy { it.price.amount }
            }


    suspend fun purchase(purchasePackageId: PurchasePackageId): Result<SubscriptionProviderUser> =
        subscriptionProvider.purchase(purchasePackageId)

    suspend fun restorePurchase(): Result<SubscriptionProviderUser> =
        subscriptionProvider.restorePurchase()

    suspend fun SubscriptionProviderUser.asPremiumSubscription(retrieveExtraDetails: Boolean = true): Subscription? {
        if (retrieveExtraDetails) {
            val availablePlacements = listOfNotNull(
                Constants.PAYWALL_PLACEMENT_DEFAULT,
                Constants.PAYWALL_PLACEMENT_ONBOARDING,
                //Add more placements if needed
            )

            val grantedAccessWithDetails = subscriptionProvider
                .getGrantedAccessesWithDetails(placements = availablePlacements)
                .getOrNull()?.find { it.id in PREMIUM_ACCESS_ID_LIST }

            return grantedAccessWithDetails?.let {
                Subscription(
                    accessId = grantedAccessWithDetails.id,
                    expirationDateInMillis = grantedAccessWithDetails.expirationDateMillis,
                    willRenew = grantedAccessWithDetails.willRenew,
                    name = grantedAccessWithDetails.details?.title,
                    formattedPrice = grantedAccessWithDetails.details?.price?.localizedString,
                    durationType = when (grantedAccessWithDetails.details?.durationType) {
                        GrantedAccess.DurationType.MONTHLY -> Subscription.DurationType.MONTHLY
                        GrantedAccess.DurationType.WEEKLY -> Subscription.DurationType.WEEKLY
                        GrantedAccess.DurationType.YEARLY -> Subscription.DurationType.YEARLY
                        GrantedAccess.DurationType.LIFETIME -> Subscription.DurationType.LIFETIME
                        GrantedAccess.DurationType.UNKNOWN -> Subscription.DurationType.UNKNOWN
                        null -> null
                    }
                )
            }
        } else {
            val grantedAccess = grantedAccesses.values.firstOrNull()
            return grantedAccess?.let {
                Subscription(
                    accessId = grantedAccess.id,
                    expirationDateInMillis = grantedAccess.expirationDateMillis,
                    willRenew = grantedAccess.willRenew
                )
            }
        }
    }

    companion object {
        val PREMIUM_ACCESS_ID_LIST = listOf(PAYWALL_PREMIUM_ACCESS)
    }

}