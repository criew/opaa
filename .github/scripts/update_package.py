#!/usr/bin/env python3
"""Builds the update package of a release (docs/releases.md, "Update-Paket").

    update_package.py previous --tag <vX.Y.Z[-pre]> [--commit <ref>]
    update_package.py build --tag <vX.Y.Z[-pre]> --out <dir> [--previous <ref>]
                            [--labelled-prs <file>] [--repository <owner/name>]
    update_package.py operations --from <ref> [--to <ref>] --out <dir> [--delta-json <file>]

`previous` prints the final release tag vX.Y.Z the package compares with: the highest one below
the version of --tag among the tags reachable from --commit (default: the tag). Pre-release tags
never count; it prints nothing before the first final release.

`build` describes the checked-out commit. If the tag exists it must point there; otherwise the run
is a trial and the package says so. It renders fact sheets and manifest delta with Gradle, checks
the licenses of backend and frontend (frontend/node_modules must be installed) and writes
opaa-update-paket-<version>.tar.gz with its .sha256 file into --out. Without a previous final
release the delta starts from the oldest root commit, which has no manifest. Dates and file
metadata come from the commit, so the same commit yields the same files.

`operations` writes the files of betrieb/ for any two references (default --to: HEAD): break and
preparation, reindexing, schema migration, new ports and egress targets, answer quality. Every
value is read from Git at the two commits from the repository root, never from the working tree;
data flows come from the manifest delta (--delta-json, as `./gradlew complianceDelta` writes it)
and are reported as not determined without it. A delta of other commits or a missing --delta-json
file fails the run. `build` writes the same files for its interval into the package.

Exit codes: 0 success, 1 failure, 2 usage. Standard library only.
"""

from __future__ import annotations

import argparse
import datetime
import gzip
import hashlib
import io
import json
import os
import re
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
NOTICE = "> Fakten aus dem Code; rechtliche Bewertung bleibt bei der Behörde."
UPGRADE_NOTES = "docs/upgrade-notes/"
CHANGELOG = "backend/src/main/resources/db/changelog/"
CHART_VALUES = "deploy/helm/opaa/values.yaml"
CHART_TEMPLATES = "deploy/helm/opaa/templates/"
BASELINES = "eval/baseline/"
OUTBOUND = MANIFEST_PATH + "outbound.yaml"
EMBEDDING_MODEL = "spring.ai.openai.embedding.model"
OPERATIONS_FILES = ("antwortqualitaet.md", "betriebsfolgen.md")
NOT_MEASURED = "nicht gemessen"


class PackageError(Exception):
    """A failure the run reports with its message alone."""


def run(args: list[str], cwd: Path, stdout=subprocess.PIPE) -> str:
    result = subprocess.run(args, cwd=cwd, stdout=stdout, stderr=subprocess.PIPE, text=True,
                            encoding="utf-8")
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
class Side:
    """One end of the interval: the reference as named, its commit and its release version (None
    for anything that is no release tag)."""

    label: str
    commit: str
    version: str | None


def files_at(repo: Path, commit: str, directory: str) -> list[str]:
    """Paths from the repository root, whatever the working directory of `repo`."""
    return sorted(git(repo, "ls-tree", "-r", "--full-tree", "--name-only", commit, "--", directory)
                  .splitlines())


def read_at(repo: Path, commit: str, path: str) -> str:
    return run(["git", "cat-file", "blob", f"{commit}:{path}"], repo)


def version_key(version: str) -> tuple:
    """Semantic Versioning precedence: a pre-release sorts before its final release."""
    pre = version.partition("-")[2]
    ids = tuple((0, int(p), "") if p.isdigit() else (1, 0, p) for p in pre.split(".")) if pre else ()
    return core(version), 0 if pre else 1, ids


def where(side: Side) -> str:
    return f"`{side.label}` (Commit `{side.commit}`)"


def cell(value: object) -> str:
    """A value as table text; the manifest delta carries booleans as strings."""
    if isinstance(value, bool) or value in ("true", "false"):
        return "ja" if value in (True, "true") else "nein"
    if isinstance(value, float):
        return f"{value:.3f}".replace(".", ",")
    if value is None:
        return "keine Angabe"
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True)
    return " ".join(text.split()).replace("|", "\\|")


