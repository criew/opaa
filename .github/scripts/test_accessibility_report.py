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
sys.path.insert(0, str(HERE))
import package_html  # noqa: E402

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


def write(run: Path, *records: dict, playwright: dict | None = None,
          expected: list[str] | None = None) -> None:
    """A run directory as the accessibility workflow leaves it: records, the Playwright report (by
    default one passed scenario per spec file) and expected.txt (by default the files of the
    records)."""
    run.mkdir(parents=True, exist_ok=True)
    for value in records:
        (run / f"{value['sequence']:04d}-t{value['retry']}.json").write_text(json.dumps(value), encoding="utf-8")
    files = expected if expected is not None else sorted({r["file"] for r in records})
    (run / "expected.txt").write_text("".join(f"{f}\n" for f in files), encoding="utf-8")
    report_value = playwright if playwright is not None else {
        "suites": [{"title": f, "specs": [{"title": "x", "tests": [{"results": [{"status": "passed"}]}]}]}
                   for f in files]}
    (run / "playwright.json").write_text(json.dumps(report_value), encoding="utf-8")


def playwright(*specs: tuple[str, str, str], file: str = "accessibility.spec.ts") -> dict:
    return {"suites": [{"title": file, "specs": [], "suites": [{
        "title": "Barrierefreiheit", "suites": [],
        "specs": [{"title": title, "tests": [{"results": [
            {"status": status, **({"error": {"message": f"\x1b[31m{error}\x1b[39m\nstack"}} if error else {})}
        ]}]} for title, status, error in specs],
    }]}]}


def report(results: Path) -> str:
    files = accessibility_report.render(accessibility_report.load(results), "1.2.3", "a" * 40)
    return files[accessibility_report.REPORT].decode("utf-8")


ID = "0b6f1a52-2f0e-4c3f-9d55-6a7c1e2b3d4f"


def test_the_report_names_version_tool_rule_set_and_every_checked_state(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat (helles Farbschema)", path=f"/spaces/{ID}/chats/new"),
          record(2, "Chat (dunkles Farbschema)", "dark", path=f"/spaces/{ID}/chats/new"),
          playwright=playwright(("Chat", "passed", "")))
    write(tmp_path / "local-auth", record(1, "Anmeldeseite", path="/login", file="07-barrierefreiheit.spec.ts"))

    text = report(tmp_path)

    assert "gilt-fuer: 1.2.3\ncommit: " + "a" * 40 + "\ngeprueft-am: 2026-10-24\n" in text
    assert "# Prüfbericht Barrierefreiheit OPAA 1.2.3" in text
    assert "| Werkzeug | axe-core `4.11.0` |" in text
    assert "`wcag21aa`" in text
    assert "| Geprüfte Zustände | 3 (hell 2, dunkel 1) auf 2 Seiten |" in text
    assert "| Zustände in beiden Farbschemata | 1 von 2 |" in text
    # A context that names its scheme keeps it once; identifiers in a path are folded.
    assert "| Chat (dunkles Farbschema) | `/spaces/<id>/chats/new` | keine | keine |" in text
    assert "| Anmeldeseite (hell) | `/login` | keine | keine |" in text
    assert "| Anmeldeseite | `/login` | nur hell |" in text
    assert "| Geprüfte Szenario-Dateien | `accessibility.spec.ts`, `07-barrierefreiheit.spec.ts` |" in text
    assert "Keine Befunde." in text
    assert "Alle Szenarien der geprüften Szenario-Dateien liefen vollständig durch." in text


def test_a_finding_of_every_level_appears_with_rule_level_elements_and_explanation(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Katalog (dunkles Farbschema)", "dark", violations=[SERIOUS, MINOR]),
          playwright=playwright(("Katalog", "passed", "")))

    text = report(tmp_path)

    assert "| Befunde | 2 (1 schwerwiegend, 1 gering) |" in text
    assert "### Katalog (dunkles Farbschema)\n" in text
    assert ("| `color-contrast` | schwerwiegend | 3 | Elements must meet minimum color contrast ratio"
            " thresholds ([Erläuterung](https://dequeuniversity.com/rules/axe/4.11/color-contrast)) |"
            " `.MuiChip-label`; `#menu > li:nth-child(2)` |") in text
    # An HTML tag of the axe help text must not reach the HTML version as markup.
    assert "| `<html>` element must have a lang attribute" in text


