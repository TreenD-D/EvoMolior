| Priority | Remaining issue                                                                        | Current status                | Required result                                                                                                    |
| -------: | -------------------------------------------------------------------------------------- | ----------------------------- | ------------------------------------------------------------------------------------------------------------------ |
|   **P0** | `GENERAL` sealed-test producer/evaluator contract split                                | **Open blocker**              | Every Council-authored test is typed, compilable, hash-bound, and executable before the field becomes active       |
|   **P0** | Startup persists a “sealed” field before full executability is established             | **Partially robust**          | Two-phase compilation and activation; rejected candidates never become active run authority                        |
|   **P0** | One-round representative profile conflicts with later-agent tool reuse                 | **Design mismatch**           | Separate tool-free runtime smoke from full multi-round Appendix B acceptance                                       |
|   **P0** | Canonical status documentation contains stale historical launch language               | **Inconsistent**              | One machine-readable current acceptance state; roadmap summary and tables generated or validated against it        |
|   **P1** | No successful short provider-backed archive after v71/v72 repairs                      | **Missing evidence**          | Non-zero executed tests, live workers/graders, receiver-usable output, quiescent verified archive                  |
|   **P1** | No conjunctively accepted multi-round Appendix B archive                               | **Missing evidence**          | One complete run proving all worker, grader, disclosure, tool-reuse, fitness, publication, and manifest invariants |
|   **P1** | Section 18.2 denominators remain unavailable                                           | **Incomplete observability**  | Typed metric export with explicit `AVAILABLE`, `UNAVAILABLE`, and `NOT_APPLICABLE` states                          |
|   **P1** | Acceptance execution still uses a mutable Gradle/source-classpath path                 | **Operational risk**          | Immutable packaged distribution, exclusive run lock, disk/provider/process admission checks                        |
|   **P2** | Same-family worker/grader dependence                                                   | **Known caveat**              | Preserve the low-independence label; later qualify a second family or external evaluator                           |
|   **P2** | Global action-credit ceiling, automatic cross-task learning, compatibility projections | **Intentional/deferred gaps** | Address only after the runtime acceptance boundary is closed                                                       |

# Executive diagnosis

The **architecture itself is no longer the main problem**. The latest roadmap marks competitive progressive-disclosure increments 0 through 10 as implemented: authoritative Atlas state, compiled Council output, redacted mortal workers, exact evidence and capture, competitive Grader Atlas, progressive disclosure, Outlier amendments, tool/simulation authority, causal fitness and publication, and the benchmark harness all have broad deterministic coverage. No accepted feature is currently classified as disconnected.

The remaining problem is an **end-to-end contract and liveness problem**:

> EvoMolior is now quite good at detecting invalid authority and failing closed, but it still does not reliably guarantee that Council-generated authority is consumable by the runtime that follows it.

v72 is the cleanest example. Council repair converged, a one-stage field was stored, and all seven test commitments were internally correct—but every `GENERAL` definition was canonical JSON `null`, so the startup evaluator compiled zero of seven tests. The preflight correctly stopped the run before workers, but the producer and consumer were speaking different languages.  The runtime failure was explicit: `0/7 tests parsed`, with every test lacking an executable assertion.

This means the next work should **not** add agents, roles, topologies, additional fallback prose, or more Council prompt instructions. It should close the executable-test ABI and then prove that closure through a cheap representative run before another expensive six-round attempt.

---

# 1. What is genuinely closed and should not be redesigned again

The long sequence of v38–v72 attempts has already repaired many substantive boundaries. These should remain regression baselines rather than being reopened:

* exact grader cardinality and no-zero-fill behavior;
* grader methodology handles instead of raw content-addressed ID transcription;
* atomic node-specific requirements and complete packet-bound grader context;
* worker and Grader Atlas round snapshots and atomic co-evolution;
* group-fair portfolio allocation and historical passing coverage;
* prior-generation WIP reactivation;
* tool-authoring versus validation versus later-use authority separation;
* opportunity-scoped active tool versions;
* expected-reuse worker cards and mandatory later execution;
* in-process host tool paths;
* non-cancellable provider-cost settlement;
* synchronous scoring-child seal audit;
* publication quiescence and manifest verification;
* compiled-worker boot authority and suppression of unassigned surplus workers;
* provider-native structured Council output and strict schema transport;
* startup rejection of non-executable test suites.

The current roadmap explicitly retains those increments as implemented even though the realistic Appendix B acceptance remains open.

The correct engineering posture is therefore:

```text
Preserve existing fail-closed authority
        +
repair the last producer → compiler → persistence → evaluator ABI
        +
improve liveness and operational packaging
        +
prove it in increasingly expensive runs
```

---

# 2. P0 blocker: replace arbitrary `GENERAL` JSON with one typed executable assertion contract