def header(title: str, previous: Side, current: Side) -> list[str]:
    return [
        "---",
        "erzeugung: generiert",
        f"gilt-fuer: {current.label}",
        f"commit: {current.commit}",
        f"vergleich: {previous.label}",
        f"vergleich-commit: {previous.commit}",
        "---",
        "",
        f"# {title} OPAA {current.label} gegenüber {previous.label}",
        "",
        NOTICE,
        "",
    ]


# --- Break and preparation (docs/upgrade-notes/) --------------------------------------------------

NO_PREPARATION = re.compile(r"\s*Keine Vorbereitung nötig\.?\s*")
REINDEXING_MARK = re.compile(
    r"^\s*(?:[-*]\s+)?(?:\*\*)?Neuindizierung:(?:\*\*)?\s*(ja|nein)\b[\s,.;:–-]*(.*?)\s*(?:\*\*)?\s*$",
    re.IGNORECASE | re.MULTILINE,
)


@dataclass(frozen=True)
class Note:
    version: str
    path: str
    text: str

    @property
    def prepares(self) -> bool:
        """Whether the note asks for more than "Keine Vorbereitung nötig."; a reindexing line alone
        is no preparation."""
        return NO_PREPARATION.fullmatch(REINDEXING_MARK.sub("", self.text)) is None


def upgrade_notes(repo: Path, previous: Side, current: Side) -> list[Note]:
    """The notes of every version in (previous, current]: by version where the comparison is a
    release, else the files the comparison does not hold. A final X.Y.Z note replaces those of its
    pre-releases, as it names every step since the previous final release (docs/releases.md)."""
    before = set(files_at(repo, previous.commit, UPGRADE_NOTES))
    found: dict[str, str] = {}
    for path in files_at(repo, current.commit, UPGRADE_NOTES):
        name = path[len(UPGRADE_NOTES):]
        version = release_version("v" + name[:-3]) if name.endswith(".md") and "/" not in name else None
        if version is None:
            continue
        if current.version and version_key(version) > version_key(current.version):
            continue
        if previous.version and version_key(version) <= version_key(previous.version):
            continue
        if not previous.version and path in before:
            continue
        found[version] = path
    finals = {v for v in found if "-" not in v}
    chosen = sorted((v for v in found if "-" not in v or v.split("-", 1)[0] not in finals), key=version_key)
    return [Note(v, found[v], read_at(repo, current.commit, found[v]).strip()) for v in chosen]


def notes_source(previous: Side, current: Side) -> str:
    bound = f"über `{previous.version}`" if previous.version else f"die {where(previous)} nicht enthält"
    return (f"Quelle: `{UPGRADE_NOTES}<Version>.md` im Stand {where(current)}, jede Version {bound}"
            f" bis zur eigenen; ein Hinweis zu X.Y.Z ersetzt die seiner Vorab-Releases"
            " (`docs/releases.md`, „Vorbereitung für Bestandsinstallationen“).")


def break_section(notes: list[Note], previous: Side, current: Side) -> list[str]:
    lines = ["## Bruch und Vorbereitung", ""]
    preparing = [n for n in notes if n.prepares]
    if preparing:
        lines.append(f"**Ja.** Vor dem Update ist etwas vorzubereiten, nach den Upgrade-Hinweisen zu "
                     f"{', '.join(n.version for n in preparing)}. Wer Versionen überspringt, erledigt"
                     " die Schritte aller Hinweise.")
    elif notes:
        lines.append(f"**Nein.** Die Upgrade-Hinweise zu {', '.join(n.version for n in notes)}"
                     " verlangen keine Vorbereitung.")
    else:
        lines.append("**Nein.** Kein Upgrade-Hinweis im Intervall; ohne Hinweis verlangt ein Release"
                     " keine Vorbereitung.")
        if current.version and re.fullmatch(r"\d+\.\d+\.0", current.version):
            lines.append(f"Für das finale Release {current.version} fehlt der Pflicht-Hinweis; so"
                         " veröffentlicht der Release-Lauf es nicht.")
    lines += ["", "Als Bruch gilt, was eine Bestandsinstallation zu Handarbeit zwingt; diese Schritte"
              " nennt der Upgrade-Hinweis jeder Version.", "", notes_source(previous, current), ""]
    for note in preparing:
        lines += [f"### Vorbereitung für {note.version}", "", note.text, ""]
    return lines


