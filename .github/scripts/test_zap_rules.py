"""Self-check for zap_rules.py and its wiring in zap-baseline.yml and renovate.json5.

The nightly ZAP baseline scan is red for every unsuppressed warning, and a suppression without
reason or expiry, or one past its expiry, must not keep it green. Only the scheduled run shows the
result, so these cases pin the validation, the rendered ZAP rule file, the verdict on a report and
the image pin that Renovate maintains.
"""

import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github" / "scripts" / "zap_rules.py"
RULES = ROOT / ".github" / "zap" / "rules.yaml"
WORKFLOW = ROOT / ".github" / "workflows" / "zap-baseline.yml"
RENOVATE_CONFIG = ROOT / "renovate.json5"
TODAY = "2026-11-01"
PINNED_IMAGE = re.compile(r"ghcr\.io/zaproxy/zaproxy:(\d+\.\d+\.\d+)@(sha256:[0-9a-f]{64})")


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, SCRIPT.as_posix(), *args, "--today", TODAY],
        capture_output=True,
        text=True,
    )


def entry(**overrides) -> dict:
    base = {
        "id": 10055,
        "name": "CSP: style-src unsafe-inline",
        "reason": "Laufzeit-Styles von Emotion; script-src bleibt streng.",
        "expires": "2026-12-01",
    }
    base.update(overrides)
    return {key: value for key, value in base.items() if value is not None}


def write_rules(tmp_path: Path, *entries: dict) -> Path:
    path = tmp_path / "rules.yaml"
    path.write_text(yaml.safe_dump({"rules": list(entries)}), encoding="utf-8")
    return path


def alert(plugin_id: str, risk: str, *instances: tuple[str, str]) -> dict:
    return {
        "pluginid": plugin_id,
        "name": f"Rule {plugin_id}",
        "riskcode": risk,
        "instances": [{"uri": uri, "param": param} for uri, param in instances],
    }


def write_report(tmp_path: Path, *alerts: dict) -> Path:
    path = tmp_path / "report.json"
    path.write_text(
        json.dumps({"site": [{"@name": "http://localhost:13010", "alerts": list(alerts)}]}),
        encoding="utf-8",
    )
    return path


def summary(rules: Path, report: Path, zap_exit: int = 0) -> subprocess.CompletedProcess:
    return run("summary", "--rules", str(rules), "--report", str(report), "--zap-exit", str(zap_exit))


# check


def test_the_repository_rules_are_well_formed() -> None:
    """Expiry is the nightly run's business; here only the shape, so main never turns red by date."""
    sys.path.insert(0, str(SCRIPT.parent))
    try:
        import zap_rules
    finally:
        sys.path.pop(0)
    rules = zap_rules.load(RULES)

    for rule in rules:
        day_before = datetime.date.fromisoformat(str(rule["expires"])) - datetime.timedelta(days=1)
        assert zap_rules.problems(rule, day_before) == [], rule
    ids = [str(rule["id"]) for rule in rules]
    assert len(ids) == len(set(ids))


def test_accepts_a_complete_entry(tmp_path: Path) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry())))

    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize("missing", ["id", "name", "reason", "expires"])
def test_rejects_an_entry_without_a_mandatory_field(tmp_path: Path, missing: str) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry(**{missing: None}))))

    assert result.returncode == 1
    assert f"missing {missing}" in result.stdout


@pytest.mark.parametrize("reason", ["", "   "])
def test_rejects_an_empty_reason(tmp_path: Path, reason: str) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry(reason=reason))))

    assert result.returncode == 1
    assert "reason is empty" in result.stdout


@pytest.mark.parametrize("expires", [TODAY, "2026-10-31"])
def test_rejects_an_entry_on_and_after_its_expiry(tmp_path: Path, expires: str) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry(expires=expires))))

    assert result.returncode == 1
    assert f"expired on {expires}" in result.stdout


def test_rejects_an_expiry_more_than_a_quarter_ahead(tmp_path: Path) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry(expires="2027-02-02"))))

    assert result.returncode == 1
    assert "more than 92 days ahead" in result.stdout