## 2.1 Root cause

The current `GENERAL` path permits Synthesis to emit arbitrary tagged JSON. Compilation currently proves things such as:

* the value is valid JSON;
* the test ID and node association are valid;
* the commitment matches the persisted bytes;
* the suite is structurally sealed.

The runtime evaluator, however, recognizes only its existing assertion grammar. Thus, a test can be:

```text
valid JSON
+ correctly hashed
+ correctly persisted
+ correctly associated with a node
+ completely non-executable
```

v72 produced seven instances of exactly that state. The copied-SQLite evidence confirms that `null` was persisted and correctly committed, so this was not corruption or commitment drift.

## 2.2 The wrong fixes

Do **not**:

* interpret `null` as pass;
* interpret `null` as an optional skipped test;
* infer an assertion from the test description;
* ask the evaluator model to reinterpret arbitrary JSON;
* retain arbitrary JSON and add more heuristic parsers;
* add a deterministic fallback test when Council output is unusable;
* wait until `EvoMoliorRunService.startRun()` to discover that definitions cannot compile.

Any of those would recreate the earlier `tests=0/0` problem under a different label.

## 2.3 Recommended contract

Introduce a new suite version—preferably `sealed-tests-v3`—with a closed discriminated `GENERAL` assertion language.

Keep the first version deliberately small. One test should contain exactly one assertion. Multiple conditions become multiple sealed tests instead of a recursive `AND`/`OR` expression tree. That makes the provider schema smaller, the evaluator deterministic, and replay easier.

```kotlin
@Serializable
data class GeneralTestDefinitionV1(
    val target: GeneralTestTargetV1,
    val assertion: GeneralAssertionV1,
)

@Serializable
sealed interface GeneralTestTargetV1 {

    @Serializable
    @SerialName("FINAL_OUTPUT_TEXT")
    data object FinalOutputText : GeneralTestTargetV1

    @Serializable
    @SerialName("OUTPUT_TEXT_FILE")
    data class OutputTextFile(
        val relativePath: String,
    ) : GeneralTestTargetV1

    @Serializable
    @SerialName("OUTPUT_JSON_FILE")
    data class OutputJsonFile(
        val relativePath: String,
    ) : GeneralTestTargetV1
}

@Serializable
sealed interface GeneralAssertionV1 {

    @Serializable
    @SerialName("TEXT_CONTAINS")
    data class TextContains(
        val expected: String,
        val caseSensitive: Boolean,
    ) : GeneralAssertionV1

    @Serializable
    @SerialName("TEXT_MATCHES_RE2")
    data class TextMatchesRe2(
        val pattern: String,
    ) : GeneralAssertionV1

    @Serializable
    @SerialName("JSON_POINTER_EXISTS")
    data class JsonPointerExists(
        val pointer: String,
    ) : GeneralAssertionV1

    @Serializable
    @SerialName("JSON_POINTER_EQUALS_STRING")
    data class JsonPointerEqualsString(
        val pointer: String,
        val expected: String,
    ) : GeneralAssertionV1

    @Serializable
    @SerialName("JSON_POINTER_NUMBER_RANGE")
    data class JsonPointerNumberRange(
        val pointer: String,
        val minimumInclusive: Double,
        val maximumInclusive: Double,
    ) : GeneralAssertionV1
}
```

A separate top-level sealed definition should discriminate `GENERAL` from existing promotion tests:

```kotlin
@Serializable
sealed interface SealedTestDefinitionV3 {

    @Serializable
    @SerialName("GENERAL")
    data class General(
        val definition: GeneralTestDefinitionV1,
    ) : SealedTestDefinitionV3

    @Serializable
    @SerialName("PROMOTION")
    data class Promotion(
        val input: TypedJsonValue,
        val expectedOutput: TypedJsonValue,
    ) : SealedTestDefinitionV3
}
```

### Why this shape

**Advantages**

* `null` is impossible in the `GENERAL` branch.
* Provider output schema, Kotlin decoding, compiler validation, and evaluator input all derive from the same serializer.
* No arbitrary-key maps or unconstrained JSON are required.
* It fits the strict provider-subset lessons from v58–v70.
* RE2/J remains the only regex language.
* Every assertion has a clear deterministic result and bounded execution cost.
* A future assertion type can be added by bumping the assertion-language version.

**Cost**

* Old `sealed-tests-v2` suites cannot silently become v3.
* Read-only archive tooling must retain a v2 decoder.
* Resuming a v2 run should require a pinned v2 evaluator or fail with `UNSUPPORTED_EXECUTABLE_TEST_VERSION`.

That cost is desirable. Reinterpreting already committed arbitrary JSON under new semantics would invalidate historical hashes and replay.

---

# 3. Compile tests once; do not parse raw test JSON again at runtime