# --- Reindexing -----------------------------------------------------------------------------------

def read_baselines(repo: Path, commit: str) -> dict[str, dict | None]:
    """eval/baseline/*.json by file name; None for a file that is no JSON object."""
    found: dict[str, dict | None] = {}
    for path in files_at(repo, commit, BASELINES):
        name = path[len(BASELINES):]
        if "/" in name or not name.endswith(".json"):
            continue
        try:
            value = json.loads(read_at(repo, commit, path))
        except ValueError:
            value = None
        found[name] = value if isinstance(value, dict) else None
    return found


def pipeline_versions(baselines: dict[str, dict | None]) -> tuple[str, dict[str, str]] | None:
    """The registered ingestion pipelines with their versions, from the first baseline naming them;
    PipelinePathIsolationTest holds the fingerprint equal to the code."""
    for name, value in sorted(baselines.items()):
        points = (value or {}).get("fixedPoints") or {}
        fingerprint = points.get("ingestionPipelineFingerprint") or (points.get("pipeline") or {}).get(
            "ingestionPipelineFingerprint")
        if isinstance(fingerprint, str) and fingerprint:
            pairs = (part.rpartition(":") for part in fingerprint.split(","))
            return name, {fmt: ver for fmt, _, ver in pairs}
    return None


def delta_changes(delta_json: Path | None, previous: Side, current: Side) -> list[dict] | None:
    """The changes of a manifest delta written for exactly this interval; None without a delta."""
    if delta_json is None:
        return None
    if not delta_json.is_file():
        raise PackageError(f"manifest delta {delta_json} is missing")
    try:
        delta = json.loads(delta_json.read_text(encoding="utf-8"))
    except ValueError as error:
        raise PackageError(f"{delta_json} is no JSON: {error}") from error
    for end, side in (("from", previous), ("to", current)):
        commit = ((delta.get(end) if isinstance(delta, dict) else None) or {}).get("commit")
        if commit != side.commit:
            raise PackageError(f"{delta_json} compares {end} commit {commit}, the interval has"
                               f" {side.commit} ({side.label})")
    changes = delta.get("changes")
    if not isinstance(changes, list):
        raise PackageError(f"{delta_json} holds no list 'changes'")
    return changes