def test_fields_of_a_record_are_masked_before_markdown_and_html(tmp_path: Path) -> None:
    hostile = dict(SERIOUS, helpUrl="javascript:alert(1)", nodes="<b>3</b>")
    write(tmp_path / "e2e", record(1, "Katalog <!-- x -->", violations=[hostile],
                                   axeVersion="<script>x</script>", measuredAt="<img src=x>"))

    files = accessibility_report.render(accessibility_report.load(tmp_path), "1.2.3", "a" * 40)
    text = files[accessibility_report.REPORT].decode("utf-8")
    page = package_html.render(files, "Paket")["barrierefreiheit/pruefbericht.html"].decode("utf-8")

    for markup in ("<script", "<img", "<!--", "<b>", "javascript:"):
        assert markup not in page, markup
    assert "| `color-contrast` | schwerwiegend | 0 |" in text
    assert "| Prüfdatum | unbekannt |" in text


def test_a_scenario_that_stopped_early_is_named_with_its_first_error_line(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat (helles Farbschema)"),
          playwright=playwright(("Chat", "passed", ""), ("Katalog", "failed", "Timed out | waiting")))

    text = report(tmp_path)

    assert "| Szenarien nicht vollständig | 1 |" in text
    assert ("| accessibility.spec.ts › Barrierefreiheit › Katalog | `failed` | Timed out \\| waiting |"
            in text)
    assert "\x1b" not in text and "stack" not in text


def test_a_stopped_scenario_of_a_spec_without_axe_check_is_not_listed(tmp_path: Path) -> None:
    other = playwright(("Konten anlegen", "failed", "Timed out"), file="02-konten.spec.ts")
    other["suites"] += playwright(("Anmeldeseite", "passed", ""))["suites"]
    write(tmp_path / "local-auth", record(1, "Anmeldeseite"), playwright=other)

    text = report(tmp_path)

    assert "| Szenarien nicht vollständig | 0 |" in text
    assert "Konten anlegen" not in text


# regression guard: one target without records while the other has some must fail the run, not
# read as "every scenario finished".
def test_a_target_without_a_single_record_fails_although_another_target_has_records(tmp_path: Path) -> None:
    write(tmp_path / "e2e", playwright={"suites": []}, expected=["accessibility.spec.ts"])
    write(tmp_path / "local-auth", record(1, "Anmeldeseite", file="07-barrierefreiheit.spec.ts"))

    with pytest.raises(accessibility_report.ReportError, match="e2e measured nothing in accessibility.spec.ts"):
        accessibility_report.load(tmp_path)


