#!/usr/bin/env python3
"""Deterministic generator for the "comic-characters" evaluation corpus.

Reads the frozen `jrtec/Superheroes` (CC0-1.0) dataset snapshot from HuggingFace,
keeps only structured fact fields (explicitly excluding the free-text
`history_text` and `powers_text` columns), and emits one Markdown document per
character with a YAML frontmatter of structured facts plus a short,
generator-authored prose paragraph.

Design goals (see docs/features/search-quality-evaluation.md, "Der Testkorpus",
and ADR-0008):

- No sampling: the full dataset (train + test splits) is used.
- No network access is required to reproduce a run once the raw CSV files are
  cached locally under `raw-source/`; the script verifies their SHA-256 against
  the pinned values below before processing anything.
- Deterministic: entities are sorted by the dataset's numeric `id` column
  before sequential corpus IDs (`comic-0001`, ...) are assigned, and no
  wall-clock timestamps are embedded into generated files. Two runs against
  the same cached raw files produce byte-identical output.
- Documents stay under a conservative byte ceiling chosen to keep the
  "one entity = one chunk" property intact. See the comment on
  MAX_DOCUMENT_BYTES below for how that number was derived and why it is a
  byte, not token, limit.

Usage:
    python eval/generator/generate_corpus.py

See eval/generator/README.md for prerequisites and verification steps.
"""

from __future__ import annotations

import ast
import csv
import hashlib
import re
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path

# --- Pinned source snapshot ------------------------------------------------
#
# The dataset commit these files were retrieved from. Using the commit hash
# (rather than `resolve/main`) guarantees byte-identical downloads even if the
# dataset's default branch moves later.
SOURCE_COMMIT = "a2f7f35c36a4d551625a0607c7759ae7916fc6be"
SOURCE_REPO = "jrtec/Superheroes"
RETRIEVED_AT = "2026-08-02"
SOURCE_LICENSE = "CC0-1.0"
SOURCE_FIELD_VALUE = "huggingface/jrtec/Superheroes"

RAW_FILES = {
    "train.csv": "6db455fcb39c5eb1cce639c4d92e971bad96a53daa7cc2d2c04c3c73dca89f0c",
    "test.csv": "565ab276d1f754a9ef35ebc7d11df087fab92c5c9de4d004f5b184033f9b0103",
}

HF_BASE_URL = f"https://huggingface.co/datasets/{SOURCE_REPO}/resolve/{SOURCE_COMMIT}"

REPO_ROOT = Path(__file__).resolve().parents[2]
RAW_SOURCE_DIR = Path(__file__).resolve().parent / "raw-source"
CORPUS_DIR = REPO_ROOT / "eval" / "corpus" / "comic-characters"
DOMAIN = "comic-characters"

# The default field size limit (131072 bytes) is close to the largest
# `history_text` value observed in this snapshot (129594 chars, 1.1% margin)
# even though that column is never read here. Raised defensively so a future
# domain (#234) with denser free text doesn't hit a hard `csv.Error` here.
# `sys.maxsize` alone overflows the C `long` `_csv` uses on 32-bit builds
# (notably still the case for CPython's `_csv` module on Windows even in a
# 64-bit interpreter); halving down avoids that platform pitfall instead of
# hard-coding a guessed safe constant.
_field_size_limit = sys.maxsize
while True:
    try:
        csv.field_size_limit(_field_size_limit)
        break
    except OverflowError:
        _field_size_limit //= 10

# Frontmatter field order, matching docs/features/search-quality-evaluation.md.
FRONTMATTER_FIELDS = [
    "id",
    "domain",
    "name",
    "real_name",
    "creator",
    "alignment",
    "gender",
    "type_race",
    "place_of_birth",
    "first_appearance",
    "occupation",
    "teams",
    "eye_color",
    "hair_color",
    "height_cm",
    "weight_kg",
    "intelligence_score",
    "strength_score",
    "speed_score",
    "durability_score",
    "combat_score",
    "overall_score",
    "superpowers",
    "source",
    "license",
]

