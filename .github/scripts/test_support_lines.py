"""Self-check for support_lines.py and its use in renovate.yml and renovate.json5.

.github/support-lines.json is the one place that says which release lines are supported until when
(docs/releases.md, "Supportlinien"). Renovate maintains main and the branch release/X.Y of every
line still supported on the day of the run; a line past its support end drops out without anyone
editing renovate.json5. Only the scheduled run shows the result, so these cases pin the selection,
the validation of the file and the wiring in workflow and configuration.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github" / "scripts" / "support_lines.py"
LINES_FILE = ROOT / ".github" / "support-lines.json"
WORKFLOW = ROOT / ".github" / "workflows" / "renovate.yml"
RENOVATE_CONFIG = ROOT / "renovate.json5"
RELEASE_BRANCHES = "matchBaseBranches: ['/^release\\\\/\\\\d+\\\\.\\\\d+$/']"


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, SCRIPT.as_posix(), *args], capture_output=True, text=True
    )


def write(tmp_path: Path, lines: object) -> Path:
    path = tmp_path / "support-lines.json"
    path.write_text(json.dumps({"lines": lines}), encoding="utf-8")
    return path


THREE_LINES = [
    {"line": "0.2", "kind": "normal", "supportEnd": "2027-01-31"},
    {"line": "0.3", "kind": "lts", "supportEnd": "2028-06-30"},
    {"line": "0.4", "kind": "normal", "supportEnd": None},
]


@pytest.mark.parametrize(
    ("date", "expected"),
    [
        ("2027-01-30", ["0.2", "0.3", "0.4"]),
        # The support end is the last day with patch releases.
        ("2027-01-31", ["0.2", "0.3", "0.4"]),
        ("2027-02-01", ["0.3", "0.4"]),
        ("2028-07-01", ["0.4"]),
    ],
)
def test_lines_supported_on_a_date(tmp_path: Path, date: str, expected: list[str]) -> None:
    result = run("lines", "--file", write(tmp_path, THREE_LINES).as_posix(), "--date", date)

    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == expected


def test_base_branches_are_main_and_the_supported_maintenance_branches(tmp_path: Path) -> None:
    path = write(tmp_path, THREE_LINES).as_posix()

    result = run("base-branches", "--file", path, "--date", "2027-02-01")

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == ["main", "release/0.3", "release/0.4"]


def test_without_supported_lines_only_main_remains(tmp_path: Path) -> None:
    result = run("base-branches", "--file", write(tmp_path, []).as_posix(), "--date", "2027-02-01")

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == ["main"]


@pytest.mark.parametrize(
    "lines",
    [
        [{"line": "v0.2", "kind": "normal", "supportEnd": None}],
        [{"line": "0.2.1", "kind": "normal", "supportEnd": None}],
        [{"line": "0.02", "kind": "normal", "supportEnd": None}],
        [{"line": "0.2", "kind": "maintenance", "supportEnd": None}],
        # An LTS line has its end fixed when it appears.
        [{"line": "0.2", "kind": "lts", "supportEnd": None}],
        [{"line": "0.2", "kind": "normal", "supportEnd": "31.01.2027"}],
        [{"line": "0.2", "kind": "normal", "supportEnd": "2027-02-30"}],
        [{"line": "0.2", "kind": "normal"}],
        [{"line": "0.2", "kind": "normal", "supportEnd": None, "extra": 1}],
        [
            {"line": "0.2", "kind": "normal", "supportEnd": None},
            {"line": "0.2", "kind": "lts", "supportEnd": "2028-06-30"},
        ],
        "0.2",
    ],
)
def test_refuses_a_malformed_file(tmp_path: Path, lines: object) -> None:
    result = run("lines", "--file", write(tmp_path, lines).as_posix(), "--date", "2027-01-01")

    assert result.returncode == 1
    assert result.stdout == ""
    assert result.stderr


def test_refuses_a_missing_file(tmp_path: Path) -> None:
    result = run("lines", "--file", (tmp_path / "missing.json").as_posix())

    assert result.returncode == 1


def test_the_repository_file_is_valid() -> None:
    result = run("lines", "--file", LINES_FILE.as_posix())

    assert result.returncode == 0, result.stderr


def test_renovate_workflow_forces_the_supported_branches() -> None:
    # Global "force" overrides the repository config, so the list from the file wins over the
    # main-only list in renovate.json5.
    text = WORKFLOW.read_text(encoding="utf-8")

    assert "python3 .github/scripts/support_lines.py base-branches" in text
    assert '{"baseBranchPatterns":%s}' in text
    assert "-e RENOVATE_FORCE" in text


def test_renovate_config_names_only_main_as_base_branch() -> None:
    # Without the workflow (local runs) Renovate stays on main; matchBaseBranches needs the key.
    text = RENOVATE_CONFIG.read_text(encoding="utf-8")

    assert re.findall(r"^\s*baseBranch(?:Pattern)?e?s\s*:.*$", text, re.M) == [
        "  baseBranchPatterns: ['main'],"
    ]


def package_rule_blocks() -> list[str]:
    text = RENOVATE_CONFIG.read_text(encoding="utf-8")
    return re.findall(r"\{[^{}]*\}", text[text.index("packageRules:"):])


def release_branch_rules() -> list[str]:
    return [block for block in package_rule_blocks() if "matchBaseBranches" in block]


def test_maintenance_branches_get_no_minor_or_major_update() -> None:
    disabling = [rule for rule in release_branch_rules() if "enabled: false" in rule]

    assert len(disabling) == 1
    rule = disabling[0]
    assert RELEASE_BRANCHES in rule
    for update_type in ("major", "minor", "replacement", "rollback", "bump"):
        assert f"'{update_type}'" in rule
    for update_type in ("patch", "digest", "pin"):
        assert f"'{update_type}'" not in rule



def test_only_explicit_exceptions_follow_the_disabling_rule() -> None:
    # An update beyond patch versions comes only by a later rule for the maintenance branches that
    # names its packages and never merges automatically.
    blocks = package_rule_blocks()
    disabling = next(i for i, b in enumerate(blocks) if "matchBaseBranches" in b and "enabled: false" in b)
    for rule in blocks[disabling + 1:]:
        assert "matchBaseBranches" in rule
        assert "matchPackageNames" in rule
        assert "automerge: false" in rule
