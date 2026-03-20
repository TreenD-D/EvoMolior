package com.achulkov.evomolior.auth.firebase

import com.achulkov.evomolior.auth.api.AuthServiceProviderFactory

internal actual val authServiceProviderFactory: AuthServiceProviderFactory
    get() = AuthServiceProviderFactory { FirebaseAuthServiceProvider() }