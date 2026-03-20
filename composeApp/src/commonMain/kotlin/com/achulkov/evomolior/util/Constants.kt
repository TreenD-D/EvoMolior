package com.achulkov.evomolior.util

import com.achulkov.evomolior.auth.api.AuthServiceProviderFactory
import com.achulkov.evomolior.auth.firebase.Firebase
import com.achulkov.evomolior.subscription.api.SubscriptionProviderFactory
import com.achulkov.evomolior.subscription.revenuecat.RevenueCat

object Constants {
    const val URL_PRIVACY_POLICY = ""
    const val URL_TERMS_CONDITIONS = ""
    const val CONTACT_EMAIL = "team@measify.com"
    const val APPSTORE_APP_ID = ""

    // Enables Apple and Google sign-in. If false, only anonymous login is supported.
    const val AUTH_SOCIAL_LOGIN_ENABLED = true

    /**
     * Make sure to set the subscription provider in `gradle.properties` first:
     *
     *   SUBSCRIPTION_PROVIDER=REVENUECAT
     *   or
     *   SUBSCRIPTION_PROVIDER=ADAPTY
     *
     *   Also in local.properties add the following:
     *
     *   SUBSCRIPTION_PROVIDER_ANDROID_API_KEY=YOUR_API_KEY
     *   SUBSCRIPTION_PROVIDER_IOS_API_KEY=YOUR_API_KEY
     *
     * Based on this value, either `SubscriptionProviderFactory.RevenueCat`
     * or `SubscriptionProviderFactory.Adapty` will be available.
     */
    val subscriptionProviderFactory get() = SubscriptionProviderFactory.RevenueCat

    /**
     * Identifier used to unlock Premium access.
     *
     * - RevenueCat: Entitlement ID
     * - Adapty: Access Level ID
     */
    const val PAYWALL_PREMIUM_ACCESS = "Premium"

    /**
     * Credit pack paywall placement identifier.
     *
     * When showing the credit pack paywall:
     * - In RevenueCat, target this value as the placement (Target by Placement ID).
     * - In Adapty, configure the placement with this exact identifier.
     */
    const val PAYWALL_PLACEMENT_CREDITS_PACK = "credits_pack"


    /**
     * Prefix used to identify credit pack products.
     *
     * Credit pack product IDs must start with this prefix and
     * include a numeric credit, amount and suffix, for example:
     *
     * - credit_pack_50
     * - credit_pack_100_v2
     *
     * The numeric part is extracted after a successful purchase and used
     * to determine how many credits should be added to the user’s account.
     */
    const val CREDIT_PACK_PRODUCT_ID_PREFIX = "credit_pack_"


    /**
     * Default paywall placement.
     *
     * - RevenueCat: Placement is optional.
     * - Adapty: Placement is required.
     *
     * If set to `null`, the provider will fall back to the "default" placement.
     */
    val PAYWALL_PLACEMENT_DEFAULT: String? = null

    /**
     * Optional placement for showing a different paywall during onboarding
     * (for example, a higher-priced or special offer paywall).
     *
     * Set a value like "onboarding" and configure the corresponding placement
     * in RevenueCat or Adapty.
     *
     * If `null`, the default paywall will be used.
     */
    val PAYWALL_PLACEMENT_ONBOARDING: String? = null


    /**
     * CLOUD_FUNCTIONS_URL should be something like: "https://REGION-PROJECT_ID.cloudfunctions.net"
     * Regions:
     * US(Default): us-central1
     * EU: europe-west1
     *
     * This is used AI proxy functions such as OpenAi, Replicate
     */
    const val CLOUD_FUNCTIONS_URL = ""

    const val LOCAL_DB_STORAGE_NAME = "local_storage.db"

    val subscriptionUrl =
        if (isAndroid) "https://play.google.com/store/account/subscriptions"
        else "https://apps.apple.com/account/subscriptions"

    val authServiceProviderFactory get() = AuthServiceProviderFactory.Firebase

}

