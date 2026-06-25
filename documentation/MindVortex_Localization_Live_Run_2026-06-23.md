# MindVortex Localization Live Run - 2026-06-23

## Purpose
This document preserves the reusable workflow and final audit trail for the MindVortex multilingual article localization run. The working run artifacts remain under `documentation/tmp/mindvortex_localization_run/`, but this durable summary should survive ordinary tmp cleanup.

## Source
- Source refined HTML: `documentation/tmp/mindvortex_localization_run/input/mindvortex-refined-source.html`
- Source origin: `documentation/tmp/mindvortex_fast_cli_run/output/mindvortex-fast-html-wordgate-20260623-114736/outputs/refined/final-article-refined.html`

## Strategy
The EvoKammer fast CLI/archive path captures one requested final HTML artifact per stage (`outputs/final-article.html` plus the automatic fast follow-up at `outputs/refined/final-article-refined.html`). Because the product goal required separate localized article versions, the validated approach was one live fast CLI run per locale rather than one run trying to pack multiple independent HTML documents into a single captured output.

Reusable files created for the run:
- Plan: `documentation/tmp/mindvortex_localization_run/PLAN.md`
- Runner: `documentation/tmp/mindvortex_localization_run/run_localizations.sh`
- Verifier: `documentation/tmp/mindvortex_localization_run/verify_localizations.py`
- Latest run manifest: `documentation/tmp/mindvortex_localization_run/latest-runs.tsv`
- Verification JSON: `documentation/tmp/mindvortex_localization_run/latest-verification.json`
- Detailed results: `documentation/tmp/mindvortex_localization_run/RESULTS.md`

## Final Verified Artifacts
- `es`: `mindvortex-l10n-es-20260623-170915` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-es-20260623-170915/outputs/refined/final-article-refined.html`
- `de`: `mindvortex-l10n-de-20260623-153129` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-de-20260623-153129/outputs/refined/final-article-refined.html`
- `fr`: `mindvortex-l10n-fr-20260623-154547` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-fr-20260623-154547/outputs/refined/final-article-refined.html`
- `ja`: `mindvortex-l10n-ja-20260623-170915` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-ja-20260623-170915/outputs/refined/final-article-refined.html`
- `ko`: `mindvortex-l10n-ko-20260623-155542` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-ko-20260623-155542/outputs/refined/final-article-refined.html`
- `nl`: `mindvortex-l10n-nl-20260623-160116` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-nl-20260623-160116/outputs/refined/final-article-refined.html`
- `sv`: `mindvortex-l10n-sv-20260623-162442` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-sv-20260623-162442/outputs/refined/final-article-refined.html`
- `it`: `mindvortex-l10n-it-20260623-163048` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-it-20260623-163048/outputs/refined/final-article-refined.html`
- `pt-BR`: `mindvortex-l10n-pt_BR-20260623-164603` -> `documentation/tmp/mindvortex_localization_run/output/mindvortex-l10n-pt_BR-20260623-164603/outputs/refined/final-article-refined.html`

## Verification
Final verifier command:

```sh
python3 documentation/tmp/mindvortex_localization_run/verify_localizations.py > documentation/tmp/mindvortex_localization_run/latest-verification.json
```

Final verifier summary:

```text
verified_locales es,de,fr,ja,ko,nl,sv,it,pt-BR
all_ok True count 9
```

Verification covered:

- Complete standalone HTML structure: doctype, closing `</html>`, `main`, `article`, `footer`, and embedded style block.
- Localized publishing metadata: `html lang`, canonical URL, Open Graph/Twitter tags, and JSON-LD.
- Content safety and brand checks: no `example.com`, `mindvortex.app` present, and locale signal fragments present.
- Substantial localized length: word counts for alphabetic languages and CJK character counts for Japanese/Korean.
- Not-English-only heuristic checks.

## Reruns
- Spanish first passed structure but used `ADHD` in visible text instead of localized `TDAH`; rerun `mindvortex-l10n-es-20260623-170915` passed.
- Japanese first failed because the final writer did not publish an artifact; rerun `mindvortex-l10n-ja-20260623-170915` passed.

## Secret Handling Incident
During setup, an `rg` command printed the local `OPENROUTER_API_KEY` value from `local.properties` into tool output. Immediate mitigation performed in the workspace:

- Confirmed `local.properties` is gitignored and untracked.
- Replaced the local value with `OPENROUTER_API_KEY=__REPLACE_WITH_ROTATED_KEY__`.

Required external action: rotate the exposed OpenRouter key in the OpenRouter dashboard before using OpenRouter again from this workspace, then put the rotated key back into `local.properties` locally.
