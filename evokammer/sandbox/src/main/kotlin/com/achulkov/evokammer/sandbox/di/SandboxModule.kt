package com.achulkov.evokammer.sandbox.di

import com.achulkov.evokammer.sandbox.enforcer.ConstitutionalEnforcer
import com.achulkov.evokammer.sandbox.network.AllowlistProxy
import com.achulkov.evokammer.sandbox.network.ContentSanitiser
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import org.koin.dsl.module

/**
 * Koin DI module for :evokammer:sandbox.
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
val evokammerSandboxModule = sandboxBindingsModule()
