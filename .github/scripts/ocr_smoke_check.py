#!/usr/bin/env python3
"""Checks the answer of opaa-ocr to the test page ocr/test/scan-bescheid.pdf, as the OCR smoke test
(.github/scripts/ocr_smoke_test.sh) received it from /v1/convert/source.

Passes when the conversion succeeded and the DoclingDocument JSON holds the expected text with both
paragraph signs, the fee table as a table with its cells, and a frame (page and bounding box) for
every text and table element. Prints what it found; exit code 0 on success, 1 otherwise.

Usage: ocr_smoke_check.py <response.json>
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

# Phrases of the test page (ocr/test/make_fixture.py) that must come back verbatim, paragraph signs
# included; only the spacing around them may differ.
EXPECTED_TEXT = [
    r"Gebührenfestsetzung",
    r"§\s*12\s*Abs\.\s*3",
    r"§\s*4\s*Nr\.\s*2",
    r"Kommunalabgabengesetzes",
    r"Widerspruch",
]
EXPECTED_CELLS = [
    r"Amtshandlung",
    r"Beglaubigung einer Abschrift",
    r"12,50\s*Euro",
    r"Ausstellung einer Bescheinigung",
    r"8,00\s*Euro",
]


def frame_problems(kind: str, index: int, item: dict) -> list[str]:
    provenance = item.get("prov") or []
    if not provenance:
        return [f"{kind}[{index}] has no provenance"]
    problems = []
    for entry in provenance:
        bbox = entry.get("bbox") or {}
        values = [bbox.get(side) for side in ("l", "t", "r", "b")]
        if entry.get("page_no") != 1:
            problems.append(f"{kind}[{index}] names page {entry.get('page_no')}, expected 1")
        if not all(isinstance(value, (int, float)) for value in values):
            problems.append(f"{kind}[{index}] has no complete bounding box: {bbox}")
        elif values[0] >= values[2]:
            problems.append(f"{kind}[{index}] has an empty bounding box: {bbox}")
    return problems


def check(response: dict) -> list[str]:
    problems = []
    if response.get("status") != "success":
        problems.append(f"status is {response.get('status')!r}, errors: {response.get('errors')}")
    document = (response.get("document") or {}).get("json_content")
    if not isinstance(document, dict):
        return problems + ["the response carries no DoclingDocument under document.json_content"]

    texts = document.get("texts") or []
    tables = document.get("tables") or []
    text = "\n".join(item.get("text", "") for item in texts)
    cells = [cell.get("text", "") for table in tables for cell in (table.get("data") or {}).get("table_cells", [])]
    print(f"{len(texts)} text elements, {len(tables)} tables, {len(cells)} table cells")
    print("recognized text:\n" + text)
    print("table cells: " + " | ".join(cells))

    for pattern in EXPECTED_TEXT:
        if not re.search(pattern, text):
            problems.append(f"expected text /{pattern}/ not found")
    if not tables:
        problems.append("no table recognized")
    for pattern in EXPECTED_CELLS:
        if not any(re.search(pattern, cell) for cell in cells):
            problems.append(f"expected table cell /{pattern}/ not found")
    for index, item in enumerate(texts):
        problems += frame_problems("texts", index, item)
    for index, item in enumerate(tables):
        problems += frame_problems("tables", index, item)
    return problems


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    response = json.loads(Path(argv[0]).read_text(encoding="utf-8"))
    problems = check(response)
    for problem in problems:
        print(f"FAIL: {problem}", file=sys.stderr)
    if not problems:
        print("OK: text with § and the table recognized, every element carries a frame")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