@pytest.mark.parametrize("expires", ["morgen", "2026-13-01", "01.12.2026"])
def test_rejects_an_expiry_that_is_no_date(tmp_path: Path, expires: str) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry(expires=expires))))

    assert result.returncode == 1
    assert "is not a date" in result.stdout


@pytest.mark.parametrize(
    "overrides, message",
    [
        ({"id": "abc"}, "is not a ZAP rule id"),
        ({"paths": ["api/"]}, "does not start with /"),
        ({"paths": ["/api/("]}, "no regular expression"),
        ({"paths": []}, "paths is not a non-empty list"),
        ({"params": [""]}, "params holds an empty"),
        ({"severity": "low"}, "unknown key severity"),
    ],
)
def test_rejects_malformed_optional_fields(tmp_path: Path, overrides: dict, message: str) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry(**overrides))))

    assert result.returncode == 1
    assert message in result.stdout


def test_rejects_a_rule_listed_twice(tmp_path: Path) -> None:
    result = run("check", "--rules", str(write_rules(tmp_path, entry(), entry())))

    assert result.returncode == 1
    assert "listed twice" in result.stdout


def test_rejects_a_file_without_a_rules_list(tmp_path: Path) -> None:
    path = tmp_path / "rules.yaml"
    path.write_text("suppressions: []\n", encoding="utf-8")

    assert run("check", "--rules", str(path)).returncode == 1


# render


def test_renders_ignore_and_out_of_scope_lines_and_leaves_out_expired_entries(
    tmp_path: Path,
) -> None:
    rules = write_rules(
        tmp_path,
        entry(),
        entry(id=10096, name="Timestamp Disclosure - Unix", paths=["/assets/"]),
        entry(id=10010, name="Cookie No HttpOnly Flag", expires="2026-10-01"),
    )
    out = tmp_path / "rules.conf"

    result = run("render", "--rules", str(rules), "--out", str(out))

    assert result.returncode == 0
    lines = [line for line in out.read_text(encoding="utf-8").splitlines() if not line.startswith("#")]
    assert lines == [
        "10055\tIGNORE\t(CSP: style-src unsafe-inline)",
        "10096\tOUTOFSCOPE\t^https?://[^/]+/assets/",
    ]
    assert "left out: 10010" in result.stdout


# summary


def test_passes_when_every_warning_is_suppressed(tmp_path: Path) -> None:
    rules = write_rules(tmp_path, entry())
    report = write_report(tmp_path, alert("10055", "2", ("http://localhost:13010/", "")))

    result = summary(rules, report)

    assert result.returncode == 0
    assert "Keine neuen Warnungen." in result.stdout
    assert "| 10055 |" in result.stdout


def test_fails_on_a_new_warning(tmp_path: Path) -> None:
    rules = write_rules(tmp_path, entry())
    report = write_report(tmp_path, alert("10021", "1", ("http://localhost:13010/api/x", "")))

    result = summary(rules, report)

    assert result.returncode == 1
    assert "**Neue Warnungen: 1**" in result.stdout
    assert "| 10021 |" in result.stdout


def test_an_expired_suppression_no_longer_suppresses(tmp_path: Path) -> None:
    rules = write_rules(tmp_path, entry(expires="2026-10-01"))
    report = write_report(tmp_path, alert("10055", "2", ("http://localhost:13010/", "")))

    assert summary(rules, report).returncode == 1


def test_informational_findings_never_fail(tmp_path: Path) -> None:
    rules = write_rules(tmp_path, entry())
    report = write_report(tmp_path, alert("10109", "0", ("http://localhost:13010/", "")))

    result = summary(rules, report)

    assert result.returncode == 0
    assert "Informativ" in result.stdout


