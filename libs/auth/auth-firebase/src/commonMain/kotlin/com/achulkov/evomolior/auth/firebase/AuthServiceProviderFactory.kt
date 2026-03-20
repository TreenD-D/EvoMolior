package com.achulkov.evomolior.auth.firebase

import com.achulkov.evomolior.auth.api.AuthServiceProviderFactory

val AuthServiceProviderFactory.Companion.Firebase: AuthServiceProviderFactory
    get() = authServiceProviderFactory

internal expect val authServiceProviderFactory: AuthServiceProviderFactory