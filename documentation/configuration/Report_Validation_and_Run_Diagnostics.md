# Report validation and run diagnostics

## Three independent kinds of checks

1. **Operator output contract:** deterministic path, media type, structure, word count and
   explicitly required statements. A malformed artifact cannot claim it meets that contract.
2. **Acceptance authority:** sealed tests, contribution/node lineage, lifecycle settlement,
   grading and publication rules. Recovery delivery does not bypass these gates.
3. **Automatic source checking:** advisory checks against immutable uploaded text.
   These findings are **non-blocking** and never grant or remove acceptance authority.

The source policy was confirmed on September 10, 2026: check every report, request one
correction pass when possible, and publish remaining issues separately.

## Automatic source feedback

After an authorized agent stages a DOC, its successful tool result can include one request
to correct source issues. The correction uses that same agent's existing credit/time allowance;
it does not spawn another worker or grant additional credits. Later submissions receive remaining
findings without another correction request. No verified sources means limitations only; the
agent is not asked to invent references or obtain additional access.

External-URL verification limitations alone do not consume the correction pass or require
rewriting citations to refer to discovery seed files. Concrete incorrect source ranges,
unknown/ambiguous input references and quotation mismatches remain correction candidates.

The writer retains its original allowance to finish corrections and finalization. Publication
then selects its newest contract-valid staged candidate after actual execution settles. A malformed
advisory correction does not discard an earlier valid report; unresolved source findings remain
in that report's sidecar. Accepted publication still requires exact DOC-specific lineage.
If it must be stopped
after staging, that explicit stop is recorded separately from unexpected cancellation. Staged,
settled, validated, and accepted are distinct states.

Source loading checks exact immutable size/SHA-256, strict UTF-8 and safe paths, with limits of
2 MiB per file and 16 MiB total. Unreadable, changed, binary or oversized inputs remain explicit
limitations. Existing source-access policy still controls which inputs a worker may inspect.

Supported offline checks include `filename:Lnumber[-Lnumber]` source ranges, ambiguous/unknown
filenames, adjacent explicitly delimited quotations, and malformed URLs (`MALFORMED_URL`: invisible,
format, control, or non-ASCII space characters such as U+200B, or an invalid host). A malformed URL
is correctable from the report alone, so it keeps the one correction pass even when no verified
source text exists. Dollar signs and shell quotes must
survive unchanged. This is not a semantic fact checker: paraphrases, unmarked quotations and
external websites can remain unverified. No external URLs are fetched by this feature.

Every published report gets a manifest-bound attachment under `source-checks/`, named by a
run/path hash and bound to the report's exact bytes. CLI publication and pipeline ZIPs retain
these attachments. They describe remaining issues without rewriting the original report or
presenting source checking as proof of acceptance. Text-only/backend fallback reports can still
be checked at publication even when they did not use the interactive staging-feedback path.

## Complete report contracts

Use explicit controls for requirements whose success can be checked deterministically:

```json
{
  "relativePath": "review.md",
  "mediaType": "text/markdown",
  "validation": {
    "exactMarkdownHeadings": [
      "Summary", "Evidence", "Proposed change", "Validation plan", "Risks"
    ],
    "maximumWordCount": 900,
    "requiredTextStatements": ["These tests were not executed"]
  }
}
```

`minimumPublicUrlCount` counts distinct well-formed public URLs only. Spans containing invisible
characters or an invalid host never count, and the violation message names the first one with
its code points escaped (for example `senti<U+200B><U+200B>nels.copernicus.eu`). Trailing prose
punctuation is ignored, so `https://example.org/a.` and `https://example.org/a` are one URL.
CSV evidence-URL columns use the same rule.

