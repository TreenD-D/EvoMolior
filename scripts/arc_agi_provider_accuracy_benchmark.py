#!/usr/bin/env python3
"""Score provider ARC notebooks on visible training-pair leave-one-out accuracy."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


DEFAULT_SKIPPED_CELL_IDS = {"code-06", "code-07", "code-08"}


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


def load_namespace(
    notebook_path: Path,
    skipped_cell_ids: set[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Execute only definition cells from one provider notebook."""
    notebook = json.loads(notebook_path.read_text(encoding="utf-8"))
    namespace: dict[str, Any] = {"__name__": "__main__"}
    executed_cells: list[str] = []
    for cell in notebook.get("cells", []):
        if cell.get("cell_type") != "code":
            continue
        cell_id = str(cell.get("id", ""))
        if cell_id in skipped_cell_ids:
            continue
        source = source_text(cell)
        exec(compile(source, f"<cell:{cell_id}>", "exec"), namespace)
        executed_cells.append(cell_id)
    return namespace, {
        "nbformat": notebook.get("nbformat"),
        "nbformat_minor": notebook.get("nbformat_minor"),
        "cell_count": len(notebook.get("cells", [])),
        "executed_code_cells": executed_cells,
    }


def selected_predictions(
    namespace: dict[str, Any],
    input_grid: Any,
    remaining_demos: list[tuple[Any, Any]],
) -> tuple[list[Any], list[str]]:
    """Call the provider's selected solver interface for one holdout."""
    if callable(namespace.get("build_task_solvers")):
        solvers = list(namespace["build_task_solvers"](remaining_demos))
        predictions = [solver(input_grid) for solver in solvers[:2]]
        families = [
            getattr(solver, "__name__", str(solver)) for solver in solvers[:2]
        ]
    elif callable(namespace.get("attempt_1")) and callable(namespace.get("attempt_2")):
        predictions = []
        families = []
        for name in ("attempt_1", "attempt_2"):
            result = namespace[name](input_grid, remaining_demos)
            if (
                isinstance(result, tuple)
                and len(result) == 2
                and isinstance(result[1], str)
            ):
                prediction, family = result
            else:
                prediction, family = result, name
            predictions.append(prediction)
            families.append(family)
    else:
        raise RuntimeError(
            "Notebook exposes neither build_task_solvers(demos) nor "
            "attempt_1/attempt_2(input, demos)"
        )
    if not predictions:
        raise RuntimeError("Notebook selected no solver outputs")
    while len(predictions) < 2:
        predictions.append(predictions[0])
        families.append(families[0])
    return predictions[:2], families[:2]


def score_candidate(
    label: str,
    notebook_path: Path,
    challenge: dict[str, Any],
    skipped_cell_ids: set[str],
) -> dict[str, Any]:
    """Score one notebook with exact visible-pair leave-one-out accuracy."""
    namespace, structure = load_namespace(notebook_path, skipped_cell_ids)
    rows = 0
    top1_correct = 0
    any_two_correct = 0
    family_counts: dict[str, int] = {}
    task_scores: dict[str, dict[str, int]] = {}

    for task_id, task in challenge.items():
        demos = [
            (pair["input"], pair["output"])
            for pair in task.get("train", [])
        ]
        task_result = {"rows": 0, "top1_correct": 0, "any_two_correct": 0}
        for holdout_index, holdout in enumerate(demos):
            remaining = demos[:holdout_index] + demos[holdout_index + 1 :]
            if not remaining:
                continue
            predictions, families = selected_predictions(
                namespace,
                holdout[0],
                remaining,
            )
            rows += 1
            task_result["rows"] += 1
            top1 = predictions[0] == holdout[1]
            any_two = any(prediction == holdout[1] for prediction in predictions)
            top1_correct += int(top1)
            any_two_correct += int(any_two)
            task_result["top1_correct"] += int(top1)
            task_result["any_two_correct"] += int(any_two)
            for family in families:
                family_counts[family] = family_counts.get(family, 0) + 1
        if task_result["rows"]:
            task_scores[task_id] = task_result

    if rows == 0:
        raise RuntimeError(f"{label} has no eligible visible holdout rows")
    return {
        "label": label,
        "notebook": str(notebook_path),
        "notebook_sha256": sha256(notebook_path),
        "structure": structure,
        "holdout_rows": rows,
        "top1_correct": top1_correct,
        "top1_rate_percent": round(100.0 * top1_correct / rows, 6),
        "any_two_correct": any_two_correct,
        "any_two_rate_percent": round(100.0 * any_two_correct / rows, 6),
        "selected_family_counts": family_counts,
        "task_scores": task_scores,
    }


def main() -> None:
    """Run the exact local benchmark and optionally write a report."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--challenge", type=Path, required=True)
    parser.add_argument(
        "--candidate",
        action="append",
        required=True,
        metavar="LABEL=NOTEBOOK",
        help="Repeat for each candidate; the first candidate is the baseline.",
    )
    parser.add_argument("--report", type=Path)
    parser.add_argument(
        "--skip-cell-id",
        action="append",
        default=[],
        help="Additional notebook code-cell id to skip during definition loading.",
    )
    args = parser.parse_args()

    challenge = json.loads(args.challenge.read_text(encoding="utf-8"))
    skipped_cell_ids = DEFAULT_SKIPPED_CELL_IDS | set(args.skip_cell_id)
    candidates: list[dict[str, Any]] = []
    for specification in args.candidate:
        if "=" not in specification:
            parser.error(f"candidate must be LABEL=NOTEBOOK: {specification}")
        label, notebook = specification.split("=", 1)
        candidates.append(
            score_candidate(
                label=label,
                notebook_path=Path(notebook).resolve(),
                challenge=challenge,
                skipped_cell_ids=skipped_cell_ids,
            )
        )

    result: dict[str, Any] = {
        "benchmark": {
            "name": "visible-training-pair-leave-one-out",
            "rule": (
                "Remove one visible training pair from each task, fit using the "
                "remaining pairs, and count exact equality of the first selected "
                "output (top1) or either of the two selected outputs (any_two)."
            ),
            "ground_truth": "held-out visible training-pair output",
            "tasks": len(challenge),
            "challenge": str(args.challenge.resolve()),
            "challenge_sha256": sha256(args.challenge),
            "skipped_cell_ids": sorted(skipped_cell_ids),
        },
        "candidates": candidates,
    }
    if len(candidates) >= 2:
        baseline = candidates[0]
        result["comparison_to_first_candidate"] = [
            {
                "candidate": candidate["label"],
                "top1_delta_rows": candidate["top1_correct"] - baseline["top1_correct"],
                "top1_delta_percentage_points": round(
                    candidate["top1_rate_percent"] - baseline["top1_rate_percent"],
                    6,
                ),
                "any_two_delta_rows": (
                    candidate["any_two_correct"] - baseline["any_two_correct"]
                ),
                "any_two_delta_percentage_points": round(
                    candidate["any_two_rate_percent"]
                    - baseline["any_two_rate_percent"],
                    6,
                ),
            }
            for candidate in candidates[1:]
        ]
    serialized = json.dumps(result, indent=2, sort_keys=True) + "\n"
    print(serialized, end="")
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(serialized, encoding="utf-8")


if __name__ == "__main__":
    main()