# The "one entity = one chunk" property that the whole corpus exists for is a
# statement about TOKENS, not bytes: `opaa.indexing` chunks with a Spring AI
# `TokenTextSplitter` whose `chunkSize` counts cl100k_base tokens, and that
# splitter has no overlap, so any document at or above the configured
# chunk-size (1000 tokens) becomes two-plus chunks. A byte ceiling is only a
# proxy for that; it holds exactly as long as no document's token density
# exceeds what this constant assumes.
#
# Decision (see docs/decisions/0010-ein-chunk-invariante-evaluierungskorpus.md
# for the alternatives and full reasoning): keep this generator standard
# library only rather than adding a `tiktoken` dependency just to measure
# bytes-per-token here. Instead, this ceiling is set conservatively below the
# worst token density actually measured across the current corpus (the
# densest document, comic-0295_chroma.md, sits at ~0.3146 tokens/byte, which
# would cross 1000 tokens at ~3178 bytes) and is deliberately re-verified
# whenever the corpus is regenerated — this check catches "prose got denser",
# it does not prove "no chunking regression", which is why the real proof
# lives in the Java retrieval-harness integration test (#227): it runs the
# actual TokenTextSplitter and can assert chunk-count-per-document == 1
# directly.
#
# NOTE for the Product Manager: the "4 KB" ceiling in issue #225's acceptance
# criteria is itself the source of this imprecision — it should be corrected
# there and in the #234 follow-up issue (the other three domains) to either
# reference a token budget, or explicitly document that any byte ceiling is a
# conservative proxy, not a guarantee.
MAX_DOCUMENT_BYTES = 3000
SMALL_WORDS = {"and", "of", "the"}


def download_raw_files() -> None:
    """Download the pinned CSV snapshots into raw-source/ if not already cached."""
    RAW_SOURCE_DIR.mkdir(parents=True, exist_ok=True)
    for filename, expected_sha256 in RAW_FILES.items():
        target = RAW_SOURCE_DIR / filename
        if target.exists() and sha256_of(target) == expected_sha256:
            continue
        url = f"{HF_BASE_URL}/{filename}"
        print(f"Downloading {url} -> {target}", file=sys.stderr)
        with urllib.request.urlopen(url) as response:  # noqa: S310 (pinned HTTPS URL)
            target.write_bytes(response.read())
        actual = sha256_of(target)
        if actual != expected_sha256:
            raise SystemExit(
                f"SHA-256 mismatch for {filename}: expected {expected_sha256}, got {actual}. "
                "The source snapshot may have drifted; do not proceed silently."
            )


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def verify_raw_files() -> None:
    for filename, expected_sha256 in RAW_FILES.items():
        path = RAW_SOURCE_DIR / filename
        if not path.exists():
            raise SystemExit(
                f"Missing raw source file {path}. Run download_raw_files() or place the "
                "file manually (see eval/generator/README.md)."
            )
        actual = sha256_of(path)
        if actual != expected_sha256:
            raise SystemExit(
                f"SHA-256 mismatch for {filename}: expected {expected_sha256}, got {actual}."
            )


# --- Parsing helpers --------------------------------------------------------


