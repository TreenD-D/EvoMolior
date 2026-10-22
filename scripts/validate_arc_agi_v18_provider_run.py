#!/usr/bin/env python3
"""Validate one recovered ARC-AGI-2 provider notebook without editing it."""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
from collections import Counter
from pathlib import Path
from typing import Any


REQUIRED_CODE_SIGNALS = (
    "def is_grid",
    "def select_two_outputs",
    "def validate_submission",
    "contextual_symbolic_relay",
    "def synthetic_equivariant_views",
    "def callable_signature_smoke_test",
    "def load_local_inputs",
    "def execute_challenge",
    "def write_receiver",
    "for task_id, task in challenges.items()",
    "task.get(\"train\"",
    "task.get(\"test\"",
    "attempt_1",
    "attempt_2",
    "submission.json",
    "sample_submission",
    "time.monotonic",
    "def run_self_tests",
    "def rotate_grid",
    "color_permutation",
    "mutation",
)
REQUIRED_MARKDOWN_SIGNALS = (
    "EvoARC-2 symbolic baseline qualification",
    "offline",
    "neural and task-time adaptation branches are deferred",
    "equivariance",
    "mutation",
)
FORBIDDEN_CODE_SIGNALS = (
    "requests.",
    "urllib.",
    "http://",
    "https://",
    "kagglehub",
    "pip install",
    "!wget",
    "!curl",
    "demos = []",
    "for task_id, test_inputs in challenge.items()",
)


