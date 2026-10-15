"""Self-check for check_data_impact.sh: a matched path without label and without "Keine Wirkung"
fails, the label or a ticked "Keine Wirkung" passes, a ticked impact without label fails, unmatched
paths pass; every pattern of the real path list matches a tracked file."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent
SCRIPT = SCRIPTS / "check_data_impact.sh"
REPO_ROOT = SCRIPTS.parent.parent
PATHS = REPO_ROOT / ".github" / "data-impact-paths.txt"
BASH = shutil.which("bash") or "bash"

SECTION = "## Wirkung auf Daten und Behördenunterlagen\n\n"
NO_IMPACT = (
    SECTION + "- [ ] Neue Sichtbarkeit\n"
    "- [x] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen\n"
    "\n## Checkliste\n\n- [x] Tests bestehen lokal\n"
)
IMPACT = SECTION + "- [X] Neue Sichtbarkeit: Daten werden für weitere Rollen sichtbar\n- [ ] Keine Wirkung\n"
UNTICKED = SECTION + "- [ ] Neue Sichtbarkeit\n- [ ] Keine Wirkung\n\n## Checkliste\n\n- [x] Keine Wirkung\n"


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=repo, check=True, capture_output=True, text=True
    ).stdout.strip()


def write(repo: Path, name: str, text: str = "x\n") -> None:
    target = repo / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    git(tmp_path, "init", "-q")
    git(tmp_path, "config", "user.email", "probe@example.org")
    git(tmp_path, "config", "user.name", "probe")
    write(
        tmp_path,
        ".github/data-impact-paths.txt",
        "# comment\n\nbackend/src/main/resources/db/changelog/**\nbackend/src/main/java/**/*Properties.java\n",
    )
    write(tmp_path, "README.md")
    git(tmp_path, "add", "-A")
    git(tmp_path, "commit", "-q", "-m", "base")
    return tmp_path


def change(repo: Path, *names: str) -> None:
    for name in names:
        write(repo, name, "changed\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "change")


def run(repo: Path, body: str = "", label: bool = False) -> subprocess.CompletedProcess:
    env = {**os.environ, "HAS_LABEL": "true" if label else "false", "PR_BODY": body}
    env.pop("GITHUB_STEP_SUMMARY", None)
    return subprocess.run(
        [BASH, SCRIPT.as_posix(), "HEAD^1", "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
        env=env,
    )


def test_matched_path_without_label_or_statement_fails(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/2026-10-15-new.yaml")
    result = run(repo)
    assert result.returncode == 1
    assert "::error::Files with possible data impact" in result.stdout
    assert "db/changelog/x/2026-10-15-new.yaml" in result.stdout


def test_double_star_spans_directories(repo: Path) -> None:
    change(repo, "backend/src/main/java/io/opaa/chat/ChatNoteProperties.java")
    assert run(repo).returncode == 1


def test_label_passes(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    result = run(repo, label=True)
    assert result.returncode == 0
    assert "::notice::" in result.stdout


def test_ticked_no_impact_passes_with_warning(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    result = run(repo, body=NO_IMPACT)
    assert result.returncode == 0
    assert "::warning::" in result.stdout


def test_crlf_body_is_read(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    assert run(repo, body=NO_IMPACT.replace("\n", "\r\n")).returncode == 0


def test_no_impact_ticked_outside_section_does_not_count(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    assert run(repo, body=UNTICKED).returncode == 1


def test_ticked_impact_without_label_fails_even_without_matched_path(repo: Path) -> None:
    change(repo, "frontend/src/features/x.tsx")
    result = run(repo, body=IMPACT)
    assert result.returncode == 1
    assert "label data-impact is missing" in result.stdout


def test_ticked_impact_with_label_passes(repo: Path) -> None:
    change(repo, "frontend/src/features/x.tsx")
    assert run(repo, body=IMPACT, label=True).returncode == 0


def test_unmatched_path_passes(repo: Path) -> None:
    change(repo, "frontend/src/features/x.tsx", "docs/x.md")
    result = run(repo)
    assert result.returncode == 0
    assert "No changed file matches" in result.stdout


def test_every_pattern_matches_a_tracked_file() -> None:
    patterns = [
        line.strip()
        for line in PATHS.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    assert patterns
    stale = [
        p
        for p in patterns
        if not git(REPO_ROOT, "ls-files", "--", f":(glob){p}")
    ]
    assert stale == [], f"patterns without a tracked file in {PATHS.name}: {stale}"
