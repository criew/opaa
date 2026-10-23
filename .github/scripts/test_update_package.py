"""Self-check for update_package.py, the update package of the release run (docs/releases.md).

Only a release tag runs the package job, and no pull request does, so a wrong previous release, a
non-repeatable archive or a cache in the release job would surface at a release at the earliest.
These cases run the previous-release rule against a local repository, assemble a package from
stand-in inputs and check the release job of publish-images.yml as text.
"""

import gzip
import hashlib
import html
import importlib.util
import json
import os
import posixpath
import re
import subprocess
import sys
import tarfile
import urllib.parse
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "update_package.py"
WORKFLOW = HERE.parent / "workflows" / "publish-images.yml"
RELEASE_NOTES = HERE.parents[1] / "deploy" / "helm" / "ci" / "release-notes.sh"

spec = importlib.util.spec_from_file_location("update_package", SCRIPT)
update_package = importlib.util.module_from_spec(spec)
sys.modules["update_package"] = update_package
spec.loader.exec_module(update_package)


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@example.org", *args],
        cwd=cwd, check=True, capture_output=True, text=True,
    ).stdout.strip()


def commit(repo: Path, message: str) -> str:
    git(repo, "commit", "-q", "--allow-empty", "-m", message)
    return git(repo, "rev-parse", "HEAD")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    """main: c1 (v0.1.0-rc.1) - c2 (v0.1.0) - c3 (v0.1.1) - c4 (v0.2.0-rc.1); a side branch from
    c2 carries v0.1.5, which main never contains."""
    work = tmp_path / "repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    c1 = commit(work, "c1")
    git(work, "tag", "v0.1.0-rc.1", c1)
    c2 = commit(work, "c2")
    git(work, "tag", "-a", "-m", "OPAA v0.1.0", "v0.1.0", c2)
    git(work, "checkout", "-q", "-b", "side")
    git(work, "tag", "v0.1.5", commit(work, "side"))
    git(work, "checkout", "-q", "main")
    git(work, "tag", "v0.1.1", commit(work, "c3"))
    git(work, "tag", "v0.2.0-rc.1", commit(work, "c4"))
    git(work, "tag", "not-a-release", "HEAD")
    git(work, "tag", "v0.2", "HEAD")
    return work


def previous(repo: Path, tag: str, at: str | None = None) -> str | None:
    return update_package.previous_final_tag(repo, tag, at or tag)


def test_a_pre_release_compares_with_the_last_final_release_not_with_a_pre_release(repo: Path) -> None:
    assert previous(repo, "v0.2.0-rc.1") == "v0.1.1"


def test_a_final_release_on_the_commit_of_its_candidate_compares_with_the_last_final_release(repo: Path) -> None:
    git(repo, "tag", "v0.2.0", "v0.2.0-rc.1")

    assert previous(repo, "v0.2.0") == "v0.1.1"


def test_a_patch_release_without_code_change_compares_with_the_release_on_the_same_commit(repo: Path) -> None:
    git(repo, "tag", "v0.1.2", "v0.1.1")

    assert previous(repo, "v0.1.2") == "v0.1.1"


def test_the_first_final_release_has_no_previous_one(repo: Path) -> None:
    assert previous(repo, "v0.1.0") is None
    assert previous(repo, "v0.1.0-rc.1") is None


def test_a_tag_outside_the_history_of_the_release_never_counts(repo: Path) -> None:
    assert previous(repo, "v0.1.1") == "v0.1.0"


def test_a_tag_of_the_same_or_a_higher_version_never_counts(repo: Path) -> None:
    git(repo, "tag", "v1.0.0", "v0.1.0-rc.1")

    assert previous(repo, "v0.2.0-rc.1") == "v0.1.1"


def test_a_trial_names_the_commit_its_tag_does_not_exist_for(repo: Path) -> None:
    assert previous(repo, "v0.3.0", "main") == "v0.1.1"


@pytest.mark.parametrize("tag", ["v0.2", "0.2.0", "v0.2.0-", "release"])
def test_a_tag_that_is_no_release_version_is_refused(repo: Path, tag: str) -> None:
    with pytest.raises(update_package.PackageError, match="no release tag"):
        previous(repo, tag, "main")


def test_an_unknown_commit_is_refused(repo: Path) -> None:
    with pytest.raises(update_package.PackageError, match="unknown commit"):
        previous(repo, "v0.3.0", "--output=/tmp/x")


def test_the_command_prints_the_previous_release_or_nothing(repo: Path) -> None:
    def cli(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(SCRIPT), "previous", *args],
                              cwd=repo, capture_output=True, text=True)

    assert cli("--tag", "v0.2.0-rc.1").stdout == "v0.1.1\n"
    first = cli("--tag", "v0.1.0")
    assert (first.returncode, first.stdout) == (0, "")
    assert cli("--tag", "v0.2").returncode == 1


def test_without_a_previous_release_the_delta_starts_at_the_oldest_root_commit(tmp_path: Path) -> None:
    work = tmp_path / "repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    oldest = commit(work, "first root")
    git(work, "checkout", "-q", "--orphan", "other")
    subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@example.org",
         "commit", "-q", "--allow-empty", "-m", "second root"],
        cwd=work, check=True, env={**os.environ, "GIT_COMMITTER_DATE": "2030-01-01T00:00:00Z"},
    )
    git(work, "checkout", "-q", "main")
    git(work, "merge", "-q", "--allow-unrelated-histories", "-m", "join", "other")

    assert update_package.empty_base(work, "HEAD") == oldest


def test_a_root_commit_with_a_manifest_is_no_empty_base(tmp_path: Path) -> None:
    work = tmp_path / "repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    manifest = work / update_package.MANIFEST_PATH
    manifest.mkdir(parents=True)
    (manifest / "audit-log.yaml").write_text("title: x\n")
    git(work, "add", ".")
    commit(work, "root with manifest")

    with pytest.raises(update_package.PackageError, match="already holds a manifest"):
        update_package.empty_base(work, "HEAD")


EPOCH = 1_790_000_000


def release(**changes) -> "update_package.Release":
    values = dict(version="1.2.3", tag="v1.2.3", commit="a" * 40, previous="v1.2.2",
                  previous_commit="b" * 40, trial=False, repository="criew/opaa", epoch=EPOCH)
    values.update(changes)
    return update_package.Release(**values)


@pytest.fixture
def inputs(tmp_path: Path) -> dict:
    sheets = tmp_path / "in" / "faktenblaetter"
    sheets.mkdir(parents=True)
    for name in ("datenkatalog.md", "fristen.md"):
        (sheets / name).write_text(f"# {name}\n")
    (sheets / "faktenblaetter.json").write_text("{}\n")
    delta = tmp_path / "in" / "delta"
    delta.mkdir()
    (delta / "manifest-delta.md").write_text("# Delta\n")
    (delta / "manifest-delta.json").write_text("{}\n")
    licenses = [tmp_path / "in" / "lizenzen-backend.md", tmp_path / "in" / "lizenzen-frontend.md"]
    for report in licenses:
        report.write_text("| Paket |\n")
    checkout = tmp_path / "in" / "checkout"
    templates = checkout / "docs" / "behoerden"
    for path, text in {
        "README.md": "# Behördenunterlagen\n\n[Update-Vermerk](09-betrieb-update/update-vermerk.md)\n",
        "AGENTS.md": "# Regeln für Autoren\n",
        "CLAUDE.md": "@AGENTS.md\n",
        "09-betrieb-update/update-vermerk.md": "# Update-Vermerk\n\n## Zweck\n",
    }.items():
        (templates / path).parent.mkdir(parents=True, exist_ok=True)
        (templates / path).write_text(text, encoding="utf-8")
    handbook = checkout / "docs" / "handbuch"
    for path, text in {
        "README.md": "# Handbuch\n\n[Betrieb](betrieb.md#ports)\n",
        "AGENTS.md": "# Regeln für Autoren\n",
        "CLAUDE.md": "@AGENTS.md\n",
        "betrieb.md": "# Betrieb\n\n## Ports\n",
    }.items():
        (handbook / path).parent.mkdir(parents=True, exist_ok=True)
        (handbook / path).write_bytes(text.encode("utf-8"))
    for path in ("SECURITY.md", "docs/releases.md"):
        (checkout / path).parent.mkdir(parents=True, exist_ok=True)
        (checkout / path).write_text("# x\n")
    operations = tmp_path / "in" / "betrieb"
    operations.mkdir()
    for name in update_package.OPERATIONS_FILES:
        (operations / name).write_text(f"# {name}\n")
    sboms = {}
    for name in ("backend", "frontend"):
        sboms[name] = tmp_path / "in" / f"{name}.cdx.json"
        sboms[name].write_text(cyclonedx(f"pkg:npm/{name}-dependency@1.0.0"), encoding="utf-8")
    return dict(fact_sheets=sheets, delta=delta, operations=operations, licenses=licenses,
                sboms=sboms, templates=templates, handbook=handbook)


def cyclonedx(*purls: str, **fields) -> str:
    """A CycloneDX BOM as a generator writes it, with its own serial number and time of the run."""
    bom = {"bomFormat": "CycloneDX", "specVersion": "1.6",
           "serialNumber": "urn:uuid:00000000-0000-4000-8000-000000000000", "version": 1,
           "metadata": {"timestamp": "2099-01-01T00:00:00Z"},
           "components": [{"type": "library", "name": purl, "purl": purl} for purl in purls]}
    bom.update(fields)
    return json.dumps(bom)


def put_template(inputs: dict, path: str, text: str) -> None:
    target = inputs["templates"] / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(text.encode("utf-8"))


