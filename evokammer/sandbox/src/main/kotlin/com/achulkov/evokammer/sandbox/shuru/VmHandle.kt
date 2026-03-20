package com.achulkov.evokammer.sandbox.shuru

/**
 * Handle to a live Shuru microVM instance.
 *
 * @param shuruVmId The identifier assigned by Shuru CLI to this VM instance.
 * @param agentId The EvoKammer agent running in this VM.
 * @param groupId The group this agent belongs to.
 * @param runId The run this agent belongs to.
 * @param ipcPort The port used for IPC between this VM and the LifecycleManager.
 * @param pid The host process ID of the Shuru VM process (for direct signaling fallback).
 */
data class VmHandle(
    val shuruVmId: String,
    val agentId: String,
    val groupId: String,
    val runId: String,
    val ipcPort: Int,
    val pid: Long
)
