package com.achulkov.evomolior.util

import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.Module

internal expect val platformModule: Module
internal expect fun onApplicationStartPlatformSpecific()
internal expect val isAndroid: Boolean
internal expect val isDebug: Boolean

expect val defaultAsyncDispatcher : CoroutineDispatcher