Create a pure compiler in `core`, shared by Council validation, startup preflight, and evaluation:

```kotlin
data class CompiledGeneralTestPlan(
    val testId: String,
    val target: ResolvedGeneralTestTarget,
    val operation: CompiledGeneralOperation,
    val definitionHash: String,
    val assertionLanguageVersion: String,
    val evaluatorVersion: String,
    val planHash: String,
)

sealed interface GeneralTestCompileResult {
    data class Success(
        val plan: CompiledGeneralTestPlan,
    ) : GeneralTestCompileResult

    data class Failure(
        val errors: List<GeneralTestCompileError>,
    ) : GeneralTestCompileResult
}
```

The authoritative path should become:

```text
Council Synthesis typed wire object
        ↓
strict Kotlin decode
        ↓
GeneralTestDefinitionV1
        ↓
GeneralTestCompiler
        ↓
CompiledGeneralTestPlan
        ↓
canonical definition hash + plan hash
        ↓
StartingField/test-suite persistence
        ↓
readback verification
        ↓
runtime evaluator executes CompiledGeneralTestPlan
```

The runtime evaluator should never independently rediscover semantics from `testDefinitionJson`.

## Required compile failures

Use typed errors instead of the current generic “no executable assertion”:

```text
GENERAL_TEST_NULL
GENERAL_TEST_UNKNOWN_VERSION
GENERAL_TEST_EMPTY_EXPECTED_TEXT
GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC
GENERAL_TEST_TARGET_TYPE_MISMATCH
GENERAL_TEST_INVALID_JSON_POINTER
GENERAL_TEST_INVALID_RE2
GENERAL_TEST_PATTERN_TOO_LARGE
GENERAL_TEST_RANGE_NON_FINITE
GENERAL_TEST_RANGE_REVERSED
GENERAL_TEST_UNSUPPORTED_ASSERTION
GENERAL_TEST_NOT_EXECUTABLE
```

Those errors should route specifically to `CouncilSynthesis`, because the identity and purpose of the test are already Task-derived authority. Do not regenerate Task Anatomy for an assertion encoding failure.

## Security and boundedness

Apply deterministic limits:

| Element                            |                                    Suggested initial bound |
| ---------------------------------- | ---------------------------------------------------------: |
| Tests per suite                    |                                                         64 |
| Expected text per assertion        |                                                      4 KiB |
| RE2 pattern                        |                                                      4 KiB |
| JSON pointer depth                 |                                                         32 |
| Output file size evaluated as text |                                                      2 MiB |
| Parsed JSON depth                  |                                                         64 |
| Total suite execution time         |                                    Explicit bounded budget |
| File paths                         | Normalized relative paths constrained by `FinalOutputSpec` |

The `evaluatorVersion` and assertion-language version should be included in the suite commitment and final manifest. Otherwise the same definition could change meaning after an evaluator upgrade.

---

# 4. Move test executability before active field persistence

The v72 log says the field was stored and described as sealed before the startup preflight rejected all tests.  That is fail-closed with respect to workers, but the terminology and persistence ordering are misleading.

Introduce a two-phase Council activation state:

```text
COUNCIL_OUTPUT_DECODED
        ↓
FIELD_CANDIDATE_COMPILED
        ↓
ALL_TESTS_COMPILED
        ↓
TOOL_FIXTURES_PREFLIGHTED
        ↓
SHARED_SEAL_COMPUTED
        ↓
CANDIDATE_PERSISTED_AND_READBACK_VERIFIED
        ↓
FIELD_ACTIVATED
        ↓
WORKER/GRADER SUBSTRATES SEEDED
```

A candidate whose tests do not compile should become:

```text
REJECTED_BEFORE_ACTIVATION
```

not an active “sealed StartingField.”

A useful implementation contract would be:

```kotlin
enum class CompiledFieldState {
    CANDIDATE,
    EXECUTABILITY_VERIFIED,
    ACTIVATABLE,
    ACTIVE,
    REJECTED_BEFORE_ACTIVATION,
}
```

Persist rejected candidates in a dedicated diagnostic record, not in the same authority slot read by run recovery.

## Startup rejection artifact

Because pre-round failures have no normal results ZIP, publish a bounded diagnostic artifact:

```json
{
  "schemaVersion": "startup-rejection-v1",
  "runId": "...",
  "phase": "TEST_EXECUTABILITY_PREFLIGHT",
  "candidateFieldHash": "...",
  "testSuiteHash": "...",
  "declaredTestCount": 7,
  "compiledTestCount": 0,
  "errors": [
    {
      "testId": "...",
      "code": "GENERAL_TEST_NULL",
      "definitionHash": "..."
    }
  ],
  "sourceManifestHash": "...",
  "providerExecutionRefs": ["..."],
  "createdAt": "..."
}
```

