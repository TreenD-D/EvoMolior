@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Canonical mechanism order shared by runtime, recovery, and startup reports. */
object MechanismActivationCatalog {
    /** Exact major mechanisms required in every terminal report. */
    val orderedMechanismIds: List<String> = listOf(
        "progressive-disclosure",
        "competitive-node-capture",
        "promoted-tool-later-node-reuse",
        "useful-exit-accounting",
        "typed-control-intervention",
        "summary-validation",
        "publication-barrier",
    )
}

/**
 * Immutable five-state mechanism activation report for one terminal run outcome.
 *
 * @param schemaVersion Stable report schema.
 * @param runId Exact run identity.
 * @param outcomeClass Exact terminal outcome class.
 * @param mechanisms Every catalog mechanism exactly once in canonical order.
 * @param generatedAt Frozen terminal publication timestamp.
 * @param reportHash Deterministic self hash excluding this field.
 */
@Serializable
data class MechanismActivationReportV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val outcomeClass: RunOutcomeClass,
    val mechanisms: List<MechanismActivation>,
    val generatedAt: Instant,
    val reportHash: String = "",
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Mechanism activation report identity is invalid"
        }
        require(
            mechanisms.map(MechanismActivation::mechanismId) ==
                MechanismActivationCatalog.orderedMechanismIds
        ) { "Mechanism activation report must contain the exact canonical catalog" }
        require(reportHash.isEmpty() || reportHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "Mechanism activation report hash is invalid"
        }
    }

    /** Returns this report with its deterministic self hash attached. */
    fun withComputedHash(): MechanismActivationReportV1 =
        copy(reportHash = mechanismActivationReportHash(this))

    /** Returns whether this report carries its exact deterministic self hash. */
    fun hasValidHash(): Boolean =
        DeliverableHashing.constantTimeEquals(reportHash, mechanismActivationReportHash(this))

    companion object {
        const val SCHEMA_VERSION: String = "mechanism-activation-report-v1"
        const val ARCHIVE_PATH: String = "mechanism-activation-report-v1.json"
    }
}

/**
 * Freezes a runtime observability snapshot into one terminal activation report.
 *
 * The publication barrier is replaced with the exact terminal archive event because this report
 * is embedded in those same in-memory archive bytes. It is anticipatory only inside that atomic
 * co-commit: if the archive write fails, neither the report nor its terminal event becomes durable.
 */
internal fun Increment9ObservabilitySnapshot.toTerminalMechanismReport(
    outcomeClass: RunOutcomeClass,
    generatedAt: Instant,
    terminalPublicationAuthorityId: String,
): MechanismActivationReportV1 {
    require(terminalPublicationAuthorityId.isNotBlank()) {
        "Terminal mechanism report requires archive publication authority"
    }
    val terminalMechanisms = mechanisms.map { mechanism ->
        if (mechanism.mechanismId != "publication-barrier") {
            mechanism
        } else {
            MechanismActivation(
                mechanismId = mechanism.mechanismId,
                state = MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE,
                authorityIds = (mechanism.authorityIds + terminalPublicationAuthorityId)
                    .distinct()
                    .sorted(),
            )
        }
    }
    return MechanismActivationReportV1(
        runId = runId,
        outcomeClass = outcomeClass,
        mechanisms = terminalMechanisms,
        generatedAt = generatedAt,
    ).withComputedHash()
}

/** Builds the canonical pre-runtime report for a startup diagnostic terminal outcome. */
internal fun startupMechanismActivationReport(
    runId: String,
    generatedAt: Instant,
    rejectionAuthorityId: String,
    terminalPublicationAuthorityId: String,
): MechanismActivationReportV1 {
    require(rejectionAuthorityId.isNotBlank() && terminalPublicationAuthorityId.isNotBlank()) {
        "Startup mechanism report requires rejection and archive authority"
    }
    val mechanisms = MechanismActivationCatalog.orderedMechanismIds.map { mechanismId ->
        if (mechanismId == "publication-barrier") {
            MechanismActivation(
                mechanismId = mechanismId,
                state = MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE,
                authorityIds = listOf(
                    "startup-publication-policy/$runId",
                    terminalPublicationAuthorityId,
                ).sorted(),
            )
        } else {
            MechanismActivation(
                mechanismId = mechanismId,
                state = MechanismActivationState.NOT_CONFIGURED,
                authorityIds = listOf(rejectionAuthorityId),
            )
        }
    }
    return MechanismActivationReportV1(
        runId = runId,
        outcomeClass = RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
        mechanisms = mechanisms,
        generatedAt = generatedAt,
    ).withComputedHash()
}

private val mechanismActivationReportJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = false
}

private fun mechanismActivationReportHash(report: MechanismActivationReportV1): String =
    DeliverableHashing.hashText(
        mechanismActivationReportJson.encodeToString(report.copy(reportHash = ""))
    )
