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


# scan-targets: the release images cve-scan.yml scans besides main.


def releases_file(tmp_path: Path, tags: list[str]) -> Path:
    path = tmp_path / "releases.jsonl"
    path.write_text(
        "".join(
            json.dumps({"tag_name": t, "prerelease": "-" in t, "draft": False}) + "\n" for t in tags
        ),
        encoding="utf-8",
    )
    return path


def scan_targets(tmp_path: Path, lines: object, tags: list[str], date: str) -> subprocess.CompletedProcess:
    return run(
        "scan-targets",
        "--file", write(tmp_path, lines).as_posix(),
        "--releases", releases_file(tmp_path, tags).as_posix(),
        "--image-prefix", "ghcr.io/criew/opaa",
        "--date", date,
    )


def target_images(result: subprocess.CompletedProcess) -> list[tuple[str, str]]:
    assert result.returncode == 0, result.stderr
    return [(t["name"], t["image"]) for t in json.loads(result.stdout)]


def test_scan_targets_take_the_newest_final_release_of_every_supported_line(tmp_path: Path) -> None:
    tags = ["v0.2.0", "v0.2.1", "v0.2.10", "v0.3.0", "v0.3.1-rc.1", "v0.4.0-rc.1", "v0.3.0-rc.2"]

    result = scan_targets(tmp_path, THREE_LINES[:2], tags, "2027-01-15")

    assert target_images(result) == [
        ("backend-0.2", "ghcr.io/criew/opaa-backend:0.2.10"),
        ("frontend-0.2", "ghcr.io/criew/opaa-frontend:0.2.10"),
        ("backend-0.3", "ghcr.io/criew/opaa-backend:0.3.0"),
        ("frontend-0.3", "ghcr.io/criew/opaa-frontend:0.3.0"),
    ]


def test_scan_targets_drop_a_line_after_its_support_end(tmp_path: Path) -> None:
    result = scan_targets(tmp_path, THREE_LINES[:2], ["v0.2.3", "v0.3.0"], "2027-02-01")

    assert [name for name, _ in target_images(result)] == ["backend-0.3", "frontend-0.3"]


def test_scan_targets_keep_the_line_of_the_highest_final_release_even_if_unlisted(
    tmp_path: Path,
) -> None:
    # The list may lag behind a release; the newest line is never left unscanned.
    result = scan_targets(tmp_path, [], ["v0.1.0", "v0.2.0", "v0.2.1"], "2027-02-01")

    assert target_images(result) == [
        ("backend-0.2", "ghcr.io/criew/opaa-backend:0.2.1"),
        ("frontend-0.2", "ghcr.io/criew/opaa-frontend:0.2.1"),
    ]


def test_scan_targets_without_any_final_release_are_empty(tmp_path: Path) -> None:
    result = scan_targets(tmp_path, [], ["v0.1.0-rc.1", "v0.1.0-rc.4"], "2027-02-01")

    assert target_images(result) == []


def test_scan_targets_warn_about_a_supported_line_without_release(tmp_path: Path) -> None:
    lines = [{"line": "0.5", "kind": "normal", "supportEnd": None}]

    result = scan_targets(tmp_path, lines, ["v0.4.0"], "2027-02-01")

    assert [name for name, _ in target_images(result)] == ["backend-0.4", "frontend-0.4"]
    assert "::warning::" in result.stderr and "0.5" in result.stderr


def test_scan_targets_ignore_drafts_and_marked_pre_releases(tmp_path: Path) -> None:
    path = tmp_path / "releases.jsonl"
    path.write_text(
        json.dumps({"tag_name": "v0.3.0", "prerelease": False, "draft": True}) + "\n"
        + json.dumps({"tag_name": "v0.2.2", "prerelease": True, "draft": False}) + "\n"
        + json.dumps({"tag_name": "v0.2.1", "prerelease": False, "draft": False}) + "\n",
        encoding="utf-8",
    )

    result = run(
        "scan-targets", "--file", write(tmp_path, []).as_posix(), "--releases", path.as_posix(),
        "--image-prefix", "ghcr.io/criew/opaa", "--date", "2027-02-01",
    )

    assert [image for _, image in target_images(result)] == [
        "ghcr.io/criew/opaa-backend:0.2.1",
        "ghcr.io/criew/opaa-frontend:0.2.1",
    ]


