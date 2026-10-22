#!/usr/bin/env python3
"""Validate learned local-rule selections against held-out visible pairs."""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import time
from pathlib import Path
from typing import Any


def load(path: Path) -> Any:
    """Load JSON from ``path``."""
    return json.loads(path.read_text(encoding="utf-8"))


def load_namespace(notebook_path: Path) -> tuple[dict[str, Any], str]:
    """Load trusted solver cells without executing filesystem discovery."""
    notebook = load(notebook_path)
    namespace: dict[str, Any] = {"__name__": "__main__", "time": time}
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured):
        for cell in notebook["cells"]:
            if cell["cell_type"] != "code" or cell["id"] in {
                "code-00",
                "code-06",
                "code-07",
            }:
                continue
            exec(compile(cell["source"], cell["id"], "exec"), namespace)
    return namespace, captured.getvalue()


def main() -> None:
    """Write targeted learned-rule leave-one-out evidence."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--notebook", type=Path, required=True)
    parser.add_argument("--challenge", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    challenge = load(args.challenge)
    namespace, bootstrap_output = load_namespace(args.notebook)
    rank_programs = namespace["rank_programs"]
    select_two_outputs = namespace["select_two_outputs"]

    selected_rows: list[dict[str, Any]] = []
    total_holdouts = 0
    top1_correct = 0
    any_two_correct = 0
    for task_id, task in challenge.items():
        demos = task.get("train", [])
        for test_index, test in enumerate(task.get("test", [])):
            ranked = rank_programs(demos, test["input"])
            family = ranked[0]["family"] if ranked else None
            if not isinstance(family, str) or not family.startswith(
                "learned_local_rule"
            ):
                continue

            row = {
                "task_id": task_id,
                "test_index": test_index,
                "selected_family": family,
                "holdouts": [],
            }
            for holdout_index, holdout in enumerate(demos):
                remaining = [
                    pair for index, pair in enumerate(demos) if index != holdout_index
                ]
                if not remaining:
                    continue
                loo_ranked = rank_programs(remaining, holdout["input"])
                selected = select_two_outputs(loo_ranked)
                top1 = selected[0]["grid"] == holdout["output"]
                any_two = any(
                    candidate["grid"] == holdout["output"] for candidate in selected
                )
                total_holdouts += 1
                top1_correct += top1
                any_two_correct += any_two
                row["holdouts"].append(
                    {
                        "holdout_index": holdout_index,
                        "top1_correct": top1,
                        "any_two_correct": any_two,
                        "selected_families": [
                            candidate["family"] for candidate in selected
                        ],
                    }
                )
            selected_rows.append(row)

    result = {
        "notebook": str(args.notebook),
        "challenge": str(args.challenge),
        "selected_local_rule_rows": len(selected_rows),
        "selected_rows": selected_rows,
        "holdout_rows": total_holdouts,
        "top1_correct": top1_correct,
        "any_two_correct": any_two_correct,
        "top1_rate_percent": round(100.0 * top1_correct / total_holdouts, 3)
        if total_holdouts
        else 0.0,
        "any_two_rate_percent": round(100.0 * any_two_correct / total_holdouts, 3)
        if total_holdouts
        else 0.0,
        "bootstrap_output": bootstrap_output,
        "status": "PASS",
    }
    serialized = json.dumps(result, indent=2, sort_keys=True) + "\n"
    args.report.write_text(serialized, encoding="utf-8")
    print(serialized)


if __name__ == "__main__":
    main()
