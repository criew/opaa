"""Self-check for check_adr_style.sh: the appended-block pattern must catch the plural heading
"## Nachträge" and a bold "Nachtrag" lead-in inside an indented block quote, which a pattern
anchored at column 0 on the singular misses."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_adr_style.sh"
BASH = shutil.which("bash") or "bash"
CLEAN = (
    "# ADR-0999: Probe\n\n## Status\n\nAkzeptiert · zuletzt geändert 2026-10-14\n\n"
    "## Kontext\n\nx\n\n## Entscheidung\n\n### 1. A\n\nx\n"
)


def run_check(repo: Path) -> subprocess.CompletedProcess:
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    (tmp_path / "docs" / "decisions").mkdir(parents=True)
    return tmp_path


def test_clean_adr_passes(repo: Path) -> None:
    (repo / "docs/decisions/0999-probe.md").write_text(CLEAN, encoding="utf-8")
    assert run_check(repo).returncode == 0


@pytest.mark.parametrize("block", ["## Nachträge\n\nx\n", "- Punkt\n  > **Nachtrag (#1):** x\n"])
def test_rejects_plural_heading_and_indented_quote(repo: Path, block: str) -> None:
    (repo / "docs/decisions/0999-probe.md").write_text(CLEAN + "\n" + block, encoding="utf-8")
    result = run_check(repo)
    assert result.returncode == 1
    assert "appended block" in result.stdout


def test_rejects_proposed_status(repo: Path) -> None:
    (repo / "docs/decisions/0999-probe.md").write_text(
        CLEAN.replace("Akzeptiert ·", "Vorgeschlagen ·"), encoding="utf-8"
    )
    result = run_check(repo)
    assert result.returncode == 1
    assert "Akzeptiert" in result.stdout


def test_rejects_change_log_section(repo: Path) -> None:
    (repo / "docs/decisions/0999-probe.md").write_text(
        CLEAN + "\n## Änderungsverlauf\n\n| a |\n|---|\n", encoding="utf-8"
    )
    result = run_check(repo)
    assert result.returncode == 1
    assert "Änderungsverlauf" in result.stdout
