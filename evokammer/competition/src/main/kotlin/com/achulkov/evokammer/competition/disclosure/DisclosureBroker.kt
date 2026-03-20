package com.achulkov.evokammer.competition.disclosure

import com.achulkov.evokammer.competition.dispatch.DispatchRelay
import com.achulkov.evokammer.competition.dispatch.DispatchMessage
import com.achulkov.evokammer.core.contract.LeakPacket
import io.github.aakira.napier.Napier

/**
 * Handles the Disclosure protocol — when an agent deliberately "leaks" a document
 * to a neighbouring group through the ring topology.
 *
 * The [DisclosureBroker] validates the [LeakPacket] and forwards it to [DispatchRelay],
 * which routes it through the [com.achulkov.evokammer.competition.leaks.LeakOracle].
 *
 * Agents invoke this broker via the [WriteStagingTool] with `type = DISCLOSURE`.
 *
 * @param relay The [DispatchRelay] that owns the ring topology.
 */
class DisclosureBroker(private val relay: DispatchRelay) {

    /**
     * Submits a disclosure from [sourceGroupId].
     *
     * @param sourceGroupId The group initiating the disclosure.
     * @param packet The [LeakPacket] to relay.
     */
    suspend fun disclose(sourceGroupId: String, packet: LeakPacket) {
        Napier.d("DisclosureBroker: $sourceGroupId disclosing leakId=${packet.leakId} domain=${packet.domain}")
        relay.dispatch(sourceGroupId, DispatchMessage.Leak(packet))
    }

    /**
     * Broadcasts a plain text inter-group message from [sourceGroupId] to its neighbours.
     * Used for meta-communication (e.g. trade offers, provocations).
     */
    suspend fun broadcast(sourceGroupId: String, content: String, metadata: Map<String, String> = emptyMap()) {
        Napier.d("DisclosureBroker: $sourceGroupId broadcasting text message len=${content.length}")
        relay.dispatch(sourceGroupId, DispatchMessage.Text(content, metadata))
    }
}
