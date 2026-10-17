#!/usr/bin/env python3
"""Builds the update package of a release (docs/releases.md, "Update-Paket").

    update_package.py previous --tag <vX.Y.Z[-pre]> [--commit <ref>]
    update_package.py build --tag <vX.Y.Z[-pre]> --out <dir> [--previous <ref>]
                            [--labelled-prs <file>] [--repository <owner/name>]

`previous` prints the final release tag vX.Y.Z the package compares with: the highest one below
the version of --tag among the tags reachable from --commit (default: the tag). Pre-release tags
never count; it prints nothing before the first final release.

`build` describes the checked-out commit. If the tag exists it must point there; otherwise the run
is a trial and the package says so. It renders fact sheets and manifest delta with Gradle, checks
the licenses of backend and frontend (frontend/node_modules must be installed) and writes
opaa-update-paket-<version>.tar.gz with its .sha256 file into --out. Without a previous final
release the delta starts from the oldest root commit, which has no manifest. Dates and file
metadata come from the commit, so the same commit yields the same files.

Exit codes: 0 success, 1 failure, 2 usage. Standard library only.
"""

from __future__ import annotations

import argparse
import datetime
import gzip
import hashlib
import io
import os
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELEASE_VERSION = ROOT / "deploy" / "helm" / "ci" / "release-version.sh"
CHECK_LICENSES = ROOT / ".github" / "scripts" / "check_licenses.py"
TEMPLATE = ROOT / "docs" / "behoerden" / "09-betrieb-update" / "update-vermerk.md"
MANIFEST_PATH = "backend/src/main/resources/compliance/manifest/"
FACT_SHEETS = Path("backend/build/compliance/faktenblaetter")
DELTA = Path("backend/build/compliance/delta")
DELTA_FILES = ("manifest-delta.md", "manifest-delta.json")
CYCLONEDX = Path("backend/build/reports/cyclonedx/application.cdx.json")
DEFAULT_REPOSITORY = "criew/opaa"
PREFIX = "opaa-update-paket-"


class PackageError(Exception):
    """A failure the run reports with its message alone."""


def run(args: list[str], cwd: Path, stdout=subprocess.PIPE) -> str:
    result = subprocess.run(args, cwd=cwd, stdout=stdout, stderr=subprocess.PIPE, text=True)
    if result.returncode != 0:
        raise PackageError(f"{' '.join(args)} failed ({result.returncode}): {result.stderr.strip()}")
    return result.stdout if stdout is subprocess.PIPE else ""


def git(repo: Path, *args: str) -> str:
    return run(["git", *args], repo).strip()


