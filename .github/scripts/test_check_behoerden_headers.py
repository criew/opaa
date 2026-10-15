"""Self-check for check_behoerden_headers.sh: every Markdown file under docs/behoerden/ except the
top-level README carries the full header block, the verbatim disclaimer right after the title and
only well-formed fact sheet placeholders; an overdue review date is no error."""

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
    write(repo, "# Ohne Kopf\n", "05-mitbestimmung/README.md")
    write(repo, "# Ohne Kopf\n", "ohne-kopf.md")
    result = run_check(repo)
    assert result.returncode == 1
    assert "docs/behoerden/05-mitbestimmung/README.md" in result.stdout
    assert "docs/behoerden/ohne-kopf.md" in result.stdout
    assert "04-datenschutz/vorlage.md" not in result.stdout


def test_rejects_missing_folder_readme(repo: Path) -> None:
    (repo / "docs/behoerden/README.md").unlink()
    assert_rejected(repo, "README.md")


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