This is not a success manifest and must not be mistaken for an accepted archive. It would make future startup diagnosis much faster than extracting internal SQLite state.

---

# 5. Add the exact v71/v72 regression—not a simplified approximation

The next static cycle must contain literal regression evidence for both failure modes.

## v71 regression

Construct a `sealed-tests-v2` fixture containing the nested typed envelope that previously produced runtime `tests=0/0`.

Expected result:

```text
Fresh Appendix-B/representative run:
  rejected as unsupported legacy executable-test version

Archive inspection:
  readable as historical bytes
  no reinterpretation
```

## v72 regression

Use the copied seven-test fixture with seven canonical `null` definitions.

Expected result:

```text
Council compiler:
  0 definitions accepted
  exact GENERAL_TEST_NULL errors
  no active StartingField persisted
  no worker/grader substrate seeded
  no RunStarted event
```

## Positive sibling fixture

The same Task-derived test identities should be represented with seven valid `GeneralTestDefinitionV1` values.

Expected result:

```text
declared = 7
compiled = 7
persisted = 7
read back = 7
preflight executable = 7
executed in representative run = 7
```

## Additional contract tests

1. Every value accepted by the generated provider JSON Schema must decode and compile locally.
2. Every locally encoded definition must validate against the emitted provider schema.
3. The actual contextual Synthesis schema must be validated by an independent Draft 2020-12 validator.
4. Unknown discriminator, missing field, extra field, `null`, empty assertion, and invalid RE2 must fail.
5. Definition and compiled-plan hashes must survive persistence/readback.
6. Worker and grader projections must reference one common test authority rather than independently serialized variants.
7. A tampered compiled plan, evaluator version, target path, or definition must fail readback.
8. Resume must verify the same assertion-language/evaluator versions before activation.

---

# 6. Fix Council closure before Synthesis to reduce expensive repair cycles

v72 eventually converged, but the first compiled bundle reported multiple cross-role defects:

* runtime prompts targeting Council roles;
* missing runtime model assignments;
* missing KG mappings for nodes;
* invalid seed KG facts;
* a tool-reuse path whose earliest use was outside the one-round envelope.

Those are not fundamentally Synthesis problems. They are specialist-bundle closure problems.

## Add `CouncilSpecialistClosureValidator`

Run it after the seven specialist outputs are available but before the first Synthesis call:

```kotlin
data class CouncilSpecialistBundle(
    val taskAnatomist: TaskAnatomistOutput,
    val incentiveDesigner: IncentiveDesignerOutput,
    val ecologist: EcologistOutput,
    val promptArchitect: PromptArchitectOutput,
    val kgArchitect: KgArchitectOutput,
    val modelEcologist: ModelEcologistOutput,
    val seedCurator: SeedCuratorOutput,
)

data class CouncilClosureReport(
    val errorsByResponsibleRole: Map<CouncilRole, List<CouncilCompileError>>,
    val frozenValidRoleHashes: Map<CouncilRole, String>,
)
```

Validate at least:

```text
Task nodes
  ↔ KG mappings
  ↔ seed fact relations
  ↔ role prompts
  ↔ model assignments
  ↔ group count and runtime roles
  ↔ disclosure stage count
  ↔ earliest executable rounds
  ↔ tool validation and expected-reuse topology
```

Only after this report is empty should Synthesis run.

This will reduce:

* long Synthesis prompts containing inconsistent dependencies;
* full Synthesis retries for specialist-owned errors;
* repeated model copying of unchanged data;
* Council timeout exposure;
* provider cost.

---

# 7. Separate the one-round runtime smoke from full Appendix B acceptance

v72 used a one-round envelope while the Council attempted a tool with downstream reuse. The compiler correctly rejected that path because reuse would first become possible in round 1, outside rounds `0..0`.

A one-round run cannot prove:

```text
tool authored
→ promoted
→ another mortal agent receives later node
→ later agent executes tool
→ result affects evidence/grade/final output
```

That requires at least two worker rounds and often more because disclosure is grade-gated.

## Introduce an explicit acceptance profile

```kotlin
enum class AcceptanceProfile {
    NORMAL,
    RUNTIME_USABILITY_SMOKE,
    APPENDIX_B_FULL,
}
```

### `RUNTIME_USABILITY_SMOKE`

```text
rounds = 1
stages = 1
tool opportunity policy = FORBIDDEN
expected reuse = FORBIDDEN
GENERAL tests = REQUIRED
at least two worker groups = REQUIRED
at least two grader groups = REQUIRED
explicit FinalOutputSpec = REQUIRED
receiver-usable output = REQUIRED
archive + manifest = REQUIRED
```

### `APPENDIX_B_FULL`

