"""Self-check for accessibility_report.py and its wiring into the release run (docs/releases.md,
"Prüfbericht Barrierefreiheit").

Only a release tag runs the accessibility job for the package, so a broken report or a lost link
between the axe run and the package would surface at a release at the earliest. These cases render
reports from stand-in records as e2e/fixtures/a11y.ts writes them and check the workflows as text.
"""

import importlib.util
import json
import re
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("accessibility_report", HERE / "accessibility_report.py")
accessibility_report = importlib.util.module_from_spec(spec)
sys.modules["accessibility_report"] = accessibility_report
spec.loader.exec_module(accessibility_report)

PUBLISH = HERE.parent / "workflows" / "publish-images.yml"
REUSABLE = HERE.parent / "workflows" / "accessibility-report.yml"
FIXTURE = ROOT / "e2e" / "fixtures" / "a11y.ts"


def record(sequence: int, context: str, scheme: str = "light", violations=(), retry: int = 0,
           test: str = "Barrierefreiheit › Chat", **fields) -> dict:
    value = {
        "sequence": sequence, "context": context, "test": test, "file": "accessibility.spec.ts",
        "retry": retry, "path": "/spaces/1/chats/new", "colorScheme": scheme,
        "measuredAt": "2026-10-24T08:00:00.000Z", "axeVersion": "4.11.0",
        "tags": ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"], "disabledRules": [], "excluded": [],
        "unsettledAnimations": 0, "violations": list(violations),
        "passes": ["color-contrast", "label"], "incomplete": [],
    }
    value.update(fields)
    return value


SERIOUS = {"id": "color-contrast", "impact": "serious",
           "help": "Elements must meet minimum color contrast ratio thresholds",
           "helpUrl": "https://dequeuniversity.com/rules/axe/4.11/color-contrast", "nodes": 3,
           "targets": [".MuiChip-label", "#menu > li:nth-child(2)"]}
MINOR = {"id": "html-has-lang", "impact": "minor", "help": "<html> element must have a lang attribute",
         "helpUrl": "https://dequeuniversity.com/rules/axe/4.11/html-has-lang", "nodes": 1,
         "targets": ["html"]}


def write(run: Path, *records: dict, playwright: dict | None = None) -> None:
    run.mkdir(parents=True, exist_ok=True)
    for value in records:
        (run / f"{value['sequence']:04d}-t{value['retry']}.json").write_text(json.dumps(value), encoding="utf-8")
    if playwright is not None:
        (run / "playwright.json").write_text(json.dumps(playwright), encoding="utf-8")


def playwright(*specs: tuple[str, str, str]) -> dict:
    return {"suites": [{"title": "accessibility.spec.ts", "specs": [], "suites": [{
        "title": "Barrierefreiheit", "suites": [],
        "specs": [{"title": title, "tests": [{"results": [
            {"status": status, **({"error": {"message": f"\x1b[31m{error}\x1b[39m\nstack"}} if error else {})}
        ]}]} for title, status, error in specs],
    }]}]}


def report(results: Path) -> str:
    files = accessibility_report.render(accessibility_report.load(results), "1.2.3", "a" * 40)
    return files[accessibility_report.REPORT].decode("utf-8")


def test_the_report_names_version_tool_rule_set_and_every_checked_state(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat (helles Farbschema)"),
          record(2, "Chat (dunkles Farbschema)", "dark", path="/spaces/1/chats/new"),
          playwright=playwright(("Chat", "passed", "")))
    write(tmp_path / "local-auth", record(1, "Anmeldeseite", path="/login"))

    text = report(tmp_path)

    assert "gilt-fuer: 1.2.3\ncommit: " + "a" * 40 + "\ngeprueft-am: 2026-10-24\n" in text
    assert "# Prüfbericht Barrierefreiheit OPAA 1.2.3" in text
    assert "| Werkzeug | axe-core 4.11.0 |" in text
    assert "`wcag21aa`" in text
    assert "| Geprüfte Zustände | 3 (hell 2, dunkel 1) auf 2 Seiten |" in text
    assert "| Chat (dunkles Farbschema) (dunkel) | `/spaces/1/chats/new` | keine | keine |" in text
    assert "| Anmeldeseite (hell) | `/login` | keine | keine |" in text
    assert "Keine Befunde." in text
    assert "Alle Szenarien liefen vollständig durch." not in text  # local-auth has no report
    assert "Ohne Playwright-Bericht" in text and "`local-auth`" in text