def clean(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip()
    if not value or value == "-":
        return None
    return value


def parse_height_cm(value: str) -> int | None:
    value = clean(value)
    if value is None:
        return None
    parsed: int | None = None
    match = re.search(r"([\d.]+)\s*cm", value)
    if match:
        parsed = round(float(match.group(1)))
    else:
        match = re.search(r"([\d.]+)\s*meters", value)
        if match:
            parsed = round(float(match.group(1)) * 100)
    # The source encodes "unknown" both as "-" (handled by clean()) and as a
    # literal zero measurement (e.g. "0'0 • 0 cm", 16 rows in the current
    # snapshot). No character is genuinely 0 cm tall, so this is a second,
    # undocumented sentinel for missing data and is normalized to None too.
    if parsed == 0:
        return None
    return parsed


def parse_weight_kg(value: str) -> int | None:
    value = clean(value)
    if value is None:
        return None
    parsed: int | None = None
    match = re.search(r"([\d.]+)\s*kg", value)
    if match:
        parsed = round(float(match.group(1)))
    else:
        match = re.search(r"([\d,.]+)\s*tons", value)
        if match:
            parsed = round(float(match.group(1).replace(",", "")) * 1000)
    # No zero-weight rows exist in the current snapshot, but apply the same
    # sentinel normalization as parse_height_cm for symmetry and in case a
    # future dataset refresh introduces one (0 kg is not a real measurement).
    if parsed == 0:
        return None
    return parsed


def parse_score(value: str) -> int | str | None:
    """Parse one of the six 0-100-ish attribute/overall score fields.

    Unlike height/weight, a `0` here is kept as a genuine value, not
    normalized to missing. Rationale (documented, not silently decided): in
    the current snapshot, 104 of the 105 rows whose `overall_score` is empty
    also have all five attribute scores at exactly 0 — that correlation
    suggests the dataset's "unrated character" sentinel is an empty
    `overall_score`, not a zero attribute score. Coercing 0 to null across
    the board would instead destroy the individual, plausible zero scores
    that exist on partially-rated characters (e.g. a purely physical
    character genuinely rated 0 for intelligence while other attributes are
    non-zero). Downstream consumers building numeric-range golden queries
    (#226) should be aware of this correlation and may want to additionally
    filter on `overall_score is not null` where an "unrated" semantic is
    needed.
    """
    value = clean(value)
    if value is None:
        return None
    if value == "∞":  # infinity, used by a handful of omnipotent characters
        return value
    try:
        return round(float(value))
    except ValueError:
        return None


def parse_list_literal(value: str) -> list[str]:
    value = (value or "").strip()
    if not value:
        return []
    try:
        parsed = ast.literal_eval(value)
    except (ValueError, SyntaxError):
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item).strip() for item in parsed if str(item).strip()]


def label_ability(column: str) -> str:
    """Turn a `has_*` column name into a human-readable ability label."""
    name = column[len("has_") :].replace("_", " ")
    words = name.split(" ")
    labeled = []
    for index, word in enumerate(words):
        if "-" in word:
            parts = word.split("-")
            parts = [part[:1].upper() + part[1:] if part else part for part in parts]
            labeled.append("-".join(parts))
        elif index > 0 and word in SMALL_WORDS:
            labeled.append(word)
        else:
            labeled.append(word[:1].upper() + word[1:] if word else word)
    return " ".join(labeled)


# --- YAML emission -----------------------------------------------------------
#
# A minimal, dependency-free, always-valid emitter: every string scalar is
# double-quoted (safe for any content, including colons, parentheses and
# apostrophes found in the source data); numbers and `null` are unquoted.
# This keeps output deterministic across environments and Python versions,
# which a general-purpose YAML library does not strictly guarantee.


def yaml_scalar(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, list):
        value = ", ".join(value) if value else None
        return yaml_scalar(value)
    text = str(value).replace("\\", "\\\\").replace('"', '\\"')
    return f'"{text}"'


def yaml_sequence(items: list[str]) -> str:
    """Render a real YAML flow sequence, each item safely double-quoted.

    Used for `teams` only: unlike ability labels (which never contain a
    comma), team names sometimes do (e.g. "Villainy, Inc."). Joining team
    names into a single comma-separated string, as done for `superpowers`,
    would make that comma indistinguishable from a separator and silently
    corrupt downstream parsing (e.g. the golden-query derivation in #226).
    """
    if not items:
        return "null"
    return "[" + ", ".join(yaml_scalar(item) for item in items) + "]"


def render_frontmatter(fields: dict) -> str:
    lines = ["---"]
    for key in FRONTMATTER_FIELDS:
        if key == "teams":
            lines.append(f"{key}: {yaml_sequence(fields[key])}")
        else:
            lines.append(f"{key}: {yaml_scalar(fields[key])}")
    lines.append("---")
    return "\n".join(lines)


# --- Prose generation --------------------------------------------------------
#
# The paragraph below is composed entirely from the structured fact fields
# already present in the frontmatter. No text is copied from the source
# dataset's free-text fields (which are excluded from processing entirely).


def possessive_pronouns(gender: str | None) -> tuple[str, str, str]:
    """Return (subject, possessive, object) pronouns for a gender string."""
    if gender and gender.strip().lower() == "male":
        return "He", "His", "him"
    if gender and gender.strip().lower() == "female":
        return "She", "Her", "her"
    return "They", "Their", "them"


