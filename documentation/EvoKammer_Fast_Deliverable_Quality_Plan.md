# EvoKammer Fast Deliverable Quality Plan

## Goal

Raise the quality floor for the fast MindVortex HTML-deliverable scenario so a 10-20 round run
produces a substantially richer final article instead of a thin summary-grade artifact.

## Current Evidence

- The current fast CLI run configuration is biased toward shallow output:
  - `:evokammer:cli` uses a single generic scoring dimension: `accuracy`.
  - `:evokammer:cli` uses `maxAgentsPerGroup = 1`, which limits within-round iteration.
  - fast runs are `isTestRun = true`, so the worker side skips the Genesis Council and relies on
    a minimal default starting field.
- The current publication path is structurally biased toward short HTML:
  - `spawnRoundSubmissionSummaries()` is active in production.
  - the round-summary prompt explicitly tells the Archivist to keep the output concise.
  - the run-completion marker currently stores only `submissionSummariesByGroup` and
    `judgeOutputsByGrader`; it does not persist `finalDeliverableHtml`.
  - `spawnFinalDeliverableHtml()` exists, but the run loop never calls it before archiving.

## What Needs To Change

1. Restore a dedicated final-publication step for browser deliverables.
   - Keep round summaries concise for graders.
   - Use the separate final deliverable writer for the published HTML artifact.
   - Persist the authored final HTML into `RunCompletionRecord.finalDeliverableHtml`.

2. Make the final deliverable prompt materially stricter.
   - Require embedded styles, semantic sections, stronger metadata, and more substantial copy.
   - Require synthesis of insights from multiple rounds rather than a one-shot miniature article.

3. Make the fast CLI grading target publication quality directly.
   - Replace the single `accuracy` dimension with a richer article-quality rubric for browser
     deliverables.
   - Bias the grader toward depth, semantics/accessibility, visual polish, and conversion value.

4. Give fast browser-deliverable runs enough worker bandwidth to iterate.
   - Increase `maxAgentsPerGroup` for this scenario above the current single-agent limit.

5. Re-run a fresh fast MindVortex article scenario with 10-20 rounds and inspect:
   - final article size/structure,
   - whether the final HTML came from the dedicated writer,
   - whether the richer rubric appears in the run config and logs.

## Checklist

- [x] Implement the dedicated final-deliverable publication path in the run loop.
- [x] Tighten final HTML writer prompt requirements.
- [x] Upgrade fast CLI browser-deliverable scoring dimensions and worker limits.
- [x] Add/adjust targeted tests.
- [x] Execute a fresh `-fast` verification run with 10-20 rounds.
- [x] Audit the resulting final article and archive outputs.

## Current Verification State

- Targeted tests passed:
  - `./gradlew --no-daemon :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`
  - `./gradlew --no-daemon :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest`
  - `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- CLI distribution build passed:
  - `./gradlew --no-daemon :evokammer:cli:installDist`
- Remaining blocker for the live fast rerun:
  - none for the original requested verification; one 10-round run completed successfully.

## Completed 10-Round Verification

- Run ID: `mindvortex-fast-quality-20260604-160448`
- Output directory:
  - `/tmp/evokammer-fast-quality-output-j62THQ/mindvortex-fast-quality-20260604-160448`
- Mode:
  - `-fast -rounds 10`
- Outcome:
  - `COMPLETED`
- Winner:
  - `group-0`
- Duration:
  - `3,080,714 ms` (about 51.3 minutes)

## Artifact Audit

- Published article path:
  - `/tmp/evokammer-fast-quality-output-j62THQ/mindvortex-fast-quality-20260604-160448/.evokammer-data/evokammer/runs/mindvortex-fast-quality-20260604-160448/results/final-article.html`
- ZIP parity:
  - `results.zip -> outputs/final-article.html` exactly matches the standalone article file.
- Concrete article properties:
  - title: `MindVortex – Transform Your Focus, Harness Your Ideas`
  - `<style>` block present
  - `6` semantic `<section>` blocks present
  - FAQ and CTA sections present
  - measured visible word count: about `505`

## What Improved

- The fast CLI run now grades browser deliverables against article-quality dimensions instead of a
  single generic `accuracy` dimension.
- The 10-round live logs repeatedly show article-focused dimensions:
  - `content_depth`
  - `semantic_html`
  - `visual_polish`
  - `product_positioning`
  - `evidence_fidelity`
- Round summaries now repeatedly describe a full HTML5 article with the required section set:
  hero, ADHD framing, features/workflow, tips/use cases, FAQ, and CTA.
- The runtime now spawns a dedicated final deliverable writer at run completion for browser
  objectives instead of only relying on the summary path.

## Remaining Caveat Found By The Verification

- The completed 10-round run proved the quality floor improved structurally, but the final article
  was still shorter than the desired 1,200-2,000 word bar.
- Root cause from live logs:
  - the dedicated final deliverable writer was spawned
  - but the fast `Builder` model completed in a warning-only path without calling `write_staging`
  - the published article therefore still appears to have been harvested from the summary path
- Follow-up hardening implemented after that discovery:
  - the dedicated final deliverable writer now explicitly uses the fast `Archivist` model
    assignment when available, falling back to the `Builder` assignment only otherwise
- A second long rerun was started to validate that follow-up tweak specifically, but it was
  manually terminated after confirming the run remained dominated by grader timeouts. The original
  requested 10-round verification had already completed successfully before that extra experiment.