`maximumWordCount` counts whitespace-delimited words including headings and code.
`minimumWordCount` applies the same count as a floor, so a writer cannot silently condense a
report below the requested depth; a violation triggers another writer attempt like any other
contract error, and the retry instructions state the floor. Both are omitted from canonical
serialization when unset, preserving existing sealed-contract hashes. Exact headings
are ordered and case-sensitive; fenced-code headings do not count. Required statements are
case-insensitive literal checks, **not proof that a model's statement is true**. The existing
`requiredMarkdownSectionSignals` remains available for looser heading requirements.
Contract checks apply at writer and archive/CLI validation boundaries; corresponding controls
are included in the sealed Council execution envelope.

Candidate-time validation records the exact artifact/contract hashes and results of
candidate-addressable sealed tests in `CANDIDATE_OUTPUT_VALIDATION_V1` meta-archive entries.
It does not replace earlier round snapshots or retroactively change their failed gates.

## Planning and grading

Council evaluates necessary scheduling bounds against unchanged per-group slots and rounds.
An infeasible plan can consume a bounded replan from the existing shared Council repair limit.
If a valid plan still exceeds those bounds, execution may continue with an explicit
provisional-only warning. A plan fitting the bounds is not guaranteed to finish or be accepted:
grading, credit use, replication, disclosure and contention can require more work.
Normal runtime persists `COUNCIL_FEASIBILITY_V1` for inspection.

Feasibility uses credit-affordable worker slots, not the nominal per-group ceiling: when a run's
credit share per group-round is below two of the cheapest permitted profile grants, one worker
is funded, which is what the allocator spawns. The Council's planning context receives the same
bound, and an infeasible report states how many nominal slots are actually affordable.

RoundJudge snapshots share a 24,000-character knowledge-base budget per group: short documents
stay whole, each keeps at least 600 characters, and the remainder flows to longer documents. The
prompt carries up to 40,000 snapshot characters (at least 4,000 per group). Previously each
document was cut to 600 characters and each snapshot to 2,000, so judges scored a substantial
report as "insufficient evidence".

The Critic settles runtime-generated staging records without a model call: source-fetch excerpts
are promoted at their staged trust, and contract/no-action fallbacks are rejected as observability.
A grader's typed methodology draft is reviewed first, and a draft the deadline-bounded pass did not
reach gets a review-only pass before any re-authoring cycle. A full local volume stops a review
pass and methodology retries instead of failing every remaining item.

From round 1, each grader civilisation starts from its newest Critic-promoted criteria and typed
methodology instead of re-researching every dimension. Criteria agents receive that baseline
(projected onto the round's handle matrix when the leased frontier is unchanged) and stage one
consolidated revision marking kept, changed, new, and retired items; the structured author edits
the baseline methodology. When neither rejection nor failure-telemetry pressure exists and the
frontier is unchanged, only a GraderBuilder runs. RoundJudge prompts carry up to 8,000 criteria
characters and always keep the grader-authored automated test results.

Grader-authored tests remain explicitly scoped to the **bounded interim canon snapshot**.
Final-report checks belong to the existing sealed-suite final-output machinery. This division
is intentional; no test-target migration or heuristic skipping of inconvenient tests is applied.

`AUTHORED_GRADING_DIAGNOSTIC` grader-local events retain raw judgment, original rationale,
predicate, snapshot/freeze identity, test execution status and gated scores. Missing/mismatched
authority or execution errors cannot become valid test evidence. Mandatory failures still gate
scores; the host applies penalties once. Log messages distinguish actual structured-output
repair from a completeness check that needed no extra request.

## Budget and performance interpretation

Credit telemetry separates allocated, live, spent and settled-unused balances by generation.
Settled unused credits are observations, not permission to grant more work. Empty fleets do
not trigger depletion warnings. Stale observations cannot overwrite newer generations.
None of these credits is provider USD.

Provider archival summaries use the cost ledger, report missing usage explicitly, and sum
request durations rather than pretending that overlapping calls equal elapsed run time.
Role labels come from immutable registrations where available; unavailable roles remain unknown.
Allowed constitutional audit events are distinct from actual blocked violations.

Single-family grading can have correct procedural independence while lacking model-family
diversity. Do not interpret one independence metric as evidence of every kind of independence.
