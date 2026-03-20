package com.achulkov.evomolior.auth.api

fun interface AuthServiceProviderFactory {
    companion object {}

    fun create(): AuthServiceProvider
}