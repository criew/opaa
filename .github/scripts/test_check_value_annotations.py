"""Self-check for check_value_annotations.sh: a count above the limit fails with the file list, the
count at the limit passes, a count below it passes with the lower limit; *Properties classes and
comment lines are not counted."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_value_annotations.sh"
BASH = shutil.which("bash") or "bash"
ROOT = "backend/src/main/java/io/opaa/probe/"


def run(repo: Path, limit: int) -> subprocess.CompletedProcess:
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
    env = {**os.environ, "OPAA_RATCHET_LIMIT": str(limit)}
    return subprocess.run(
        [BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True, env=env
    )


def write(repo: Path, name: str, text: str) -> None:
    target = repo / ROOT / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    write(
        tmp_path,
        "ProbeService.java",
        "package io.opaa.probe;\n\n@Service\nclass ProbeService {\n"
        "  /** Not bound through @Value(\"${x}\"). */\n"
        "  ProbeService(@Value(\"${a}\") String a, @Value(\"${b}\") String b) {}\n}\n",
    )
    write(
        tmp_path,
        "ProbeProperties.java",
        "package io.opaa.probe;\n\nrecord ProbeProperties(@Value(\"${c}\") String c) {}\n",
    )
    return tmp_path


def test_count_at_limit_passes(repo: Path) -> None:
    result = run(repo, 2)
    assert result.returncode == 0, result.stdout
    assert "at the limit of 2" in result.stdout


def test_count_above_limit_fails_with_file_list(repo: Path) -> None:
    result = run(repo, 1)
    assert result.returncode == 1
    assert "::error::2 uses" in result.stdout
    assert f"2 {ROOT}ProbeService.java" in result.stdout
    assert "ProbeProperties" not in result.stdout


def test_count_below_limit_asks_to_lower_it(repo: Path) -> None:
    result = run(repo, 4)
    assert result.returncode == 0, result.stdout
    assert "::notice::" in result.stdout
    assert "lower the limit to 2" in result.stdout