def test_an_expected_spec_file_without_a_record_fails(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat"), expected=["accessibility.spec.ts", "space-members.spec.ts"])

    with pytest.raises(accessibility_report.ReportError, match="space-members.spec.ts"):
        accessibility_report.load(tmp_path)


# regression guard: a scenario Playwright skips because an earlier one of its describe.serial group
# failed did not run, and must not read as finished; a scenario meant to be skipped stays out.
def test_scenarios_skipped_after_a_failure_in_a_serial_group_are_named(tmp_path: Path) -> None:
    serial = {"suites": [{"title": "space-members.spec.ts", "specs": [], "suites": [{
        "title": "Space-Anlage und Mitglieder", "suites": [], "specs": [
            {"title": "A", "tests": [{"expectedStatus": "passed", "status": "expected",
                                      "results": [{"status": "passed"}, {"status": "passed"}]}]},
            {"title": "B", "tests": [{"expectedStatus": "passed", "status": "unexpected",
                                      "results": [{"status": "failed", "error": {"message": "Timeout"}},
                                                  {"status": "failed", "error": {"message": "Timeout"}}]}]},
            {"title": "C", "tests": [{"expectedStatus": "passed", "status": "skipped",
                                      "results": [{"status": "skipped"}, {"status": "skipped"}]}]},
            {"title": "D", "tests": [{"expectedStatus": "skipped", "status": "skipped",
                                      "results": [{"status": "skipped"}]}]},
        ]}]}]}
    write(tmp_path / "e2e", record(1, "A", file="space-members.spec.ts"), playwright=serial)

    text = report(tmp_path)

    assert "| Szenarien nicht vollständig | 2 |" in text
    assert "| space-members.spec.ts › Space-Anlage und Mitglieder › B | `failed` | Timeout |" in text
    assert ("| space-members.spec.ts › Space-Anlage und Mitglieder › C | `übersprungen nach Fehlschlag`"
            in text)
    assert "Mitglieder › D" not in text


def test_colour_schemes_are_counted_per_state_not_per_page(tmp_path: Path) -> None:
    write(tmp_path / "e2e",
          record(1, "Katalog (helles Farbschema)", path="/catalog"),
          record(2, "Katalog (dunkles Farbschema)", "dark", path="/catalog"),
          record(3, "Katalog, Menü Weitere Aktionen", "dark", path="/catalog"),
          record(4, "Passwort festlegen (light)", path="/set-password"),
          record(5, "Passwort festlegen (dark)", "dark", path="/set-password"))

    text = report(tmp_path)

    assert "| Zustände in beiden Farbschemata | 2 von 3 |" in text
    # The menu on a page checked in both schemes is named, though its page is not.
    assert "| Katalog, Menü Weitere Aktionen | `/catalog` | nur dunkel |" in text
    assert "| Katalog | `/catalog` |" not in text
    # English scheme names of a scenario read in German.
    assert "| Passwort festlegen (hell) |" in text and "(light)" not in text


def test_only_the_last_attempt_of_a_retried_scenario_counts(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat (helles Farbschema)", violations=[SERIOUS]),
          record(2, "Chat (helles Farbschema)", retry=1))

    text = report(tmp_path)

    assert "| Geprüfte Zustände | 1 (hell 1, dunkel 0) auf 1 Seiten |" in text
    assert "Keine Befunde." in text


def test_states_are_ordered_by_measurement_time_across_a_worker_restart(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(7, "Erster", measuredAt="2026-10-24T08:00:00.000Z", test="A"),
          record(1, "Zweiter", measuredAt="2026-10-24T08:05:00.000Z", test="B"))

    text = report(tmp_path)

    assert text.index("| Erster (hell)") < text.index("| Zweiter (hell)")


def test_undecided_rules_and_exceptions_are_listed(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "E-Mail, Vorlagen", disabledRules=["region"],
                                   excluded=['iframe[title="Vorschau"]'],
                                   incomplete=[{"id": "color-contrast", "nodes": 4}]))

    text = report(tmp_path)

    assert "Regel `region` aus, ohne `iframe[title=\"Vorschau\"]`" in text
    assert "| `color-contrast` | 1 | 4 |" in text
    assert "| `color-contrast` | 1 | 0 | 1 |" in text


@pytest.mark.parametrize("case", ["missing", "no-expected", "no-report", "empty", "malformed"])
def test_results_that_cannot_make_up_a_report_fail(tmp_path: Path, case: str) -> None:
    results = tmp_path / "results"
    if case != "missing":
        write(results / "e2e", record(1, "Chat"))
    if case == "no-expected":
        (results / "e2e" / "expected.txt").unlink()
    if case == "no-report":
        (results / "e2e" / "playwright.json").unlink()
    if case == "empty":
        (results / "e2e" / "0001-t0.json").unlink()
    if case == "malformed":
        (results / "e2e" / "0002.json").write_text("{\"context\": 1}", encoding="utf-8")

    with pytest.raises(accessibility_report.ReportError):
        accessibility_report.load(results)