def sha256(path: Path) -> str:
    """Return the SHA-256 digest of one file."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def source_text(cell: dict[str, Any]) -> str:
    """Normalize a notebook source field to text."""
    source = cell.get("source", "")
    return source if isinstance(source, str) else "".join(source)


def is_grid(value: Any) -> bool:
    """Check the bounded ARC integer-grid contract."""
    return (
        isinstance(value, list)
        and bool(value)
        and all(isinstance(row, list) and row for row in value)
        and len({len(row) for row in value}) == 1
        and all(isinstance(item, int) and 0 <= item <= 9 for row in value for item in row)
    )


def validate_receiver(
    submission: dict[str, Any],
    challenge: dict[str, Any],
    baseline: dict[str, Any],
) -> dict[str, Any]:
    """Validate task rows and compare them to the immutable prior submission."""
    expected_rows = sum(len(task.get("test", [])) for task in challenge.values())
    rows = 0
    invalid = 0
    duplicate = 0
    distinct = 0
    changed = 0
    attempt_one_identity = 0
    attempt_two_identity = 0
    for task_id, task in challenge.items():
        task_rows = submission.get(task_id)
        tests = task.get("test", [])
        if not isinstance(task_rows, list) or len(task_rows) != len(tests):
            invalid += 1
            continue
        for index, row in enumerate(task_rows):
            rows += 1
            valid = (
                isinstance(row, dict)
                and set(row) == {"attempt_1", "attempt_2"}
                and is_grid(row["attempt_1"])
                and is_grid(row["attempt_2"])
            )
            if not valid:
                invalid += 1
                continue
            if row["attempt_1"] == row["attempt_2"]:
                duplicate += 1
            else:
                distinct += 1
            if row["attempt_1"] == tests[index]["input"]:
                attempt_one_identity += 1
            if row["attempt_2"] == tests[index]["input"]:
                attempt_two_identity += 1
            if row != baseline[task_id][index]:
                changed += 1
    return {
        "challenge_tasks": len(challenge),
        "submission_tasks": len(submission),
        "expected_rows": expected_rows,
        "rows": rows,
        "invalid_tasks_or_rows": invalid,
        "duplicate_attempt_rows": duplicate,
        "distinct_attempt_rows": distinct,
        "changed_vs_baseline": changed,
        "attempt_1_identity_rows": attempt_one_identity,
        "attempt_2_identity_rows": attempt_two_identity,
        "strict_contract_pass": (
            set(submission) == set(challenge)
            and rows == expected_rows
            and invalid == 0
        ),
    }


def main() -> None:
    """Run structural, compilation, cumulative, and receiver checks."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()

    run_dir = args.run_dir.resolve()
    provider_path = run_dir / "provider-artifact/outputs/recovered/submission.ipynb"
    receiver_dir = run_dir / "receiver-validation"
    challenge_path = receiver_dir / "arc-agi_evaluation_challenges.json"
    baseline_path = receiver_dir / "prior-kaggle-submission.json"
    output_path = receiver_dir / "submission.json"
    notebook = json.loads(provider_path.read_text(encoding="utf-8"))
    cells = notebook.get("cells", [])
    code_cells = [cell for cell in cells if cell.get("cell_type") == "code"]
    markdown_cells = [cell for cell in cells if cell.get("cell_type") == "markdown"]
    code_text = "\n".join(source_text(cell) for cell in code_cells)
    markdown_text = "\n".join(source_text(cell) for cell in markdown_cells)
    ids = [cell.get("id") for cell in cells]

    independent_compile_errors: list[str] = []
    for cell in code_cells:
        try:
            ast.parse(source_text(cell), filename=f"<cell:{cell.get('id')}>")
        except SyntaxError as error:
            independent_compile_errors.append(f"{cell.get('id')}: {error}")

    previous_cwd = Path.cwd()
    cumulative_error: str | None = None
    audit_result: dict[str, Any] | None = None
    branch_counts: dict[str, Any] = {}
    try:
        os.chdir(receiver_dir)
        namespace: dict[str, Any] = {"__name__": "__main__", "__file__": str(provider_path)}
        for cell in code_cells:
            exec(compile(source_text(cell), f"<cell:{cell.get('id')}>", "exec"), namespace)
        self_tests_pass = bool(namespace["run_self_tests"]())
        self_audit = namespace["self_audit_notebook"](str(provider_path))
        assets = namespace["load_local_inputs"]()
        submission, audit_rows = namespace["execute_challenge"](
            assets["challenge_data"], assets["prior_data"]
        )
        namespace["write_receiver"](submission, str(output_path))
        branch_counts = {
            "family_1": dict(Counter(row["family_1"] for row in audit_rows)),
            "family_2": dict(Counter(row["family_2"] for row in audit_rows)),
            "baseline_fallback_rows": sum(
                bool(row["counterfactual"]["baseline"]) for row in audit_rows
            ),
            "nonbaseline_rows": sum(
                not bool(row["counterfactual"]["baseline"]) for row in audit_rows
            ),
        }
        audit_result = {
            "code_cells_executed": len(code_cells),
            "self_tests_pass": self_tests_pass,
            "self_audit": self_audit,
            "challenge_sha256": assets["challenge_sha256"],
            "baseline_sha256": assets["prior_sha256"],
            "audit_rows": len(audit_rows),
            "branch_provenance": branch_counts,
        }
    except Exception as error:  # noqa: BLE001 - persist exact receiver failure evidence.
        cumulative_error = f"{type(error).__name__}: {error}"
    finally:
        os.chdir(previous_cwd)

    challenge = json.loads(challenge_path.read_text(encoding="utf-8"))
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    submission = json.loads(output_path.read_text(encoding="utf-8")) if output_path.exists() else {}
    receiver = validate_receiver(submission, challenge, baseline)
    validation = {
        "run_id": run_dir.name,
        "provider_artifact": {
            "path": str(provider_path),
            "sha256": sha256(provider_path),
            "bytes": provider_path.stat().st_size,
            "archive_entry": "outputs/recovered/submission.ipynb",
        },
        "inputs": {
            "challenge": str(challenge_path),
            "challenge_sha256": sha256(challenge_path),
            "baseline": str(baseline_path),
            "baseline_sha256": sha256(baseline_path),
        },
        "notebook_structure": {
            "nbformat": notebook.get("nbformat"),
            "nbformat_minor": notebook.get("nbformat_minor"),
            "cell_count": len(cells),
            "code_cell_count": len(code_cells),
            "unique_nonblank_cell_ids": (
                len(ids) == len(set(ids)) and all(isinstance(item, str) and item for item in ids)
            ),
            "null_execution_counts": all(
                cell.get("execution_count") is None for cell in code_cells
            ),
            "empty_outputs": all(cell.get("outputs") == [] for cell in code_cells),
            "independent_compilation_pass": not independent_compile_errors,
            "independent_compile_errors": independent_compile_errors,
        },
        "signals": {
            "required_code_missing": [signal for signal in REQUIRED_CODE_SIGNALS if signal not in code_text],
            "required_markdown_missing": [
                signal for signal in REQUIRED_MARKDOWN_SIGNALS if signal not in markdown_text
            ],
            "forbidden_code_present": [
                signal for signal in FORBIDDEN_CODE_SIGNALS if signal in code_text
            ],
        },
        "cumulative_execution": {
            "pass": cumulative_error is None,
            "error": cumulative_error,
            "details": audit_result,
        },
        "receiver": receiver,
    }
    (receiver_dir / "validation-results.json").write_text(
        json.dumps(validation, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (receiver_dir / "receiver-comparison.json").write_text(
        json.dumps(
            {
                "provider_submission_sha256": sha256(output_path) if output_path.exists() else None,
                "baseline_submission_sha256": sha256(baseline_path),
                "receiver": receiver,
                "branch_provenance": branch_counts,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(json.dumps(validation, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
