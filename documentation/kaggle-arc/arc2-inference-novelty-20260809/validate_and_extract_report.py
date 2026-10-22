#!/usr/bin/env python3
"""Publish, validate, and extract the ARC2 EvoMolior HTML report."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from typing import Any


HYPOTHESIS_FIELDS = (
    "id",
    "title",
    "novelty_class",
    "mechanism",
    "evidence_paths",
    "minimum_slice",
    "metric",
    "budget",
    "falsifier",
    "kill_gate",
    "promotion_gate",
    "priority",
    "expected_information_value",
)
REQUIRED_SECTION_IDS = {
    "decision",
    "state",
    "diagnosis",
    "hypotheses",
    "backlog",
    "kaggle",
    "portfolio",
    "risk",
    "roadmap",
    "trace",
    "recommendation",
}
PATH_CORRECTIONS = {
    "ARC2_A2_2_INTERFACE_FREEZE.md": "A2_2_INTERFACE_FREEZE.md",
    "Inference, Novelty & Kaggle-Safe": "Inference, Novelty &amp; Kaggle-Safe",
}


class ReportParser(HTMLParser):
    """Collects structural and embedded-payload evidence without rewriting HTML."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=False)
        self.tag_counts: dict[str, int] = {}
        self.ids: set[str] = set()
        self.html_lang: str | None = None
        self.has_charset = False
        self.has_viewport = False
        self.external_dependencies: list[str] = []
        self.interaction_controls: set[str] = set()
        self.inline_scripts: list[str] = []
        self.payloads: dict[str, list[str]] = {}
        self._script_id: str | None = None
        self._script_data: list[str] = []
        self._script_is_payload = False
        self._inside_script = False

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        values = dict(attrs)
        self.tag_counts[tag] = self.tag_counts.get(tag, 0) + 1
        element_id = values.get("id")
        if element_id:
            self.ids.add(element_id)
        if tag == "html":
            self.html_lang = values.get("lang")
        if tag == "meta":
            self.has_charset = self.has_charset or bool(values.get("charset"))
            self.has_viewport = self.has_viewport or values.get("name") == "viewport"
        if tag == "input" and element_id:
            self.interaction_controls.add(element_id)
        if tag == "select" and element_id:
            self.interaction_controls.add(element_id)
        if tag == "script":
            self._inside_script = True
            self._script_id = element_id
            self._script_data = []
            self._script_is_payload = values.get("type") == "application/json"
            source = values.get("src")
            if source:
                self.external_dependencies.append(f"script:{source}")
        if tag == "link":
            href = values.get("href")
            if href and "stylesheet" in (values.get("rel") or ""):
                self.external_dependencies.append(f"stylesheet:{href}")
        if tag in {"img", "audio", "video", "iframe"}:
            source = values.get("src")
            if source:
                self.external_dependencies.append(f"{tag}:{source}")

    def handle_data(self, data: str) -> None:
        if self._inside_script:
            self._script_data.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag != "script":
            return
        script = "".join(self._script_data)
        if self._script_is_payload and self._script_id:
            self.payloads.setdefault(self._script_id, []).append(script)
        elif script.strip():
            self.inline_scripts.append(script)
        self._script_id = None
        self._script_data = []
        self._script_is_payload = False
        self._inside_script = False


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def publish_report(source: Path, destination: Path) -> dict[str, int]:
    text = source.read_text(encoding="utf-8")
    corrections: dict[str, int] = {}
    for incorrect, corrected in PATH_CORRECTIONS.items():
        count = text.count(incorrect)
        corrections[f"{incorrect} -> {corrected}"] = count
        text = text.replace(incorrect, corrected)
    destination.write_text(text, encoding="utf-8")
    return corrections


def extract_payload(parser: ReportParser, payload_id: str) -> dict[str, Any]:
    payloads = parser.payloads.get(payload_id, [])
    require(len(payloads) == 1, f"Expected exactly one {payload_id} payload")
    return json.loads(payloads[0])


def validate_hypotheses(payload: dict[str, Any]) -> tuple[list[dict[str, Any]], int]:
    require(
        payload.get("schema_version") == "arc2-hypothesis-register-v1",
        "Unexpected hypothesis-register schema",
    )
    hypotheses = payload.get("hypotheses")
    require(isinstance(hypotheses, list), "Hypotheses must be an array")
    require(len(hypotheses) >= 12, "At least 12 hypotheses are required")
    priorities: list[int] = []
    for hypothesis in hypotheses:
        require(isinstance(hypothesis, dict), "Each hypothesis must be an object")
        missing = set(HYPOTHESIS_FIELDS) - hypothesis.keys()
        require(not missing, f"Hypothesis is missing fields: {sorted(missing)}")
        require(
            isinstance(hypothesis["evidence_paths"], list)
            and hypothesis["evidence_paths"],
            f"{hypothesis['id']} requires evidence paths",
        )
        priorities.append(hypothesis["priority"])
    require(len(priorities) == len(set(priorities)), "Hypothesis priorities must be unique")
    novel_count = sum(
        hypothesis["novelty_class"].strip().lower() == "novel"
        for hypothesis in hypotheses
    )
    require(novel_count >= 6, "At least six hypotheses must be classified as novel")
    return hypotheses, novel_count


