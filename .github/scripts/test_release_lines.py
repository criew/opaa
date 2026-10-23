"""Self-check for the release scripts that know about support lines (docs/releases.md).

release_origin.sh is the guard in publish-images.yml that admits a tag only on main or on the
maintenance branch of its own line; release_line_info.sh decides whether a release becomes
"Latest" on GitHub and which release its generated notes start from. Only a release runs them and
no pull request does, so these cases run both against a local history with two lines:

    main:        A (v0.1.0) - B (v0.2.0) - C (v0.3.0) - D
    release/0.2:              B - F (v0.2.1)
    feature/x:                          C - X
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent
ORIGIN = SCRIPTS / "release_origin.sh"
LINE_INFO = SCRIPTS / "release_line_info.sh"
BASH = shutil.which("bash") or "bash"


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


def commit(repo: Path, message: str) -> str:
    git(repo, "-c", "user.name=t", "-c", "user.email=t@example.org",
        "commit", "-q", "--allow-empty", "-m", message)
    return git(repo, "rev-parse", "HEAD")


def tag(repo: Path, name: str, sha: str) -> None:
    git(repo, "tag", name, sha)


@pytest.fixture
def repo(tmp_path: Path) -> dict[str, object]:
    """A checkout like the one in publish-images.yml: every branch of origin fetched, every tag."""
    origin = tmp_path / "origin.git"
    work = tmp_path / "work"
    git(tmp_path, "init", "-q", "--bare", "-b", "main", origin.as_posix())
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    git(work, "remote", "add", "origin", origin.as_posix())
    shas = {"A": commit(work, "A"), "B": commit(work, "B")}
    git(work, "switch", "-q", "-c", "release/0.2")
    shas["F"] = commit(work, "F fix")
    git(work, "switch", "-q", "main")
    shas["C"] = commit(work, "C")
    shas["D"] = commit(work, "D")
    git(work, "switch", "-q", "-c", "feature/x", shas["C"])
    shas["X"] = commit(work, "X")
    git(work, "switch", "-q", "main")
    tag(work, "v0.1.0", shas["A"])
    tag(work, "v0.2.0", shas["B"])
    tag(work, "v0.2.1", shas["F"])
    tag(work, "v0.3.0", shas["C"])
    git(work, "push", "-q", "origin", "main", "release/0.2", "feature/x", "--tags")
    git(work, "fetch", "-q", "origin")
    return {"work": work, **shas}


def run(script: Path, cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [BASH, script.as_posix(), *args], cwd=cwd, capture_output=True, text=True,
        env={**os.environ},
    )


def info(cwd: Path, release_tag: str, sha: str) -> dict[str, str]:
    result = run(LINE_INFO, cwd, release_tag, sha)
    assert result.returncode == 0, result.stderr
    return dict(line.split("=", 1) for line in result.stdout.splitlines())


# release_origin.sh


@pytest.mark.parametrize(
    ("release_tag", "at", "branch"),
    [
        ("v0.3.0", "C", "main"),
        ("v0.4.0-rc.1", "D", "main"),
        ("v0.2.1", "F", "release/0.2"),
        ("v0.2.2-rc.1", "F", "release/0.2"),
        # The branch point lies on both; main is named first.
        ("v0.2.0", "B", "main"),
    ],
)
def test_origin_admits_main_and_the_branch_of_the_own_line(
    repo: dict, release_tag: str, at: str, branch: str
) -> None:
    result = run(ORIGIN, repo["work"], release_tag, repo[at])

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == branch


@pytest.mark.parametrize(
    ("release_tag", "at"),
    [
        # A tag of another line on a maintenance branch.
        ("v0.3.1", "F"),
        ("v1.2.1", "F"),
        # A commit on a feature branch only.
        ("v0.3.1", "X"),
        # The line exists, but the commit is not on its branch.
        ("v0.2.3", "X"),
    ],
)
def test_origin_refuses_a_commit_outside_main_and_the_own_line(
    repo: dict, release_tag: str, at: str
) -> None:
    result = run(ORIGIN, repo["work"], release_tag, repo[at])

    assert result.returncode == 1
    assert result.stdout == ""
    assert "::error::" in result.stderr


def test_origin_refuses_a_patch_on_main_that_contains_a_newer_line(repo: dict) -> None:
    # D on main contains v0.3.0: an image 0.2.1 from there would carry the functions of 0.3.
    result = run(ORIGIN, repo["work"], "v0.2.1", repo["D"])

    assert result.returncode == 1
    assert "v0.3.0" in result.stderr


def test_origin_refuses_a_pre_release_below_a_contained_final_release(repo: dict) -> None:
    result = run(ORIGIN, repo["work"], "v0.3.0-rc.2", repo["D"])

    assert result.returncode == 1
    assert "::error::" in result.stderr


def branch_off(repo: dict, branch: str, at: str) -> str:
    """Pushes a new commit on top of <at> as origin/<branch> and returns it."""
    work = repo["work"]
    git(work, "switch", "-q", "-c", branch, repo[at])
    sha = commit(work, f"{branch} only")
    git(work, "switch", "-q", "main")
    git(work, "push", "-q", "origin", branch)
    git(work, "fetch", "-q", "origin")
    return sha


@pytest.mark.parametrize("release_tag", ["v0.5.0", "v0.5.0-rc.1"])
def test_origin_refuses_to_open_a_line_on_a_maintenance_branch(repo: dict, release_tag: str) -> None:
    # A line opens on main; release/X.Y only carries its patch releases.
    sha = branch_off(repo, "release/0.5", "D")

    result = run(ORIGIN, repo["work"], release_tag, sha)

    assert result.returncode == 1
    assert "::error::" in result.stderr


def test_origin_refuses_a_patch_on_a_branch_without_the_lines_first_release(repo: dict) -> None:
    # release/0.4 forked from main without a v0.4.0 in its history.
    sha = branch_off(repo, "release/0.4", "D")

    result = run(ORIGIN, repo["work"], "v0.4.1", sha)

    assert result.returncode == 1
    assert "v0.4.0" in result.stderr


def test_origin_matches_the_line_exactly(repo: dict) -> None:
    work = repo["work"]
    git(work, "push", "-q", "origin", f"{repo['F']}:refs/heads/release/0.20")
    git(work, "push", "-q", "origin", ":release/0.2")
    git(work, "fetch", "-q", "--prune", "origin")

    result = run(ORIGIN, work, "v0.2.1", repo["F"])

    assert result.returncode == 1
    assert "::error::" in result.stderr


def test_origin_fails_without_main(repo: dict) -> None:
    work = repo["work"]
    git(work, "update-ref", "-d", "refs/remotes/origin/main")

    result = run(ORIGIN, work, "v0.3.0", repo["C"])

    assert result.returncode == 2
    assert "::error::" in result.stderr


def test_origin_refuses_a_malformed_tag(repo: dict) -> None:
    result = run(ORIGIN, repo["work"], "release-0.3", repo["C"])

    assert result.returncode == 2


# release_line_info.sh


def test_patch_of_an_older_line_is_not_latest_and_compares_within_its_line(repo: dict) -> None:
    assert info(repo["work"], "v0.2.1", repo["F"]) == {"latest": "false", "previous": "v0.2.0"}


def test_highest_final_release_is_latest_and_compares_with_the_previous_final_on_main(
    repo: dict,
) -> None:
    # v0.2.1 is higher than v0.2.0 but lies on release/0.2, not in v0.3.0.
    assert info(repo["work"], "v0.3.0", repo["C"]) == {"latest": "true", "previous": "v0.2.0"}


def test_patch_of_the_newest_line_stays_latest(repo: dict) -> None:
    work = repo["work"]
    tag(work, "v0.3.1", repo["D"])

    assert info(work, "v0.3.1", repo["D"]) == {"latest": "true", "previous": "v0.3.0"}


def test_patch_without_code_change_compares_with_the_release_on_the_same_commit(
    repo: dict,
) -> None:
    work = repo["work"]
    tag(work, "v0.2.2", repo["F"])

    assert info(work, "v0.2.2", repo["F"]) == {"latest": "false", "previous": "v0.2.1"}


def test_pre_release_is_never_latest_and_pre_release_tags_never_count(repo: dict) -> None:
    work = repo["work"]
    tag(work, "v0.4.0-rc.1", repo["D"])
    tag(work, "v0.3.1-rc.1", repo["D"])

    assert info(work, "v0.4.0-rc.1", repo["D"]) == {"latest": "false", "previous": "v0.3.0"}

    tag(work, "v0.3.1", repo["D"])
    assert info(work, "v0.3.1", repo["D"]) == {"latest": "true", "previous": "v0.3.0"}


def test_versions_compare_by_number_not_by_text(repo: dict) -> None:
    work = repo["work"]
    sha = commit(work, "E")
    tag(work, "v0.9.0", repo["D"])
    tag(work, "v0.10.0", sha)

    assert info(work, "v0.10.0", sha) == {"latest": "true", "previous": "v0.9.0"}
    assert info(work, "v0.9.0", repo["D"])["latest"] == "false"


def test_first_release_has_no_previous_release(repo: dict) -> None:
    work = repo["work"]
    for name in ("v0.2.0", "v0.2.1", "v0.3.0"):
        git(work, "tag", "-d", name)

    assert info(work, "v0.1.0", repo["A"]) == {"latest": "true", "previous": ""}


def test_line_info_refuses_a_malformed_tag(repo: dict) -> None:
    result = run(LINE_INFO, repo["work"], "v0.3", repo["C"])

    assert result.returncode == 2


# publish-images.yml uses both scripts; no pull request runs the jobs that call them.

WORKFLOW = SCRIPTS.parent / "workflows" / "publish-images.yml"


def workflow() -> str:
    return WORKFLOW.read_text(encoding="utf-8")


def test_workflow_guards_the_tag_with_release_origin() -> None:
    text = workflow()

    assert '.github/scripts/release_origin.sh "$TAG" "$GITHUB_SHA"' in text
    assert "--is-ancestor" not in text


def test_workflow_refuses_runs_on_a_maintenance_branch() -> None:
    text = workflow()

    assert "if: startsWith(github.ref, 'refs/heads/release/')" in text
    # Pushes stay on main: no image of a maintenance branch; the weekly rebuild runs on main.
    assert "  push:\n    branches: [main]\n" in text


def test_workflow_marks_only_the_highest_final_release_latest() -> None:
    text = workflow()

    assert '.github/scripts/release_line_info.sh "$TAG" "$GITHUB_SHA"' in text
    assert 'flags+=("--latest=$latest")' in text
    assert "flags+=(--prerelease --latest=false)" in text
    assert '--notes-start-tag "$previous"' in text
    assert "(--latest)" not in text