```text
fixed multi-round horizon
at least two disclosure stages
tool opportunity = REQUIRED
promotion tests = REQUIRED
validation node distinct from expected-reuse node
independent later AGENT_USE schedulable inside horizon
tool result capable of influencing a later evidence node
all Appendix B lineage requirements enabled
```

The profile should alter the Council’s provider schema, not merely its prose:

* Under one-round smoke, `toolOpportunity` has only the `null` branch.
* Under full Appendix B, at least one structurally feasible opportunity is required.
* Stage count and earliest-round rules are dynamic schema/compiler constraints.

This prevents spending model calls repeatedly repairing an intentionally impossible one-round tool topology.

---

# 8. Required short representative run before another six-round authorization

The project documents explicitly require a short provider-backed run that proves non-zero test execution and produces one receiver-usable archived output before another full six-round attempt. v72 did not meet that condition and no provider run is currently authorized.

The next representative run should not be considered successful merely because the CLI exits zero.

## Exact pass gate

| Gate         | Required evidence                                                                               |
| ------------ | ----------------------------------------------------------------------------------------------- |
| Council      | Typed field compiled with no unresolved degradation                                             |
| Tests        | `declared == compiled == preflighted == executed > 0`                                           |
| Workers      | Both groups spawn at least one ordinary node-authorized worker                                  |
| Mortality    | Every ordinary exit has accepted contribution, structured dead end, or explicit no-contribution |
| Graders      | Every grader freezes current criteria before reveal                                             |
| Bundles      | Exact expected cardinality, no missing/duplicate/unknown bundles                                |
| Node grading | Every revealed mandatory node has replayable grade or explicit `NO_EVIDENCE`                    |
| Output       | Explicit receiver-facing file exists and satisfies `FinalOutputSpec`                            |
| Test result  | Tests run against the actual published candidate, not an intermediate draft                     |
| Cost         | Zero active reservations at publication                                                         |
| Quiescence   | No live non-daemon worker, grader, Critic, or final writer                                      |
| Archive      | Manifest verifies all output, DB/WAL, field, test, event, cost, and final-reference entries     |
| Status       | `COMPLETED`, not recovered or inferred from partial artifacts                                   |

This smoke is intentionally not evidence of tool compounding or society superiority. Its purpose is to prove the runtime ABI and publication path cheaply.

---

# 9. Export Section 18.2 metrics before the expensive run

The remaining goal checklist includes collecting and reconciling Section 18.2 metrics, preserving unavailable denominators, completing Appendix B, producing a quantitative comparison report, and then reconciling the roadmap.

Do not wait until after a successful six-round run to discover that denominators were not exported.

## Typed metric record

```kotlin
@Serializable
data class RunMetricObservation(
    val metricId: String,
    val status: MetricAvailability,
    val numerator: Double? = null,
    val denominator: Double? = null,
    val value: Double? = null,
    val unit: String?,
    val evidenceRefs: List<String>,
    val unavailableReason: String? = null,
)

enum class MetricAvailability {
    AVAILABLE,
    UNAVAILABLE,
    NOT_APPLICABLE,
}
```

Never encode:

```text
unknown denominator → 0
```

## Metrics that need first-class export

| Metric                      | Authoritative source                                          |
| --------------------------- | ------------------------------------------------------------- |
| Useful-exit rate            | ordinary lifecycle exit records                               |
| Atomic node completion      | evidence packets plus node-grade/capture authority            |
| Mandatory-node coverage     | disclosure decisions and historical pass resolver             |
| Group-frontier overlap      | exact portfolio membership events                             |
| Unplanned redundancy cost   | contribution/cost lineage on non-required duplicated nodes    |
| Outlier acceptance          | proposal and shadow-verdict tables                            |
| Outlier downstream adoption | later contribution references and amendment lineage           |
| Tool-native agent rate      | valid tool actions per eligible ordinary agent                |
| Tool reuse factor           | independent `AGENT_USE` executions per promoted version       |
| Simulation influence        | simulation records referenced by later evidence/final lineage |
| KG syntactic acceptance     | accepted typed fact attempts divided by all fact attempts     |
| Grader-bundle validity      | valid exact bundles divided by expected bundles               |
| Requirement novelty         | new requirement hashes versus prior grader history            |
| Evaluation independence     | provider/family profile                                       |
| Capture concentration       | owner distribution or policy-defined concentration statistic  |
| Reward usefulness           | applied reward whose causal descendants improve accepted work |
| Node-to-final lineage       | required final components backed by accepted node references  |
| Cost per accepted node      | actual provider/tool cost divided by accepted nodes           |
| Score replay error          | live versus deterministic replay result                       |

Write `run-metrics-v1.json`, hash it, and require it in the manifest. Missing measurement infrastructure should fail publication for acceptance profiles; an explicitly unavailable provider price should remain a valid unavailable observation.