def test_a_finding_of_every_level_appears_with_rule_level_elements_and_explanation(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Katalog (dunkles Farbschema)", "dark", violations=[SERIOUS, MINOR]),
          playwright=playwright(("Katalog", "passed", "")))

    text = report(tmp_path)

    assert "| Befunde | 2 (1 schwerwiegend, 1 gering) |" in text
    assert "### Katalog (dunkles Farbschema) (dunkel)" in text
    assert ("| `color-contrast` | schwerwiegend | 3 | Elements must meet minimum color contrast ratio"
            " thresholds ([Erläuterung](https://dequeuniversity.com/rules/axe/4.11/color-contrast)) |"
            " `.MuiChip-label`; `#menu > li:nth-child(2)` |") in text
    # An HTML tag of the axe help text must not reach the HTML version as markup.
    assert "| `<html>` element must have a lang attribute" in text
    assert "Alle Szenarien liefen vollständig durch." in text


def test_a_scenario_that_stopped_early_is_named_with_its_first_error_line(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat (helles Farbschema)"),
          playwright=playwright(("Chat", "passed", ""), ("Katalog", "failed", "Timed out | waiting")))

    text = report(tmp_path)

    assert "| Szenarien nicht vollständig | 1 |" in text
    assert ("| accessibility.spec.ts › Barrierefreiheit › Katalog | `failed` | Timed out \\| waiting |"
            in text)
    assert "\x1b" not in text and "stack" not in text


def test_a_stopped_scenario_of_a_spec_without_axe_check_is_not_listed(tmp_path: Path) -> None:
    other = playwright(("Konten anlegen", "failed", "Timed out"))
    other["suites"][0]["title"] = "02-konten.spec.ts"
    write(tmp_path / "local-auth", record(1, "Anmeldeseite"), playwright=other)

    text = report(tmp_path)

    assert "| Szenarien nicht vollständig | 0 |" in text
    assert "Konten anlegen" not in text
    assert "| Szenario-Dateien mit Messung | `accessibility.spec.ts` |" in text


def test_only_the_last_attempt_of_a_retried_scenario_counts(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat (helles Farbschema)", violations=[SERIOUS]),
          record(2, "Chat (helles Farbschema)", retry=1))

    text = report(tmp_path)

    assert "| Geprüfte Zustände | 1 (hell 1, dunkel 0) auf 1 Seiten |" in text
    assert "Keine Befunde." in text


def test_undecided_rules_and_exceptions_are_listed(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "E-Mail, Vorlagen", disabledRules=["region"],
                                   excluded=['iframe[title="Vorschau"]'],
                                   incomplete=[{"id": "color-contrast", "nodes": 4}]))

    text = report(tmp_path)

    assert "Regel `region` aus, ohne `iframe[title=\"Vorschau\"]`" in text
    assert "| `color-contrast` | 1 | 4 |" in text
    assert "| `color-contrast` | 1 | 0 | 1 |" in text


@pytest.mark.parametrize("case", ["missing", "empty", "only-report", "malformed"])
def test_results_without_a_single_record_fail(tmp_path: Path, case: str) -> None:
    results = tmp_path / "results"
    if case != "missing":
        (results / "e2e").mkdir(parents=True)
    if case == "only-report":
        (results / "e2e" / "playwright.json").write_text("{}", encoding="utf-8")
    if case == "malformed":
        (results / "e2e" / "0001.json").write_text("{\"context\": 1}", encoding="utf-8")

    with pytest.raises(accessibility_report.ReportError):
        accessibility_report.load(results)


