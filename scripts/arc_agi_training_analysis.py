#!/usr/bin/env python3
"""Summarize visible ARC training-pair structure without running a solver."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


def load(path: Path) -> Any:
    """Load JSON from ``path``."""
    return json.loads(path.read_text(encoding="utf-8"))


def rotate(grid: list[list[int]]) -> list[list[int]]:
    """Rotate a grid clockwise."""
    return [list(row) for row in zip(*grid[::-1])]


def transforms(grid: list[list[int]]) -> dict[str, list[list[int]]]:
    """Return the basic geometry transforms."""
    result: dict[str, list[list[int]]] = {}
    current = grid
    for index in range(4):
        result[f"rotate_{index * 90}"] = current
        result[f"rotate_{index * 90}_reflect"] = [row[::-1] for row in current]
        current = rotate(current)
    result["transpose"] = [list(row) for row in zip(*grid)]
    return result


def shape(grid: list[list[int]]) -> tuple[int, int]:
    """Return a grid's height and width."""
    return len(grid), len(grid[0]) if grid else 0


def consistent_color_map(
    source: list[list[int]], target: list[list[int]]
) -> bool:
    """Check whether output colors are a consistent per-color relabeling."""
    if shape(source) != shape(target):
        return False
    mapping: dict[int, int] = {}
    for source_row, target_row in zip(source, target):
        for source_cell, target_cell in zip(source_row, target_row):
            if source_cell in mapping and mapping[source_cell] != target_cell:
                return False
            mapping[source_cell] = target_cell
    return True


def summarize(challenge: dict[str, Any]) -> dict[str, Any]:
    """Compute structural counts over all visible training pairs."""
    pair_count = 0
    same_shape = 0
    color_maps = 0
    geometric = Counter()
    input_shapes = Counter()
    output_shapes = Counter()
    changed_pairs = 0
    for task in challenge.values():
        for pair in task.get("train", []):
            source, target = pair["input"], pair["output"]
            pair_count += 1
            input_shapes[shape(source)] += 1
            output_shapes[shape(target)] += 1
            if source != target:
                changed_pairs += 1
            if shape(source) == shape(target):
                same_shape += 1
                color_maps += consistent_color_map(source, target)
            for name, transformed in transforms(source).items():
                if transformed == target:
                    geometric[name] += 1
    return {
        "tasks": len(challenge),
        "training_pairs": pair_count,
        "changed_pairs": changed_pairs,
        "same_shape_pairs": same_shape,
        "consistent_color_map_pairs": color_maps,
        "geometric_exact_pairs": dict(geometric),
        "common_input_shapes": [
            {"shape": list(grid_shape), "count": count}
            for grid_shape, count in input_shapes.most_common(12)
        ],
        "common_output_shapes": [
            {"shape": list(grid_shape), "count": count}
            for grid_shape, count in output_shapes.most_common(12)
        ],
    }


def main() -> None:
    """Write the structural summary."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--challenge", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(load(args.challenge))
    args.report.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