def test_the_raw_results_lie_beside_the_report_for_tools(tmp_path: Path) -> None:
    write(tmp_path / "e2e", record(1, "Chat", violations=[SERIOUS]))

    files = accessibility_report.render(accessibility_report.load(tmp_path), "1.2.3", "a" * 40)

    raw = json.loads(files[accessibility_report.RESULTS])
    assert raw["version"] == "1.2.3"
    assert raw["runs"][0]["expected"] == ["accessibility.spec.ts"]
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
    assert body.count("E2E_A11Y_REPORT_ONLY: 'true'") == 3
    assert "pnpm run test:local-auth || rc=$?" in body
    assert "pnpm run test:installation || rc=$?" in body
    assert "accessibility-results/e2e" in body and "accessibility-results/local-auth" in body
    assert "accessibility-results/installation" in body
    assert "python3 .github/scripts/accessibility_report.py" in body
    assert "name: accessibility-results" in body


def test_every_spec_with_an_axe_check_is_expected_and_runs_in_the_accessibility_run() -> None:
    """The run finds the specs itself; a new one is measured, and expected, without a list."""
    body = job(REUSABLE.read_text(encoding="utf-8"), "axe")

    # The set-up assistant needs a stack in installation mode and is measured in its own step.
    assert ("grep -l 'expectNoSeriousA11yViolations(' tests/*.spec.ts | xargs -n1 basename \\\n"
            "            | grep -vx 'installation-assistant.spec.ts' | sort)" in body)
    assert "echo installation-assistant.spec.ts >\"$E2E_A11Y_RESULTS_DIR/expected.txt\"" in body
    assert "pnpm test \"${specs[@]}\" || rc=$?" in body
    assert "grep -l 'expectNoSeriousA11yViolations(' local-auth/tests/*.spec.ts" in body
    # An empty search stops before a suite runs, not after it.
    assert body.index("if ((${#specs[@]} == 0)); then") < body.index('pnpm test "${specs[@]}"')
    assert body.index('if [[ ! -s "$E2E_A11Y_RESULTS_DIR/expected.txt" ]]; then') < body.index(
        "pnpm run test:local-auth")
    assert body.count('>"$E2E_A11Y_RESULTS_DIR/expected.txt"') == 3
    # The pattern finds a call, not the import of the fixture.
    calls = [p for p in (ROOT / "e2e").rglob("*.spec.ts") if "node_modules" not in p.parts
             and "expectNoSeriousA11yViolations(" in p.read_text(encoding="utf-8")]
    assert any(p.name == "accessibility.spec.ts" for p in calls)
    assert any("local-auth" in p.parts for p in calls)


def test_the_seed_of_the_accessibility_run_installs_only_hash_pinned_wheels() -> None:
    body = job(REUSABLE.read_text(encoding="utf-8"), "axe")
    runner = (ROOT / "e2e" / "scripts" / "run-e2e.mjs").read_text(encoding="utf-8")
    pinned = (HERE.parent / "requirements-seed.txt").read_text(encoding="utf-8")
    seed = (ROOT / "demo" / "seed" / "requirements.txt").read_text(encoding="utf-8")

    assert "E2E_SEED_HASHED: 'true'" in body
    assert "process.env.E2E_SEED_HASHED === 'true'" in runner
    for flag in ("'--require-hashes'", "'--only-binary=:all:'", "'--no-deps'", "'.github/requirements-seed.txt'"):
        assert flag in runner, flag
    names = re.findall(r"^([A-Za-z0-9_.-]+)==\S+ \\\n    --hash=sha256:[0-9a-f]{64}", pinned, re.M)
    assert names
    for line in seed.splitlines():
        if line.strip() and not line.startswith("#"):
            assert re.split(r"[<>=!~\[ ]", line, maxsplit=1)[0] in names, line


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