---

# 10. Make live executions operationally immutable

The historical evidence contains several failures that were not agent reasoning failures:

* a live source-classpath run was invalidated when a concurrent Gradle build changed class files underneath the JVM;
* the macOS system temporary volume produced `ENOSPC` failures until Codex and JVM temporary files were redirected;
* stale daemon state rejected new CLI arguments before the actual inline run;
* provider usage limits contaminated a long attempt with 103 capacity errors;
* lifecycle cancellation previously left an active provider-cost reservation, although the non-cancellable settlement repair now has regression coverage.

## 10.1 Stop using `gradlew :engine:cli:run` for acceptance

Build once:

```text
clean
→ full gates
→ installDist / immutable runnable bundle
→ hash every binary and dependency
→ copy into run-specific read-only directory
→ execute Java distribution directly
```

The manifest should bind:

```text
application JAR hashes
dependency JAR hashes
configuration hash
JVM version
Codex CLI hash/version
provider model assignment
source revision
gate-evidence hashes
```

This removes the live build output directory from the runtime trust boundary.

## 10.2 Exclusive acceptance-run lock

Acquire an OS-level file lock:

```text
.evomolior-locks/appendix-b-acceptance.lock
```

While held:

* another acceptance run cannot start;
* Gradle compile/test tasks for the repository fail immediately;
* CLI daemon startup is disabled;
* source/doc mutation checks run at round boundaries;
* the lock owner PID and binary manifest are recorded.

## 10.3 Admission controller

Before any provider call, verify:

```text
[ ] JDK 17
[ ] immutable runnable bundle
[ ] clean/new run ID and output directory
[ ] source and binary hashes match authorized gate record
[ ] no existing acceptance JVM
[ ] no Gradle compile/test process
[ ] application daemon disabled
[ ] scoring child can bind/start/health-check
[ ] sandbox executable and Python/shell capabilities available
[ ] output and temporary volumes writable
[ ] free-space threshold met on both volumes
[ ] Codex temporary directory is on the repository/external volume
[ ] provider credentials present
[ ] model assignment supported
[ ] generated provider schema independently validates
[ ] every declared sealed test compiles
[ ] Council execution profile matches round horizon
```

A practical disk threshold is:

```text
minimumFreeBytes =
    max(10 GiB, 4 × estimatedMaximumRunArchiveBytes)
```

The exact value should be configurable, but the run should fail before Council rather than during a Critic or grader call.

## 10.4 Provider circuit breaker

Acceptance mode should continue to prohibit silent provider fallback. Ordinary production mode may support a **predeclared sealed route**, but it must never silently change model identity.

```kotlin
data class SealedModelRoute(
    val primary: ModelArtifactRef,
    val fallbacks: List<ModelArtifactRef>,
    val fallbackEligibleFailures: Set<ProviderFailureKind>,
    val maximumAttempts: Int,
)
```

Rules:

* capacity and rate-limit failures can trigger bounded backoff;
* fallback is allowed only if declared before the run;
* provider/model change is recorded in the agent phenotype and independence profile;
* no fallback after a partially accepted structured response;
* exact acceptance runs can set `fallbacks = emptyList()`.

---

# 11. Add phase-level post-round checkpoints

This is additional hardening rather than a v72-specific defect, but it will prevent losing long runs after one late process failure.

Persist an idempotent round phase ledger:

```kotlin
enum class RoundPhase {
    ROUND_STARTED,
    WORKERS_QUIESCENT,
    CRITIC_AUTHORITY_COMMITTED,
    EVIDENCE_PACKETS_SEALED,
    GRADER_CRITERIA_FROZEN,
    EXACT_BUNDLES_SEALED,
    BLIND_SCORING_COMMITTED,
    NODE_GRADES_COMMITTED,
    CAPTURE_COMMITTED,
    DISCLOSURE_COMMITTED,
    YIELDS_SCHEDULED,
    OUTLIER_BOUNDARY_COMMITTED,
    ROUND_ARCHIVED,
}
```

Each phase record should include:

```text
input authority hashes
output authority hashes
phase implementation version
idempotency key
started/completed timestamps
provider-call references
cost-settlement state
```

Recovery rules:

1. Verify all hashes through the last completed phase.
2. Never rerun a sealed provider call merely because the process restarted.
3. Resume at the next uncommitted phase.
4. Recompute only deterministic projections.
5. Abort on mixed Atlas versions, changed evaluator version, or changed binary manifest.
6. Never advance to the next round until `ROUND_ARCHIVED`.

This turns a long run from one enormous transaction into a sequence of replayable sealed barriers.

---

# 12. Resolve the canonical-document inconsistency

