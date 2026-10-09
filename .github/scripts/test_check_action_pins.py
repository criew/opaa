"""Self-check for check_action_pins.sh.

The CI run only exercises the green branch against the repository's own workflows; a change
that makes the guard laxer would go unnoticed there. These cases pin the rejecting branches
(tag, branch, short SHA, SHA without version comment, docker tag, flow mapping, quoted key,
missing workflow). They also keep the guarded file list in sync with the Renovate rules and with
the workflows that actually hold a write permission.
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
            f"      - {{ name: Flow, uses: actions/cache@{SHA} }} # v6.0.0\n",
            f'      - "uses": "actions/setup-node@{SHA}" # v7.1.0\n',
        ),
    )

    result = run_guard(repo)

    assert result.returncode == 0, result.stdout
    assert "All 12 action references" in result.stdout


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


def github_actions_rules() -> list[str]:
    """Every packageRule of the github-actions manager; the rules hold no nested objects."""
    config = RENOVATE_CONFIG.read_text(encoding="utf-8")
    return [
        rule
        for rule in re.findall(r"\{[^{}]*\}", config)
        if re.search(r"matchManagers: \['github-actions'\]", rule)
    ]


def file_names(rule: str) -> list[str]:
    files = re.search(r"matchFileNames: \[([^\]]*)\]", rule)
    return sorted(re.findall(r"'([^']+)'", files.group(1))) if files else []


def only_rule(*settings: str) -> str:
    rules = [rule for rule in github_actions_rules() if all(s in rule for s in settings)]
    assert len(rules) == 1, f"expected exactly one github-actions rule with {settings}"
    return rules[0]


def test_every_renovate_rule_for_the_pinned_workflows_lists_exactly_them() -> None:
    pinned = sorted(guarded_workflows())
    pin_rule = only_rule("pinDigests: true", "minimumReleaseAge: '3 days'")
    digest_rule = only_rule(
        "matchUpdateTypes: ['digest']", "dependencyDashboardApproval: true", "automerge: false"
    )

    assert file_names(pin_rule) == pinned
    assert file_names(digest_rule) == pinned
    for rule in github_actions_rules():
        if any(s in rule for s in ("pinDigests", "minimumReleaseAge", "dependencyDashboardApproval")):
            assert file_names(rule) == pinned, rule


def test_publish_images_actions_never_automerge() -> None:
    rule = only_rule("matchFileNames: ['.github/workflows/publish-images.yml']", "automerge: false")

    assert "matchUpdateTypes" not in rule and "matchDepNames" not in rule
    assert ".github/workflows/publish-images.yml" in guarded_workflows()


# Workflows that hold a write permission but are deliberately not pinned (docs/renovate.md):
# they write issues or PR comments only, never published artifacts or repository contents.
WRITE_WITHOUT_PINNING = {
    ".github/workflows/baseline-diff.yml": "pull-requests: write - PR comment only",
    ".github/workflows/e2e.yml": "issues: write - failure alert issue only",
    ".github/workflows/retrieval-regression.yml": "issues/pull-requests: write - comments only",
}


def write_privileged_workflows(root: Path) -> set[str]:
    return {
        path.relative_to(root).as_posix()
        for path in sorted((root / ".github" / "workflows").glob("*.y*ml"))
        if re.search(r"^\s*[a-z-]+:\s*write", path.read_text(encoding="utf-8"), re.M)
    }


def test_every_write_privileged_workflow_is_pinned_or_explicitly_exempt() -> None:
    root = SCRIPTS.parent.parent
    pinned = set(guarded_workflows())
    privileged = write_privileged_workflows(root)

    assert privileged - pinned - WRITE_WITHOUT_PINNING.keys() == set()
    assert pinned.isdisjoint(WRITE_WITHOUT_PINNING)
    assert set(WRITE_WITHOUT_PINNING) <= privileged, "stale exemption"