# Vowel-letter word starts that are nonetheless pronounced with a leading
# consonant sound ("yoo-", "wun-"), where "a" is correct despite the letter:
# "university"/"united" (yoo-), "US"/"user" (yoo-), "European" (yoo-),
# "one-off" (wun-). A pure vowel-letter heuristic gets these wrong ("an
# University student"); this is not a full CMU-dictionary-grade solution,
# just the specific false positives observed in this corpus.
CONSONANT_SOUND_PREFIXES = ("uni", "us", "eu", "one")


def indefinite_article(phrase: str) -> str:
    """Pick "a" or "an" for the first word of `phrase` (vowel-sound heuristic)."""
    first_word = phrase.strip().split(" ", 1)[0] if phrase.strip() else ""
    lowered = first_word.lower()
    if lowered.startswith(CONSONANT_SOUND_PREFIXES):
        return "a"
    return "an" if lowered[:1] in "aeiou" else "a"


# Hair-color values that mean "no hair", not a color. Left verbatim as the
# genuinely reported value in the frontmatter, but the prose gets a "is bald"
# clause instead of the nonsensical "has No Hair hair" (13% of the corpus).
BALD_HAIR_VALUES = {"no hair", "none", "bald"}


# Matches a run of two or more "single letter + dot" groups at the end of a
# string, e.g. "S.H.I.E.L.D." or "U.S.". Used to tell a dotted-abbreviation
# ending apart from an ordinary sentence-ending period.
_ABBREVIATION_TAIL = re.compile(r"(?:[A-Za-z]\.){2,}$")


def strip_trailing_period(text: str) -> str:
    """Drop a single trailing sentence-ending '.' so the field can be
    embedded mid-sentence without producing '..' or '. and ...' when more
    text follows — but leave a dotted abbreviation's own final period alone
    (e.g. "S.H.I.E.L.D.", 6 documents in the current corpus), since stripping
    it would silently corrupt the abbreviation rather than fix punctuation."""
    if _ABBREVIATION_TAIL.search(text):
        return text
    if text.endswith("."):
        return text[:-1]
    return text


def join_natural(items: list[str]) -> str:
    if not items:
        return ""
    if len(items) == 1:
        return items[0]
    if len(items) == 2:
        return f"{items[0]} and {items[1]}"
    return ", ".join(items[:-1]) + f" and {items[-1]}"