def members(archive: Path) -> dict[str, tarfile.TarInfo]:
    with tarfile.open(archive) as tar:
        return {m.name: m for m in tar.getmembers()}


def read(archive: Path, name: str) -> bytes:
    with tarfile.open(archive) as tar:
        return tar.extractfile(name).read()


def test_the_package_holds_every_part_under_one_versioned_directory(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    assert archive.name == "opaa-update-paket-1.2.3.tar.gz"
    every = sorted(n for n, m in members(archive).items() if m.isfile())
    files = [n for n in every if not n.endswith((".html", ".css"))]
    assert files == [
        "opaa-update-paket-1.2.3/LIESMICH.md",
        "opaa-update-paket-1.2.3/SHA256SUMS",
        "opaa-update-paket-1.2.3/betrieb/antwortqualitaet.md",
        "opaa-update-paket-1.2.3/betrieb/betriebsfolgen.md",
        "opaa-update-paket-1.2.3/delta/manifest-delta.json",
        "opaa-update-paket-1.2.3/delta/manifest-delta.md",
        "opaa-update-paket-1.2.3/faktenblaetter/datenkatalog.md",
        "opaa-update-paket-1.2.3/faktenblaetter/faktenblaetter.json",
        "opaa-update-paket-1.2.3/faktenblaetter/fristen.md",
        "opaa-update-paket-1.2.3/handbuch/README.md",
        "opaa-update-paket-1.2.3/handbuch/betrieb.md",
        "opaa-update-paket-1.2.3/lizenzen/lizenzen-backend.md",
        "opaa-update-paket-1.2.3/lizenzen/lizenzen-frontend.md",
        "opaa-update-paket-1.2.3/sbom/opaa-backend-1.2.3.cdx.json",
        "opaa-update-paket-1.2.3/sbom/opaa-frontend-1.2.3.cdx.json",
        "opaa-update-paket-1.2.3/update-vermerk.md",
        "opaa-update-paket-1.2.3/vorlagen/09-betrieb-update/update-vermerk.md",
        "opaa-update-paket-1.2.3/vorlagen/README.md",
    ]
    assert [n for n in every if n not in files] == sorted(
        [n[:-3] + ".html" for n in files if n.endswith(".md")]
        + ["opaa-update-paket-1.2.3/index.html", "opaa-update-paket-1.2.3/style.css"])


def test_the_checksum_files_match_the_package(tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/loeschkonzept.md", "# Löschkonzept\n")
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    sums = read(archive, "opaa-update-paket-1.2.3/SHA256SUMS").decode().splitlines()
    every = {n.split("/", 1)[1] for n, m in members(archive).items() if m.isfile()}
    assert {line.split("  ")[1] for line in sums} == every - {"SHA256SUMS"}
    assert any(line.endswith("  index.html") for line in sums)
    assert any(line.endswith("  handbuch/betrieb.md") for line in sums)
    assert any(line.endswith("  vorlagen/04-datenschutz/loeschkonzept.md") for line in sums)
    assert any(line.endswith("  sbom/opaa-frontend-1.2.3.cdx.json") for line in sums)
    for line in sums:
        digest, path = line.split("  ")
        assert hashlib.sha256(read(archive, f"opaa-update-paket-1.2.3/{path}")).hexdigest() == digest
    outer = (archive.parent / "opaa-update-paket-1.2.3.tar.gz.sha256").read_text()
    assert outer == f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}\n"


def test_the_same_inputs_give_the_same_bytes_whatever_their_file_times(tmp_path: Path, inputs: dict) -> None:
    first = update_package.assemble(release(), out=tmp_path / "first", **inputs)
    for path in inputs["fact_sheets"].iterdir():
        os.utime(path, (1, 1))
    second = update_package.assemble(release(), out=tmp_path / "second", **inputs)

    assert first.read_bytes() == second.read_bytes()
    with gzip.open(first) as compressed:
        compressed.read()
    for member in members(first).values():
        assert (member.mtime, member.uid, member.gid, member.uname, member.gname) == (EPOCH, 0, 0, "", "")


def test_the_readme_names_version_previous_release_and_the_verification(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    assert "gilt-fuer: 1.2.3\ncommit: " + "a" * 40 in text
    assert "vorgaenger: v1.2.2\n" in text
    assert "gegenüber v1.2.2, dem vorigen finalen Release." in text
    assert "gh attestation verify opaa-update-paket-1.2.3.tar.gz --repo criew/opaa" in text
    assert "-PtoRef=v1.2.3" in text
    assert "Probelauf" not in text
    assert "| `betrieb/betriebsfolgen.md` |" in text
    assert "| `betrieb/antwortqualitaet.md` |" in text
    assert "| `sbom/` |" in text
    assert "update_package.py operations --from <älteres Tag> --to v1.2.3" in text
    assert "Betriebsfolgen (Bruch" not in text


def test_a_first_release_says_that_every_entry_is_new(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(previous=None), out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    assert "vorgaenger: keiner\n" in text
    assert "kein voriges finales Release" in text


def test_a_trial_says_it_is_no_published_release_and_names_the_commit(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(trial=True), out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    assert "**Probelauf:**" in text
    assert "-PtoRef=" + "a" * 40 in text
    assert "-PtoRef=v1.2.3" not in text


def test_a_given_comparison_is_not_called_the_previous_final_release(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(
        release(previous="520713d46", previous_given=True), out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    assert "Änderungen gegenüber 520713d46." in text
    assert "vorigen finalen Release" not in text


def test_a_first_release_names_its_comparison_base_without_the_raw_commit(
        tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(previous=None), out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    body = text.split("\n---\n", 1)[1]
    assert "Stand ohne Fakten-Manifest (Commit `bbbbbbb`)" in body
    assert "b" * 40 not in body


def test_a_given_commit_without_manifest_is_named_readably(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(
        release(previous="c" * 40, previous_commit="c" * 40, previous_given=True, previous_bare=True),
        out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    assert "Änderungen gegenüber dem Stand ohne Fakten-Manifest (Commit `ccccccc`)." in text
    assert "c" * 40 not in text.split("\n---\n", 1)[1]


def package_markdown(archive: Path) -> dict[str, str]:
    with tarfile.open(archive) as tar:
        return {m.name.split("/", 1)[1]: tar.extractfile(m).read().decode()
                for m in tar.getmembers() if m.isfile() and m.name.endswith(".md")}


def with_placeholders(inputs: dict, template: str, operations: str = "") -> dict:
    (inputs["fact_sheets"] / "fristen.md").write_text(
        "# Faktenblatt: Fristen\n\n| <a id=\"audit-retention\"></a>`audit` |\n")
    (inputs["fact_sheets"] / "datenkatalog.md").write_text(
        "# Faktenblatt: Datenkatalog\n\n## <a id=\"bereich-audit-log\"></a>Protokoll\n")
    put_template(inputs, "09-betrieb-update/update-vermerk.md", template)
    if operations:
        (inputs["operations"] / "betriebsfolgen.md").write_text(operations)
    return inputs


def test_fact_sheet_placeholders_become_links_to_the_sheets_in_the_package(
        tmp_path: Path, inputs: dict) -> None:
    with_placeholders(
        inputs,
        "- `faktenblaetter/`: etwa {{faktenblatt:fristen}} und\n  {{faktenblatt:datenkatalog}},\n"
        "Frist: {{faktenblatt:fristen#audit-retention}}\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_markdown(archive)
    assert ("etwa [Faktenblatt Fristen](faktenblaetter/fristen.md) und\n"
            "  [Faktenblatt Datenkatalog](faktenblaetter/datenkatalog.md),") in files["update-vermerk.md"]
    assert "[Faktenblatt Fristen](faktenblaetter/fristen.md#audit-retention)" in files["update-vermerk.md"]
    for path, text in files.items():
        assert "{{" not in text, path
        for target in re.findall(r"\]\(([^)#]+\.md)(?:#[^)]*)?\)", text):
            assert os.path.normpath(os.path.join(os.path.dirname(path), target)) in files, (path, target)


@pytest.mark.parametrize("placeholder", [
    "{{faktenblatt:speicherorte}}",
    "{{faktenblatt:fristen#gibt-es-nicht}}",
    "{{ faktenblatt:fristen }}",
    "{{faktenblatt:fristen",
    "{{faktenblatt:faktenblaetter}}",
])
def test_an_unknown_or_malformed_placeholder_fails_the_package(
        tmp_path: Path, inputs: dict, placeholder: str) -> None:
    with_placeholders(inputs, f"Siehe {placeholder}.\n")

    with pytest.raises(update_package.PackageError, match="placeholder"):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


def test_a_link_from_a_subdirectory_is_relative_to_it() -> None:
    sheets = {"datenkatalog": "# Faktenblatt: Datenkatalog\n\n<a id=\"audit-log\"></a>\n"}

    text = update_package.resolve_placeholders(
        "Siehe {{faktenblatt:datenkatalog#audit-log}}.", "betrieb/x.md", sheets)

    assert text == "Siehe [Faktenblatt Datenkatalog](../faktenblaetter/datenkatalog.md#audit-log)."


def test_placeholders_in_code_stay_as_written(tmp_path: Path, inputs: dict) -> None:
    template = ("Form: `{{faktenblatt:<name>}}` oder ``{{faktenblatt:fristen}}``.\n\n"
                "```text\n{{faktenblatt:<name>#<anker>}}\n```\n\n"
                "~~~\n{{ faktenblatt:fristen }}\n~~~\n\n"
                "Siehe {{faktenblatt:fristen}}.\n")
    with_placeholders(inputs, template)

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    assert package_markdown(archive)["update-vermerk.md"] == template.replace(
        "Siehe {{faktenblatt:fristen}}.", "Siehe [Faktenblatt Fristen](faktenblaetter/fristen.md).")


def test_a_placeholder_in_a_pull_request_title_stays_text(tmp_path: Path, inputs: dict) -> None:
    delta = ("# Delta\n\n## Weitere gekennzeichnete Änderungen\n\n"
             "- [#7 docs: Verweis {{faktenblatt:fristen}} ergaenzen](https://github.com/criew/opaa/pull/7)\n"
             "- [#8 docs: {{faktenblatt:gibt-es-nicht}}](https://github.com/criew/opaa/pull/8)\n")
    with_placeholders(inputs, "# Update-Vermerk\n")
    (inputs["delta"] / "manifest-delta.md").write_text(delta)

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    assert package_markdown(archive)["delta/manifest-delta.md"] == delta


def test_an_upgrade_note_with_a_placeholder_stays_as_written(tmp_path: Path, inputs: dict) -> None:
    operations = ("# Betriebsfolgen\n\n### Vorbereitung für 1.2.3\n\n"
                  "- Vorlagen nutzen jetzt `{{faktenblatt:<name>}}`, etwa {{faktenblatt:speicherorte}}.\n")
    with_placeholders(inputs, "# Update-Vermerk\n", operations)

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    assert package_markdown(archive)["betrieb/betriebsfolgen.md"] == operations


def relative_targets(files: dict[str, str]) -> list[tuple[str, str]]:
    """(file, target path) of every link outside code in the package that has no scheme and is no
    bare anchor; code here means the plain ``` blocks and single-backtick spans of these cases."""
    found = []
    for path, text in files.items():
        text = re.sub(r"`[^`\n]*`", "", re.sub(r"```.*?```", "", text, flags=re.DOTALL))
        for target in re.findall(r"\]\(<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\)|^ {0,3}\[[^\]]+\]:\s*(\S+)",
                                 text, re.MULTILINE):
            target = next(t for t in target if t).split("#", 1)[0]
            if target and not re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target):
                found.append((path, target))
    return found


def test_every_template_lands_under_vorlagen_with_its_folder_and_without_author_rules(
        tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/README.md", "# Datenschutz\n")
    put_template(inputs, "04-datenschutz/loeschkonzept.md", "# Löschkonzept\n")
    put_template(inputs, "04-datenschutz/AGENTS.md", "# Kein Autorenregelwerk dieser Ebene\n")
    put_template(inputs, "04-datenschutz/notizen.txt", "kein Markdown\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_markdown(archive)
    assert sorted(p for p in files if p.startswith("vorlagen/")) == [
        "vorlagen/04-datenschutz/AGENTS.md",
        "vorlagen/04-datenschutz/README.md",
        "vorlagen/04-datenschutz/loeschkonzept.md",
        "vorlagen/09-betrieb-update/update-vermerk.md",
        "vorlagen/README.md",
    ]
    assert files["vorlagen/04-datenschutz/loeschkonzept.md"] == "# Löschkonzept\n"
    assert files["update-vermerk.md"] == files["vorlagen/09-betrieb-update/update-vermerk.md"]


def test_placeholders_in_every_template_link_to_the_sheets_from_its_depth(
        tmp_path: Path, inputs: dict) -> None:
    with_placeholders(inputs, "Frist: {{faktenblatt:fristen#audit-retention}}\n")
    put_template(inputs, "README.md", "Daten: {{faktenblatt:datenkatalog}}, `{{faktenblatt:<name>}}`\n")
    put_template(inputs, "04-datenschutz/loeschkonzept.md",
                 "Siehe {{faktenblatt:datenkatalog#bereich-audit-log}}.\n\n```text\n{{faktenblatt:x}}\n```\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_markdown(archive)
    assert files["vorlagen/README.md"] == (
        "Daten: [Faktenblatt Datenkatalog](../faktenblaetter/datenkatalog.md), `{{faktenblatt:<name>}}`\n")
    assert files["vorlagen/04-datenschutz/loeschkonzept.md"] == (
        "Siehe [Faktenblatt Datenkatalog](../../faktenblaetter/datenkatalog.md#bereich-audit-log)."
        "\n\n```text\n{{faktenblatt:x}}\n```\n")
    assert ("[Faktenblatt Fristen](../../faktenblaetter/fristen.md#audit-retention)"
            in files["vorlagen/09-betrieb-update/update-vermerk.md"])
    assert "[Faktenblatt Fristen](faktenblaetter/fristen.md#audit-retention)" in files["update-vermerk.md"]


def test_an_unknown_placeholder_in_any_template_fails_the_package(tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/loeschkonzept.md", "Siehe {{faktenblatt:speicherorte}}.\n")

    with pytest.raises(update_package.PackageError, match="04-datenschutz/loeschkonzept.md.*placeholder"):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


LINKS = (
    "# Löschkonzept\n\n## Fristen\n\n"
    "Notfall: [Notfallkonzept](../09-betrieb-update/update-vermerk.md#zweck), [hier](#fristen),\n"
    "[Übersicht](../README.md), [Ordner](./), [Nachbar](./README.md \"Titel\").\n"
    "Betrieb: [Handbuch](../../handbuch/betrieb.md#ports) und\n"
    "[Meldewege](../../../SECURITY.md), [Releases](<../../releases.md#update-paket>),\n"
    "[Handbuch-Ordner](../../handbuch/), [Regeln](../AGENTS.md).\n"
    "Extern: [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu), [Mail](mailto:a@example.org).\n"
    "Code: `[x](../../handbuch/betrieb.md)`\n\n"
    "```markdown\n[y](../../../SECURITY.md)\n```\n\n"
    "[ref]: ../../handbuch/betrieb.md#ports\n"
)


def test_links_inside_the_package_stay_relative_and_links_out_of_it_point_to_the_release(
        tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/README.md", "# Datenschutz\n")
    put_template(inputs, "04-datenschutz/loeschkonzept.md", LINKS)

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    blob = "https://github.com/criew/opaa/blob/v1.2.3/"
    assert package_markdown(archive)["vorlagen/04-datenschutz/loeschkonzept.md"] == (
        "# Löschkonzept\n\n## Fristen\n\n"
        "Notfall: [Notfallkonzept](../09-betrieb-update/update-vermerk.md#zweck), [hier](#fristen),\n"
        "[Übersicht](../README.md), [Ordner](./), [Nachbar](./README.md \"Titel\").\n"
        "Betrieb: [Handbuch](../../handbuch/betrieb.md#ports) und\n"
        f"[Meldewege]({blob}SECURITY.md), [Releases](<{blob}docs/releases.md#update-paket>),\n"
        f"[Handbuch-Ordner](../../handbuch/), [Regeln]({blob}docs/behoerden/AGENTS.md).\n"
        "Extern: [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu), [Mail](mailto:a@example.org).\n"
        "Code: `[x](../../handbuch/betrieb.md)`\n\n"
        "```markdown\n[y](../../../SECURITY.md)\n```\n\n"
        "[ref]: ../../handbuch/betrieb.md#ports\n"
    )


def test_links_of_the_update_note_at_the_top_of_the_package_follow_its_place(
        tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/loeschkonzept.md", "# Löschkonzept\n\n## Fristen\n")
    with_placeholders(inputs, "[Löschkonzept](../04-datenschutz/loeschkonzept.md#fristen),"
                              " [Releases](../../releases.md), [hier](#zweck),"
                              " [Handbuch](../../handbuch/betrieb.md#ports)\n\n## Zweck\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_markdown(archive)
    assert files["update-vermerk.md"] == (
        "[Löschkonzept](vorlagen/04-datenschutz/loeschkonzept.md#fristen),"
        " [Releases](https://github.com/criew/opaa/blob/v1.2.3/docs/releases.md), [hier](#zweck),"
        " [Handbuch](handbuch/betrieb.md#ports)\n\n## Zweck\n")
    assert files["vorlagen/09-betrieb-update/update-vermerk.md"].startswith(
        "[Löschkonzept](../04-datenschutz/loeschkonzept.md#fristen),")


def test_a_trial_links_out_of_the_templates_to_its_commit(tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "README.md", "[Meldewege](../../SECURITY.md)\n")

    archive = update_package.assemble(release(trial=True), out=tmp_path / "out", **inputs)

    assert package_markdown(archive)["vorlagen/README.md"] == (
        f"[Meldewege](https://github.com/criew/opaa/blob/{'a' * 40}/SECURITY.md)\n")


def test_no_relative_link_in_the_package_points_nowhere(tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/README.md", "# Datenschutz\n")
    put_template(inputs, "04-datenschutz/loeschkonzept.md", LINKS)
    with_placeholders(inputs, "[Löschkonzept](../04-datenschutz/loeschkonzept.md), {{faktenblatt:fristen}}\n"
                              "\n## Zweck\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_markdown(archive)
    directories = {posixpath.dirname(p) for p in files}
    targets = relative_targets(files)
    assert ("vorlagen/04-datenschutz/loeschkonzept.md", "../README.md") in targets
    for path, target in targets:
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
        assert resolved in files or resolved in directories, (path, target)


@pytest.mark.parametrize("link", [
    "../gibt-es-nicht.md",
    "../../handbuch/gibt-es-nicht.md#x",
    "../../../../ausserhalb.md",
])
def test_a_relative_link_to_nothing_fails_the_package(tmp_path: Path, inputs: dict, link: str) -> None:
    put_template(inputs, "04-datenschutz/loeschkonzept.md", f"[Ziel]({link})\n")

    with pytest.raises(update_package.PackageError, match="04-datenschutz/loeschkonzept.md.*link"):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


def put_handbook(inputs: dict, path: str, text: str) -> None:
    target = inputs["handbook"] / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(text.encode("utf-8"))


def test_the_handbook_of_the_version_lands_under_handbuch_without_author_rules(
        tmp_path: Path, inputs: dict) -> None:
    put_handbook(inputs, "betrieb/README.md", "# Betrieb\n")
    put_handbook(inputs, "betrieb/notizen.txt", "kein Markdown\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_markdown(archive)
    assert sorted(p for p in files if p.startswith("handbuch/")) == [
        "handbuch/README.md", "handbuch/betrieb.md", "handbuch/betrieb/README.md"]
    assert files["handbuch/betrieb.md"] == "# Betrieb\n\n## Ports\n"


def test_links_of_the_handbook_stay_in_the_package_or_point_to_the_release(
        tmp_path: Path, inputs: dict) -> None:
    put_handbook(inputs, "betrieb/nachweise.md",
                 "# Nachweise\n\n[Ports](../betrieb.md#ports), [Vermerk](../../behoerden/09-betrieb-update/"
                 "update-vermerk.md#zweck), [Behörden](../../behoerden/), [ADR](../../releases.md#x),"
                 " [Meldewege](../../../SECURITY.md), [Regeln](../AGENTS.md), [Code](../../../docs/)\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    blob = "https://github.com/criew/opaa/blob/v1.2.3/"
    assert package_markdown(archive)["handbuch/betrieb/nachweise.md"] == (
        "# Nachweise\n\n[Ports](../betrieb.md#ports), [Vermerk](../../vorlagen/09-betrieb-update/"
        f"update-vermerk.md#zweck), [Behörden](../../vorlagen/), [ADR]({blob}docs/releases.md#x),"
        f" [Meldewege]({blob}SECURITY.md), [Regeln]({blob}docs/handbuch/AGENTS.md),"
        " [Code](https://github.com/criew/opaa/tree/v1.2.3/docs)\n")


def test_no_link_of_templates_update_note_or_readme_leads_to_the_handbook_on_github(
        tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/loeschkonzept.md", LINKS)
    put_template(inputs, "04-datenschutz/README.md", "# Datenschutz\n\n[Handbuch](../../handbuch/README.md)\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_markdown(archive)
    for path in [p for p in files if p.startswith("vorlagen/")] + ["update-vermerk.md", "LIESMICH.md"]:
        assert "docs/handbuch" not in files[path], path
    assert "](handbuch/README.md)" in files["LIESMICH.md"]
    assert "[Handbuch](../../handbuch/README.md)" in files["vorlagen/04-datenschutz/README.md"]


@pytest.mark.parametrize("link", ["gibt-es-nicht.md", "../../gibt-es-nicht.md#x", "../../../ausserhalb.md"])
def test_a_relative_link_to_nothing_in_the_handbook_fails_the_package(
        tmp_path: Path, inputs: dict, link: str) -> None:
    put_handbook(inputs, "betrieb.md", f"# Betrieb\n\n## Ports\n\n[Ziel]({link})\n")

    with pytest.raises(update_package.PackageError, match="handbuch/betrieb.md.*link"):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


def html_references(files: dict[str, str]) -> tuple[dict[str, set[str]], list[tuple[str, str]]]:
    """Ids of every HTML page and (page, link) of every href and src in them."""
    ids, links = {}, []
    for path, text in files.items():
        if path.endswith(".html"):
            ids[path] = {html.unescape(i) for i in re.findall(r'<[a-z0-9]+[^>]*?\s(?:id|name)="([^"]+)"', text)}
            links += [(path, html.unescape(t)) for t in re.findall(r'\s(?:href|src)="([^"]*)"', text)]
    return ids, links


def package_files(archive: Path) -> dict[str, str]:
    with tarfile.open(archive) as tar:
        return {m.name.split("/", 1)[1]: tar.extractfile(m).read().decode("utf-8", "replace")
                for m in tar.getmembers() if m.isfile()}


def test_the_package_holds_an_html_version_whose_links_and_anchors_resolve(
        tmp_path: Path, inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/README.md", "# Datenschutz\n")
    put_template(inputs, "04-datenschutz/loeschkonzept.md", LINKS)
    with_placeholders(inputs, "---\nstand: 2026-10-23\n---\n\n# Update-Vermerk\n\n## Zweck\n\n"
                              "Frist: {{faktenblatt:fristen#audit-retention}},"
                              " [Löschkonzept](../04-datenschutz/loeschkonzept.md#fristen)\n")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    files = package_files(archive)
    assert "index.html" in files
    for path in [p for p in files if p.endswith(".md")]:
        assert path[:-3] + ".html" in files, path
    ids, links = html_references(files)
    checked = 0
    for path, link in links:
        if re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:|//", link):
            continue
        file, _, anchor = link.partition("#")
        target = posixpath.normpath(posixpath.join(posixpath.dirname(path), urllib.parse.unquote(file))) \
            if file else path
        assert target in files, (path, link)
        if anchor:
            assert urllib.parse.unquote(anchor) in ids[target], (path, link)
        checked += 1
    assert checked > 20
    vermerk = files["update-vermerk.html"]
    assert 'href="faktenblaetter/fristen.html#audit-retention"' in vermerk
    assert 'href="vorlagen/04-datenschutz/loeschkonzept.html#fristen"' in vermerk
    assert "<dt>stand</dt><dd>2026-10-23</dd>" in vermerk
    assert 'href="../../handbuch/betrieb.html#ports"' in files["vorlagen/04-datenschutz/loeschkonzept.html"]
    assert not re.search(r'<(script|link|img)[^>]+(src|href)="(https?:)?//', "".join(files.values()))


@pytest.mark.parametrize("link", ["../betrieb.md#gibt-es-nicht", "#gibt-es-nicht"])
def test_a_link_to_an_anchor_the_package_lacks_fails_the_package(
        tmp_path: Path, inputs: dict, link: str) -> None:
    put_handbook(inputs, "betrieb/kapitel.md", f"# Kapitel\n\n[Ziel]({link})\n")

    with pytest.raises(update_package.PackageError, match="handbuch/betrieb/kapitel.html.*gibt-es-nicht"):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


def test_the_readme_names_the_handbook_and_the_html_start_page(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    assert "`index.html`" in text
    assert "| [`handbuch/`](handbuch/README.md) |" in text


RELEASE_INSTALL = "pip install --require-hashes --only-binary=:all: --no-deps -r .github/requirements-release.txt"


def test_the_package_job_installs_only_hash_pinned_wheels() -> None:
    body = job("update-package")
    requirements = (HERE.parent / "requirements-release.txt").read_text(encoding="utf-8")
    entries = [e for e in requirements.replace("\\\n", " ").splitlines() if e and not e.startswith("#")]

    assert sorted(e.split("==", 1)[0] for e in entries) == ["markdown-it-py", "mdurl"]
    for entry in entries:
        assert re.fullmatch(r"[a-z-]+==\d+(\.\d+)+( +--hash=sha256:[0-9a-f]{64})+", entry), entry
    assert "actions/setup-python@" in body
    assert "requirements-ci.txt" not in body and "pytest" not in body
    install = body.index(RELEASE_INSTALL)
    assert body.index("gh pr list") < install < body.index("update_package.py build")


def ci_job(name: str) -> str:
    text = (HERE.parent / "workflows" / "ci.yml").read_text(encoding="utf-8")
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", text, re.M | re.S)
    assert match, f"job {name} missing"
    return match.group(1)


def test_every_pull_request_checks_the_links_the_package_would_refuse() -> None:
    body = ci_job("changes")
    step = body[body.index("update_package.py links") - 400:body.index("update_package.py links")]

    assert RELEASE_INSTALL in body
    assert body.index(RELEASE_INSTALL) < body.index("update_package.py links")
    assert "if:" not in step.rsplit("- name:", 1)[1]
    assert RELEASE_INSTALL in ci_job("report-script")


def test_the_link_check_finds_what_would_stop_the_package(inputs: dict) -> None:
    put_template(inputs, "04-datenschutz/README.md", "# Datenschutz\n\n[Handbuch](../../handbuch/betrieb.md#ports)\n")
    assert update_package.check_links(inputs["templates"], inputs["handbook"]) == []

    put_handbook(inputs, "betrieb/kapitel.md", "# Kapitel\n\n[Ziel](../betrieb.md#gibt-es-nicht)\n")
    assert update_package.check_links(inputs["templates"], inputs["handbook"]) == [
        "handbuch/betrieb/kapitel.html: ../betrieb.html#gibt-es-nicht"]

    put_handbook(inputs, "betrieb/kapitel.md", "# Kapitel\n\n[Ziel](gibt-es-nicht.md)\n")
    assert "handbuch/betrieb/kapitel.md" in update_package.check_links(inputs["templates"], inputs["handbook"])[0]


def test_the_start_page_of_the_package_links_the_checksums(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    assert '<a href="SHA256SUMS">SHA256SUMS</a>' in read(archive, "opaa-update-paket-1.2.3/index.html").decode()


def test_the_delta_is_rendered_for_the_given_repository() -> None:
    command = update_package.gradle_command(
        version="1.2.3", day="2026-01-01", from_ref="v1.2.2", to_ref="v1.2.3",
        repository="example/opaa-fork", labelled_prs=None)

    assert "-Prepository=example/opaa-fork" in command
    assert "complianceDelta" in command


def test_the_update_package_names_a_version_like_the_delta() -> None:
    """The cases are shared with ManifestDeltaRendererTest, which checks the delta against them."""
    cases = json.loads((HERE.parents[1] / "backend" / "src" / "test" / "resources" / "compliance"
                        / "delta" / "bare-commit-names.json").read_text(encoding="utf-8"))

    assert len(cases) > 5
    for case in cases:
        bare = update_package.names_bare_commit(case["label"], case["commit"], case["hasManifest"])
        side = update_package.Side(case["label"], case["commit"], None, bare=bare)
        assert side.name == case["name"], case


def test_operations_name_a_comparison_without_manifest_readably(interval: dict) -> None:
    old = interval["old"]
    bare = update_package.Side(label=old.commit, commit=old.commit, version=None, bare=True)

    files = report(interval, previous=bare)

    readable = f"Stand ohne Fakten-Manifest (Commit `{old.commit[:7]}`)"
    for name, text in files.items():
        title = next(line for line in text.splitlines() if line.startswith("# "))
        assert title.endswith(f"gegenüber {readable}"), name
    assert f"| Kennzahl | {readable} | v1.2.3 |" in files["antwortqualitaet.md"]


@pytest.mark.parametrize("missing", ["delta", "fact_sheets", "template", "licenses", "operations", "sbom"])
def test_a_missing_part_fails_the_package(tmp_path: Path, inputs: dict, missing: str) -> None:
    if missing == "sbom":
        inputs["sboms"]["frontend"].unlink()
    elif missing == "operations":
        (inputs["operations"] / "antwortqualitaet.md").unlink()
    elif missing == "delta":
        (inputs["delta"] / "manifest-delta.json").unlink()
    elif missing == "fact_sheets":
        (inputs["fact_sheets"] / "faktenblaetter.json").unlink()
    elif missing == "template":
        (inputs["templates"] / "09-betrieb-update" / "update-vermerk.md").unlink()
    else:
        inputs["licenses"][1].unlink()

    with pytest.raises(update_package.PackageError):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


def sbom(archive: Path, name: str) -> dict:
    return json.loads(read(archive, f"opaa-update-paket-1.2.3/sbom/{name}-1.2.3.cdx.json"))


def test_the_sboms_of_backend_and_frontend_lie_in_the_package_and_beside_it(
        tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    for name in ("backend", "frontend"):
        packaged = read(archive, f"opaa-update-paket-1.2.3/sbom/opaa-{name}-1.2.3.cdx.json")
        assert (tmp_path / "out" / f"opaa-{name}-1.2.3.cdx.json").read_bytes() == packaged
        assert sbom(archive, f"opaa-{name}")["components"][0]["purl"] == f"pkg:npm/{name}-dependency@1.0.0"
    assert sorted(p.name for p in (tmp_path / "out").iterdir()) == [
        "opaa-backend-1.2.3.cdx.json", "opaa-frontend-1.2.3.cdx.json",
        "opaa-update-paket-1.2.3.tar.gz", "opaa-update-paket-1.2.3.tar.gz.sha256"]


def test_an_sbom_carries_the_commit_time_and_a_serial_number_of_its_release(
        tmp_path: Path, inputs: dict) -> None:
    first = update_package.assemble(release(), out=tmp_path / "first", **inputs)
    for path in inputs["sboms"].values():
        path.write_text(cyclonedx(f"pkg:npm/{path.name.split('.')[0]}-dependency@1.0.0",
                                  serialNumber="urn:uuid:11111111-1111-4111-8111-111111111111"))
    second = update_package.assemble(release(), out=tmp_path / "second", **inputs)
    other = update_package.assemble(release(commit="c" * 40), out=tmp_path / "other", **inputs)

    assert first.read_bytes() == second.read_bytes()
    backend, frontend = sbom(first, "opaa-backend"), sbom(first, "opaa-frontend")
    assert backend["metadata"]["timestamp"] == "2026-09-21T14:13:20Z"
    assert re.fullmatch(r"urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                        backend["serialNumber"])
    assert backend["serialNumber"] != frontend["serialNumber"]
    assert sbom(other, "opaa-backend")["serialNumber"] != backend["serialNumber"]


def test_an_sbom_leaves_out_the_annotations_naming_the_day_of_its_generator_run(
        tmp_path: Path, inputs: dict) -> None:
    summary = {"bom-ref": "metadata-annotations", "timestamp": "2099-01-01T00:00:00Z",
               "text": "This SBOM document was created on Thursday, January 1, 2099 with cdxgen."}
    inputs["sboms"]["frontend"].write_text(
        cyclonedx("pkg:npm/react@19.0.0", annotations=[summary]), encoding="utf-8")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    frontend = sbom(archive, "opaa-frontend")
    assert "annotations" not in frontend
    assert "2099" not in json.dumps(frontend)
    assert frontend["components"][0]["purl"] == "pkg:npm/react@19.0.0"


@pytest.mark.parametrize("content", [
    "not json",
    json.dumps({"bomFormat": "SPDX", "components": [{"name": "x"}]}),
    cyclonedx(),
])
def test_an_sbom_that_is_no_cyclonedx_bom_with_components_fails_the_package(
        tmp_path: Path, inputs: dict, content: str) -> None:
    inputs["sboms"]["backend"].write_text(content, encoding="utf-8")

    with pytest.raises(update_package.PackageError, match="backend"):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


def test_the_frontend_sbom_of_a_release_is_generated_like_the_one_of_main(tmp_path: Path) -> None:
    """sbom.yml writes the SBOM of main; a release must not differ in tool, install or scope."""
    workflow = (HERE.parent / "workflows" / "sbom.yml").read_text(encoding="utf-8")
    main = re.search(r"run: \.\./(\S+/cdxgen) (.*)$", workflow, re.M)
    release_command = update_package.frontend_sbom_command(tmp_path / "bom.json")

    assert Path(release_command[0]) == update_package.ROOT / main.group(1)
    assert Path(main.group(1)) == update_package.CDXGEN
    output = release_command.index("-o")
    assert release_command[output + 1] == str(tmp_path / "bom.json")
    arguments = main.group(2).split()
    position = arguments.index("-o")
    assert release_command[1:output] + release_command[output + 2:] == arguments[:position] + arguments[position + 2:]
    assert "--required-only" in release_command
    install = f"working-directory: {update_package.CDXGEN_TOOL.as_posix()}\n        run: {update_package.CDXGEN_INSTALL}\n"
    assert install in workflow
    assert install in job("update-package")


def test_cdxgen_and_everything_it_loads_are_fixed_by_a_lockfile() -> None:
    tool = update_package.ROOT / update_package.CDXGEN_TOOL
    manifest = json.loads((tool / "package.json").read_text(encoding="utf-8"))
    version = manifest["dependencies"]["@cyclonedx/cdxgen"]

    assert re.fullmatch(r"\d+\.\d+\.\d+", version)
    assert manifest["packageManager"] == json.loads(
        (update_package.ROOT / "frontend" / "package.json").read_text(encoding="utf-8"))["packageManager"]
    lockfile = (tool / "pnpm-lock.yaml").read_text(encoding="utf-8")
    assert f"'@cyclonedx/cdxgen@{version}':" in lockfile
    assert "--frozen-lockfile" in update_package.CDXGEN_INSTALL
    assert "--ignore-scripts" in update_package.CDXGEN_INSTALL


def test_the_frontend_sbom_describes_the_release_version(tmp_path: Path, inputs: dict) -> None:
    root = {"name": "frontend", "version": "0.0.0", "purl": "pkg:npm/frontend@0.0.0",
            "bom-ref": "pkg:npm/frontend@0.0.0"}
    inputs["sboms"]["frontend"].write_text(
        cyclonedx("pkg:npm/react@19.0.0", metadata={"component": root}), encoding="utf-8")

    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    component = sbom(archive, "opaa-frontend")["metadata"]["component"]
    assert component["version"] == "1.2.3"
    assert component["purl"] == "pkg:npm/frontend@1.2.3"
    assert component["bom-ref"] == "pkg:npm/frontend@0.0.0"


def test_the_release_notes_name_the_package_and_its_verification(tmp_path: Path) -> None:
    notes = subprocess.run(
        ["bash", str(RELEASE_NOTES), "criew/opaa", "v1.2.4"],
        cwd=RELEASE_NOTES.parents[3], capture_output=True, text=True, check=True,
        env={**os.environ, "UPGRADE_NOTES_DIR": str(tmp_path)},
    ).stdout

    assert f"`{update_package.PREFIX}1.2.4.tar.gz`" in notes
    assert f"gh attestation verify {update_package.PREFIX}1.2.4.tar.gz --repo criew/opaa" in notes
    assert "`opaa-backend-1.2.4.cdx.json`" in notes and "`opaa-frontend-1.2.4.cdx.json`" in notes
    assert "./gradlew complianceDelta -PfromRef=" in notes


def job(name: str) -> str:
    text = WORKFLOW.read_text(encoding="utf-8")
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", text, re.M | re.S)
    assert match, f"job {name} missing"
    return match.group(1)


def permissions(body: str) -> list[str]:
    lines = body.split("    permissions:\n", 1)[1].splitlines()
    granted = []
    for line in lines:
        if not line.startswith("      "):
            break
        if not line.strip().startswith("#"):
            granted.append(line.strip())
    return granted


def test_the_package_job_runs_on_release_tags_without_any_cache() -> None:
    body = job("update-package")

    assert "if: startsWith(github.ref, 'refs/tags/v')" in body
    caches = [line.strip() for line in body.splitlines() if re.search(r"cache", line) and not line.strip().startswith("#")]
    assert caches == ["package-manager-cache: false"]
    assert "setup-gradle" not in body and "actions/cache" not in body
    assert "fetch-depth: 0" in body
    assert "GRADLE_OPTS: -Dorg.gradle.daemon=false" in body
    query = body.index("gh pr list")
    assert query < body.index("setup-java") and query < body.index("./gradlew")
    assert query < body.index("pnpm install")


def test_a_labelled_list_that_reaches_the_limit_fails_the_run() -> None:
    body = job("update-package")
    step = body[body.index("name: Gekennzeichnete Pull Requests abfragen"):]
    step = step[: step.index("\n      - ")]

    assert '--limit "$LIMIT"' in step
    assert "LIMIT: 1000" in step
    assert re.search(r"if \(\(count >= LIMIT\)\); then\n.*\n\s+exit 1", step)
    assert permissions(body) == ["contents: read", "pull-requests: read"]


def test_publication_waits_for_the_package_and_the_release_carries_it() -> None:
    assert re.search(r"needs: \[prepare, build, update-package\]", job("publish"))
    assert "needs.update-package.result" in job("publish")
    attestation = job("update-package-attestation")
    assert permissions(attestation) == ["id-token: write", "attestations: write"]
    subjects = attestation[attestation.index("subject-path:"):]
    assert "update-package/*.tar.gz" in subjects and "update-package/*.cdx.json" in subjects
    release_job = job("release")
    assert "persist-credentials: false" in release_job
    assert "GH_TOKEN: ${{ github.token }}" in release_job
    assert "update-package-attestation" in release_job
    assert "update-package/" in release_job


# Operational consequences and answer quality (betrieb/), read from Git at both ends of the interval.

NOTES = "docs/upgrade-notes/"
CHANGELOG = "backend/src/main/resources/db/changelog/"
VALUES = "deploy/helm/opaa/values.yaml"
DEPLOYMENT = "deploy/helm/opaa/templates/backend-deployment.yaml"
BASELINE = "eval/baseline/"


def put(repo: Path, path: str, text: str) -> None:
    target = repo / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(text.encode("utf-8"))


def snapshot(repo: Path, message: str) -> str:
    git(repo, "add", "-A")
    return commit(repo, message)


def changeset_file(*ids: str) -> str:
    return "databaseChangeLog:\n" + "".join(
        f"  - changeSet:\n      id: {i}\n      author: opaa\n      changes:\n"
        f"        - sql:\n            sql: SELECT 1;\n" for i in ids)


def baseline(measured: str, ndcg: float, fingerprint: str = "markdown:3,pdf:5") -> str:
    overall = {"n": 49, "hitRateAt5": 0.857, "mrr": 0.731, "ndcgAt10": ndcg, "recallAt10": 0.823,
               "recallAt10Ceiling": 1.0, "hitCountAt5": 42}
    return json.dumps({
        "measurementContractVersion": 16,
        "fixedPoints": {"embeddingModel": "nomic-embed-text:v1.5",
                        "ingestionPipelineFingerprint": fingerprint},
        "groups": {"overall": overall},
        "measuredAt": measured,
        "provenance": {"sourcePullRequest": f"CI-Lauf vom {measured}"},
    }, indent=2)


DELTA_JSON = {
    "changes": [
        {"group": "DATA_FLOWS", "section": "dataFlows", "id": "mail-gateway", "title": "mail-gateway",
         "kind": "ADDED", "field": None, "before": None,
         "after": {"id": "mail-gateway", "target": "Der SMTP-Server des Hauses", "viaOutboundProxy": "false"}},
        {"group": "DATA_FLOWS", "section": "dataFlows", "id": "chat-model", "title": "chat-model",
         "kind": "CHANGED", "field": "target", "before": "Das Chat-Modell", "after": "Das aktive Chat-Modell"},
        {"group": "DEFAULTS", "section": "settings", "id": "opaa.rerank.enabled", "title": "x",
         "kind": "CHANGED", "field": "defaultValue", "before": "false", "after": "true"},
    ],
}


@pytest.fixture
def interval(tmp_path: Path) -> dict:
    """v1.2.2 holds one changeset, a raw-vector baseline and the chart; the commit after it adds
    upgrade notes, three changesets, a chart port, a container port and a pipeline baseline."""
    work = tmp_path / "repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    put(work, CHANGELOG + "db.changelog-master.yaml", "databaseChangeLog: []\n")
    put(work, CHANGELOG + "foundation/2026-01-01-start.yaml", changeset_file("foundation-2026-01-01-start"))
    put(work, VALUES, "backend:\n  # Port of the actuator\n  managementPort: 8081\n"
                      "  service:\n    port: 8080\n")
    put(work, DEPLOYMENT, "        ports:\n          - name: http\n            containerPort: 8080\n")
    put(work, BASELINE + "verwaltung.json", baseline("2026-01-10", 0.700))
    put(work, NOTES + "1.2.2.md", "- Alte Vorbereitung, die nicht mehr gilt.\n")
    old = snapshot(work, "v1.2.2")
    git(work, "tag", "v1.2.2", old)

    put(work, NOTES + "1.2.3-rc.1.md", "- Vorbereitung des Kandidaten.\n")
    put(work, NOTES + "1.2.3.md",
        "1. **Datenbank sichern.** Dieser Stand entfernt eine Tabelle.\n"
        "2. **Ports freigeben.** Der neue Metrik-Port muss erreichbar sein.\n\n"
        "Neuindizierung: ja, rund eine Stunde je 10.000 Dokumente bei GPU-Einbettung\n")
    put(work, NOTES + "1.3.0.md", "- Vorbereitung einer späteren Version.\n")
    put(work, CHANGELOG + "identity/2026-02-01-sessions.yaml",
        changeset_file("identity-2026-02-01-sessions", "identity-2026-02-01-sessions--index"))
    put(work, CHANGELOG + "workspace/2026-02-02-spaces.yaml", changeset_file("workspace-2026-02-02-spaces"))
    put(work, VALUES, "backend:\n  # Port of the actuator\n  managementPort: 8081\n"
                      "  service:\n    port: 8080\nmetrics:\n  exporterPort: 9464\n")
    put(work, DEPLOYMENT, "        ports:\n          - name: http\n            containerPort: 8080\n"
                          "          - name: metrics\n"
                          "            containerPort: {{ .Values.metrics.exporterPort }}\n")
    put(work, BASELINE + "verwaltung.json", baseline("2026-02-10", 0.718, "markdown:4,pdf:5"))
    put(work, BASELINE + "pipeline-verwaltung.json", baseline("2026-02-11", 0.640, "markdown:4,pdf:5"))
    new = snapshot(work, "v1.2.3")
    delta = write_delta(tmp_path / "manifest-delta.json", old, new, DELTA_JSON["changes"])
    return dict(
        repo=work,
        old=update_package.Side(label="v1.2.2", commit=old, version="1.2.2"),
        new=update_package.Side(label="v1.2.3", commit=new, version="1.2.3"),
        delta=delta,
    )


def write_delta(path: Path, from_commit: str, to_commit: str, changes: list[dict]) -> Path:
    """A manifest delta as ManifestDeltaGenerator writes it, reduced to what the package reads."""
    path.write_text(json.dumps({
        "from": {"kind": "ref", "commit": from_commit},
        "to": {"kind": "ref", "commit": to_commit},
        "changes": changes,
    }), encoding="utf-8")
    return path


def report(interval: dict, **changes) -> dict[str, str]:
    """The files for the interval; without an explicit delta_json, DELTA_JSON written for the
    actual two commits."""
    args = dict(repo=interval["repo"], previous=interval["old"], current=interval["new"])
    args.update(changes)
    if "delta_json" not in changes:
        args["delta_json"] = write_delta(
            interval["delta"].with_name("delta-for-interval.json"), args["previous"].commit,
            args["current"].commit, DELTA_JSON["changes"])
    return update_package.operations(**args)


def section(text: str, heading: str) -> str:
    start = text.index(f"## {heading}")
    end = text.find("\n## ", start + 1)
    return text[start:] if end < 0 else text[start:end]


def test_note_changeset_and_new_data_flow_of_an_interval_appear_in_the_package(
        tmp_path: Path, inputs: dict, interval: dict) -> None:
    operations = tmp_path / "betrieb"
    operations.mkdir()
    for name, text in report(interval).items():
        (operations / name).write_text(text, encoding="utf-8")
    archive = update_package.assemble(
        release(), out=tmp_path / "out", **{**inputs, "operations": operations})

    text = read(archive, "opaa-update-paket-1.2.3/betrieb/betriebsfolgen.md").decode()
    assert "**Ja.**" in section(text, "Bruch und Vorbereitung")
    assert "Dieser Stand entfernt eine Tabelle." in text
    migration = section(text, "Schemamigration")
    assert "3 neue Changesets" in migration
    assert "| identity | 2 |" in migration and "| workspace | 1 |" in migration
    assert "nur vorwärts" in migration and "Datenbank sichern" in migration
    egress = section(text, "Neue Ports und Egress-Ziele")
    assert "`mail-gateway`: Der SMTP-Server des Hauses; über den Ausgangsproxy: nein" in egress
    assert "Das Chat-Modell" in egress and "Das aktive Chat-Modell" in egress
    assert "opaa.rerank.enabled" not in egress


def test_only_the_notes_inside_the_interval_count_and_a_final_note_replaces_its_candidates(
        interval: dict) -> None:
    text = report(interval)["betriebsfolgen.md"]

    assert "Vorbereitung für 1.2.3" in text
    assert "Vorbereitung des Kandidaten" not in text
    assert "Alte Vorbereitung" not in text
    assert "späteren Version" not in text


def test_a_pre_release_counts_the_notes_of_its_candidates_up_to_its_own(interval: dict) -> None:
    candidate = update_package.Side(label="v1.2.3-rc.2", commit=interval["new"].commit, version="1.2.3-rc.2")
    text = report(interval, current=candidate)["betriebsfolgen.md"]

    assert "Vorbereitung des Kandidaten" in text
    assert "Dieser Stand entfernt eine Tabelle." not in text


def test_a_given_commit_as_comparison_counts_the_notes_it_does_not_hold(interval: dict) -> None:
    previous = update_package.Side(label=interval["old"].commit[:9], commit=interval["old"].commit,
                                   version=None)
    text = report(interval, previous=previous)["betriebsfolgen.md"]

    assert "Dieser Stand entfernt eine Tabelle." in text
    assert "Alte Vorbereitung" not in text


def test_an_interval_without_preparation_names_no_break(interval: dict) -> None:
    repo = interval["repo"]
    for name in ("1.2.3-rc.1.md", "1.3.0.md"):
        (repo / NOTES / name).unlink()
    put(repo, NOTES + "1.2.3.md", "Keine Vorbereitung nötig.\n")
    now = update_package.Side(label="v1.2.3", commit=snapshot(repo, "none"), version="1.2.3")

    text = report(interval, current=now)["betriebsfolgen.md"]

    assert "**Nein.**" in section(text, "Bruch und Vorbereitung")
    assert "Neuindizierung: nicht nötig" in section(text, "Neuindizierung")


def test_the_reindexing_mark_and_its_estimate_come_from_the_note(interval: dict) -> None:
    reindexing = section(report(interval)["betriebsfolgen.md"], "Neuindizierung")

    assert "Neuindizierung: **nötig**" in reindexing
    assert "rund eine Stunde je 10.000 Dokumente bei GPU-Einbettung" in reindexing
    assert "`markdown` 3 → 4" in reindexing


def test_a_note_without_reindexing_mark_says_so_and_names_no_estimate(interval: dict) -> None:
    repo = interval["repo"]
    put(repo, NOTES + "1.2.3.md", "- Datenbank sichern.\n")
    now = update_package.Side(label="v1.2.3", commit=snapshot(repo, "unmarked"), version="1.2.3")

    reindexing = section(report(interval, current=now)["betriebsfolgen.md"], "Neuindizierung")

    assert "nicht gekennzeichnet" in reindexing
    assert "Dauerschätzung: nicht angegeben" in reindexing


def test_a_changed_default_embedding_model_asks_for_re_embedding(interval: dict, tmp_path: Path) -> None:
    delta = write_delta(tmp_path / "embedding-delta.json", interval["old"].commit, interval["new"].commit, [
        {"group": "MODELS_AND_PROMPTS", "section": "settings", "id": "spring.ai.openai.embedding.model",
         "title": "x", "kind": "CHANGED", "field": "defaultValue", "before": "nomic-embed-text",
         "after": "bge-m3"}])

    reindexing = section(report(interval, delta_json=delta)["betriebsfolgen.md"], "Neuindizierung")

    assert "Neueinbettung" in reindexing and "`nomic-embed-text` → `bge-m3`" in reindexing


def test_new_ports_of_the_chart_appear_with_their_file(interval: dict) -> None:
    egress = section(report(interval)["betriebsfolgen.md"], "Neue Ports und Egress-Ziele")

    assert "`metrics.exporterPort`: 9464" in egress
    assert "managementPort" not in egress
    assert "`containerPort: {{ .Values.metrics.exporterPort }}`" in egress
    assert "backend-deployment.yaml" in egress


def test_without_the_manifest_delta_the_data_flows_are_reported_as_not_determined(interval: dict) -> None:
    egress = section(report(interval, delta_json=None)["betriebsfolgen.md"], "Neue Ports und Egress-Ziele")

    assert "nicht ermittelt" in egress


def test_every_section_names_its_source(interval: dict) -> None:
    files = report(interval)
    text = files["betriebsfolgen.md"]

    for heading, source in [
        ("Bruch und Vorbereitung", "`docs/upgrade-notes/"),
        ("Neuindizierung", "`docs/upgrade-notes/"),
        ("Neuindizierung", "`eval/baseline/"),
        ("Schemamigration", "`backend/src/main/resources/db/changelog/`"),
        ("Neue Ports und Egress-Ziele", "`deploy/helm/opaa/"),
        ("Neue Ports und Egress-Ziele", "delta/manifest-delta.json"),
    ]:
        part = section(text, heading)
        assert "Quelle:" in part and source in part, (heading, source)
    assert "Quelle:" in files["antwortqualitaet.md"] and "`eval/baseline/" in files["antwortqualitaet.md"]
    assert interval["old"].commit in text and interval["new"].commit in text


def test_answer_quality_names_both_versions_with_measurement_date(interval: dict) -> None:
    raw = section(report(interval)["antwortqualitaet.md"], "Rohvektor-Pfad, verwaltung")

    assert "| nDCG@10 | 0,700 | 0,718 |" in raw
    assert "2026-01-10" in raw and "2026-02-10" in raw
    assert "v1.2.2" in raw and "v1.2.3" in raw
    assert "Messgrundlage geändert" in raw


def test_missing_quality_values_are_reported_as_not_measured(interval: dict) -> None:
    text = report(interval)["antwortqualitaet.md"]
    pipeline = section(text, "Pipeline-Pfad, verwaltung")

    assert "| nDCG@10 | nicht gemessen | 0,640 |" in pipeline
    assert "Messlauf auf dem Commit dieser Version: nicht gemessen" in text


def test_a_comparison_without_any_baseline_is_not_measured_for_every_value(tmp_path: Path) -> None:
    work = tmp_path / "root-repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    root = commit(work, "root")
    put(work, BASELINE + "verwaltung.json", baseline("2026-02-10", 0.718))
    tip = snapshot(work, "tip")

    files = update_package.operations(
        work, update_package.Side(label=root, commit=root, version=None),
        update_package.Side(label="v1.2.3", commit=tip, version="1.2.3"), None)

    assert "| Hit Rate@5 | nicht gemessen | 0,857 |" in files["antwortqualitaet.md"]
    assert "Kein Upgrade-Hinweis" in files["betriebsfolgen.md"]


def test_the_same_interval_gives_the_same_files(interval: dict) -> None:
    assert report(interval) == report(interval)


def test_the_operations_command_writes_both_files(tmp_path: Path, interval: dict) -> None:
    out = tmp_path / "betrieb-out"
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "operations", "--from", "v1.2.2", "--to", "HEAD",
         "--out", str(out), "--delta-json", str(interval["delta"])],
        cwd=interval["repo"], capture_output=True, text=True,
    )

    assert result.returncode == 0, result.stderr
    assert sorted(p.name for p in out.iterdir()) == sorted(update_package.OPERATIONS_FILES)
    assert "`mail-gateway`" in (out / "betriebsfolgen.md").read_text(encoding="utf-8")


def test_the_operations_command_reads_from_the_repository_root_when_started_in_a_subdirectory(
        tmp_path: Path, interval: dict, monkeypatch: pytest.MonkeyPatch) -> None:
    out = tmp_path / "from-subdirectory"
    monkeypatch.chdir(interval["repo"] / "backend")

    code = update_package.main(["operations", "--from", "v1.2.2", "--to", "HEAD", "--out", str(out),
                                "--delta-json", str(interval["delta"])])

    assert code == 0
    text = (out / "betriebsfolgen.md").read_text(encoding="utf-8")
    assert "**Ja.**" in section(text, "Bruch und Vorbereitung")
    assert "3 neue Changesets" in section(text, "Schemamigration")
    assert "`metrics.exporterPort`: 9464" in text


def test_a_manifest_delta_of_another_interval_is_refused(tmp_path: Path, interval: dict) -> None:
    other = write_delta(tmp_path / "other.json", interval["new"].commit, interval["new"].commit, [])

    with pytest.raises(update_package.PackageError, match="from commit"):
        report(interval, delta_json=other)


def test_a_manifest_delta_without_commits_is_refused(tmp_path: Path, interval: dict) -> None:
    bare = tmp_path / "bare.json"
    bare.write_text(json.dumps(DELTA_JSON), encoding="utf-8")

    with pytest.raises(update_package.PackageError, match="compares"):
        report(interval, delta_json=bare)


def test_a_given_manifest_delta_that_does_not_exist_is_an_error(tmp_path: Path, interval: dict) -> None:
    with pytest.raises(update_package.PackageError, match="is missing"):
        report(interval, delta_json=tmp_path / "missing.json")


def test_no_preparation_with_a_reindexing_line_is_no_break(interval: dict) -> None:
    repo = interval["repo"]
    put(repo, NOTES + "1.2.3.md", "Keine Vorbereitung nötig.\n\nNeuindizierung: nein\n")
    now = update_package.Side(label="v1.2.3", commit=snapshot(repo, "none, marked"), version="1.2.3")

    text = report(interval, current=now)["betriebsfolgen.md"]

    assert "**Nein.**" in section(text, "Bruch und Vorbereitung")
    assert "Neuindizierung: nicht nötig" in section(text, "Neuindizierung")


def test_a_bold_reindexing_line_keeps_no_asterisks_in_the_estimate(interval: dict) -> None:
    repo = interval["repo"]
    put(repo, NOTES + "1.2.3.md", "- Datenbank sichern.\n\n**Neuindizierung: ja, rund 2 h**\n")
    now = update_package.Side(label="v1.2.3", commit=snapshot(repo, "bold"), version="1.2.3")

    reindexing = section(report(interval, current=now)["betriebsfolgen.md"], "Neuindizierung")

    assert "Dauerschätzung (1.2.3): rund 2 h\n" in reindexing


# --- Second comparison of an LTS release (docs/releases.md, "Update-Paket") -----------------------

LINES = ".github/support-lines.json"


def lines_json(*entries: tuple[str, str, str | None]) -> str:
    return json.dumps({"lines": [{"line": l, "kind": k, "supportEnd": e} for l, k, e in entries]})


@pytest.fixture
def lts_repo(tmp_path: Path) -> dict:
    """main: a (v0.3.0, LTS) - b (v0.4.0) - c (v0.5.0-rc.1, LTS); release/0.3 forks at a and carries
    v0.3.1 and v0.3.2, which main never contains. Each commit holds the list of lines of its day."""
    work = tmp_path / "repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    put(work, CHANGELOG + "foundation/2026-06-01-start.yaml", changeset_file("foundation-start"))
    put(work, LINES, lines_json(("0.3", "lts", "2028-06-30")))
    a = snapshot(work, "a")
    git(work, "tag", "v0.3.0", a)
    git(work, "checkout", "-q", "-b", "release/0.3")
    put(work, CHANGELOG + "foundation/2026-07-01-fix.yaml", changeset_file("foundation-fix"))
    git(work, "tag", "v0.3.1", snapshot(work, "p1"))
    p2 = commit(work, "p2")
    git(work, "tag", "v0.3.2", p2)
    git(work, "tag", "v0.3.3-rc.1", commit(work, "p3"))
    git(work, "checkout", "-q", "main")
    put(work, CHANGELOG + "foundation/2026-07-01-fix.yaml", changeset_file("foundation-fix"))
    put(work, CHANGELOG + "workspace/2026-11-01-spaces.yaml", changeset_file("workspace-spaces"))
    put(work, NOTES + "0.4.0.md", "- Vorbereitung des Dezember-Release.\n")
    put(work, LINES, lines_json(("0.3", "lts", "2028-06-30"), ("0.4", "normal", None)))
    b = snapshot(work, "b")
    git(work, "tag", "v0.4.0", b)
    put(work, CHANGELOG + "workspace/2027-05-01-more.yaml", changeset_file("workspace-more"))
    put(work, NOTES + "0.5.0-rc.1.md", "- Vorbereitung des LTS-Kandidaten.\n")
    put(work, LINES, lines_json(("0.3", "lts", "2028-06-30"), ("0.4", "normal", "2027-07-31"),
                                ("0.5", "lts", "2029-06-30")))
    c = snapshot(work, "c")
    git(work, "tag", "v0.5.0-rc.1", c)
    return dict(repo=work, a=a, b=b, c=c, p2=p2)


def lts_previous(lts: dict, tag: str, at: str) -> str | None:
    return update_package.lts_previous_tag(lts["repo"], tag, lts[at])


@pytest.mark.parametrize("tag", ["v0.5.0-rc.1", "v0.5.0"])
def test_an_lts_release_and_its_candidates_compare_also_with_the_newest_release_of_the_previous_lts(
        lts_repo: dict, tag: str) -> None:
    # v0.3.2 lies on release/0.3, outside the history of c; the candidate v0.3.3-rc.1 never counts.
    assert lts_previous(lts_repo, tag, "c") == "v0.3.2"
    assert previous(lts_repo["repo"], "v0.5.0-rc.1") == "v0.4.0"


def test_a_normal_release_has_no_second_comparison(lts_repo: dict) -> None:
    assert lts_previous(lts_repo, "v0.4.0", "b") is None


def test_the_first_lts_release_has_no_second_comparison(lts_repo: dict) -> None:
    assert lts_previous(lts_repo, "v0.3.0", "a") is None


def test_a_patch_release_of_an_lts_line_has_no_second_comparison(lts_repo: dict) -> None:
    git(lts_repo["repo"], "tag", "v0.5.1", lts_repo["c"])

    assert lts_previous(lts_repo, "v0.5.1", "c") is None


def test_a_line_missing_from_the_list_of_the_commit_is_no_lts(lts_repo: dict) -> None:
    assert lts_previous(lts_repo, "v0.6.0", "c") is None


def test_the_list_is_read_from_the_tagged_commit_not_the_working_tree(lts_repo: dict) -> None:
    put(lts_repo["repo"], LINES, lines_json(("0.4", "lts", "2028-06-30")))

    assert lts_previous(lts_repo, "v0.4.0", "b") is None


def test_a_previous_lts_line_without_final_release_gives_no_second_comparison(tmp_path: Path) -> None:
    work = tmp_path / "repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    put(work, LINES, lines_json(("0.2", "lts", "2027-06-30"), ("0.5", "lts", "2029-06-30")))
    git(work, "tag", "v0.5.0", snapshot(work, "x"))

    assert update_package.lts_previous_tag(work, "v0.5.0", "v0.5.0") is None


def test_a_malformed_list_fails_the_package(tmp_path: Path) -> None:
    work = tmp_path / "repo"
    git(tmp_path, "init", "-q", "-b", "main", work.as_posix())
    put(work, LINES, "{\"lines\": \"0.5\"}")
    git(work, "tag", "v0.5.0", snapshot(work, "x"))

    with pytest.raises(update_package.PackageError, match="support-lines.json"):
        update_package.lts_previous_tag(work, "v0.5.0", "v0.5.0")


def test_the_command_prints_the_lts_comparison_or_nothing(lts_repo: dict) -> None:
    def cli(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(SCRIPT), "lts-previous", *args],
                              cwd=lts_repo["repo"], capture_output=True, text=True)

    assert cli("--tag", "v0.5.0-rc.1").stdout == "v0.3.2\n"
    normal = cli("--tag", "v0.4.0")
    assert (normal.returncode, normal.stdout) == (0, "")


def test_operations_compare_with_a_release_outside_the_history(lts_repo: dict) -> None:
    # The backport foundation-fix is in both; the notes count by version, the changesets by id.
    files = update_package.operations(
        lts_repo["repo"],
        update_package.Side("v0.3.2", lts_repo["p2"], "0.3.2"),
        update_package.Side("v0.5.0-rc.1", lts_repo["c"], "0.5.0-rc.1"),
        None,
    )
    text = files["betriebsfolgen.md"]

    assert "Vorbereitung des Dezember-Release." in text
    assert "Vorbereitung des LTS-Kandidaten." in text
    migration = section(text, "Schemamigration")
    assert "2 neue Changesets" in migration
    assert "| workspace | 2 |" in migration and "foundation" not in migration.split("|", 1)[1]


def test_the_lts_delta_compares_with_the_given_release_in_gradle() -> None:
    command = update_package.lts_gradle_command("v0.3.2", "v0.5.0", "criew/opaa", "prs.json")

    assert command[:2] == ["./gradlew", "complianceDelta"]
    assert "-PfromRef=v0.3.2" in command and "-PtoRef=v0.5.0" in command
    assert "-Prepository=criew/opaa" in command
    assert any(arg.startswith("-PlabelledPrs=") for arg in command)


def lts_inputs(tmp_path: Path, inputs: dict) -> dict:
    delta = tmp_path / "in" / "delta-lts"
    delta.mkdir()
    (delta / "manifest-delta.md").write_text("# Delta LTS\n")
    (delta / "manifest-delta.json").write_text("{}\n")
    operations = tmp_path / "in" / "betrieb-lts"
    operations.mkdir()
    for name in update_package.OPERATIONS_FILES:
        (operations / name).write_text(f"# LTS {name}\n")
    return {**inputs, "lts_delta": delta, "lts_operations": operations}


def test_an_lts_package_holds_both_comparisons_apart(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(
        release(version="1.4.0", tag="v1.4.0", previous="v1.3.0", lts_previous="v1.2.7",
                lts_previous_commit="c" * 40),
        out=tmp_path / "out", **lts_inputs(tmp_path, inputs))

    root = "opaa-update-paket-1.4.0/"
    names = set(members(archive))
    for part in ("delta-lts/manifest-delta.md", "delta-lts/manifest-delta.json",
                 "betrieb-lts/betriebsfolgen.md", "betrieb-lts/antwortqualitaet.md",
                 "delta/manifest-delta.md", "betrieb/betriebsfolgen.md"):
        assert root + part in names
    assert read(archive, root + "delta-lts/manifest-delta.md") == b"# Delta LTS\n"
    text = read(archive, root + "LIESMICH.md").decode()
    assert "vorgaenger-lts: v1.2.7\n" in text
    assert "| `delta-lts/manifest-delta.md` |" in text and "| `betrieb-lts/betriebsfolgen.md` |" in text
    assert "v1.2.7" in section(text, "Welcher Vergleich für wen")
    assert "v1.3.0" in section(text, "Welcher Vergleich für wen")


def test_a_package_without_lts_comparison_names_none(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    text = read(archive, "opaa-update-paket-1.2.3/LIESMICH.md").decode()
    assert "delta-lts" not in text and "vorgaenger-lts" not in text
    assert not [n for n in members(archive) if "-lts/" in n]


def test_an_lts_comparison_needs_both_parts(tmp_path: Path, inputs: dict) -> None:
    parts = lts_inputs(tmp_path, inputs)
    del parts["lts_operations"]

    with pytest.raises(update_package.PackageError, match="LTS"):
        update_package.assemble(release(lts_previous="v1.2.7", lts_previous_commit="c" * 40),
                                out=tmp_path / "out", **parts)
