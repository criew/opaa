"""Tests for ocr_models_verify.py with the image and the network replaced by fixtures."""

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "ocr_models_verify", Path(__file__).with_name("ocr_models_verify.py"))
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)

COMMIT = "a" * 40
WEIGHTS = "1" * 64
README = b"# heron\n"
DICT = b"a\nb\n"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@pytest.fixture
def sources(monkeypatch: pytest.MonkeyPatch) -> dict:
    state = {
        "image": {
            "rapidocr": {
                "PP-OCRv6_rec_medium.onnx": [["https://example.org/rec.onnx", "2" * 64]],
                "ppocrv6_dict.txt": [["https://example.org/small/dict.txt", None],
                                     ["https://example.org/medium/dict.txt", None]],
            },
            "easyocr": {"latin_g2.pth": {"expected": "m" * 32, "actual": "m" * 32}},
            "commits": {},
        },
        "web": {
            f"{verify.HF}/api/models/docling-project/heron/tree/{COMMIT}?recursive=true": json.dumps([
                {"type": "file", "path": "README.md"},
                {"type": "file", "path": "model.safetensors", "lfs": {"oid": WEIGHTS}},
            ]).encode(),
            f"{verify.HF}/docling-project/heron/resolve/{COMMIT}/README.md": README,
            "https://example.org/small/dict.txt": b"other\n",
            "https://example.org/medium/dict.txt": DICT,
        },
    }
    monkeypatch.setattr(verify, "in_image", lambda image: state["image"])
    monkeypatch.setattr(verify, "fetch", lambda url: state["web"][url])
    return state


def run(tmp_path: Path, pins: list[tuple[str, str]], revisions: str) -> int:
    (tmp_path / "models.sha256").write_text(
        "".join(f"{digest}  {name}\n" for name, digest in pins), encoding="utf-8")
    (tmp_path / "models.revisions").write_text(revisions, encoding="utf-8")
    return verify.main(["img", str(tmp_path / "models.sha256"), str(tmp_path / "models.revisions")])


GOOD = [
    ("EasyOcr/latin_g2.pth", "e" * 64),
    ("RapidOcr/PP-OCRv6_rec_medium.onnx", "2" * 64),
    ("RapidOcr/ppocrv6_dict.txt", sha(DICT)),
    ("docling-project--heron/README.md", sha(README)),
    ("docling-project--heron/model.safetensors", WEIGHTS),
]
REVISIONS = f"# pins\ndocling-project/heron {COMMIT}\n"


def test_every_line_matching_its_source_passes(tmp_path: Path, sources: dict) -> None:
    assert run(tmp_path, GOOD, REVISIONS) == 0


@pytest.mark.parametrize("index, digest", [(1, "3" * 64), (2, "7" * 64), (3, "4" * 64),
                                           (4, "5" * 64)])
def test_a_line_differing_from_its_source_fails(
    tmp_path: Path, sources: dict, index: int, digest: str, capsys: pytest.CaptureFixture,
) -> None:
    pins = list(GOOD)
    pins[index] = (pins[index][0], digest)

    assert run(tmp_path, pins, REVISIONS) == 1
    assert "::error::" in capsys.readouterr().out


def test_an_easyocr_file_differing_from_its_md5_fails(tmp_path: Path, sources: dict) -> None:
    sources["image"]["easyocr"]["latin_g2.pth"]["actual"] = "x" * 32

    assert run(tmp_path, GOOD, REVISIONS) == 1


def test_a_repository_without_pinned_commit_fails(tmp_path: Path, sources: dict) -> None:
    assert run(tmp_path, GOOD, "") == 1


def test_a_file_without_independent_source_fails(tmp_path: Path, sources: dict) -> None:
    assert run(tmp_path, GOOD + [("Other/model.bin", "6" * 64)], REVISIONS) == 1


def test_a_pinned_repository_without_baked_file_fails(tmp_path: Path, sources: dict) -> None:
    assert run(tmp_path, GOOD, REVISIONS + f"docling-project/unused {COMMIT}\n") == 1