def build_prose(fields: dict) -> str:
    name = fields["name"]
    subject, possessive, _ = possessive_pronouns(fields["gender"])
    plural = subject == "They"

    def verb(singular_form: str, plural_form: str) -> str:
        return plural_form if plural else singular_form

    sentences: list[str] = []

    intro_bits = []
    if fields["real_name"]:
        intro_bits.append(f"real name {fields['real_name']}")
    descriptor_bits = []
    if fields["alignment"]:
        descriptor_bits.append(f"{fields['alignment'].lower()}-aligned")
    if fields["gender"]:
        descriptor_bits.append(fields["gender"].lower())
    if fields["type_race"]:
        descriptor_bits.append(fields["type_race"])
    descriptor = " ".join(descriptor_bits + ["character"])
    intro = f"{name}"
    if intro_bits:
        intro += f", {', '.join(intro_bits)},"
    intro += f" is {indefinite_article(descriptor)} {descriptor}"
    if fields["creator"]:
        intro += f" created by {fields['creator']}"
    origin_bits = []
    if fields["place_of_birth"]:
        origin_bits.append(f"born in {strip_trailing_period(fields['place_of_birth'])}")
    if fields["first_appearance"]:
        origin_bits.append(
            f"first appearing in {strip_trailing_period(fields['first_appearance'])}"
        )
    if origin_bits:
        intro += f", {' and '.join(origin_bits)}"
    intro += "."
    sentences.append(intro)

    role_bits = []
    if fields["occupation"]:
        occupation = strip_trailing_period(fields["occupation"])
        role_bits.append(f"{verb('works', 'work')} as {indefinite_article(occupation)} {occupation}")
    teams = fields["teams"]
    if teams:
        team_names = [strip_trailing_period(team) for team in teams]
        role_bits.append(f"{verb('is', 'are')} affiliated with {join_natural(team_names)}")
    if role_bits:
        sentences.append(f"{subject} {' and '.join(role_bits)}.")

    hair_color = fields["hair_color"]
    is_bald = bool(hair_color) and hair_color.strip().lower() in BALD_HAIR_VALUES
    hair_for_prose = None if is_bald else hair_color

    has_verb = verb("has", "have")
    physical_bits = []
    if fields["eye_color"] and hair_for_prose:
        physical_bits.append(f"{has_verb} {fields['eye_color']} eyes and {hair_for_prose} hair")
    elif fields["eye_color"]:
        physical_bits.append(f"{has_verb} {fields['eye_color']} eyes")
    elif hair_for_prose:
        physical_bits.append(f"{has_verb} {hair_for_prose} hair")
    if is_bald:
        physical_bits.append(f"{verb('is', 'are')} bald")
    if fields["height_cm"] is not None and fields["weight_kg"] is not None:
        physical_bits.append(
            f"{verb('stands', 'stand')} {fields['height_cm']} cm tall and "
            f"{verb('weighs', 'weigh')} {fields['weight_kg']} kg"
        )
    elif fields["height_cm"] is not None:
        physical_bits.append(f"{verb('stands', 'stand')} {fields['height_cm']} cm tall")
    elif fields["weight_kg"] is not None:
        physical_bits.append(f"{verb('weighs', 'weigh')} {fields['weight_kg']} kg")
    if physical_bits:
        sentences.append(f"{subject} {' and '.join(physical_bits)}.")

    if fields["superpowers"]:
        sentences.append(
            f"{possessive} notable abilities include {join_natural(fields['superpowers'])}."
        )

    score_bits = []
    for label, key in [
        ("intelligence", "intelligence_score"),
        ("strength", "strength_score"),
        ("speed", "speed_score"),
        ("durability", "durability_score"),
        ("combat", "combat_score"),
    ]:
        if fields[key] is not None:
            score_bits.append(f"{fields[key]} for {label}")
    if score_bits:
        sentences.append(
            f"Rated on a 0-100 scale across attributes, {subject.lower()} "
            f"{verb('scores', 'score')} {join_natural(score_bits)}."
        )
    # overall_score is reported on its own scale (observed range in this
    # snapshot: 1-237, plus the literal string "∞" for a handful of
    # omnipotent characters) — not an average or otherwise derived from the
    # five 0-100 attribute scores above. Phrased as an independent sentence
    # so the prose doesn't imply a computation that doesn't exist.
    if fields["overall_score"] is not None:
        sentences.append(
            f"On a separate overall ranking scale, {possessive.lower()} overall score is "
            f"{fields['overall_score']}."
        )

    return " ".join(sentences)


# --- Entity model ------------------------------------------------------------


@dataclass
class Entity:
    raw_id: int
    fields: dict

    @property
    def slug(self) -> str:
        text = re.sub(r"[^a-z0-9]+", "-", self.fields["name"].lower()).strip("-")
        return text or "entity"

    @property
    def filename(self) -> str:
        return f"{self.fields['id']}_{self.slug}.md"


def transform_row(row: dict) -> Entity | None:
    """Convert one raw CSV row into structured fields, or None if discarded.

    Discard rule (documented per issue #225): an entity is dropped if its
    `name` field is empty or whitespace-only, or if its `id` is not a valid
    integer. Every row in the current dataset snapshot has both, so this rule
    currently discards nothing — it exists to keep the corpus reproducible if
    the upstream dataset ever adds incomplete rows.
    """
    name = clean(row.get("name"))
    raw_id_text = clean(row.get("id"))
    if name is None or raw_id_text is None or not raw_id_text.isdigit():
        return None

    has_columns = sorted(key for key in row.keys() if key.startswith("has_"))
    abilities = []
    for column in has_columns:
        raw_value = row.get(column, "").strip()
        try:
            is_set = float(raw_value) == 1.0
        except ValueError:
            is_set = False
        if is_set:
            abilities.append(label_ability(column))
    abilities.sort()

    fields = {
        "name": name,
        "real_name": clean(row.get("real_name")),
        "creator": clean(row.get("creator")),
        "alignment": clean(row.get("alignment")),
        "gender": clean(row.get("gender")),
        "type_race": clean(row.get("type_race")),
        "place_of_birth": clean(row.get("place_of_birth")),
        "first_appearance": clean(row.get("first_appearance")),
        "occupation": clean(row.get("occupation")),
        "teams": parse_list_literal(row.get("teams", "")),
        "eye_color": clean(row.get("eye_color")),
        "hair_color": clean(row.get("hair_color")),
        "height_cm": parse_height_cm(row.get("height", "")),
        "weight_kg": parse_weight_kg(row.get("weight", "")),
        "intelligence_score": parse_score(row.get("intelligence_score", "")),
        "strength_score": parse_score(row.get("strength_score", "")),
        "speed_score": parse_score(row.get("speed_score", "")),
        "durability_score": parse_score(row.get("durability_score", "")),
        "combat_score": parse_score(row.get("combat_score", "")),
        "overall_score": parse_score(row.get("overall_score", "")),
        "superpowers": abilities,
        "source": SOURCE_FIELD_VALUE,
        "license": SOURCE_LICENSE,
    }
    return Entity(raw_id=int(raw_id_text), fields=fields)


