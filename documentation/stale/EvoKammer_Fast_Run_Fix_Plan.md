# EvoKammer Fast Run Fix Plan

## Follow-up Runs

- Historical fixed smoke run:
  - Run ID: `mindvortex-fast-smoke-fixed`
  - Mode: `-fast -rounds 5`
  - Outcome: `COMPLETED`
  - Winner: `group-0`
  - Duration: `240525 ms` (about 4.0 minutes)
  - Output directory: `/tmp/evokammer-fast-smoke-vRAWXd/mindvortex-fast-smoke-fixed`
- Diagnostic verification run after the `/tmp` failure review:
  - Run ID: `mindvortex-fast-verify-20260602-224611`
  - Mode: `-fast -rounds 1`
  - Outcome: `COMPLETED`
  - Duration: about 95 seconds
  - What it proved:
    - worker agents were no longer cancelled immediately at round start
    - the summary Archivist now respected the fast-mode Archivist model assignment
    - the summary path produced a real `ROUND SUBMISSION SUMMARY` staging entry again
  - Remaining blocker during that run:
    - a later dedicated final-deliverable Builder step hit OpenRouter `402` token-budget failures and was removed from the design
- Final verification rerun on the embedded-summary HTML path:
  - Run ID: `mindvortex-fast-verify-20260602-225836`
  - Mode: `-fast -rounds 1`
  - Outcome: `COMPLETED`
  - Output directory: `/tmp/evokammer-fast-verify-output-LY1pa4/mindvortex-fast-verify-20260602-225836`
  - Verified artifact:
    - `results/final-article.html` is now a standalone publication-style HTML article, extracted from the summary agent’s embedded `FINAL DELIVERABLE HTML` block
    - Proof chain:
      - `group-1` promoted `PLAN_UPDATE` summary contains both `ROUND SUBMISSION SUMMARY` and `FINAL DELIVERABLE HTML`
      - the extracted article begins with `<!DOCTYPE html>` and title `Unlock Your Creativity with MindVortex – The Ultimate Thought‑Mapping Platform`
      - `/tmp/evokammer-fast-verify-output-LY1pa4/mindvortex-fast-verify-20260602-225836/.evokammer-data/evokammer/runs/mindvortex-fast-verify-20260602-225836/results/final-article.html`
        matches that embedded HTML instead of the old archive scaffold
  - Remaining runtime caveat:
    - one `qwen/qwen3.5-flash-02-23` worker path still hit an upstream Alibaba/OpenRouter `502` tool-call formatting error, but the run still completed and the final article artifact path worked
- Hardening verification rerun after summary retry/fallback tightening:
  - Run ID: `mindvortex-fast-verify-20260603-095106`
  - Mode: `-fast -rounds 1`
  - Outcome: `COMPLETED`
  - Output directory: `/tmp/evokammer-fast-verify-output-roWm54/mindvortex-fast-verify-20260603-095106`
  - Verified artifact:
    - both group summary records now contain `ROUND SUBMISSION SUMMARY`
    - both summaries also contain `FINAL DELIVERABLE HTML`
    - `results.zip -> outputs/final-article.html` and the standalone
      `.evokammer-data/evokammer/runs/.../results/final-article.html` both render the authored MindVortex article titled
      `Unlock Your Mind’s Potential with MindVortex – The Ultimate Thought‑Flow Platform`
  - What this proved:
    - the tightened summary prompt is sufficient to get Archivists to write a real `PLAN_UPDATE` even when the substrate is sparse
    - the final article output path no longer depends on pre-existing worker artefacts to avoid the scaffold fallback
  - Remaining runtime caveat:
    - `qwen/qwen3.5-flash-02-23` Researcher still frequently fails on Alibaba/OpenRouter `502` tool-call formatting
    - RoundJudge still often ends in the empty-assistant recovery path
    - neither issue blocked final article production in this rerun

## What The Run Showed

1. The CLI and engine wiring are functional end to end. The run completes, writes `summary.json`, `debug-log.txt`, `results.zip`, and a standalone `final-article.html`, and the ZIP includes `outputs/final-article.html`.
2. The root cause of the original `/tmp` failure was the fast CLI setting `roundDurationMinutes = 0`, which caused the round loop to expire immediately and cancel worker agents almost as soon as they spawned.
3. The post-round summary path had a second bug: the Archivist summariser was hard-wired to `DEFAULT_RUNTIME_RESEARCHER_MODEL` instead of inheriting the active fast Archivist assignment.
4. A dedicated post-run Builder-based article writer is too expensive for the current provider-credit envelope. It repeatedly triggered OpenRouter `402` failures once credits got tight, so the design now embeds the publishable HTML into the already-running summary path instead of spending an extra model call after the run.
5. The remaining notable runtime caveat is now upstream-provider instability rather than the original fast-run control-flow bug. The latest hardening rerun still showed persistent `qwen`/Alibaba `502` failures and an empty-assistant RoundJudge, but that no longer prevented summary generation, archival, or final article extraction.
6. The summary path is now explicitly resilient to sparse substrates. When the workers fail to leave useful artefacts, the Archivist is instructed to still write the required summary and to author the standalone HTML deliverable directly from the objective when necessary.

## Required Fixes

1. Keep the non-zero fast round duration.
   - Implemented: fast CLI runs now use a real round window, which allows workers to complete and still lets the round end early when agents drain.

2. Keep the summary Archivist on the active fast model assignment.
   - Implemented: the post-round summary agent now uses the configured Archivist assignment instead of bypassing it.

3. Make the summary path carry the final publication HTML.
   - Implemented: the summary prompt now tells the Archivist to append a `FINAL DELIVERABLE HTML` block with a full standalone HTML5 document when the objective calls for browser-renderable output.
   - Implemented: `RunArchiveService` now extracts that embedded HTML block before falling back to the old scaffold renderer.

4. Do not spend an extra post-run model call for publication HTML in fast mode.
   - Implemented: the dedicated final-deliverable Builder step was removed from the active runtime path after proving too expensive under real provider budgets.

5. Remaining operational issue:
   - Keep an eye on upstream `qwen`/Alibaba tool-call formatting failures (`502`) in fast runs. The output path is now resilient enough to complete despite them, but they still reduce worker productivity.
6. Harden the round-summary path against empty/no-progress completions.
   - Implemented: round submission summaries now get a bounded retry, and the retry prompt explicitly instructs the Archivist to stop exploring and write the summary even when KB/KG are empty.
   - Implemented: the summary prompt now explicitly allows authoring the final HTML directly from the objective when no prior deliverable artefacts exist.

## Verification For The Next Run

1. Confirm `summary.json` reports `COMPLETED`.
2. Confirm the main worker path no longer shows immediate `JobCancellationException` failures right after spawn.
3. Confirm the post-round summary agents use the active fast Archivist model.
4. Confirm at least one summary contains both:
   - `ROUND SUBMISSION SUMMARY`
   - `FINAL DELIVERABLE HTML`
5. Confirm `results.zip` and `results/final-article.html` render the embedded article HTML rather than the archive scaffold fallback.
6. Optional hardening follow-up: reduce or eliminate the remaining upstream `qwen`/Alibaba `502` tool-call failures and the RoundJudge empty-assistant path.