def resolve(repo: Path, ref: str) -> str | None:
    """The commit `ref` points to, None for an unknown reference."""
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", "--end-of-options", f"{ref}^{{commit}}"],
        cwd=repo, capture_output=True, text=True,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def release_version(tag: str) -> str | None:
    """X.Y.Z[-pre] for a release tag, None for anything else (release-version.sh is the format)."""
    result = subprocess.run(["bash", str(RELEASE_VERSION), tag], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


def core(version: str) -> tuple[int, int, int]:
    major, minor, patch = version.split("-", 1)[0].split(".")
    return int(major), int(minor), int(patch)


def previous_final_tag(repo: Path, tag: str, commit: str) -> str | None:
    """The highest final tag below the version of `tag` that `commit` contains."""
    version = release_version(tag)
    if version is None:
        raise PackageError(f"{tag} is no release tag vX.Y.Z or vX.Y.Z-<pre> (docs/releases.md)")
    sha = resolve(repo, commit)
    if sha is None:
        raise PackageError(f"unknown commit {commit!r} (checked out with tags and history?)")
    best: tuple[tuple[int, int, int], str] | None = None
    for candidate in git(repo, "tag", "--list", "--merged", sha, "v*").splitlines():
        candidate_version = release_version(candidate)
        if candidate == tag or candidate_version is None or "-" in candidate_version:
            continue
        key = core(candidate_version)
        if key < core(version) and (best is None or key > best[0]):
            best = (key, candidate)
    return best[1] if best else None


def empty_base(repo: Path, commit: str) -> str:
    """The oldest root commit of `commit`; the delta of a first release starts there."""
    root = git(repo, "rev-list", "--max-parents=0", commit).splitlines()[-1]
    if git(repo, "ls-tree", "--name-only", root, "--", MANIFEST_PATH):
        raise PackageError(f"root commit {root} already holds a manifest; give --previous")
    return root


@dataclass(frozen=True)
class Release:
    """What the package describes and what it compares with."""

    version: str
    tag: str
    commit: str
    previous: str | None
    previous_commit: str
    trial: bool
    repository: str
    epoch: int


def readme(release: Release) -> str:
    name = PREFIX + release.version
    lines = [
        "---",
        "erzeugung: generiert",
        f"gilt-fuer: {release.version}",
        f"commit: {release.commit}",
        f"vorgaenger: {release.previous or 'keiner'}",
        f"vorgaenger-commit: {release.previous_commit}",
        "---",
        "",
        f"# Update-Paket OPAA {release.version}",
        "",
        "> Fakten aus dem Code; rechtliche Bewertung bleibt bei der Behörde.",
        "",
    ]
    if release.trial:
        lines += [
            f"> **Probelauf:** Das Tag `{release.tag}` gibt es nicht. Das Paket beschreibt den Commit",
            f"> `{release.commit}` und ist kein veröffentlichtes Release.",
            "",
        ]
    lines.append(
        f"Dieses Paket gehört zu OPAA {release.version} (Commit `{release.commit}`). Es enthält die"
        " Fakten dieser Version und ihre Änderungen gegenüber"
        + (
            f" {release.previous}, dem vorigen finalen Release."
            if release.previous
            else " einem Stand ohne Fakten-Manifest: Es gibt kein voriges finales Release, deshalb"
            " erscheint im Delta jeder Eintrag als neu."
        )
    )
    lines += [
        "",
        "| Datei | Inhalt |",
        "|---|---|",
        "| `delta/manifest-delta.md` | Änderungen der Fakten mit Stufenvorschlag, Bezug zu VVT, DSFA"
        " und Sicherheitskonzept und den als datenrelevant gekennzeichneten Pull Requests |",
        "| `delta/manifest-delta.json` | dieselben Änderungen einzeln, für Werkzeuge |",
        "| `faktenblaetter/` | die Faktenblätter dieser Version und `faktenblaetter.json` |",
        "| `lizenzen/` | Lizenzübersicht der ausgelieferten Abhängigkeiten von Backend und Frontend |",
        "| `update-vermerk.md` | Vorlage für den Update-Vermerk der Behörde |",
        "| `SHA256SUMS` | Prüfsummen aller Dateien des Pakets |",
        "",
        "## Prüfen",
        "",
        "```bash",
        f"gh attestation verify {name}.tar.gz --repo {release.repository}",
        "sha256sum -c SHA256SUMS   # im entpackten Verzeichnis",
        "```",
        "",
        "## Delta für ein anderes Versionspaar",
        "",
        "Aus `backend/` eines Checkouts mit Tags:",
        "",
        "```bash",
        f"./gradlew complianceDelta -PfromRef=<älteres Tag> -PtoRef={release.tag}",
        "```",
        "",
        "## Noch nicht enthalten",
        "",
        "- Betriebsfolgen (Bruch, Neuindizierung, Schemamigration, neue Ports oder Ziele) und"
        " Antwortqualität. Was eine Bestandsinstallation vor dem Update erledigen muss, steht in den"
        " Release-Notizen unter „Vorbereitung für Bestandsinstallationen“.",
        "- Der Abgleich mit der Konfiguration einer bestimmten Installation.",
        "",
    ]
    return "\n".join(lines)


def assemble(
    release: Release,
    fact_sheets: Path,
    delta: Path,
    licenses: list[Path],
    template: Path,
    out: Path,
) -> Path:
    """Writes <name>.tar.gz and <name>.tar.gz.sha256 into `out`; returns the archive."""
    name = PREFIX + release.version
    files: dict[str, bytes] = {"LIESMICH.md": readme(release).encode()}
    sheets = sorted(p for p in fact_sheets.glob("*") if p.is_file()) if fact_sheets.is_dir() else []
    if not any(p.name == "faktenblaetter.json" for p in sheets) or len(sheets) < 2:
        raise PackageError(f"no fact sheets in {fact_sheets}")
    for sheet in sheets:
        files[f"faktenblaetter/{sheet.name}"] = sheet.read_bytes()
    for file in DELTA_FILES:
        if not (delta / file).is_file():
            raise PackageError(f"{delta / file} is missing")
        files[f"delta/{file}"] = (delta / file).read_bytes()
    for report in licenses:
        if not report.is_file():
            raise PackageError(f"license overview {report} is missing")
        files[f"lizenzen/{report.name}"] = report.read_bytes()
    if not template.is_file():
        raise PackageError(f"update note template {template} is missing")
    files["update-vermerk.md"] = template.read_bytes()
    files["SHA256SUMS"] = "".join(
        f"{hashlib.sha256(content).hexdigest()}  {path}\n" for path, content in sorted(files.items())
    ).encode()

    out.mkdir(parents=True, exist_ok=True)
    archive = out / f"{name}.tar.gz"
    with open(archive, "wb") as raw, gzip.GzipFile(
        filename="", mode="wb", fileobj=raw, mtime=release.epoch, compresslevel=9
    ) as compressed, tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        directories = sorted({f"{name}/{p.rsplit('/', 1)[0]}" for p in files if "/" in p} | {name})
        for directory in directories:
            tar.addfile(entry(directory, tarfile.DIRTYPE, 0o755, 0, release.epoch))
        for path, content in sorted(files.items()):
            tar.addfile(entry(f"{name}/{path}", tarfile.REGTYPE, 0o644, len(content), release.epoch),
                        io.BytesIO(content))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (out / f"{name}.tar.gz.sha256").write_text(f"{digest}  {archive.name}\n", encoding="utf-8")
    return archive


def entry(path: str, kind: bytes, mode: int, size: int, mtime: int) -> tarfile.TarInfo:
    info = tarfile.TarInfo(path)
    info.type, info.mode, info.size, info.mtime = kind, mode, size, mtime
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    return info


def build(args: argparse.Namespace) -> Path:
    version = release_version(args.tag)
    if version is None:
        raise PackageError(f"{args.tag} is no release tag vX.Y.Z or vX.Y.Z-<pre> (docs/releases.md)")
    head = git(ROOT, "rev-parse", "HEAD")
    tag_commit = resolve(ROOT, args.tag)
    if tag_commit is not None and tag_commit != head:
        raise PackageError(f"{args.tag} points to {tag_commit}, the checkout is {head}")
    if args.previous:
        previous = args.previous
        from_commit = resolve(ROOT, previous)
        if from_commit is None:
            raise PackageError(f"unknown previous release {previous!r}")
    else:
        previous = previous_final_tag(ROOT, args.tag, head)
        from_commit = resolve(ROOT, previous) if previous else empty_base(ROOT, head)
    epoch = int(git(ROOT, "show", "-s", "--format=%ct", head))
    release = Release(
        version=version,
        tag=args.tag,
        commit=head,
        previous=previous,
        previous_commit=from_commit,
        trial=tag_commit is None,
        repository=args.repository,
        epoch=epoch,
    )
    day = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).date().isoformat()
    gradle = [
        "./gradlew", "complianceDocs", "complianceDelta", "cyclonedxBom",
        f"-PopaaVersion={version}", f"-PgeneratedOn={day}",
        f"-PfromRef={previous or from_commit}", f"-PtoRef={args.tag if tag_commit else head}",
    ]
    if args.labelled_prs:
        gradle.append(f"-PlabelledPrs={Path(args.labelled_prs).resolve()}")
    run(gradle, ROOT / "backend", stdout=None)

    with tempfile.TemporaryDirectory() as work:
        reports = Path(work)
        pnpm = reports / "pnpm-licenses.json"
        pnpm.write_text(run(["pnpm", "licenses", "list", "--prod", "--json"], ROOT / "frontend"),
                        encoding="utf-8")
        policy = ["--allowed", str(ROOT / ".github/licenses/allowed.txt"),
                  "--exceptions", str(ROOT / ".github/licenses/exceptions.txt")]
        backend = reports / "lizenzen-backend.md"
        frontend = reports / "lizenzen-frontend.md"
        run([sys.executable, str(CHECK_LICENSES), *policy, "--cyclonedx", str(ROOT / CYCLONEDX),
             "--ignore", "pkg:maven/io.opaa/*", "--title", "Backend", "--report", str(backend)],
            ROOT, stdout=None)
        run([sys.executable, str(CHECK_LICENSES), *policy, "--pnpm", str(pnpm),
             "--title", "Frontend", "--report", str(frontend)], ROOT, stdout=None)
        return assemble(release, ROOT / FACT_SHEETS, ROOT / DELTA, [backend, frontend], TEMPLATE,
                        Path(args.out))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    previous = commands.add_parser("previous")
    previous.add_argument("--tag", required=True)
    previous.add_argument("--commit")
    package = commands.add_parser("build")
    package.add_argument("--tag", required=True)
    package.add_argument("--out", required=True)
    package.add_argument("--previous")
    package.add_argument("--labelled-prs")
    package.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", DEFAULT_REPOSITORY))
    args = parser.parse_args(argv)
    try:
        if args.command == "previous":
            repo = Path.cwd()
            found = previous_final_tag(repo, args.tag, args.commit or args.tag)
            if found:
                print(found)
        else:
            archive = build(args)
            print(archive)
            print(f"{archive}.sha256")
    except PackageError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