def reindexing_section(notes: list[Note], baselines: tuple[dict, dict], changes: list[dict] | None,
                       previous: Side, current: Side) -> list[str]:
    lines = ["## Neuindizierung", ""]
    marks = [(n, m) for n in notes if n.prepares for m in REINDEXING_MARK.finditer(n.text)]
    needed = [(n, m.group(2)) for n, m in marks if m.group(1).lower() == "ja"]
    unmarked = [n for n in notes if n.prepares and not REINDEXING_MARK.search(n.text)]
    if needed:
        lines.append(f"Neuindizierung: **nötig**, nach dem Upgrade-Hinweis zu "
                     f"{', '.join(dict.fromkeys(n.version for n, _ in needed))}.")
        for note, estimate in needed:
            lines.append(f"Dauerschätzung ({note.version}): {estimate}" if estimate
                         else f"Dauerschätzung ({note.version}): nicht angegeben.")
    elif unmarked:
        lines += [f"Neuindizierung: nicht gekennzeichnet. Der Upgrade-Hinweis zu "
                  f"{', '.join(n.version for n in unmarked)} trägt keine Zeile „Neuindizierung: ja“"
                  " oder „Neuindizierung: nein“; ob eine nötig ist, steht allenfalls in seinem Text"
                  " unter „Bruch und Vorbereitung“.", "Dauerschätzung: nicht angegeben."]
    else:
        lines.append("Neuindizierung: nicht nötig. Eine Neuindizierung ist ein Bruch und stünde im"
                     " Upgrade-Hinweis; keiner im Intervall verlangt sie.")
    lines += ["", "Einen Richtwert der Dauer aus der Evaluierung gibt es nicht: Die Retrieval-Regression"
              " legt ihre Laufzeit nur als Freitext ab, gemessen mit einem kleinen Modell auf CPU, und"
              " sie ist auf den Embedding-Endpunkt einer Installation nicht übertragbar. Die Schätzung"
              " stammt deshalb nur aus dem Upgrade-Hinweis.", "", notes_source(previous, current), ""]

    lines += ["### Nachzug", ""]
    before, after = (pipeline_versions(b) for b in baselines)
    if before is None or after is None:
        missing = where(previous if before is None else current)
        lines.append(f"Versionen der Verarbeitung je Format: nicht ermittelt, kein Abdruck im Stand {missing}.")
    else:
        moved = [f"`{fmt}` {before[1][fmt]} → {after[1][fmt]}" for fmt in sorted(after[1])
                 if fmt in before[1] and before[1][fmt] != after[1][fmt]]
        if moved:
            lines.append(f"Die Verarbeitung dieser Formate hat eine neue Version: {', '.join(moved)}."
                         " Bibliotheken mit solchen Dokumenten zeigen „Nachzug ausstehend“; der Nachzug"
                         " zerlegt sie aus dem Original neu, ohne Neuindizierung. Bis dahin bleiben sie"
                         " in der älteren Fassung durchsuchbar.")
        else:
            lines.append("Keine Verarbeitung eines Formats hat eine neue Version; es ist kein Nachzug nötig.")
    sources = " und ".join(dict.fromkeys(f"`{BASELINES}{found[0]}`" for found in (before, after) if found))
    lines += ["", f"Quelle: Feld `ingestionPipelineFingerprint` der Baselines in `{BASELINES}`"
              + (f" ({sources})" if sources else "") + f", in {where(previous)} und {where(current)};"
              " `PipelinePathIsolationTest` hält es gleich den registrierten Pipelines.", ""]

    lines += ["### Neueinbettung", ""]
    if changes is None:
        lines.append("Vorgabe des Embedding-Modells: nicht ermittelt, ohne Manifest-Delta.")
    else:
        model = [c for c in changes if c.get("section") == "settings" and c.get("id") == EMBEDDING_MODEL
                 and c.get("kind") == "CHANGED" and c.get("field") == "defaultValue"]
        if model:
            lines.append(f"Die Vorgabe des Embedding-Modells ändert sich: `{cell(model[0].get('before'))}`"
                         f" → `{cell(model[0].get('after'))}`. Eine Installation, die das Modell nicht"
                         " selbst setzt, erkennt den Wechsel beim Start und verlangt eine Neueinbettung"
                         " aus der Administration, keine Neuindizierung.")
        else:
            lines.append("Die Vorgabe des Embedding-Modells bleibt; es ist keine Neueinbettung nötig.")
    lines += ["", f"Quelle: Eintrag `{EMBEDDING_MODEL}` unter `settings` im Fakten-Manifest beider"
              " Stände, gelesen über `delta/manifest-delta.json`.", ""]
    return lines


# --- Schema migration (Liquibase) -----------------------------------------------------------------

CHANGESET_START = re.compile(r"^\s*-\s*changeSet:\s*$", re.MULTILINE)
CHANGESET_ID = re.compile(r"^\s*id:\s*[\"']?([^\"'\s#]+)", re.MULTILINE)


def changesets(repo: Path, commit: str) -> dict[str, str]:
    """Every changeSet id of the changelogs with its area, the directory below db/changelog/."""
    found: dict[str, str] = {}
    for path in files_at(repo, commit, CHANGELOG):
        if not path.endswith((".yaml", ".yml")):
            continue
        relative = path[len(CHANGELOG):]
        area = relative.split("/", 1)[0] if "/" in relative else relative
        for block in CHANGESET_START.split(read_at(repo, commit, path))[1:]:
            match = CHANGESET_ID.search(block)
            if match is None:
                raise PackageError(f"changeSet without id in {path} at {commit}")
            found[match.group(1)] = area
    return found