def validate_evidence(
    payload: dict[str, Any],
    hypotheses: list[dict[str, Any]],
    source_roots: list[Path],
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    require(
        payload.get("schema_version") == "arc2-evidence-index-v1",
        "Unexpected evidence-index schema",
    )
    sources = payload.get("sources")
    require(isinstance(sources, list) and sources, "Evidence sources must be non-empty")
    indexed_paths = {
        source["path"]
        for source in sources
        if isinstance(source, dict) and isinstance(source.get("path"), str)
    }
    hypothesis_paths = {
        path
        for hypothesis in hypotheses
        for path in hypothesis["evidence_paths"]
    }
    require(
        hypothesis_paths <= indexed_paths,
        f"Hypothesis evidence is absent from the evidence index: "
        f"{sorted(hypothesis_paths - indexed_paths)}",
    )
    resolved: dict[str, str] = {}
    for path in sorted(indexed_paths):
        match = next(
            (root / path for root in source_roots if (root / path).is_file()),
            None,
        )
        require(match is not None, f"Evidence path does not exist: {path}")
        resolved[path] = str(match)
    return sources, resolved


def write_backlog(path: Path, hypotheses: list[dict[str, Any]]) -> None:
    fields = (
        "priority",
        "id",
        "title",
        "novelty_class",
        "mechanism",
        "minimum_slice",
        "metric",
        "budget",
        "falsifier",
        "kill_gate",
        "promotion_gate",
        "expected_information_value",
        "evidence_paths",
    )
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for hypothesis in sorted(hypotheses, key=lambda item: item["priority"]):
            row = {field: hypothesis[field] for field in fields}
            row["evidence_paths"] = "; ".join(hypothesis["evidence_paths"])
            writer.writerow(row)


def main() -> None:
    argument_parser = argparse.ArgumentParser()
    argument_parser.add_argument("--source-report", type=Path, required=True)
    argument_parser.add_argument("--report", type=Path, required=True)
    argument_parser.add_argument("--output-dir", type=Path, required=True)
    argument_parser.add_argument(
        "--source-root",
        action="append",
        type=Path,
        required=True,
    )
    args = argument_parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    corrections = publish_report(args.source_report, args.report)
    text = args.report.read_text(encoding="utf-8")
    parser = ReportParser()
    parser.feed(text)
    parser.close()

    require(
        re.match(r"^\s*<!doctype\s+html>", text, flags=re.IGNORECASE) is not None,
        "Report must begin with an HTML5 doctype",
    )
    require(parser.html_lang == "en", "Report requires html[lang=en]")
    require(parser.has_charset, "Report requires a charset declaration")
    require(parser.has_viewport, "Report requires a viewport declaration")
    require(parser.tag_counts.get("nav", 0) >= 1, "Report requires navigation")
    require(parser.tag_counts.get("main", 0) == 1, "Report requires exactly one main")
    require(parser.tag_counts.get("section", 0) >= 10, "Report requires semantic sections")
    require(parser.tag_counts.get("article", 0) >= 12, "Report requires hypothesis cards")
    require(parser.tag_counts.get("style", 0) >= 1, "Report requires inline presentation")
    require(not parser.external_dependencies, "Report has external asset dependencies")
    require(
        REQUIRED_SECTION_IDS <= parser.ids,
        f"Missing report sections: {sorted(REQUIRED_SECTION_IDS - parser.ids)}",
    )
    require(
        {"q", "novelty"} <= parser.interaction_controls,
        "Report requires hypothesis filtering controls",
    )
    require(
        any("addEventListener" in script for script in parser.inline_scripts),
        "Report requires built-in interaction",
    )

    hypothesis_payload = extract_payload(parser, "hypothesis-register")
    evidence_payload = extract_payload(parser, "evidence-index")
    hypotheses, novel_count = validate_hypotheses(hypothesis_payload)
    sources, resolved_sources = validate_evidence(
        evidence_payload,
        hypotheses,
        args.source_root,
    )

    hypothesis_path = args.output_dir / "hypothesis-register.json"
    evidence_path = args.output_dir / "evidence-index.json"
    backlog_path = args.output_dir / "experiment-backlog.csv"
    hypothesis_path.write_text(
        json.dumps(hypothesis_payload, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    evidence_path.write_text(
        json.dumps(evidence_payload, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    write_backlog(backlog_path, hypotheses)

    receipt = {
        "schema_version": "arc2-report-validation-v1",
        "report": str(args.report),
        "report_sha256": sha256(args.report),
        "source_report": str(args.source_report),
        "source_report_sha256": sha256(args.source_report),
        "path_corrections": corrections,
        "html5_doctype": True,
        "standalone_no_external_assets": True,
        "interactive": True,
        "required_section_count": len(REQUIRED_SECTION_IDS),
        "hypothesis_count": len(hypotheses),
        "novel_hypothesis_count": novel_count,
        "evidence_source_count": len(sources),
        "resolved_evidence_paths": resolved_sources,
        "embedded_payloads_parse": True,
        "generated_files": {
            "hypothesis_register": str(hypothesis_path),
            "evidence_index": str(evidence_path),
            "experiment_backlog": str(backlog_path),
        },
    }
    validation_path = args.output_dir / "report-validation.json"
    validation_path.write_text(
        json.dumps(receipt, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(receipt, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
