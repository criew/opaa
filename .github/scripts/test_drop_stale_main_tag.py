"""Self-check for drop_stale_main_tag.sh, the :main guard of publish-images.yml.

The workflow only runs on main and never on a pull request, so a broken filter would surface as a
:main pointing at an older commit, unnoticed. These cases run the script against a local origin:
stale and current commit, release tags without :main, exact matching, and an unreadable origin.
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "drop_stale_main_tag.sh"
BASH = shutil.which("bash") or "bash"
IMAGE = "ghcr.io/criew/opaa-backend"
OTHER = "ghcr.io/criew/opaa-frontend"


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


def commit(repo: Path, message: str) -> str:
    git(repo, "-c", "user.name=t", "-c", "user.email=t@example.org",
        "commit", "-q", "--allow-empty", "-m", message)
    return git(repo, "rev-parse", "HEAD")


@pytest.fixture
def clone(tmp_path: Path) -> Path:
    """A checkout whose origin is a local bare repository with two commits on main."""
    origin = tmp_path / "origin.git"
    work = tmp_path / "work"
    git(tmp_path, "init", "-q", "--bare", "-b", "main", origin.as_posix())
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    git(work, "remote", "add", "origin", origin.as_posix())
    commit(work, "older")
    commit(work, "newer")
    git(work, "push", "-q", "origin", "main")
    return work


def run_filter(
    cwd: Path, sha: str, tags: list[str], env: dict[str, str] | None = None
) -> subprocess.CompletedProcess:
    return subprocess.run(
        [BASH, SCRIPT.as_posix(), IMAGE, sha],
        cwd=cwd,
        input="".join(f"{tag}\n" for tag in tags),
        capture_output=True,
        text=True,
        env={**os.environ, "LS_REMOTE_RETRY_DELAY": "0", **(env or {})},
    )


def flaky_git(tmp_path: Path, failures: int) -> dict[str, str]:
    """PATH with a git whose first <failures> ls-remote calls fail; counts every ls-remote call."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    counter = tmp_path / "ls-remote-calls"
    counter.write_text("0", encoding="utf-8")
    real_git = shutil.which("git")
    wrapper = bin_dir / "git"
    wrapper.write_text(
        "#!/usr/bin/env bash\n"
        'if [[ "$1" == ls-remote ]]; then\n'
        f'  n=$(( $(cat "{counter}") + 1 )); echo "$n" >"{counter}"\n'
        f'  if (( n <= {failures} )); then echo "fatal: network down" >&2; exit 128; fi\n'
        "fi\n"
        f'exec "{real_git}" "$@"\n',
        encoding="utf-8",
    )
    wrapper.chmod(0o755)
    return {"PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}", "CALLS": counter.as_posix()}


def main_tags(sha: str) -> list[str]:
    return [f"{IMAGE}:main", f"{IMAGE}:sha-{sha}", f"{OTHER}:main", f"{OTHER}:sha-{sha}"]


def test_drops_main_for_a_commit_that_is_no_longer_the_tip(clone: Path) -> None:
    older = git(clone, "rev-parse", "HEAD~1")

    result = run_filter(clone, older, main_tags(older))

    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == [
        f"{IMAGE}:sha-{older}",
        f"{OTHER}:main",
        f"{OTHER}:sha-{older}",
    ]
    assert "::notice::" in result.stderr


def test_keeps_main_for_the_tip_of_main(clone: Path) -> None:
    tip = git(clone, "rev-parse", "HEAD")

    result = run_filter(clone, tip, main_tags(tip))

    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == main_tags(tip)
    assert result.stderr == ""


def test_passes_release_tags_without_asking_origin(tmp_path: Path) -> None:
    # No repository at all: asking origin would fail the run.
    tags = [f"{IMAGE}:1.2.3", f"{IMAGE}:1.2", f"{OTHER}:1.2.3", f"{OTHER}:1.2"]

    result = run_filter(tmp_path, "0" * 40, tags)

    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == tags


def test_matches_the_main_tag_exactly(clone: Path) -> None:
    older = git(clone, "rev-parse", "HEAD~1")
    tags = [f"{IMAGE}:main", f"{IMAGE}:mainline", f"{IMAGE}:main-x", f"{IMAGE}-x:main"]

    result = run_filter(clone, older, tags)

    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == tags[1:]


def test_retries_a_failed_query_of_origin(clone: Path, tmp_path: Path) -> None:
    older = git(clone, "rev-parse", "HEAD~1")
    env = flaky_git(tmp_path, failures=2)

    result = run_filter(clone, older, main_tags(older), env)

    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == main_tags(older)[1:]
    assert Path(env["CALLS"]).read_text(encoding="utf-8").strip() == "3"


def test_gives_up_after_three_failed_queries(clone: Path, tmp_path: Path) -> None:
    tip = git(clone, "rev-parse", "HEAD")
    env = flaky_git(tmp_path, failures=99)

    result = run_filter(clone, tip, main_tags(tip), env)

    assert result.returncode == 1
    assert result.stdout == ""
    assert "::error::" in result.stderr
    assert Path(env["CALLS"]).read_text(encoding="utf-8").strip() == "3"


def test_fails_without_tags_when_origin_cannot_be_read(clone: Path, tmp_path: Path) -> None:
    git(clone, "remote", "set-url", "origin", (tmp_path / "missing.git").as_posix())
    tip = git(clone, "rev-parse", "HEAD")

    result = run_filter(clone, tip, main_tags(tip))

    assert result.returncode == 1
    assert result.stdout == ""
    assert "::error::" in result.stderr


def test_fails_when_origin_has_no_main(clone: Path, tmp_path: Path) -> None:
    empty = tmp_path / "empty.git"
    git(tmp_path, "init", "-q", "--bare", empty.as_posix())
    git(clone, "remote", "set-url", "origin", empty.as_posix())
    git(clone, "push", "-q", "origin", "main:refs/heads/feature/refs/heads/main")
    tip = git(clone, "rev-parse", "HEAD")

    result = run_filter(clone, tip, main_tags(tip))

    assert result.returncode == 1
    assert result.stdout == ""
    assert "::error::" in result.stderr
