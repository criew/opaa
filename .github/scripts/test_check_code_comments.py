"""Self-check for check_code_comments.sh: each rule catches its form, and the forms the rule allows
(an upstream reference, an HTML entity, the spike package, a string outside a comment) pass."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_code_comments.sh"
BASH = shutil.which("bash") or "bash"
JAVA = "backend/src/main/java/io/opaa/probe/Probe.java"


def run(repo: Path) -> subprocess.CompletedProcess:
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


def write(repo: Path, path: str, text: str) -> None:
    target = repo / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def java(comment: str) -> str:
    return f"package io.opaa.probe;\n\n/**\n * {comment}\n */\nclass Probe {{\n  String s = \"(#123)\";\n}}\n"


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    return tmp_path


@pytest.mark.parametrize(
    "comment",
    [
        "A grant opens nothing else (ADR-0036, Entscheidung 5).",
        "Workaround until spring-projects/spring-ai#2042 is fixed upstream.",
        "The path /api/v1/documents/&#123;id&#125;/content stays closed.",
        "See docs/features/spaces-and-assets.md#123 for the rule.",
        "Workaround until #2042 is fixed upstream.",
        "@deprecated Removed after #2500 replaces it.",
        "Spike #1789: a throwaway path.",
    ],
)
def test_allowed_comment_passes(repo: Path, comment: str) -> None:
    write(repo, JAVA, java(comment))
    result = run(repo)
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize(
    "comment, kind",
    [
        ("#393 code review, finding 1: with three roles a check is needed.", "Review protocol"),
        ("Deliberately not defaulted (PR #700 review, nit 8).", "Review protocol"),
        ("Validated before the write (review of PR #297).", "Review protocol"),
        ("All three come from the same table since #1931.", "History"),
        ("Mirrors the pre-#888 call site.", "History"),
        ("Aborted before #1816 changed it.", "History"),
        ("A spike aside, every library has an owner (#1073).", "Origin marker"),
        ("Every library has an owner (#1073).", "Origin marker"),
        ("#1184: top-level documents only.", "Origin marker"),
    ],
)
def test_rejected_comment_fails(repo: Path, comment: str, kind: str) -> None:
    write(repo, JAVA, java(comment))
    result = run(repo)
    assert result.returncode == 1
    assert kind in result.stdout


def test_spike_package_is_exempt(repo: Path) -> None:
    write(repo, "backend/src/main/java/io/opaa/query/spike/Spike.java", java("The spike tool (#1789)."))
    assert run(repo).returncode == 0


def test_openapi_description_and_yaml_comment_are_checked(repo: Path) -> None:
    write(repo, JAVA, java("Clean."))
    write(repo, "opaa-api/src/main/openapi/probe.yaml", "x:\n  description: >\n    Paged (#1880).\n")
    assert "probe.yaml" in run(repo).stdout
    (repo / "opaa-api/src/main/openapi/probe.yaml").unlink()
    write(repo, "backend/src/main/resources/application.yml", "a:\n  # #1050: how many candidates\n  b: 1\n")
    result = run(repo)
    assert result.returncode == 1
    assert "application.yml" in result.stdout


def test_no_comment_at_all_fails(repo: Path) -> None:
    write(repo, "README.md", "x\n")
    assert run(repo).returncode == 1
