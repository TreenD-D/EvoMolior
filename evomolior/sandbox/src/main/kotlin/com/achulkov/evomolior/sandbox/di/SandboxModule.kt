package com.achulkov.evomolior.sandbox.di

import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalEnforcer
import com.achulkov.evomolior.sandbox.network.AllowlistProxy
import com.achulkov.evomolior.sandbox.network.ContentSanitiser
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import org.koin.dsl.module

/**
 * Koin DI module for :engine:sandbox.
 *
 * Provides:
 * - [ShuruVmManager] — manages Shuru microVM lifecycle per agent (singleton; wraps shuru CLI)
 * - [ConstitutionalEnforcer] — enforces constitutional laws before each agent action
 * - [AllowlistProxy] — role-aware web egress policy gate
 * - [ContentSanitiser] — sanitises web-fetched content before it enters the KB
 * - [ToolSubSandbox] — runs tool executions in nested sub-processes inside the agent VM
 */
fun sandboxBindingsModule() = module {
    single { ShuruVmManager() }
    single { ConstitutionalEnforcer() }
    single { AllowlistProxy() }
    single { ContentSanitiser() }
    single { ToolSubSandbox() }
}

/**
 * Backward-compatible exported sandbox module for existing Koin wiring.
 */
val evomoliorSandboxModule = sandboxBindingsModule()