def migration_section(repo: Path, previous: Side, current: Side) -> list[str]:
    before = changesets(repo, previous.commit)
    after = changesets(repo, current.commit)
    added = sorted(i for i in after if i not in before)
    removed = sorted(i for i in before if i not in after)
    lines = ["## Schemamigration", ""]
    if added:
        lines += [f"{len(added)} neue Changesets; Liquibase führt sie beim ersten Start der neuen Version"
                  " aus. Bereich ist das Modul, dem die Tabellen gehören.", "",
                  "| Bereich | Changesets | Kennungen |", "|---|---|---|"]
        for area in sorted({after[i] for i in added}):
            ids = [i for i in added if after[i] == area]
            lines.append(f"| {area} | {len(ids)} | {', '.join(f'`{i}`' for i in ids)} |")
        lines += ["", f"Schemaänderungen laufen nur vorwärts. **Vor dem Update die Datenbank sichern:**"
                  f" Zurück auf {previous.label} geht es nur über diese Sicherung."]
    else:
        lines.append("Keine neuen Changesets: Das Update ändert das Datenbankschema nicht.")
    if removed:
        lines += ["", f"{len(removed)} Changesets des Vergleichsstands fehlen im neuen Stand, etwa weil"
                  " die Historie zu einer neuen Baseline zusammengefasst ist. Dann ist das Update ein"
                  " Neuaufsetzen; der Upgrade-Hinweis sagt das."]
    lines += ["", f"Quelle: Kennungen der `changeSet`-Einträge unter `{CHANGELOG}` in {where(previous)}"
              f" und {where(current)}; neu ist jede Kennung, die nur der neue Stand hat.", ""]
    return lines


# --- Ports (chart) and egress targets (manifest) --------------------------------------------------

VALUES_LINE = re.compile(r"^(\s*)(-\s+)?([A-Za-z0-9_.-]+):\s*(.*?)\s*$")
CONTAINER_PORT = re.compile(r"^\s*(?:-\s+)?containerPort:\s*(.+?)\s*$", re.MULTILINE)


def value_ports(text: str) -> dict[str, list[str]]:
    """Keys ending in 'port' with a number in values.yaml, by dotted path; block style only."""
    stack: list[tuple[int, str]] = []
    found: dict[str, set[str]] = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = VALUES_LINE.match(line)
        if match is None:
            continue
        indent = len(match.group(1)) + len(match.group(2) or "")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, match.group(3)))
        value = match.group(4).split(" #", 1)[0].strip()
        if match.group(3).lower().endswith("port") and value.isdigit():
            found.setdefault(".".join(k for _, k in stack), set()).add(value)
    return {path: sorted(values) for path, values in found.items()}


def container_ports(repo: Path, commit: str) -> dict[str, list[str]]:
    """Every containerPort line of the chart templates, as text: most name a value of values.yaml."""
    return {
        f"`containerPort: {match.group(1)}` in `{path}`": []
        for path in files_at(repo, commit, CHART_TEMPLATES)
        for match in CONTAINER_PORT.finditer(read_at(repo, commit, path))
    }


def compared(before: dict[str, list[str]], after: dict[str, list[str]], show_values: bool) -> list[str]:
    lines = []
    for key in sorted(set(before) | set(after)):
        old, new = before.get(key), after.get(key)
        if old == new:
            continue
        if old is None:
            lines.append(f"- {key}{': ' + ', '.join(new) if show_values else ''} — neu")
        elif new is None:
            lines.append(f"- {key}{': ' + ', '.join(old) if show_values else ''} — entfällt")
        else:
            lines.append(f"- {key}: {', '.join(old)} → {', '.join(new)} — geändert")
    return lines


