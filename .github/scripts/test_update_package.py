"""Self-check for update_package.py, the update package of the release run (docs/releases.md).

Only a release tag runs the package job, and no pull request does, so a wrong previous release, a
non-repeatable archive or a cache in the release job would surface at a release at the earliest.
These cases run the previous-release rule against a local repository, assemble a package from
stand-in inputs and check the release job of publish-images.yml as text.
"""

import gzip
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tarfile
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
    template = tmp_path / "in" / "update-vermerk.md"
    template.write_text("# Update-Vermerk\n")
    operations = tmp_path / "in" / "betrieb"
    operations.mkdir()
    for name in update_package.OPERATIONS_FILES:
        (operations / name).write_text(f"# {name}\n")
    return dict(fact_sheets=sheets, delta=delta, operations=operations, licenses=licenses,
                template=template)


def members(archive: Path) -> dict[str, tarfile.TarInfo]:
    with tarfile.open(archive) as tar:
        return {m.name: m for m in tar.getmembers()}


def read(archive: Path, name: str) -> bytes:
    with tarfile.open(archive) as tar:
        return tar.extractfile(name).read()


def test_the_package_holds_every_part_under_one_versioned_directory(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    assert archive.name == "opaa-update-paket-1.2.3.tar.gz"
    files = sorted(n for n, m in members(archive).items() if m.isfile())
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
        "opaa-update-paket-1.2.3/lizenzen/lizenzen-backend.md",
        "opaa-update-paket-1.2.3/lizenzen/lizenzen-frontend.md",
        "opaa-update-paket-1.2.3/update-vermerk.md",
    ]


def test_the_checksum_files_match_the_package(tmp_path: Path, inputs: dict) -> None:
    archive = update_package.assemble(release(), out=tmp_path / "out", **inputs)

    sums = read(archive, "opaa-update-paket-1.2.3/SHA256SUMS").decode().splitlines()
    assert len(sums) == 11
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


@pytest.mark.parametrize("missing", ["delta", "fact_sheets", "template", "licenses", "operations"])
def test_a_missing_part_fails_the_package(tmp_path: Path, inputs: dict, missing: str) -> None:
    if missing == "operations":
        (inputs["operations"] / "antwortqualitaet.md").unlink()
    elif missing == "delta":
        (inputs["delta"] / "manifest-delta.json").unlink()
    elif missing == "fact_sheets":
        (inputs["fact_sheets"] / "faktenblaetter.json").unlink()
    elif missing == "template":
        inputs["template"].unlink()
    else:
        inputs["licenses"][1].unlink()

    with pytest.raises(update_package.PackageError):
        update_package.assemble(release(), out=tmp_path / "out", **inputs)


def test_the_release_notes_name_the_package_and_its_verification(tmp_path: Path) -> None:
    notes = subprocess.run(
        ["bash", str(RELEASE_NOTES), "criew/opaa", "v1.2.4"],
        cwd=RELEASE_NOTES.parents[3], capture_output=True, text=True, check=True,
        env={**os.environ, "UPGRADE_NOTES_DIR": str(tmp_path)},
    ).stdout

    assert f"`{update_package.PREFIX}1.2.4.tar.gz`" in notes
    assert f"gh attestation verify {update_package.PREFIX}1.2.4.tar.gz --repo criew/opaa" in notes
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
    assert "subject-path:" in attestation
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
    target.write_text(text, encoding="utf-8")


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
    delta = tmp_path / "manifest-delta.json"
    delta.write_text(json.dumps(DELTA_JSON), encoding="utf-8")
    return dict(
        repo=work,
        old=update_package.Side(label="v1.2.2", commit=old, version="1.2.2"),
        new=update_package.Side(label="v1.2.3", commit=new, version="1.2.3"),
        delta=delta,
    )


def report(interval: dict, **changes) -> dict[str, str]:
    args = dict(repo=interval["repo"], previous=interval["old"], current=interval["new"],
                delta_json=interval["delta"])
    args.update(changes)
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
    delta = tmp_path / "embedding-delta.json"
    delta.write_text(json.dumps({"changes": [
        {"group": "MODELS_AND_PROMPTS", "section": "settings", "id": "spring.ai.openai.embedding.model",
         "title": "x", "kind": "CHANGED", "field": "defaultValue", "before": "nomic-embed-text",
         "after": "bge-m3"}]}), encoding="utf-8")

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