# check-roadmap: the roadmap in docs/releases.md and the list cannot drift apart.

ROADMAP_HEAD = """# Releases

### Roadmap

| Linie | Erscheint | Spring Boot | Art | Patch-Releases bis |
|---|---|---|---|---|
"""

ROWS = (
    "| `0.2` | 15.12.2026 | 4.2 | normal | 31.01.2027 |\n"
    "| `0.3` | 20.06.2027 | 4.3 | LTS | 30.06.2028 |\n"
    "| `0.4` | 15.12.2027 | 4.4 | normal | einen Monat nach dem Juni-Release 2028 |\n"
    "| Juni-Release 2028 | 4–6 Wochen nach Spring Boot | Mai-Version 2028 | LTS | Ende des kostenlosen Supports |\n"
)


def check_roadmap(tmp_path: Path, lines: object, rows: str) -> subprocess.CompletedProcess:
    doc = tmp_path / "releases.md"
    doc.write_text(ROADMAP_HEAD + rows + "\nText danach.\n", encoding="utf-8")
    return run(
        "check-roadmap", "--file", write(tmp_path, lines).as_posix(), "--releases-doc", doc.as_posix()
    )


def test_roadmap_matching_the_list_passes(tmp_path: Path) -> None:
    result = check_roadmap(tmp_path, THREE_LINES, ROWS)

    assert result.returncode == 0, result.stderr


@pytest.mark.parametrize(
    ("lines", "rows"),
    [
        # A line in the list without its row.
        (THREE_LINES, ROWS.replace("| `0.4` |", "| Dezember-Release 2027 |")),
        # A released row without a list entry.
        (THREE_LINES[:2], ROWS),
        # Different support end.
        (THREE_LINES, ROWS.replace("31.01.2027", "28.02.2027")),
        # An open support end in the list, a date in the roadmap.
        (THREE_LINES, ROWS.replace("einen Monat nach dem Juni-Release 2028", "15.07.2028")),
        # Kind differs.
        (THREE_LINES, ROWS.replace("| 4.3 | LTS |", "| 4.3 | normal |")),
    ],
)
def test_roadmap_drifting_from_the_list_fails(tmp_path: Path, lines: object, rows: str) -> None:
    result = check_roadmap(tmp_path, lines, rows)

    assert result.returncode == 1
    assert "::error::" in result.stderr


CVE_SCAN = ROOT / ".github" / "workflows" / "cve-scan.yml"


def test_cve_scan_takes_its_release_targets_from_the_list() -> None:
    text = CVE_SCAN.read_text(encoding="utf-8")

    assert "python3 .github/scripts/support_lines.py scan-targets" in text
    assert "releases/latest" not in text


def test_cve_scan_alarm_runs_when_a_single_leg_fails_but_never_clears_then() -> None:
    # An unreachable image of one line must neither skip the alarm nor let it close unseen.
    text = CVE_SCAN.read_text(encoding="utf-8")
    alarm = text[text.index("  critical-alert:"):]

    assert "!cancelled()" in alarm
    assert "needs.targets.result == 'success'" in alarm
    assert "EXPECTED_LEGS: ${{ needs.targets.outputs.legs }}" in alarm
    assert "missing.length" in alarm


def test_the_repository_roadmap_matches_the_list() -> None:
    result = run(
        "check-roadmap", "--file", LINES_FILE.as_posix(),
        "--releases-doc", (ROOT / "docs" / "releases.md").as_posix(),
    )

    assert result.returncode == 0, result.stderr