def load_entities() -> list[Entity]:
    entities: list[Entity] = []
    discarded = 0
    for filename in RAW_FILES:
        path = RAW_SOURCE_DIR / filename
        with path.open(encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                entity = transform_row(row)
                if entity is None:
                    discarded += 1
                else:
                    entities.append(entity)

    # Stable sort by the dataset's own numeric id (deterministic regardless of
    # file read order), then assign sequential corpus ids.
    entities.sort(key=lambda entity: entity.raw_id)
    for index, entity in enumerate(entities, start=1):
        entity.fields["id"] = f"comic-{index:04d}"
        entity.fields["domain"] = DOMAIN

    print(f"Loaded {len(entities)} entities, discarded {discarded} rows.", file=sys.stderr)
    return entities


def render_document(entity: Entity) -> bytes:
    frontmatter = render_frontmatter(entity.fields)
    prose = build_prose(entity.fields)
    content = f"{frontmatter}\n\n# {entity.fields['name']}\n\n{prose}\n"
    return content.encode("utf-8")


def write_corpus(entities: list[Entity]) -> list[Path]:
    if CORPUS_DIR.exists():
        for existing in CORPUS_DIR.glob("comic-*.md"):
            existing.unlink()
    CORPUS_DIR.mkdir(parents=True, exist_ok=True)

    written: list[Path] = []
    oversized: list[str] = []
    for entity in entities:
        content = render_document(entity)
        if len(content) > MAX_DOCUMENT_BYTES:
            oversized.append(entity.filename)
        path = CORPUS_DIR / entity.filename
        path.write_bytes(content)
        written.append(path)

    if oversized:
        raise SystemExit(
            f"{len(oversized)} document(s) exceed the {MAX_DOCUMENT_BYTES}-byte proxy ceiling "
            f"for the one-entity-one-chunk property: {oversized[:10]}. This byte ceiling is a "
            "conservative proxy for a token limit (see the comment on MAX_DOCUMENT_BYTES) — "
            "verify with the Java retrieval-harness integration test (#227) before assuming the "
            "invariant actually holds."
        )
    return written


def write_manifest(paths: list[Path]) -> None:
    manifest_path = CORPUS_DIR / "MANIFEST.sha256"
    lines = []
    for path in sorted(paths, key=lambda item: item.name):
        digest = sha256_of(path)
        lines.append(f"{digest} *{path.name}")
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def verify_manifest_completeness(paths: list[Path]) -> None:
    """`sha256sum -c MANIFEST.sha256` only checks that listed files match their
    hash; it does not notice *extra* .md files sitting in the directory
    without a corresponding manifest entry (e.g. added by hand later, outside
    a regeneration run). Guard against that divergence here, immediately
    after a fresh write where the directory listing and `paths` must agree by
    construction."""
    on_disk = {path.name for path in CORPUS_DIR.glob("comic-*.md")}
    written = {path.name for path in paths}
    if on_disk != written:
        raise SystemExit(
            "Corpus directory and written-file list diverge after generation: "
            f"only on disk: {sorted(on_disk - written)[:5]}, "
            f"only in manifest: {sorted(written - on_disk)[:5]}"
        )


def main() -> None:
    download_raw_files()
    verify_raw_files()
    entities = load_entities()
    written = write_corpus(entities)
    verify_manifest_completeness(written)
    write_manifest(written)
    total_bytes = sum(path.stat().st_size for path in written)
    print(
        f"Wrote {len(written)} documents ({total_bytes / 1024:.1f} KiB total) to {CORPUS_DIR}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