def egress_section(repo: Path, changes: list[dict] | None, previous: Side, current: Side) -> list[str]:
    def values(side: Side) -> dict[str, list[str]]:
        has = CHART_VALUES in files_at(repo, side.commit, CHART_VALUES)
        ports = value_ports(read_at(repo, side.commit, CHART_VALUES)) if has else {}
        return {f"`{path}`": v for path, v in ports.items()}

    lines = ["## Neue Ports und Egress-Ziele", "", "### Ports im Helm-Chart", ""]
    ports = compared(values(previous), values(current), True) + compared(
        container_ports(repo, previous.commit), container_ports(repo, current.commit), False)
    lines += ports or ["Keine neuen, geänderten oder entfallenen Ports im Chart."]
    lines += ["", f"Quelle: Schlüssel auf `port` mit Zahlenwert in `{CHART_VALUES}` (Voreinstellungen) und"
              f" `containerPort` in `{CHART_TEMPLATES}`, in {where(previous)} und {where(current)}.", ""]

    lines += ["### Egress-Ziele", ""]
    if changes is None:
        lines.append("Ausgehende Datenflüsse: nicht ermittelt, ohne Manifest-Delta.")
    else:
        flows = []
        for change in (c for c in changes if c.get("section") == "dataFlows"):
            kind, field, flow = change.get("kind"), change.get("field"), f"`{change.get('id')}`"
            if kind == "ADDED":
                entry = change.get("after") or {}
                flows.append(f"- **Neu:** {flow}: {cell(entry.get('target'))}; über den Ausgangsproxy:"
                             f" {cell(entry.get('viaOutboundProxy'))}")
            elif kind == "REMOVED":
                flows.append(f"- **Entfällt:** {flow}: {cell((change.get('before') or {}).get('target'))}")
            elif field == "target":
                flows.append(f"- **Ziel geändert:** {flow}: vorher „{cell(change.get('before'))}“,"
                             f" jetzt „{cell(change.get('after'))}“")
            elif field == "viaOutboundProxy":
                flows.append(f"- **Weg geändert:** {flow}: über den Ausgangsproxy vorher"
                             f" {cell(change.get('before'))}, jetzt {cell(change.get('after'))}")
        lines += flows or ["Keine neuen, geänderten oder entfallenen Ziele ausgehender Datenflüsse."]
        lines += ["", "Ein Ziel ist eine Rolle, keine Adresse; welche Adresse dahintersteht, legt die"
                  " Konfiguration der Installation fest (Faktenblatt Datenflüsse)."]
    lines += ["", f"Quelle: Abschnitt `dataFlows` des Fakten-Manifests (`{OUTBOUND}`) in {where(previous)}"
              f" und {where(current)}, gelesen über `delta/manifest-delta.json`.", ""]
    return lines


# --- Answer quality (eval/baseline/) --------------------------------------------------------------

METRIC = re.compile(r"(hitRate|mrr|ndcg|recall)(?:At(\d+))?")
METRIC_NAMES = {"hitRate": "Hit Rate", "mrr": "MRR", "ndcg": "nDCG", "recall": "Recall"}


def baseline_title(name: str) -> str:
    stem = name[:-5]
    if stem.startswith("pipeline-") and stem.endswith("-conversations"):
        return f"Mehrrunden-Pfad, {stem[len('pipeline-'):-len('-conversations')]}"
    if stem.startswith("pipeline-"):
        return f"Pipeline-Pfad, {stem[len('pipeline-'):]}"
    return f"Rohvektor-Pfad, {stem}"


def metrics(value: dict | None) -> dict[str, object]:
    overall = ((value or {}).get("groups") or {}).get("overall") or {}
    found = {k: v for k, v in overall.items() if METRIC.fullmatch(k)}
    order = list(METRIC_NAMES)
    return dict(sorted(found.items(), key=lambda kv: (order.index(METRIC.fullmatch(kv[0]).group(1)), kv[0])))


def metric_name(key: str) -> str:
    match = METRIC.fullmatch(key)
    return METRIC_NAMES[match.group(1)] + (f"@{match.group(2)}" if match.group(2) else "")


def contract(value: dict | None) -> object:
    return next((v for k, v in (value or {}).items() if k.endswith("easurementContractVersion")), None)


