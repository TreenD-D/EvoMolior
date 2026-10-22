#!/usr/bin/env python3
"""Benchmark a derived ARC notebook against visible training pairs and a prior run."""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import time
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    """Load one JSON artifact from ``path``."""
    return json.loads(path.read_text(encoding="utf-8"))


def load_notebook_namespace(path: Path) -> tuple[dict[str, Any], str]:
    """Execute source cells through the audit cell and return the namespace."""
    notebook = load_json(path)
    namespace: dict[str, Any] = {"__name__": "__main__"}
    namespace["time"] = time
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured):
        for cell in notebook["cells"]:
            if cell["cell_type"] != "code" or cell["id"] in {"code-00", "code-06", "code-07"}:
                continue
            exec(compile(cell["source"], cell["id"], "exec"), namespace)
    return namespace, captured.getvalue()


def grid_equal(left: Any, right: Any) -> bool:
    """Compare two grids without accepting non-grid values."""
    return left == right


def validate_submission_shape(
    submission: dict[str, Any], challenge: dict[str, Any]
) -> dict[str, int]:
    """Return structural counts for a task-list ARC submission."""
    rows = 0
    invalid = 0
    duplicate = 0
    for task_id, task in challenge.items():
        task_rows = submission.get(task_id)
        expected = len(task.get("test", []))
        if not isinstance(task_rows, list) or len(task_rows) != expected:
            invalid += 1
            continue
        for row in task_rows:
            rows += 1
            if not isinstance(row, dict) or set(row) != {"attempt_1", "attempt_2"}:
                invalid += 1
                continue
            if row["attempt_1"] == row["attempt_2"]:
                duplicate += 1
    return {"rows": rows, "invalid": invalid, "duplicate_attempt_rows": duplicate}


def benchmark(
    notebook_path: Path,
    challenge_path: Path,
    baseline_path: Path | None,
    loo_task_limit: int | None,
) -> dict[str, Any]:
    """Benchmark candidate selection and visible-pair generalization."""
    started = time.monotonic()
    challenge = load_json(challenge_path)
    namespace, captured = load_notebook_namespace(notebook_path)
    rank_programs = namespace["rank_programs"]
    select_two_outputs = namespace["select_two_outputs"]

    baseline = load_json(baseline_path) if baseline_path else None
    family_counts: dict[str, int] = {}
    test_rows = 0
    nonidentity_attempts = 0
    changed_vs_baseline = 0
    exact_fit_rows = 0

    for task_id, task in challenge.items():
        demos = task.get("train", [])
        for index, test in enumerate(task.get("test", [])):
            test_rows += 1
            ranked = rank_programs(demos, test["input"])
            if ranked and ranked[0]["family"] != "deterministic_fallback":
                exact_fit_rows += 1
            for candidate in ranked:
                family_counts[candidate["family"]] = (
                    family_counts.get(candidate["family"], 0) + 1
                )
            selected = select_two_outputs(ranked)
            if selected[0]["grid"] != test["input"]:
                nonidentity_attempts += 1
            if baseline is not None:
                derived_row = {
                    "attempt_1": selected[0]["grid"],
                    "attempt_2": selected[1]["grid"],
                }
                if derived_row != baseline[task_id][index]:
                    changed_vs_baseline += 1

    loo_rows = 0
    loo_top1 = 0
    loo_any_attempt = 0
    loo_families: dict[str, int] = {}
    loo_tasks = 0
    for task in challenge.values():
        if loo_task_limit == 0 or (
            loo_task_limit is not None and loo_tasks >= loo_task_limit
        ):
            break
        loo_tasks += 1
        demos = task.get("train", [])
        for holdout_index, holdout in enumerate(demos):
            remaining = [
                pair for index, pair in enumerate(demos) if index != holdout_index
            ]
            if not remaining:
                continue
            loo_rows += 1
            ranked = rank_programs(remaining, holdout["input"])
            for candidate in ranked:
                loo_families[candidate["family"]] = (
                    loo_families.get(candidate["family"], 0) + 1
                )
            selected = select_two_outputs(ranked)
            if grid_equal(selected[0]["grid"], holdout["output"]):
                loo_top1 += 1
            if any(
                grid_equal(candidate["grid"], holdout["output"])
                for candidate in selected
            ):
                loo_any_attempt += 1

    return {
        "notebook": str(notebook_path),
        "challenge": str(challenge_path),
        "baseline_submission": str(baseline_path) if baseline_path else None,
        "tasks": len(challenge),
        "test_rows": test_rows,
        "exact_fit_rows": exact_fit_rows,
        "nonidentity_attempt_1": nonidentity_attempts,
        "changed_vs_baseline": changed_vs_baseline,
        "visible_train_leave_one_out": {
            "task_limit": loo_task_limit,
            "tasks_evaluated": loo_tasks,
            "rows": loo_rows,
            "top1_correct": loo_top1,
            "any_two_attempts_correct": loo_any_attempt,
            "top1_rate_percent": round(100.0 * loo_top1 / loo_rows, 3)
            if loo_rows
            else 0.0,
            "any_two_rate_percent": round(100.0 * loo_any_attempt / loo_rows, 3)
            if loo_rows
            else 0.0,
            "selected_family_counts": loo_families,
        },
        "selected_family_counts": family_counts,
        "execution_seconds": round(time.monotonic() - started, 6),
        "self_test_output": captured.strip(),
    }


def main() -> None:
    """Run the benchmark and optionally write a JSON report."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--notebook", type=Path, required=True)
    parser.add_argument("--challenge", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument(
        "--loo-task-limit",
        type=int,
        default=20,
        help="Limit leave-one-out analysis to the first N tasks; use 0 to skip it.",
    )
    args = parser.parse_args()
    result = benchmark(
        args.notebook,
        args.challenge,
        args.baseline,
        args.loo_task_limit,
    )
    serialized = json.dumps(result, indent=2, sort_keys=True)
    print(serialized)
    if args.report:
        args.report.write_text(serialized + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