def test_the_raw_results_lie_beside_the_report_for_tools(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat", violations=[SERIOUS]))

    files = accessibility_report.render(accessibility_report.load(tmp_path), "1.2.3", "a" * 40)

    raw = json.loads(files[accessibility_report.RESULTS])
    assert raw["version"] == "1.2.3"
    assert raw["runs"][0]["records"][0]["violations"][0]["id"] == "color-contrast"


def test_a_trial_without_results_says_it_was_not_checked() -> None:
    files = accessibility_report.not_measured("1.2.3", "a" * 40)

    assert set(files) == {accessibility_report.REPORT}
    assert "**Nicht geprüft.**" in files[accessibility_report.REPORT].decode("utf-8")


def job(text: str, name: str) -> str:
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", text, re.M | re.S)
    assert match, f"job {name} missing"
    return match.group(1)


def test_the_release_builds_the_package_only_after_the_accessibility_run_and_hands_it_over() -> None:
    publish = PUBLISH.read_text(encoding="utf-8")
    accessibility = job(publish, "accessibility")
    package = job(publish, "update-package")

    assert "uses: ./.github/workflows/accessibility-report.yml" in accessibility
    assert "if: startsWith(github.ref, 'refs/tags/v')" in accessibility
    assert "permissions:\n      contents: read\n" in accessibility
    assert "needs: [prepare, accessibility]" in package
    assert "name: accessibility-results" in package
    assert '--accessibility "$RUNNER_TEMP/accessibility-results"' in package
    # A skipped package on a tag (the accessibility run failed) must not let publish set tags.
    condition = re.search(r"^    if: (.*)$", job(publish, "publish"), re.M).group(1)
    assert "needs.update-package.result == 'success'" in condition
    assert "needs.update-package.result == 'skipped' && !startsWith(github.ref, 'refs/tags/')" in condition


def test_the_accessibility_run_records_without_failing_and_takes_nothing_from_a_cache() -> None:
    text = REUSABLE.read_text(encoding="utf-8")
    body = job(text, "axe")

    assert "workflow_call:" in text
    assert re.search(r"^permissions:\n  contents: read\n", text, re.M)
    assert "permissions:\n      contents: read\n" in body
    caches = [line.strip() for line in body.splitlines() if "cache" in line and not line.strip().startswith("#")]
    assert caches == ["package-manager-cache: false"]
    assert "actions/cache" not in body and "type=gha" not in body
    assert body.count("E2E_A11Y_REPORT_ONLY: 'true'") == 2
    assert "pnpm run test:local-auth || rc=$?" in body
    assert "accessibility-results/e2e" in body and "accessibility-results/local-auth" in body
    assert "python3 .github/scripts/accessibility_report.py" in body
    assert "name: accessibility-results" in body


def test_every_spec_with_an_axe_check_runs_in_the_accessibility_run() -> None:
    """A new spec calling the fixture would otherwise stay outside the report unnoticed."""
    body = job(REUSABLE.read_text(encoding="utf-8"), "axe")
    calling = sorted(p.relative_to(ROOT) for p in (ROOT / "e2e").rglob("*.spec.ts")
                     if "node_modules" not in p.parts
                     and "expectNoSeriousA11yViolations(" in p.read_text(encoding="utf-8"))

    regular = re.search(r"pnpm test (.*) \|\| rc=\$\?", body).group(1).split()
    for path in calling:
        if "local-auth" not in path.parts:
            assert path.name in regular, path.name
    assert "pnpm run test:local-auth || rc=$?" in body


def test_the_fixture_writes_the_fields_the_report_reads_and_records_findings_in_report_mode() -> None:
    fixture = FIXTURE.read_text(encoding="utf-8")

    for field in ("sequence", "context", "test", "file", "retry", "path", "colorScheme", "measuredAt",
                  "axeVersion", "tags", "disabledRules", "excluded", "violations", "passes", "incomplete"):
        assert re.search(rf"^    {field}[:,]", fixture, re.M), field
    assert "process.env.E2E_A11Y_RESULTS_DIR" in fixture
    assert "process.env.E2E_A11Y_REPORT_ONLY === 'true'" in fixture
    report_only = fixture.index("if (REPORT_ONLY) {")
    assert report_only < fixture.index("  expect(\n    failing,")
    assert "return\n" in fixture[report_only:fixture.index("  expect(\n    failing,")]
