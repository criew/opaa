"""Self-check for the base image pin of the OCR image (ocr/Dockerfile).

The base image brings the Python environment and the models of opaa-ocr, so it carries an exact
version and the registry's index digest, and Renovate keeps both current with the rule for
ocr/Dockerfile in renovate.json5. A drift between the FROM line and that rule silently ends the
updates; these cases catch it in the PR that causes it.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
DOCKERFILE = ROOT / "ocr" / "Dockerfile"
RENOVATE_CONFIG = ROOT / "renovate.json5"
IMAGE = "ghcr.io/docling-project/docling-serve-cpu"
PINNED = re.compile(rf"^FROM {re.escape(IMAGE)}:v\d+\.\d+\.\d+@sha256:[0-9a-f]{{64}}( AS \w+)?$")


def from_lines() -> list[str]:
    return [line for line in DOCKERFILE.read_text(encoding="utf-8").splitlines()
            if line.startswith("FROM ")]


def test_the_only_base_image_carries_version_and_digest() -> None:
    lines = from_lines()

    assert len(lines) == 1, lines
    assert PINNED.match(lines[0]), lines[0]


def test_renovate_keeps_version_and_digest_and_never_merges_alone() -> None:
    blocks = [block for block in re.findall(r"\{[^{}]*\}", RENOVATE_CONFIG.read_text("utf-8"))
              if "'ocr/Dockerfile'" in block]

    assert len(blocks) == 1, blocks
    block = blocks[0]
    assert f"'{IMAGE}'" in block
    assert "pinDigests: true" in block
    assert "automerge: false" in block
    assert "minimumReleaseAge: '3 days'" in block
