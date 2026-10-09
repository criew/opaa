"""Self-check for check_action_pins.sh.

The CI run only exercises the green branch against the repository's own workflows; a change
that makes the guard laxer would go unnoticed there. These cases pin the rejecting branches
(tag, branch, short SHA, SHA without version comment, docker tag, missing workflow) and keep the
guarded file list in sync with the pinDigests rule in renovate.json5.
"""

import re
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent
SCRIPT = SCRIPTS / "check_action_pins.sh"
RENOVATE_CONFIG = SCRIPTS.parent.parent / "renovate.json5"
BASH = shutil.which("bash") or "bash"
SHA = "0123456789abcdef0123456789abcdef01234567"
PINNED = f"      - uses: actions/checkout@{SHA} # v7.0.1\n"


def guarded_workflows() -> list[str]:
    body = re.search(r"PINNED_WORKFLOWS=\((.*?)\)", SCRIPT.read_text(encoding="utf-8"), re.S)
    assert body, "PINNED_WORKFLOWS array not found in check_action_pins.sh"
    return body.group(1).split()


def run_guard(repo: Path) -> subprocess.CompletedProcess:
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


def workflow(*uses_lines: str) -> str:
    return "jobs:\n  job:\n    runs-on: ubuntu-latest\n    steps:\n" + "".join(uses_lines)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    for relative in guarded_workflows():
        write(tmp_path, relative, workflow(PINNED))
    return tmp_path


def write(repo: Path, relative: str, content: str) -> None:
    path = repo / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def test_accepts_sha_pins_local_actions_and_docker_digests(repo: Path) -> None:
    write(
        repo,
        ".github/workflows/publish-images.yml",
        workflow(
            PINNED,
            f"        uses: 'docker/login-action@{SHA}' # v4.6.0\n",
            f"      - uses: github/codeql-action/upload-sarif@{SHA}  #  v4.38.3\n",
            "      - uses: ./.github/actions/local\n",
            "      - uses: docker://alpine@sha256:" + "a" * 64 + "\n",
        ),
    )

    result = run_guard(repo)

    assert result.returncode == 0, result.stdout
    assert "All 10 action references" in result.stdout


@pytest.mark.parametrize(
    "line",
    [
        "      - uses: actions/checkout@v7\n",
        "      - uses: actions/checkout@v7.0.1\n",
        "      - uses: actions/checkout@main\n",
        "      - uses: actions/checkout@3d3c42e # v7.0.1\n",
        f"      - uses: actions/checkout@{SHA}\n",
        f"      - uses: actions/checkout@{SHA} # v7\n",
        f"      - uses: actions/checkout@{SHA.upper()} # v7.0.1\n",
        "      - uses: docker://alpine:3.22\n",
        "        uses: \"aquasecurity/trivy-action@v0.36.0\"\n",
    ],
)
def test_rejects_unpinned_reference(repo: Path, line: str) -> None:
    write(repo, ".github/workflows/cve-scan.yml", workflow(PINNED, line))

    result = run_guard(repo)

    assert result.returncode == 1
    assert "::error file=.github/workflows/cve-scan.yml,line=6::" in result.stdout
    assert "1 of 7 action references" in result.stdout


def test_rejects_missing_listed_workflow(repo: Path) -> None:
    (repo / ".github/workflows/cla.yml").unlink()

    result = run_guard(repo)

    assert result.returncode == 1
    assert "::error file=.github/workflows/cla.yml::Listed workflow does not exist" in result.stdout


def test_rejects_when_nothing_was_checked(repo: Path) -> None:
    for relative in guarded_workflows():
        write(repo, relative, workflow())

    result = run_guard(repo)

    assert result.returncode == 1
    assert "would pass without checking anything" in result.stdout


def test_guarded_files_match_renovate_pin_rule() -> None:
    config = RENOVATE_CONFIG.read_text(encoding="utf-8")
    rule = re.search(
        r"\{[^{}]*matchManagers: \['github-actions'\],[^{}]*pinDigests: true,[^{}]*\}", config
    )
    assert rule, "no github-actions rule with pinDigests: true in renovate.json5"
    files = re.search(r"matchFileNames: \[([^\]]*)\]", rule.group(0))
    assert files, "pinDigests rule in renovate.json5 has no matchFileNames"

    assert sorted(re.findall(r"'([^']+)'", files.group(1))) == sorted(guarded_workflows())