The latest roadmap’s later sections correctly state that v72 is permanently rejected and no provider or six-round run is authorized.  However, the earlier Increment 10 table still contains stale language about v68 being rejected and v69 being statically launch-eligible.

Because the roadmap declares itself the sole current authority, this internal contradiction is dangerous for both humans and agents.

## Required fix

Add a small machine-readable authority file:

```json
{
  "schemaVersion": "acceptance-state-v1",
  "program": "competitive-progressive-disclosure",
  "currentAttempt": "v72",
  "currentAttemptStatus": "PERMANENTLY_REJECTED",
  "providerRunAuthorized": false,
  "fullAppendixBRunAuthorized": false,
  "currentBlockers": [
    "GENERAL_ASSERTION_CONTRACT_MISSING",
    "REPRESENTATIVE_ARCHIVE_NOT_ACCEPTED",
    "SECTION_18_2_NOT_RECONCILED",
    "APPENDIX_B_NOT_ACCEPTED"
  ],
  "authorizedNextActions": [
    "IMPLEMENT_GENERAL_ASSERTION_V1",
    "ADD_V71_V72_REGRESSIONS",
    "INDEPENDENT_REVIEW",
    "RUN_STATIC_GATES"
  ],
  "evidenceCut": "2026-07-18"
}
```

Then:

* generate the roadmap status summary from it, or validate the summary against it;
* make static-doc gates reject contradictory unqualified `launch-eligible` statements;
* require historical eligibility paragraphs to carry an explicit `HISTORICAL` label;
* update the roadmap evidence cut to July 18, 2026;
* update the Increment 10 row to the current v72 blocker;
* keep old attempt narratives append-only.

The first v72 static-status gate failed and the corrected v2 gate passed, which shows that this reconciliation machinery already catches some stale-state problems but should be made structural rather than text-pattern-dependent.

---

# 13. Full Appendix B run: launch conditions and audit

Only after the representative smoke passes should the full fixed-horizon run be considered.

## Pre-run field audit

Before workers activate, verify:

```text
exact objective preserved
receiver contract valid
worker and Grader Atlas pair matched
at least two meaningful worker groups
at least two meaningful grader groups
at least two disclosure stages
all declared tests typed and executable
all promotion fixtures executable
at least one tool authoring node schedulable
validation and later-use nodes distinct
expected-reuse execution schedulable inside horizon
all node prerequisites satisfiable inside horizon
every runtime role has prompt and model assignment
every node has KG mapping
all seed KG relations canonical and valid
explicit FinalOutputSpec present
metric-export plan present
```

## Conjunctive final audit

The accepted run must show in one manifest:

1. exact objective and receiver-oriented Atlas;
2. no early final-objective disclosure;
3. useful or explicitly non-contributing mortal exits;
4. Outliers with no ordinary work or Legacy;
5. distinct portfolios unless replication is explicit;
6. criteria frozen before worker evidence reveal;
7. exact grader cardinality and valid quorum every round;
8. every revealed mandatory node replayably graded;
9. capture and rewards based on exact grade, not completion;
10. at least one active promoted tool or simulation;
11. successful execution by a different later mortal agent;
12. execution bound to the expected node and a contribution;
13. tool result entering a later evidence packet;
14. later node grade, integration, or final-output influence;
15. causal worker/tool/Outlier fitness where applicable;
16. final output references accepted nodes, claims, tests, and uncertainties;
17. all provider reservations settled;
18. complete quiescent publication sequence;
19. manifest verification over databases/WAL, field, events, costs, tests, outputs, and lineage;
20. Section 18.2 metric file with explicit denominators and unavailable values.

A terminal `COMPLETED` status is necessary but not sufficient; v48, v51, v54, v55, and v61 all demonstrated why publication can complete without satisfying Appendix B’s conjunctive tool/progression requirements.

---

# 14. Matched single-agent comparison comes after acceptance, not before it

Increment 10’s retained matched experiment had two complete quality ties at `1.0`, while one-group and full-society arms each failed one seed. It therefore supports neither broad superiority nor a conclusion that the architecture is useless.

After the accepted Appendix B run, rerun:

```text
iterative single agent
one worker group
full worker society
```

with the same:

```text
input bytes
source access
Node Atlas
tool opportunities
output contract
evaluator version
test suite
latency policy
seeds
provider/model family
```

The report should distinguish:

* final quality;
* accepted mandatory-node coverage;
* useful-exit rate;
* failure probability;
* tool promotion and reuse;
* contradiction resolution;
* output lineage;
* provider cost when available;
* wall-clock latency;
* cost per accepted node;
* quality per provider call;
* variance across seeds.

Do not count extra artifacts, extra agents, or more staging rows as improvement.

---

# 15. Secondary gaps: important, but not current blockers

## 15.1 Cumulative action-credit ceiling

