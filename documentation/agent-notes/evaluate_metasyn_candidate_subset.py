#!/usr/bin/env python3
"""Recompute tracked MetaSyn candidate-subset metrics.

This script intentionally depends only on tracked JSON files in this directory so the audit can be
verified even when ignored `results/` artifacts are absent from a clean checkout.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise TypeError(f"Expected object JSON in {path}")
    return value


def as_int_set(value: Any, field_name: str) -> set[int]:
    if not isinstance(value, list):
        raise TypeError(f"{field_name} must be a list")
    return {int(item) for item in value}


def sorted_ids(values: set[int]) -> list[int]:
    return sorted(values)


def safe_ratio(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return numerator / denominator


def compute_metrics(gold: dict[str, Any], predictions: dict[str, Any]) -> dict[str, Any]:
    gold_included = as_int_set(gold["gold_included_corpus_ids"], "gold_included_corpus_ids")
    hard_negatives = as_int_set(gold["hard_negative_corpus_ids"], "hard_negative_corpus_ids")
    candidates = as_int_set(gold["screening_candidate_ids"], "screening_candidate_ids")
    predicted_included = as_int_set(
        predictions["predicted_included_corpus_ids"], "predicted_included_corpus_ids"
    )
    predicted_excluded = as_int_set(
        predictions["predicted_excluded_corpus_ids"], "predicted_excluded_corpus_ids"
    )

    unknown_predictions = (predicted_included | predicted_excluded) - candidates
    missing_predictions = candidates - (predicted_included | predicted_excluded)
    contradictory_predictions = predicted_included & predicted_excluded
    if unknown_predictions or missing_predictions or contradictory_predictions:
        raise ValueError(
            "Invalid prediction partition: "
            f"unknown={sorted_ids(unknown_predictions)} "
            f"missing={sorted_ids(missing_predictions)} "
            f"contradictory={sorted_ids(contradictory_predictions)}"
        )

    true_positives = predicted_included & gold_included
    false_negatives = gold_included - predicted_included
    false_positives = predicted_included - gold_included
    true_negatives = predicted_excluded & hard_negatives
    correct_candidates = true_positives | true_negatives
    synthesis_direction_match = (
        str(predictions.get("predicted_synthesis_direction", "")).casefold()
        == str(gold.get("gold_effect_direction", "")).casefold()
    )

    return {
        "dataset": gold.get("dataset"),
        "task_id": gold.get("task_id"),
        "run_id": predictions.get("run_id"),
        "included_true_positives": sorted_ids(true_positives),
        "included_false_negatives": sorted_ids(false_negatives),
        "included_false_positives": sorted_ids(false_positives),
        "hard_negative_true_negatives": sorted_ids(true_negatives),
        "included_recall": safe_ratio(len(true_positives), len(gold_included)),
        "included_precision": safe_ratio(len(true_positives), len(predicted_included)),
        "hard_negative_rejection_accuracy": safe_ratio(len(true_negatives), len(hard_negatives)),
        "candidate_level_accuracy": safe_ratio(len(correct_candidates), len(candidates)),
        "synthesis_direction_match": synthesis_direction_match,
        "is_full_leaderboard_run": False,
        "scoring_scope": "candidate_subset_with_all_matched_positive_corpus_ids",
    }


def verify_file_hashes(predictions: dict[str, Any]) -> None:
    for path_key, hash_key in (
        ("input_path", "input_sha256"),
        ("output_path", "output_sha256"),
    ):
        path_value = predictions.get(path_key)
        expected = predictions.get(hash_key)
        if not isinstance(path_value, str) or not isinstance(expected, str):
            raise ValueError(f"{path_key} and {hash_key} must be present for hash verification")
        path = Path(path_value)
        if not path.is_file():
            raise FileNotFoundError(f"Cannot verify {hash_key}; file does not exist: {path}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f"SHA-256 mismatch for {path}: expected {expected}, got {actual}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--gold",
        type=Path,
        default=Path(__file__).with_name("tier4-real-metasyn-task24-gold.json"),
    )
    parser.add_argument(
        "--predictions",
        type=Path,
        default=Path(__file__).with_name("tier4-real-metasyn-task24-predictions.json"),
    )
    parser.add_argument("--pretty", action="store_true")
    parser.add_argument(
        "--verify-files",
        action="store_true",
        help="Also verify input/output SHA-256 hashes when ignored runtime files are present.",
    )
    args = parser.parse_args()

    predictions = load_json(args.predictions)
    if args.verify_files:
        verify_file_hashes(predictions)
    metrics = compute_metrics(load_json(args.gold), predictions)
    print(json.dumps(metrics, indent=2 if args.pretty else None, sort_keys=True))


if __name__ == "__main__":
    main()
