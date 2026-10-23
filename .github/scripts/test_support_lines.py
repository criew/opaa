"""Self-check for support_lines.py and its use in renovate.yml and renovate.json5.

.github/support-lines.json is the one place that says which release lines are supported until when
(docs/releases.md, "Supportlinien"). Renovate maintains main and the branch release/X.Y of every
line still supported on the day of the run; a line past its support end drops out without anyone
editing renovate.json5. Only the scheduled run shows the result, so these cases pin the selection,
the validation of the file and the wiring in workflow and configuration.
"""

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github" / "scripts" / "support_lines.py"
LINES_FILE = ROOT / ".github" / "support-lines.json"
WORKFLOW = ROOT / ".github" / "workflows" / "renovate.yml"
RENOVATE_CONFIG = ROOT / "renovate.json5"


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


RELEASE_PATTERN = "/^release\\/\\d+\\.\\d+$/"


def package_rules() -> list[dict]:
    """packageRules of renovate.json5, read by Node: JSON5 is a JavaScript object literal."""
    node = shutil.which("node")
    assert node, "the check needs Node.js to read renovate.json5"
    script = (
        "const fs = require('fs');"
        "const text = fs.readFileSync(process.argv[1], 'utf8');"
        "process.stdout.write(JSON.stringify(new Function('return (' + text + '\\n)')().packageRules));"
    )
    result = subprocess.run(
        [node, "-e", script, RENOVATE_CONFIG.as_posix()], capture_output=True, text=True, check=True
    )
    return json.loads(result.stdout)


def disabling_index(rules: list[dict]) -> int:
    matches = [
        i for i, r in enumerate(rules)
        if r.get("matchBaseBranches") == [RELEASE_PATTERN] and r.get("enabled") is False
    ]
    assert len(matches) == 1, "expected exactly one disabling rule for the maintenance branches"
    return matches[0]


def test_maintenance_branches_get_no_minor_or_major_update() -> None:
    rule = package_rules()[disabling_index(package_rules())]

    assert set(rule) == {"matchBaseBranches", "matchUpdateTypes", "enabled"}
    assert set(rule["matchUpdateTypes"]) == {
        "major", "minor", "replacement", "rollback", "bump", "lockFileMaintenance",
    }


def test_maintenance_branches_propose_patches_apart_from_minor_versions() -> None:
    # Without it Renovate proposes only the highest non-major version; a newer minor one would take
    # the patch down with it under the disabling rule.
    rules = package_rules()
    separating = [
        i for i, r in enumerate(rules)
        if r == {"matchBaseBranches": [RELEASE_PATTERN], "separateMinorPatch": True}
    ]

    assert len(separating) == 1
    assert separating[0] < disabling_index(rules)
    assert all("separateMinorPatch" not in r for i, r in enumerate(rules) if i != separating[0])


def test_only_explicit_exceptions_follow_the_disabling_rule() -> None:
    # An update beyond patch versions comes only by a later rule for the maintenance branches that
    # names its packages and never merges automatically.
    rules = package_rules()
    for rule in rules[disabling_index(rules) + 1:]:
        assert rule.get("matchBaseBranches")
        assert rule.get("matchPackageNames")
        assert rule.get("automerge") is False
