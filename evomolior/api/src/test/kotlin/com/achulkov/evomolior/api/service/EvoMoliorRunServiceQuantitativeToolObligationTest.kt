package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvoMoliorRunServiceQuantitativeToolObligationTest {
    @Test
    fun `v3 boolean and string tool contract is rejected before runtime activation`() =
        runBlocking {
            val dataRoot = createTempDirectory("svc-quantitative-obligation-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(dataRoot)
            val lifecycleConstructions = AtomicInteger()
            val events = ConcurrentLinkedQueue<EvoMoliorEvent>()
            val runId = "quantitative-obligation-v3-rejection"
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = { _, _, _, _, _ ->
                    lifecycleConstructions.incrementAndGet()
                    error("Rejected Council authority must not construct worker infrastructure")
                },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions ->
                    GraderGroupManager(dimensions)
                },
                substrateFactory = substrateFactory,
                genesisCouncilRunner = {
                        callbackRunId,
                        callbackObjective,
                        _,
                        envelope,
                        inputArtifacts,
                        _,
                        onInvocationStarted ->
                    onInvocationStarted()
                    compiledCouncilFixtureResult(
                        runId = callbackRunId,
                        objective = callbackObjective,
                        envelope = envelope,
                        inputArtifacts = inputArtifacts,
                        outputTransform = { output ->
                            val node = output.taskAnatomist.nodes.single()
                            output.copy(
                                taskAnatomist = output.taskAnatomist.copy(
                                    nodes = listOf(
                                        node.copy(
                                            toolOpportunity = v3LikeOpportunity(
                                                node.proofContract.executableTestIds
                                            )
                                        )
                                    )
                                )
                            )
                        },
                        generalTests = listOf(
                            CompiledCouncilGeneralTestFixture(
                                testId = "delivery-test",
                                nodeKey =
                                    com.achulkov.evomolior.core.council.LocalNodeKey(
                                        "receiver-delivery"
                                    ),
                                nodeKind = com.achulkov.evomolior.core.atlas.NodeKind.DELIVERY,
                                acceptedArtifactTypes = setOf(
                                    com.achulkov.evomolior.core.atlas.ArtifactType.FINAL_DELIVERABLE
                                ),
                                expectedText = "receiver-delivery-accepted"
                            )
                        )
                    )
                },
                metaPlannerRunner = MetaPlannerRunner()
            )
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                service.events.collect(events::add)
            }

            val failure = assertFailsWith<IllegalStateException> {
                service.startRun(config(runId))
            }
            collector.cancelAndJoin()

            assertTrue(
                failure.message.orEmpty().contains("quantitativeToolObligation"),
                failure.message.orEmpty()
            )
            assertTrue(
                failure.message.orEmpty().contains("One sealed tool opportunity"),
                failure.message.orEmpty()
            )
            assertFalse(events.any { it is EvoMoliorEvent.RunStarted && it.runId == runId })
            assertTrue(lifecycleConstructions.get() == 0)

            val rejection = assertNotNull(
                StartupRejectionFileStore { rejectedRunId ->
                    PersistedRunStateStore.resolveStateFile(dataRoot, rejectedRunId).parentFile
                }.read(runId)
            )
            assertFalse(rejection.activationFlags.runStartedEmitted)
            assertFalse(rejection.activationFlags.workerInfrastructureActivated)
            assertFalse(rejection.activationFlags.graderInfrastructureActivated)
        }

    private fun config(runId: String) = RunConfig(
        runId = runId,
        objective =
            "Seal a 72-hour sensible cooling-load, electrical-power, and energy calculator.",
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = 1,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "quality",
                name = "Quality",
                description = "Receiver-facing quality",
                weight = 1.0
            )
        ),
        openRouterApiKey = "test-key",
        quantitativeToolObligation = obligation()
    )

    private fun obligation() = QuantitativeToolObligation(
        horizonTimeBasis = TimeBasis.HOUR,
        horizonIntervalCount = 72,
        allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
        requiredFields = listOf(
            fieldObligation(
                roleId = "hourly_outdoor_temperature",
                fieldName = "hourly_outdoor_temperature_c",
                location = QuantitativeToolFieldLocation.INPUT,
                quantitySpec = QuantitySpec(
                    dimension = PhysicalDimension(mapOf("temperature" to 1)),
                    unitId = "C",
                    scaleToSi = 1.0,
                    aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                    timeBasis = TimeBasis.HOUR
                )
            ),
            fieldObligation(
                roleId = "hourly_sensible_cooling_load",
                fieldName = "hourly_sensible_cooling_load_w",
                location = QuantitativeToolFieldLocation.OUTPUT,
                quantitySpec = QuantitySpec(
                    dimension = PhysicalDimension(mapOf("power" to 1)),
                    unitId = "W",
                    scaleToSi = 1.0,
                    aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                    timeBasis = TimeBasis.HOUR
                )
            ),
            fieldObligation(
                roleId = "hourly_electrical_power",
                fieldName = "hourly_electrical_power_kw",
                location = QuantitativeToolFieldLocation.OUTPUT,
                quantitySpec = QuantitySpec(
                    dimension = PhysicalDimension(mapOf("power" to 1)),
                    unitId = "kW",
                    scaleToSi = 1_000.0,
                    aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                    timeBasis = TimeBasis.HOUR
                )
            ),
            fieldObligation(
                roleId = "hourly_electrical_energy",
                fieldName = "hourly_electrical_energy_kwh",
                location = QuantitativeToolFieldLocation.OUTPUT,
                quantitySpec = QuantitySpec(
                    dimension = PhysicalDimension(mapOf("energy" to 1)),
                    unitId = "kWh",
                    scaleToSi = 3_600_000.0,
                    aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
                    timeBasis = TimeBasis.HOUR
                )
            )
        )
    )

    private fun fieldObligation(
        roleId: String,
        fieldName: String,
        location: QuantitativeToolFieldLocation,
        quantitySpec: QuantitySpec
    ) = QuantitativeToolFieldObligation(
        roleId = roleId,
        fieldName = fieldName,
        location = location,
        shape = QuantitativeToolFieldShape.HORIZON_SERIES,
        quantitySpec = quantitySpec
    )

    private fun v3LikeOpportunity(
        promotionTestIds: Set<String>
    ) = ToolOpportunity(
        opportunityId = "v3-boolean-string-calculator",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern =
            "Calculate 72 hourly cooling-load W, electrical-power kW, and energy kWh values.",
        requiredInputs = listOf(
            TypedField(
                name = "scenario",
                type = TypedFieldType.BOOLEAN,
                description = "Whether to run the 72-hour physics scenario."
            )
        ),
        requiredOutputs = listOf(
            TypedField(
                name = "hourly_sensible_cooling_load_w",
                type = TypedFieldType.STRING,
                description = "Hourly sensible cooling load in W for 72 hours."
            ),
            TypedField(
                name = "hourly_electrical_power_kw",
                type = TypedFieldType.STRING,
                description = "Hourly electrical power in kW for 72 hours."
            ),
            TypedField(
                name = "hourly_electrical_energy_kwh",
                type = TypedFieldType.STRING,
                description = "Hourly electrical energy in kWh for 72 hours."
            )
        ),
        validationStrategy =
            "Validate exact W, kW, kWh, and hour semantics described in prose.",
        expectedReuseNodeIds = setOf(NodeId("later-reuse-node")),
        preferredImplementationMode = ToolImplementationMode.PYTHON,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = promotionTestIds,
        maximumRuntimeMillis = 2_000,
        maximumOutputBytes = 32_768
    )
}
