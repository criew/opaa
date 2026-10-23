"""Self-check for check_behoerden_headers.sh: every Markdown file under docs/behoerden/ except the
top-level README, AGENTS.md and CLAUDE.md carries the full header block, the verbatim disclaimer
right after the title and only well-formed fact sheet placeholders; an overdue review date is no
error."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_behoerden_headers.sh"
BASH = shutil.which("bash") or "bash"
DISCLAIMER = (
    "> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde."
)
HEADER = {
    "stand": "2026-10-15",
    "gilt-fuer": "main",
    "erzeugung": "redaktionell",
    "rechtsstand": "2026-10-13",
    "pruefvermerk": "nicht rechtlich geprüft",
    "naechste-durchsicht": "2027-04-15",
}
BODY = (
    "# Vorlage\n\n"
    + DISCLAIMER
    + "\n\nDie Fristen stehen im {{faktenblatt:fristen}}, die Ereignisse im "
    "{{faktenblatt:protokollereignisse#anmeldung}}.\n"
)


SHEETS = ["datenkatalog", "protokollereignisse", "rollensicht", "datenfluesse", "voreinstellungen", "fristen"]
ANCHORS = (
    "# Generated; do not edit.\n"
    + "".join(f"{sheet}\n" for sheet in SHEETS)
    + "protokollereignisse#anmeldung\nfristen#audit-retention\n"
)
RULES = (
    "# Regeln für Vorlagen\n\n"
    "- `<name>` ist eines der Faktenblätter: `datenkatalog`, `protokollereignisse`,\n"
    "  `rollensicht`, `datenfluesse`, `voreinstellungen`, `fristen`.\n\n"
    "Beispiel: `{{faktenblatt:<name>#<anker>}}`, `{{faktenblatt:fristen#audit-retention}}`.\n"
)


def document(header: dict[str, str] | None = None, body: str = BODY) -> str:
    fields = HEADER if header is None else header
    lines = "".join(f"{key}: {value}\n" for key, value in fields.items())
    return f"---\n{lines}---\n\n{body}"


def run_check(repo: Path) -> subprocess.CompletedProcess:
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    folder = tmp_path / "docs" / "behoerden"
    (folder / "04-datenschutz").mkdir(parents=True)
    (folder / "README.md").write_text("# Unterlagen für Behörden\n", encoding="utf-8")
    (folder / "AGENTS.md").write_text(RULES, encoding="utf-8")
    (folder / "CLAUDE.md").write_text("@AGENTS.md\n", encoding="utf-8")
    (tmp_path / ".github").mkdir()
    (tmp_path / ".github" / "fact-sheet-anchors.txt").write_text(ANCHORS, encoding="utf-8")
    return tmp_path


def write(repo: Path, content: str, name: str = "04-datenschutz/vorlage.md") -> None:
    (repo / "docs" / "behoerden" / name).write_text(content, encoding="utf-8")


def assert_rejected(repo: Path, needle: str) -> None:
    result = run_check(repo)
    assert result.returncode == 1, result.stdout
    assert needle in result.stdout


def test_folder_with_readme_only_passes(repo: Path) -> None:
    assert run_check(repo).returncode == 0


def test_complete_document_passes(repo: Path) -> None:
    write(repo, document())
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


def test_overdue_review_date_is_no_error(repo: Path) -> None:
    write(repo, document({**HEADER, "naechste-durchsicht": "2000-01-01"}))
    assert run_check(repo).returncode == 0


@pytest.mark.parametrize("version", ["main", "1.4.0", "0.12.3"])
def test_accepts_main_or_release_version(repo: Path, version: str) -> None:
    write(repo, document({**HEADER, "gilt-fuer": version}))
    assert run_check(repo).returncode == 0


def test_quoted_values_pass(repo: Path) -> None:
    write(repo, document({**HEADER, "stand": '"2026-10-15"', "pruefvermerk": "'nicht rechtlich geprüft'"}))
    assert run_check(repo).returncode == 0


def test_rejects_document_without_header(repo: Path) -> None:
    write(repo, "# Vorlage\n\n" + DISCLAIMER + "\n")
    assert_rejected(repo, "no header block")


def test_rejects_unterminated_header(repo: Path) -> None:
    write(repo, "---\nstand: 2026-10-15\n\n# Vorlage\n")
    assert_rejected(repo, "no header block")


@pytest.mark.parametrize("field", sorted(HEADER))
def test_rejects_missing_field(repo: Path, field: str) -> None:
    write(repo, document({key: value for key, value in HEADER.items() if key != field}))
    assert_rejected(repo, f'missing field "{field}"')


def test_rejects_duplicate_field(repo: Path) -> None:
    write(repo, document().replace("stand: 2026-10-15\n", "stand: 2026-10-15\nstand: 2026-10-16\n", 1))
    assert_rejected(repo, 'field "stand" appears more than once')


@pytest.mark.parametrize("field", ["stand", "rechtsstand", "naechste-durchsicht"])
@pytest.mark.parametrize("value", ["15.10.2026", "2026-10", "", "2026-13-01"])
def test_rejects_malformed_date(repo: Path, field: str, value: str) -> None:
    write(repo, document({**HEADER, field: value}))
    assert_rejected(repo, f'field "{field}"')


@pytest.mark.parametrize("version", ["latest", "v1.4.0", "1.4", ""])
def test_rejects_malformed_version(repo: Path, version: str) -> None:
    write(repo, document({**HEADER, "gilt-fuer": version}))
    assert_rejected(repo, 'field "gilt-fuer"')


def test_rejects_generated_document(repo: Path) -> None:
    write(repo, document({**HEADER, "erzeugung": "generiert"}))
    assert_rejected(repo, 'field "erzeugung"')


def test_rejects_other_review_note(repo: Path) -> None:
    write(repo, document({**HEADER, "pruefvermerk": "rechtlich geprüft"}))
    assert_rejected(repo, 'field "pruefvermerk"')


def test_rejects_missing_disclaimer(repo: Path) -> None:
    write(repo, document(body="# Vorlage\n\nText.\n"))
    assert_rejected(repo, "disclaimer")


def test_rejects_altered_disclaimer(repo: Path) -> None:
    write(repo, document(body=BODY.replace("Nicht rechtlich geprüft.", "Nicht geprüft.")))
    assert_rejected(repo, "disclaimer")


def test_rejects_disclaimer_further_down(repo: Path) -> None:
    write(repo, document(body="# Vorlage\n\nEinleitung.\n\n" + DISCLAIMER + "\n"))
    assert_rejected(repo, "disclaimer")


def test_accepts_disclaimer_without_title(repo: Path) -> None:
    write(repo, document(body=DISCLAIMER + "\n\n# Vorlage\n"))
    assert run_check(repo).returncode == 0


@pytest.mark.parametrize(
    "placeholder",
    [
        "{{faktenblatt:speicherorte}}",
        "{{ faktenblatt:fristen }}",
        "{{Faktenblatt:fristen}}",
        "{{faktenblatt:fristen#Anmeldung}}",
        "{{faktenblatt:fristen",
    ],
)
def test_rejects_malformed_fact_sheet_placeholder(repo: Path, placeholder: str) -> None:
    write(repo, document(body=BODY + f"\nSiehe {placeholder}.\n"))
    assert_rejected(repo, "fact sheet placeholder")


def test_checks_every_subfolder_and_reports_all_files(repo: Path) -> None:
    write(repo, document())
    (repo / "docs/behoerden/05-mitbestimmung").mkdir()
    write(repo, "# Ohne Kopf\n", "05-mitbestimmung/vorlage.md")
    write(repo, "# Ohne Kopf\n", "ohne-kopf.md")
    result = run_check(repo)
    assert result.returncode == 1
    assert "docs/behoerden/05-mitbestimmung/vorlage.md" in result.stdout
    assert "docs/behoerden/ohne-kopf.md" in result.stdout
    assert "04-datenschutz/vorlage.md" not in result.stdout


def test_folder_overview_needs_no_header_but_valid_links(repo: Path) -> None:
    write(repo, "# Datenschutz\n\n[DSFA](vorlage.md)\n", "04-datenschutz/README.md")
    write(repo, document())
    assert run_check(repo).returncode == 0
    write(repo, "# Datenschutz\n\n[fehlt](fehlt.md)\n", "04-datenschutz/README.md")
    assert_rejected(repo, "points to a missing file")


def test_rejects_missing_folder_readme(repo: Path) -> None:
    (repo / "docs/behoerden/README.md").unlink()
    assert_rejected(repo, "README.md")


def test_rejects_missing_rules_file(repo: Path) -> None:
    (repo / "docs/behoerden/AGENTS.md").unlink()
    assert_rejected(repo, "AGENTS.md")


@pytest.mark.parametrize(
    "copied", ["`opaa.chat.auto-cleanup.max-age`", "Ereignis DOCUMENT_DELETED", "opaa.audit.retention"]
)
def test_copied_fact_value_only_warns(repo: Path, copied: str) -> None:
    write(repo, document(body=BODY + f"\nDie Frist steuert {copied}.\n"))
    result = run_check(repo)
    assert result.returncode == 0, result.stdout
    assert "::warning file=docs/behoerden/04-datenschutz/vorlage.md,line=" in result.stdout
    assert "fact sheet" in result.stdout


def test_placeholder_and_legal_references_do_not_warn(repo: Path) -> None:
    write(repo, document(body=BODY + "\nArt. 35 Abs. 11 DSGVO, § 80 Abs. 1 Nr. 21 BPersVG, BSI OPS.1.1.3.\n"))
    result = run_check(repo)
    assert result.returncode == 0
    assert "::warning" not in result.stdout


@pytest.mark.parametrize("name", ["vorlage.MD", "vorlage.markdown", "vorlage.Markdown"])
def test_checks_markdown_files_regardless_of_extension_case(repo: Path, name: str) -> None:
    write(repo, "# Ohne Kopf\n", name)
    assert_rejected(repo, f"docs/behoerden/{name}")


@pytest.mark.parametrize("value", ["2026-02-31", "2026-04-31", "2027-02-29"])
def test_rejects_impossible_calendar_date(repo: Path, value: str) -> None:
    write(repo, document({**HEADER, "stand": value}))
    assert_rejected(repo, 'field "stand"')


def test_accepts_leap_day(repo: Path) -> None:
    write(repo, document({**HEADER, "stand": "2028-02-29"}))
    assert run_check(repo).returncode == 0


@pytest.mark.parametrize("fence", ["```", "~~~"])
def test_code_blocks_do_not_warn(repo: Path, fence: str) -> None:
    block = f"\n{fence}yaml\nopaa.chat.auto-cleanup.max-age: 30d\nDOCUMENT_DELETED\n{fence}\n"
    write(repo, document(body=BODY + block))
    result = run_check(repo)
    assert result.returncode == 0, result.stdout
    assert "::warning" not in result.stdout


def test_designations_with_digits_after_underscore_do_not_warn(repo: Path) -> None:
    write(repo, document(body=BODY + "\nNach ISO_27001 und EN_301_549.\n"))
    result = run_check(repo)
    assert result.returncode == 0
    assert "::warning" not in result.stdout


def test_text_after_code_block_still_warns(repo: Path) -> None:
    write(repo, document(body=BODY + "\n```\nx\n```\n\nEreignis DOCUMENT_DELETED.\n"))
    assert "::warning" in run_check(repo).stdout


def test_rejects_placeholder_with_unknown_anchor(repo: Path) -> None:
    write(repo, document(body=BODY + "\nSiehe {{faktenblatt:fristen#gibt-es-nicht}}.\n"))
    assert_rejected(repo, "names no anchor")


def test_rejects_sheet_name_missing_from_the_anchor_file(repo: Path) -> None:
    anchors = repo / ".github" / "fact-sheet-anchors.txt"
    anchors.write_text(ANCHORS.replace("fristen\n", ""), encoding="utf-8")
    write(repo, document())
    assert_rejected(repo, "fact sheet name list")


def test_rejects_missing_anchor_file(repo: Path) -> None:
    (repo / ".github" / "fact-sheet-anchors.txt").unlink()
    assert_rejected(repo, "complianceAnchors")


def test_rejects_rules_name_list_that_differs(repo: Path) -> None:
    rules = repo / "docs" / "behoerden" / "AGENTS.md"
    rules.write_text(RULES.replace("`fristen`", "`speicherfristen`"), encoding="utf-8")
    assert_rejected(repo, "fact sheet name list")


def test_rejects_rules_example_with_unknown_anchor(repo: Path) -> None:
    rules = repo / "docs" / "behoerden" / "AGENTS.md"
    rules.write_text(RULES + "{{faktenblatt:rollensicht#gibt-es-nicht}}\n", encoding="utf-8")
    assert_rejected(repo, "names no target")


# Regression guard: under pipefail, a reader that stops early (grep -q, awk exit) must not read
# from a pipe, or the writer's SIGPIPE fails the check for a valid document at random.
def test_valid_anchor_among_many_targets_passes_every_run(repo: Path) -> None:
    many = "".join(f"fristen#frist-{i:05d}\n" for i in range(20000))
    (repo / ".github" / "fact-sheet-anchors.txt").write_text(
        ANCHORS + many, encoding="utf-8"
    )
    write(repo, document(body=BODY + "\nSiehe {{faktenblatt:fristen#frist-00001}}.\n"))
    for _ in range(5):
        result = run_check(repo)
        assert result.returncode == 0, result.stdout + result.stderr


def test_long_document_passes_every_run(repo: Path) -> None:
    write(repo, document(body=BODY + "\nText ohne Fakten.\n" * 20000))
    for _ in range(5):
        result = run_check(repo)
        assert result.returncode == 0, result.stdout + result.stderr


# Links: a relative link names an existing file and, with an anchor, a rendered heading.

HANDBOOK = (
    "---\ntitel: vorspann\n---\n\n"
    "# Betrieb\n\n## Software-Stückliste (SBOM)\n\n## Ebene 1 — Images\n\n"
    "## Teil\n\n## Teil 1\n\n## Teil\n\n## Anmeldung mit `oidc`\n\n```text\n# im-codeblock\n```\n\n"
    "## Ein _kursiver_ Titel mit snake_case\n\n   ### Eingerückt\n\n"
    "Setext Titel\n============\n\nZweiter Setext\n---\n\n"
    '<a id="eigener-anker"></a>\n'
)


def write_handbook(repo: Path) -> None:
    (repo / "docs" / "handbuch").mkdir(parents=True, exist_ok=True)
    (repo / "docs" / "handbuch" / "betrieb.md").write_text(HANDBOOK, encoding="utf-8")


@pytest.mark.parametrize(
    "anchor",
    [
        "software-stückliste-sbom",
        "ebene-1--images",
        "teil",
        "teil-1",
        "teil-2",
        "anmeldung-mit-oidc",
        "ein-kursiver-titel-mit-snake_case",
        "eingerückt",
        "setext-titel",
        "zweiter-setext",
        "software-st%C3%BCckliste-sbom",
        "eigener-anker",
    ],
)
def test_accepts_link_to_rendered_heading(repo: Path, anchor: str) -> None:
    write_handbook(repo)
    write(repo, document(body=BODY + f"\n[Betrieb](../../handbuch/betrieb.md#{anchor})\n"))
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize(
    "anchor", ["software-stueckliste-sbom", "teil-3", "im-codeblock", "Teil", "titel-vorspann"]
)
def test_rejects_link_to_heading_that_does_not_render(repo: Path, anchor: str) -> None:
    write_handbook(repo)
    write(repo, document(body=BODY + f"\n[Betrieb](../../handbuch/betrieb.md#{anchor})\n"))
    assert_rejected(repo, "names no heading or anchor")


def test_rejects_link_to_missing_file(repo: Path) -> None:
    write(repo, document(body=BODY + "\n[Betrieb](../../handbuch/gibt-es-nicht.md)\n"))
    assert_rejected(repo, "points to a missing file")


def test_accepts_links_to_directory_and_own_heading(repo: Path) -> None:
    write_handbook(repo)
    body = BODY + "\n## Hinweise\n\n[Handbuch](../../handbuch/), [oben](#hinweise)\n"
    write(repo, document(body=body))
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


def test_ignores_external_links_and_code(repo: Path) -> None:
    body = (
        BODY
        + "\n[extern](https://example.org/x.md#y), `[code](fehlt.md)`\n"
        + "\n```\n[block](fehlt.md)\n```\n"
    )
    write(repo, document(body=body))
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


def test_checks_links_of_the_rules_file(repo: Path) -> None:
    rules = repo / "docs" / "behoerden" / "AGENTS.md"
    rules.write_text(RULES + "\n[fehlt](04-datenschutz/fehlt.md)\n", encoding="utf-8")
    assert_rejected(repo, "points to a missing file")


# Legal templates under 03-vergabe/: sources section, citations, rechtsstand, neutrality.

SOURCES = (
    "\n## Zum Nachlesen\n\n| Aussage | Fundstelle | Herkunft |\n|---|---|---|\n"
    "| Produktneutralität | [§ 31 Abs. 6 VgV](https://www.gesetze-im-internet.de/vgv_2016/__31.html)"
    " | Normtext |\n"
)


def write_legal(
    repo: Path, body: str, name: str = "zuschlag.md", header: dict[str, str] | None = None
) -> None:
    (repo / "docs" / "behoerden" / "03-vergabe").mkdir(exist_ok=True)
    write(repo, document(header, body=body), f"03-vergabe/{name}")


def requirement_table(ids: list[str]) -> str:
    return "\n| Nr. | Text | Stufe |\n|---|---|---|\n" + "".join(f"| {i} | Text | Muss |\n" for i in ids)


def test_legal_template_with_listed_citation_passes(repo: Path) -> None:
    write_legal(repo, BODY + "\nNach § 31 VgV bleibt die Beschreibung neutral.\n" + SOURCES)
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


def test_rejects_legal_template_without_sources_section(repo: Path) -> None:
    write_legal(repo, BODY + "\nNach § 31 Abs. 6 VgV.\n")
    assert_rejected(repo, 'missing section "## Zum Nachlesen"')


def test_sources_section_is_required_only_under_vergabe(repo: Path) -> None:
    write(repo, document(body=BODY + "\nNach § 31 Abs. 6 VgV.\n"))
    assert run_check(repo).returncode == 0


@pytest.mark.parametrize(
    ("statement", "citation"),
    [("Markterkundung nach § 28 VgV.", "§ 28 VgV"), ("Art. 28 Abs. 3 DSGVO gilt.", "Art. 28 DSGVO")],
)
def test_rejects_citation_missing_from_sources(repo: Path, statement: str, citation: str) -> None:
    write_legal(repo, BODY + f"\n{statement}\n" + SOURCES)
    assert_rejected(repo, f'citation "{citation}" is missing')


def test_rejects_source_row_without_link(repo: Path) -> None:
    write_legal(repo, BODY + SOURCES + "| Schwellenwerte | Vergabeverordnung | Normtext |\n")
    assert_rejected(repo, "source row without link")


def test_accepts_source_row_marked_for_review(repo: Path) -> None:
    write_legal(repo, BODY + SOURCES + "| Schwellenwerte | Fundstelle zu prüfen | offen |\n")
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


def test_rejects_rechtsstand_after_stand(repo: Path) -> None:
    write_legal(repo, BODY + SOURCES, header={**HEADER, "rechtsstand": "2026-10-16"})
    assert_rejected(repo, "rechtsstand 2026-10-16 lies after stand 2026-10-15")


@pytest.mark.parametrize("name", ["anforderungskatalog.md", "zuschlagskriterien.md"])
def test_rejects_product_name_in_vendor_neutral_template(repo: Path, name: str) -> None:
    write_legal(repo, BODY + "\nOPAA beantwortet Fragen.\n" + SOURCES, name)
    write_legal(repo, BODY + SOURCES, "erfuellungsmatrix.md")
    assert_rejected(repo, "must not name OPAA")


def test_product_name_is_allowed_in_the_matrix(repo: Path) -> None:
    write_legal(repo, BODY + "\nOPAA erfüllt die Anforderungen.\n" + SOURCES, "erfuellungsmatrix.md")
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize(
    ("catalog", "matrix", "needle"),
    [
        (["F-01", "F-02"], ["F-01"], "requirement F-02 of"),
        (["F-01"], ["F-01", "B-09"], "requirement B-09 is not in"),
        (["F-01"], [], "requirement F-01 of"),
    ],
)
def test_matrix_answers_exactly_the_catalog(
    repo: Path, catalog: list[str], matrix: list[str], needle: str
) -> None:
    write_legal(repo, BODY + requirement_table(catalog) + SOURCES, "anforderungskatalog.md")
    write_legal(repo, BODY + requirement_table(matrix) + SOURCES, "erfuellungsmatrix.md")
    assert_rejected(repo, needle)


def test_matrix_with_the_catalog_numbers_passes(repo: Path) -> None:
    ids = ["F-01", "L-08"]
    write_legal(repo, BODY + requirement_table(ids) + SOURCES, "anforderungskatalog.md")
    write_legal(repo, BODY + requirement_table(ids) + SOURCES, "erfuellungsmatrix.md")
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize(
    "statement",
    [
        "Nach § 31\nAbs. 6 VgV.",
        "Nach § 31 Absatz 6 S. 1 VgV.",
        "Nach §§ 31 ff. VgV.",
        "(§ 31 Abs. 6 VgV)",
    ],
)
def test_recognises_citation_forms(repo: Path, statement: str) -> None:
    write_legal(repo, BODY + f"\n{statement}\n" + SOURCES)
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


def test_citation_spanning_lines_must_be_listed(repo: Path) -> None:
    write_legal(repo, BODY + "\nMarkterkundung nach § 28\nVgV.\n" + SOURCES)
    assert_rejected(repo, 'citation "§ 28 VgV" is missing')


def test_recognises_article_with_letter(repo: Path) -> None:
    sources = SOURCES + "| Rechtsgrundlage | [Art. 6 DSGVO](https://example.org) | Normtext |\n"
    write_legal(repo, BODY + "\nNach Art. 6 Abs. 1 lit. e DSGVO.\n" + sources)
    result = run_check(repo)
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize("statement", ["Nach § 31 der Verordnung.", "Siehe Art. 5 dazu.", "Das § gilt."])
def test_rejects_unrecognised_citation(repo: Path, statement: str) -> None:
    write_legal(repo, BODY + f"\n{statement}\n" + SOURCES)
    assert_rejected(repo, "not recognised")


def test_citation_in_sources_prose_is_not_a_listing(repo: Path) -> None:
    write_legal(repo, BODY + "\nNach § 28 VgV.\n" + SOURCES + "\nSiehe auch § 28 VgV.\n")
    assert_rejected(repo, 'citation "§ 28 VgV" is missing')


def test_reports_the_line_of_a_missing_citation(repo: Path) -> None:
    write_legal(repo, BODY + "\n\nMarkterkundung nach § 28 VgV.\n" + SOURCES)
    content = (repo / "docs/behoerden/03-vergabe/zuschlag.md").read_text(encoding="utf-8")
    line = content.splitlines().index("Markterkundung nach § 28 VgV.") + 1
    assert_rejected(repo, f"zuschlag.md,line={line}::citation")


def test_link_must_stand_in_the_fundstelle_column(repo: Path) -> None:
    write_legal(repo, BODY + SOURCES + "| Schwellen | Vergabeverordnung | [Fachbeitrag](https://example.org) |\n")
    assert_rejected(repo, 'in column "Fundstelle"')


def test_rejects_sources_table_without_fundstelle_column(repo: Path) -> None:
    sources = "\n## Zum Nachlesen\n\n| Aussage | Quelle |\n|---|---|\n| X | [Y](https://example.org) |\n"
    write_legal(repo, BODY + sources)
    assert_rejected(repo, 'needs a column "Fundstelle"')


@pytest.mark.parametrize(
    "mention", ["opaa", "Opaa", "[Projekt](https://github.com/criew/opaa/issues/1)"]
)
def test_rejects_product_name_in_any_case_and_in_links(repo: Path, mention: str) -> None:
    write_legal(repo, BODY + f"\nSiehe {mention}.\n" + SOURCES, "anforderungskatalog.md")
    write_legal(repo, BODY + SOURCES, "erfuellungsmatrix.md")
    assert_rejected(repo, "must not name OPAA")


def test_rejects_duplicate_requirement_number(repo: Path) -> None:
    write_legal(repo, BODY + requirement_table(["F-01"]) + SOURCES, "anforderungskatalog.md")
    write_legal(repo, BODY + requirement_table(["F-01", "F-01"]) + SOURCES, "erfuellungsmatrix.md")
    assert_rejected(repo, "requirement F-01 has more than one row")