def quality(sides: tuple[dict, dict], previous: Side, current: Side) -> str:
    lines = header("Antwortqualität", previous, current)
    lines += [
        "Die Kennzahlen stammen aus der Retrieval-Regression: Sie messen, ob die Suche zu festen Fragen"
        " die erwarteten Dokumente findet. Die Antwort des Modells messen sie nicht; sie kann aber nur"
        " belegen, was die Suche findet.",
        "",
        "Je Version stehen hier die Referenzwerte (Baselines) aus ihrem Stand im Repository: der zuletzt"
        " angenommene Messlauf mit Datum. Der nächtliche Lauf prüft den Code gegen diese Werte mit"
        " Toleranz (ADR-0013) und meldet eine Verschlechterung darüber als Regression. Ein Wert, den es"
        " in einem Stand nicht gibt, steht als „nicht gemessen“ da.",
        "",
        "**Messlauf auf dem Commit dieser Version: nicht gemessen.** Die Ergebnisse der einzelnen Läufe"
        " liegen nur als Artefakte am Workflow-Lauf, 90 Tage lang, nicht im Repository. Für einen"
        " Messwert genau dieser Version bräuchte es einen Messbericht je Release im Repository oder am"
        " Release.",
        "",
        f"Quelle: `{BASELINES}*.json` in {where(previous)} und {where(current)}, Gruppe `overall`;"
        " Verfahren in `docs/features/search-quality-evaluation.md`, Lauf"
        " `.github/workflows/retrieval-regression.yml`.",
        "",
    ]
    names = sorted(set(sides[0]) | set(sides[1]))
    if not names:
        lines += ["## Retrieval-Regression", "", f"Alle Werte: {NOT_MEASURED}, in keinem der beiden Stände"
                  f" liegt eine Baseline unter `{BASELINES}`.", ""]
    for name in names:
        old, new = sides[0].get(name), sides[1].get(name)

        def value(entry: dict | None, present: bool, getter) -> str:
            if not present:
                return NOT_MEASURED
            if entry is None:
                return "nicht lesbar"
            found = getter(entry)
            return NOT_MEASURED if found is None else cell(found)

        def provenance(entry: dict) -> object:
            origin = entry.get("provenance") or {}
            return origin.get("sourcePullRequest") or origin.get("sourceReportRunStartedAt")

        present = (name in sides[0], name in sides[1])
        rows = [("Messdatum", lambda e: e.get("measuredAt")), ("Messlauf", provenance),
                ("Messvertrag", contract)]
        keys = list(dict.fromkeys([*metrics(old), *metrics(new)]))
        rows += [(metric_name(k), lambda e, k=k: metrics(e).get(k)) for k in keys]
        lines += [f"## {baseline_title(name)}", "", f"Datei `{BASELINES}{name}`, Gruppe `overall`.", "",
                  f"| Kennzahl | {previous.label} | {current.label} |", "|---|---|---|"]
        for title, getter in rows:
            lines.append(f"| {title} | {value(old, present[0], getter)} | {value(new, present[1], getter)} |")
        lines.append("")
        if old is None or new is None:
            missing = where(previous if old is None else current)
            lines.append(f"Nicht vergleichbar: Im Stand {missing} gibt es keine lesbare Baseline dieses Pfads.")
        else:
            points = (old.get("fixedPoints") or {}, new.get("fixedPoints") or {})
            moved = sorted(k for k in set(points[0]) | set(points[1]) if points[0].get(k) != points[1].get(k))
            if contract(old) != contract(new):
                moved.insert(0, "Messvertrag")
            lines.append(f"Vergleichbar: nein, Messgrundlage geändert ({', '.join(f'`{m}`' for m in moved)}). Jede"
                         " Spalte gilt unter ihrer eigenen Messgrundlage." if moved
                         else "Vergleichbar: ja, gleiche Messgrundlage.")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def operations(repo: Path, previous: Side, current: Side, delta_json: Path | None) -> dict[str, str]:
    """The files of betrieb/ for the interval from `previous` to `current`, by file name."""
    notes = upgrade_notes(repo, previous, current)
    changes = delta_changes(delta_json, previous, current)
    baselines = (read_baselines(repo, previous.commit), read_baselines(repo, current.commit))
    lines = header("Betriebsfolgen", previous, current)
    lines += [
        f"Was ein Update von {previous.label} auf {current.label} für den Betrieb bedeutet. Jede Angabe"
        " nennt ihre Quelle; gelesen wird aus Git im Stand beider Commits, nicht aus einem Arbeitsstand.",
        "",
        *break_section(notes, previous, current),
        *reindexing_section(notes, baselines, changes, previous, current),
        *migration_section(repo, previous, current),
        *egress_section(repo, changes, previous, current),
    ]
    return {
        "antwortqualitaet.md": quality(baselines, previous, current),
        "betriebsfolgen.md": "\n".join(lines).rstrip() + "\n",
    }