def test_paths_and_params_narrow_a_suppression(tmp_path: Path) -> None:
    rules = write_rules(
        tmp_path,
        entry(id=10010, name="Cookie No HttpOnly Flag", paths=["/api/"], params=["XSRF-TOKEN"]),
    )
    covered = alert("10010", "1", ("http://localhost:13010/api/health", "XSRF-TOKEN"))
    other_cookie = alert("10010", "1", ("http://localhost:13010/api/health", "SESSION"))
    other_path = alert("10010", "1", ("http://localhost:13010/login", "XSRF-TOKEN"))

    assert summary(rules, write_report(tmp_path, covered)).returncode == 0
    assert summary(rules, write_report(tmp_path, other_cookie)).returncode == 1
    assert summary(rules, write_report(tmp_path, other_path)).returncode == 1


def test_fails_when_zap_itself_failed(tmp_path: Path) -> None:
    rules = write_rules(tmp_path, entry())

    result = summary(rules, write_report(tmp_path), zap_exit=3)

    assert result.returncode == 1
    assert "Exit-Status von zap-baseline.py: 3" in result.stdout


def test_fails_without_a_report(tmp_path: Path) -> None:
    result = summary(write_rules(tmp_path, entry()), tmp_path / "missing.json")

    assert result.returncode == 1
    assert "Kein auswertbarer Bericht" in result.stdout


def test_names_suppressions_that_suppressed_nothing(tmp_path: Path) -> None:
    rules = write_rules(tmp_path, entry())

    result = summary(rules, write_report(tmp_path))

    assert result.returncode == 0
    assert "Unterdrückungen ohne Befund" in result.stdout
    assert "10055 (CSP: style-src unsafe-inline)" in result.stdout


# wiring


def workflow_text() -> str:
    return WORKFLOW.read_text(encoding="utf-8")


def test_the_zap_image_is_pinned_to_version_and_digest() -> None:
    references = re.findall(r"(?<!depName=)ghcr\.io/zaproxy/zaproxy\S*", workflow_text())

    assert references, "zap-baseline.yml no longer references the ZAP image"
    for reference in references:
        assert PINNED_IMAGE.fullmatch(reference), reference


def renovate_block(*settings: str) -> str:
    blocks = re.findall(r"\{[^{}]*\}", RENOVATE_CONFIG.read_text(encoding="utf-8"))
    matching = [block for block in blocks if all(s in block for s in settings)]
    assert len(matching) == 1, f"expected exactly one renovate.json5 block with {settings}"
    return matching[0]


def test_regex_manager_extracts_the_pinned_version_and_digest() -> None:
    manager = renovate_block(
        "managerFilePatterns: ['.github/workflows/zap-baseline.yml']", "customType: 'regex'"
    )
    literal = re.search(r"matchStrings: \[\s*'((?:[^'\\]|\\.)*)'", manager)
    assert literal, "matchStrings entry not found in the custom manager for zap-baseline.yml"
    pattern = re.compile(re.sub(r"\\(.)", r"\1", literal.group(1)).replace("(?<", "(?P<"))

    match = pattern.search(workflow_text())

    assert match, "the custom manager for zap-baseline.yml does not match the workflow"
    pinned = PINNED_IMAGE.search(workflow_text())
    assert match.group("datasource") == "docker"
    assert match.group("depName") == "ghcr.io/zaproxy/zaproxy"
    assert match.group("currentValue") == pinned.group(1)
    assert match.group("currentDigest") == pinned.group(2)


def test_zap_updates_never_merge_automatically() -> None:
    rule = renovate_block(
        "matchFileNames: ['.github/workflows/zap-baseline.yml']", "allowedVersions"
    )

    assert "automerge: false" in rule


def test_the_workflow_checks_and_renders_the_repository_rules() -> None:
    workflow = yaml.safe_load(workflow_text())
    steps = [step.get("run", "") for job in workflow["jobs"].values() for step in job["steps"]]
    script = (ROOT / ".github" / "scripts" / "zap_baseline.sh").read_text(encoding="utf-8")

    assert any("zap_rules.py check --rules .github/zap/rules.yaml" in s for s in steps)
    assert any("zap_rules.py summary --rules .github/zap/rules.yaml" in s for s in steps)
    assert "zap_rules.py render --rules .github/zap/rules.yaml" in script
    assert "-c rules.conf" in script
