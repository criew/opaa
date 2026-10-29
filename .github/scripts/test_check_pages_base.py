"""Self-check for check_pages_base.sh.

The publish job of daily-report.yml mirrors the site built by the report job onto its own gh-pages
checkout with rsync --delete. The guard must reject every case in which both jobs saw different
states of gh-pages, since the push would otherwise drop whatever the report job did not see.
"""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_pages_base.sh"
BASH = shutil.which("bash") or "bash"
GIT = ["git", "-c", "user.name=test", "-c", "user.email=test@example.org"]


def run_guard(site: Path, expected: str) -> subprocess.CompletedProcess:
    return subprocess.run([BASH, SCRIPT.as_posix(), site.as_posix(), expected], capture_output=True, text=True)


def commit(site: Path, name: str) -> str:
    (site / name).write_text(name, encoding="utf-8")
    subprocess.run([*GIT, "-C", site.as_posix(), "add", "-A"], check=True)
    subprocess.run([*GIT, "-C", site.as_posix(), "commit", "-q", "-m", name], check=True)
    return subprocess.run(
        ["git", "-C", site.as_posix(), "rev-parse", "HEAD"], check=True, capture_output=True, text=True
    ).stdout.strip()


@pytest.fixture
def site(tmp_path: Path) -> Path:
    directory = tmp_path / "site"
    directory.mkdir()
    subprocess.run(["git", "init", "-q", directory.as_posix()], check=True)
    return directory


def test_same_commit_passes(site: Path) -> None:
    head = commit(site, "index.html")

    assert run_guard(site, head).returncode == 0


def test_branch_missing_in_both_jobs_passes(site: Path) -> None:
    # A failed checkout of a missing gh-pages leaves an initialised repository without commits.
    assert run_guard(site, "").returncode == 0


def test_no_checkout_directory_and_no_commit_passes(tmp_path: Path) -> None:
    assert run_guard(tmp_path / "missing", "").returncode == 0


def test_report_without_checkout_but_existing_branch_fails(site: Path) -> None:
    # regression guard: a failed checkout in the report job must not wipe the published site
    commit(site, "index.html")

    result = run_guard(site, "")

    assert result.returncode == 1
    assert "::error::" in result.stderr


def test_branch_moved_between_jobs_fails(site: Path) -> None:
    seen_by_report = commit(site, "index.html")
    commit(site, "landing.html")

    assert run_guard(site, seen_by_report).returncode == 1


def test_publish_without_checkout_fails(tmp_path: Path, site: Path) -> None:
    seen_by_report = commit(site, "index.html")

    assert run_guard(tmp_path / "missing", seen_by_report).returncode == 1