def write_operations(files: dict[str, str], out: Path) -> list[Path]:
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for name in OPERATIONS_FILES:
        target = out / name
        target.write_text(files[name], encoding="utf-8")
        written.append(target)
    return written


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
    # True when --previous named the comparison; only an automatic one is called the previous release.
    previous_given: bool = False


def readme(release: Release) -> str:
    name = PREFIX + release.version
    target = release.commit if release.trial else release.tag
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
        NOTICE,
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
            f" {release.previous}."
            if release.previous and release.previous_given
            else f" {release.previous}, dem vorigen finalen Release."
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
        "| `betrieb/betriebsfolgen.md` | Bruch und Vorbereitung, Neuindizierung, Schemamigration, neue"
        " Ports und Egress-Ziele, je mit Quelle |",
        "| `betrieb/antwortqualitaet.md` | Kennzahlen der Retrieval-Regression beider Versionen mit"
        " Messdatum; fehlende Werte stehen als „nicht gemessen“ da |",
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
        f"./gradlew complianceDelta -PfromRef=<älteres Tag> -PtoRef={target}",
        "```",
        "",
        "Betriebsfolgen und Antwortqualität dazu aus der Wurzel desselben Checkouts:",
        "",
        "```bash",
        f"python3 .github/scripts/update_package.py operations --from <älteres Tag> --to {target} \\",
        "  --out <Verzeichnis> --delta-json backend/build/compliance/delta/manifest-delta.json",
        "```",
        "",
        "## Noch nicht enthalten",
        "",
        "- Der Abgleich mit der Konfiguration einer bestimmten Installation.",
        "",
    ]
    return "\n".join(lines)


def assemble(
    release: Release,
    fact_sheets: Path,
    delta: Path,
    operations: Path,
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
    for file in OPERATIONS_FILES:
        if not (operations / file).is_file():
            raise PackageError(f"{operations / file} is missing")
        files[f"betrieb/{file}"] = (operations / file).read_bytes()
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
        previous_given=bool(args.previous),
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
        operations_dir = reports / "betrieb"
        write_operations(
            operations(
                ROOT,
                Side(previous or from_commit, from_commit, release_version(previous) if previous else None),
                Side(args.tag if tag_commit else head, head, version),
                ROOT / DELTA / "manifest-delta.json",
            ),
            operations_dir,
        )
        return assemble(release, fact_sheets=ROOT / FACT_SHEETS, delta=ROOT / DELTA,
                        operations=operations_dir, licenses=[backend, frontend], template=TEMPLATE,
                        out=Path(args.out))


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
    consequences = commands.add_parser("operations")
    consequences.add_argument("--from", dest="from_ref", required=True)
    consequences.add_argument("--to", dest="to_ref", default="HEAD")
    consequences.add_argument("--out", required=True)
    consequences.add_argument("--delta-json")
    args = parser.parse_args(argv)
    try:
        if args.command == "previous":
            repo = Path.cwd()
            found = previous_final_tag(repo, args.tag, args.commit or args.tag)
            if found:
                print(found)
        elif args.command == "operations":
            repo = Path(git(Path.cwd(), "rev-parse", "--show-toplevel"))
            sides = []
            for ref in (args.from_ref, args.to_ref):
                sha = resolve(repo, ref)
                if sha is None:
                    raise PackageError(f"unknown reference {ref!r} (checked out with tags and history?)")
                sides.append(Side(ref, sha, release_version(ref)))
            delta_json = Path(args.delta_json) if args.delta_json else None
            for written in write_operations(operations(repo, *sides, delta_json), Path(args.out)):
                print(written)
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