The roadmap still treats the original global action-credit budget as per-generation spawning rather than a cumulative run ceiling. This is explicitly documented as a product decision, not a hidden defect.

The provider/tool monetary ledger and reservation barrier are already more advanced. A future optional cumulative action-credit cap would still be useful:

```kotlin
data class RunActionCreditPolicy(
    val maximumAllocatedCredits: Double?,
    val maximumSpentCredits: Double?,
    val reserveForFinalization: Double,
)
```

Do not mix this with provider currency.

## 15.2 Claim ledger

Publication contracts verify shape and provenance, but claim-support checking can intentionally skip when no claim ledger exists. For research-heavy profiles, make a claim ledger mandatory:

```text
evidence-required profile
→ every material final claim references accepted claim authority
→ missing ledger blocks publication
```

This is not needed to fix v72, but it is important for trustworthy receiver output.

## 15.3 Same-family evaluation

The single-family setup remains acceptable for engineering the orchestration, but the independence profile must remain `LOW_SINGLE_FAMILY`. A successful run should not be marketed as three cognitively independent graders merely because three processes or prompts exist.

A different-family grader is a later qualification step—not a substitute for fixing exact contracts.

## 15.4 Cross-task learning and legacy projections

Automatic learning from arbitrary previous runs and removal of compatibility WIP/deliverable/resource projections remain deferred cleanup. They should not enter the current acceptance cycle.

## 15.5 Fine-tuning

Do not fine-tune a model to emit the current broken `GENERAL` representation. First stabilize and version the assertion protocol. Then tool/test-authoring traces can become training data.

Fine-tuning can improve:

* correct branch selection;
* valid tool calls;
* methodology generation;
* test authoring;
* contribution commits.

It cannot repair a compiler/evaluator ABI mismatch.

---

# 16. Exact implementation order

| Commit | Scope                                                                                  | Exit condition                                                                        |
| -----: | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
|  **1** | Add `GeneralTestDefinitionV1`, targets, assertions, canonical serializer               | `null`, maps, unknown branches, and malformed assertions cannot decode                |
|  **2** | Add pure `GeneralTestCompiler` and `CompiledGeneralTestPlan`                           | Every accepted definition deterministically compiles or returns typed errors          |
|  **3** | Add `sealed-tests-v3` and common suite authority                                       | Promotion and GENERAL branches are closed and hash-stable                             |
|  **4** | Make Synthesis provider wire emit typed GENERAL definitions                            | Actual generated schema validates independently; no arbitrary GENERAL JSON            |
|  **5** | Make evaluator consume compiled plans only                                             | No runtime raw-JSON heuristic parser remains on the v3 path                           |
|  **6** | Move full executability ahead of active-field persistence                              | Invalid tests produce `REJECTED_BEFORE_ACTIVATION`; no active field or workers        |
|  **7** | Add exact v71/v72 fixtures and positive seven-test sibling                             | v72 fails in compiler; positive fixture reaches 7/7 compile/preflight/execute         |
|  **8** | Add `CouncilSpecialistClosureValidator`                                                | Cross-role prompt/model/KG/schedule errors are resolved before Synthesis              |
|  **9** | Add `AcceptanceProfile` with tool-free smoke and full Appendix B modes                 | One-round schema cannot propose later reuse; full profile requires it                 |
| **10** | Add typed Section 18.2 metric exporter and manifest entry                              | Every metric has value or explicit unavailable status and evidence refs               |
| **11** | Add immutable distribution, run lock, admission controller, startup rejection artifact | Acceptance no longer executes against mutable Gradle class outputs                    |
| **12** | Independent current-byte review and focused contract tests                             | No blocker/high issue                                                                 |
| **13** | Clean serial nine-module, Section 18.1, runtime, Compose, CLI, and static-doc gates    | Stable pre/post source and binary manifests                                           |
| **14** | Authorized short representative provider run                                           | Non-zero executed tests and one receiver-usable verified archive                      |
| **15** | Authorized full multi-round Appendix B run                                             | Every conjunctive invariant and metric passes                                         |
| **16** | Matched iterative-single-agent comparison and quantitative report                      | Improvement, tie, regression, and unavailable values reported faithfully              |
| **17** | Canonical roadmap and goal reconciliation                                              | No stale eligibility language; Appendix B and Section 18.2/18.3 accurately classified |

# Final recommendation

The next source change should be **only the executable `GENERAL` assertion contract and its end-to-end compilation path**.

The highest-value acceptance statement for the next checkpoint is not “all unit tests pass.” It is:

> For every Council-authored sealed test accepted by the compiler, there exists exactly one deterministic runtime execution plan with the same canonical definition hash, evaluator version, target authority, persisted bytes, and replay result.

Once that invariant is true, run the tool-free representative profile. Only after that archive verifies should the project spend another full six-round budget.
