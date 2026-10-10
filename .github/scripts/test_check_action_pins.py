"""Self-check for check_action_pins.sh.

The CI run only exercises the green branch against the repository's own workflows; a change
that makes the guard laxer would go unnoticed there. These cases pin the rejecting branches
(tag, branch, short SHA, SHA without version comment, docker tag, flow mapping, quoted key) and
the file selection: every workflow file is checked, whatever its permissions. They also keep the
Renovate rules for the pins applying to every workflow.
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
# A workflow without any write permission, one that only reads and one that publishes: the guard
# makes no difference between them.
WORKFLOWS = (
    ".github/workflows/ci.yml",
    ".github/workflows/e2e.yml",
    ".github/workflows/publish-images.yml",
)


def run_guard(repo: Path) -> subprocess.CompletedProcess:
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


def workflow(*uses_lines: str) -> str:
    return "jobs:\n  job:\n    runs-on: ubuntu-latest\n    steps:\n" + "".join(uses_lines)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    for relative in WORKFLOWS:
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
            f"      - {{ name: Flow, uses: actions/cache@{SHA} }} # v6.0.0\n",
            f'      - "uses": "actions/setup-node@{SHA}" # v7.1.0\n',
        ),
    )

    result = run_guard(repo)

    assert result.returncode == 0, result.stdout
    assert "All 9 action references in 3 workflows" in result.stdout


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
        "      - { name: Flow, uses: actions/cache@v6 }\n",
        "      - {uses: actions/cache@v6, with: {path: x}}\n",
        "      - \"uses\": actions/setup-node@v7\n",
        "      - 'uses' : actions/setup-node@v7\n",
    ],
)
def test_rejects_unpinned_reference(repo: Path, line: str) -> None:
    write(repo, ".github/workflows/e2e.yml", workflow(PINNED, line))

    result = run_guard(repo)

    assert result.returncode == 1
    assert "::error file=.github/workflows/e2e.yml,line=6::" in result.stdout
    assert "1 of 4 action references" in result.stdout


def test_checks_a_workflow_that_was_never_listed_anywhere(repo: Path) -> None:
    write(repo, ".github/workflows/new-workflow.yaml", workflow("      - uses: actions/checkout@v7\n"))

    result = run_guard(repo)

    assert result.returncode == 1
    assert "::error file=.github/workflows/new-workflow.yaml,line=5::" in result.stdout
    assert "1 of 4 action references in 4 workflows" in result.stdout


def test_rejects_when_nothing_was_checked(repo: Path) -> None:
    for relative in WORKFLOWS:
        write(repo, relative, workflow())

    result = run_guard(repo)

    assert result.returncode == 1
    assert "would pass without checking anything" in result.stdout


def test_rejects_a_repository_without_workflows(repo: Path) -> None:
    shutil.rmtree(repo / ".github")

    result = run_guard(repo)

    assert result.returncode == 1
    assert "would pass without checking anything" in result.stdout


def github_actions_rules() -> list[str]:
    """Every packageRule of the github-actions manager; the rules hold no nested objects."""
    config = RENOVATE_CONFIG.read_text(encoding="utf-8")
    return [
        rule
        for rule in re.findall(r"\{[^{}]*\}", config)
        if re.search(r"matchManagers: \['github-actions'\]", rule)
    ]


def only_rule(*settings: str) -> str:
    rules = [rule for rule in github_actions_rules() if all(s in rule for s in settings)]
    assert len(rules) == 1, f"expected exactly one github-actions rule with {settings}"
    return rules[0]


def test_renovate_pins_the_actions_of_every_workflow() -> None:
    pin_rule = only_rule("pinDigests: true", "minimumReleaseAge: '3 days'")
    digest_rule = only_rule(
        "matchUpdateTypes: ['digest']", "dependencyDashboardApproval: true", "automerge: false"
    )

    assert "matchDepTypes: ['action']" in pin_rule
    assert "matchFileNames" not in pin_rule
    assert "matchFileNames" not in digest_rule
    for rule in github_actions_rules():
        if any(s in rule for s in ("pinDigests", "minimumReleaseAge", "dependencyDashboardApproval")):
            assert "matchFileNames" not in rule, rule


def test_publish_images_actions_never_automerge() -> None:
    rule = only_rule("matchFileNames: ['.github/workflows/publish-images.yml']", "automerge: false")

    assert "matchUpdateTypes" not in rule and "matchDepNames" not in rule
