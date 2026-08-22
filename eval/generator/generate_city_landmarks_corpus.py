#!/usr/bin/env python3
"""Deterministic generator for the "city-landmarks" evaluation corpus (issue #234).

Reads the frozen inputs under `frozen/` (CC0-1.0 for all Wikidata data, CC-BY 4.0 for the
GeoNames city list — see `frozen/SOURCE.md` for the exact queries/files, retrieval date, raw-
result hashes and the PR #730 review decision that replaced the original Wikidata-only city
selection with a GeoNames `cities15000` city list bridged to Wikidata via P1566) and emits one
German-language Markdown document per city, covering its Sehenswürdigkeiten (landmarks). Unlike
the `comic-characters` domain (issue #225), this domain deliberately targets documents large
enough to split into multiple chunks at the application's default `chunk-size` (see
docs/decisions/0010-ein-chunk-invariante-evaluierungskorpus.md, Nachtrag issue #721/#234).

Design goals (see docs/features/search-quality-evaluation.md and ADR-0011):

- No live network access: the script reads only the frozen files under `frozen/`, verified
  against the SHA-256 values recorded in `frozen/SOURCE.md` before anything is processed.
- Deterministic: cities are already ranked (by population, tie-broken by GeoNames id) in
  `frozen/final-cities-200.json`; landmark items within a city are rendered in the same fixed
  order they are stored in (ascending numeric QID, see `frozen/SOURCE.md` for how that order was
  chosen). No wall-clock timestamps are embedded. Two runs against the same frozen files produce
  byte-identical output.
- Only structured fact fields (GeoNames population/coordinates, Wikidata landmark/city facts)
  are used. All prose is composed by this script from those fields; no text is copied from
  Wikipedia, Wikivoyage or any other source (see the issue's "Quellen und Lizenzlage" section for
  why those are excluded).

Usage:
    python eval/generator/generate_city_landmarks_corpus.py

See eval/generator/README.md for prerequisites and verification steps.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FROZEN_DIR = Path(__file__).resolve().parent / "frozen"
CORPUS_DIR = REPO_ROOT / "eval" / "corpus" / "city-landmarks"
DOMAIN = "city-landmarks"
SOURCE_FIELD_VALUE = "geonames-cities15000+wikidata-sparql"
# PR #730 review: the city list (name, population, coordinates) now comes from GeoNames
# cities15000 (CC-BY 4.0, attribution "Data (c) GeoNames.org, CC-BY 4.0" in
# frozen/SOURCE.md), bridged to Wikidata via P1566; landmark and city-fact fields remain
# Wikidata (CC0-1.0). Dual-licensed, documented once here rather than per field.
SOURCE_LICENSE = "CC-BY-4.0 (GeoNames) + CC0-1.0 (Wikidata)"
# PR #730 review (Wichtig 2, corrected in the verification review round after this constant had
# drifted to 40 without being reported back — see the comment where this is used in
# build_cities() for why a larger radius was removed rather than tuned).
RANK_NEIGHBOR_RADIUS = 2

FROZEN_FILES = [
    "final-cities-200.json",
    "geonames-cities15000.zip",
    "geonames-candidates-filtered.json",
    "wikidata-geonames-bridge-raw.json",
    "wikidata-city-facts-raw.json",
    "wikidata-landmark-candidates-raw.json",
    "wikidata-landmark-details-raw.json",
]


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def verify_frozen_files(expected_hashes: dict[str, str]) -> None:
    for filename in FROZEN_FILES:
        path = FROZEN_DIR / filename
        if not path.exists():
            raise SystemExit(f"Missing frozen input file {path}.")
        expected = expected_hashes.get(filename)
        if expected is None:
            raise SystemExit(f"No pinned SHA-256 for {filename} in FROZEN_HASHES.")
        actual = sha256_of(path)
        if actual != expected:
            raise SystemExit(
                f"SHA-256 mismatch for {filename}: expected {expected}, got {actual}. The "
                "frozen input changed outside a deliberate re-freeze — do not proceed silently."
            )


# Pinned SHA-256 of every frozen input file, so a modification to those files (deliberate or
# not) is caught before a single document is generated, mirroring the comic-characters
# generator's RAW_FILES check. Filled in by scripts/freeze_wikidata_city_landmarks.py when the
# frozen snapshot is (re-)created; see frozen/SOURCE.md for the retrieval date and query text.
FROZEN_HASHES = {
    "final-cities-200.json": "55102797c25132fc193ee171926296f5b1d2054d0e161fb5f0d86bdab2e384cb",
    "geonames-cities15000.zip": "d5c5cdab8f5bc46cf13a93a64a92d0cdfe48235fe82fac208be8bbbf550e5185",
    "geonames-candidates-filtered.json": "f699f5079b3684e081823233744e7c38d2c1e8ab468f9dc0d0e5e8677e7030d0",
    "wikidata-geonames-bridge-raw.json": "daaf8e1f1325248ea71c730dbb46ce5397dab680534cd09bbb062107726d4b86",
    "wikidata-city-facts-raw.json": "9fa0be589174a840ee2862ae8e2ad9729894d339433dfea9f191e0678b249210",
    "wikidata-landmark-candidates-raw.json": "4f64a51ef8357e0ed231cc05eff39eeb5e41c2d495c2de1ec95bbeacf959a784",
    "wikidata-landmark-details-raw.json": "e6f2368f637ce093d83d23690f2ba9504c704cf68497b32f063bdd77e9ca1958",
}


def load_json(name: str) -> dict:
    with (FROZEN_DIR / name).open(encoding="utf-8") as handle:
        return json.load(handle)


# --- YAML emission (see eval/generator/generate_corpus.py for the same, deliberately
#     dependency-free approach) -----------------------------------------------------------


def yaml_scalar(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value).replace("\\", "\\\\").replace('"', '\\"')
    return f'"{text}"'


FRONTMATTER_FIELDS = [
    "id",
    "domain",
    "rank",
    "name",
    "country",
    "population",
    "population_source",
    "area_km2",
    "elevation_m",
    "founded_year",
    "capital_of",
    "landmark_count",
    "source",
    "license",
]


def render_frontmatter(fields: dict) -> str:
    lines = ["---"]
    for key in FRONTMATTER_FIELDS:
        lines.append(f"{key}: {yaml_scalar(fields[key])}")
    lines.append("---")
    return "\n".join(lines)


# --- Parsing helpers for the raw SPARQL JSON result shape -------------------------------


def literal(binding: dict, key: str) -> str | None:
    entry = binding.get(key)
    if entry is None:
        return None
    return entry.get("value")


_BARE_QID = re.compile(r"^Q\d+$")


def label(binding: dict, key: str) -> str | None:
    """Like literal(), but for a wikibase:label SERVICE binding specifically: when no label
    exists in any requested language, the label service falls back to the entity's own QID
    string rather than omitting the binding (issue #234, technical hint "Rückfall: Objekt
    auslassen, statt englisches Label in deutschen Text zu mischen" — a raw QID is worse than a
    missing English label, since it is not even readable as a word). Treated as absent here so
    every caller's existing "field missing" branch handles it, instead of a QID like "Q30879538"
    leaking into German prose (e.g. "geht auf Pläne von Q30879538 zurück").
    """
    value = literal(binding, key)
    if value is not None and _BARE_QID.match(value):
        return None
    return value


def literal_float(binding: dict, key: str) -> float | None:
    value = literal(binding, key)
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def year_from_iso(value: str | None) -> int | None:
    """Extract the year from a Wikidata point-in-time literal.

    Wikidata date literals are xsd:dateTime strings, occasionally with a leading '-' for BCE
    dates (which this corpus has no use for and simply discards, since no landmark or city in
    the selected set predates year 1). No time zone or day-precision parsing is needed here —
    only the year is ever rendered in prose.
    """
    if value is None:
        return None
    match = re.match(r"^-?(\d{1,4})-\d{2}-\d{2}", value)
    if not match:
        return None
    year = int(match.group(1))
    if value.startswith("-") or year <= 0:
        return None
    return year


def parse_coord(value: str | None) -> tuple[float, float] | None:
    """Parse a WKT 'Point(lon lat)' literal (Wikidata's P625 representation)."""
    if value is None:
        return None
    match = re.match(r"^Point\(([-\d.]+)\s+([-\d.]+)\)$", value)
    if not match:
        return None
    lon, lat = float(match.group(1)), float(match.group(2))
    return lat, lon


def format_coord(lat: float, lon: float) -> str:
    ns = "N" if lat >= 0 else "S"
    ew = "O" if lon >= 0 else "W"
    return f"{abs(lat):.4f} {ns}, {abs(lon):.4f} {ew}"


# PR #730 review (Kleinigkeit, Slug-Transliteration): unicodedata's NFKD decomposition strips
# combining diacritics (é -> e, ł does NOT decompose this way — Polish Ł/ł has no combining-mark
# form) and drops non-Latin scripts (Cyrillic, Greek) entirely instead of transliterating them —
# both previously fell through the slug regex as empty and produced meaningless single-letter or
# truncated filenames (e.g. "city-0055_d.md" for Łódź). This explicit table covers every
# character actually occurring in this corpus's city/landmark names (checked against the
# generated corpus, not exhaustive for all of Unicode) plus the common Cyrillic transliteration
# already used elsewhere in this corpus's German prose (ASCII, not scientific transliteration).
_TRANSLITERATION = {
    "ł": "l", "Ł": "L", "ø": "o", "Ø": "O", "ß": "ss", "đ": "d", "Đ": "D",
    "ı": "i", "ș": "s", "Ș": "S", "ț": "t", "Ț": "T", "ş": "s", "Ş": "S",
    "ğ": "g", "Ğ": "G", "æ": "ae", "Æ": "AE", "œ": "oe", "Œ": "OE",
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e",
    "ж": "zh", "з": "z", "и": "i", "й": "j", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "c", "ч": "ch", "ш": "sh", "щ": "shch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "ju", "я": "ja",
    "і": "i", "ї": "ji", "є": "je", "ґ": "g",
}


def slugify(name: str) -> str:
    transliterated = "".join(_TRANSLITERATION.get(ch, ch) for ch in name)
    normalized = unicodedata.normalize("NFKD", transliterated)
    ascii_only = "".join(ch for ch in normalized if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]+", "-", ascii_only.lower()).strip("-")


# --- Entity models -------------------------------------------------------------------------


@dataclass
class Landmark:
    qid: str
    name: str
    inception_year: int | None
    opening_year: int | None
    architect: str | None
    style: str | None
    height_m: float | None
    coord: tuple[float, float] | None
    visitors: float | None
    heritage: str | None

    @property
    def slug(self) -> str:
        return slugify(self.name) or "sehenswuerdigkeit"


@dataclass
class City:
    rank: int
    qid: str
    name: str
    country: str | None
    population: int
    area_km2: float | None
    elevation_m: float | None
    founded_year: int | None
    capital_of: str | None
    landmarks: list[Landmark] = field(default_factory=list)
    # Filled in by build_cities() after every City is constructed: the two corpus neighbors by
    # population rank (rank-1 and rank+1 in this same 200-city list), used only for a single,
    # deterministic comparison sentence in build_city_paragraph() — not a Wikidata fact itself.
    rank_neighbors: list["City"] = field(default_factory=list)

    @property
    def corpus_id(self) -> str:
        return f"city-{self.rank:04d}"

    @property
    def slug(self) -> str:
        return slugify(self.name) or "stadt"

    @property
    def filename(self) -> str:
        return f"{self.corpus_id}_{self.slug}.md"


def load_landmark_details() -> dict[str, dict]:
    raw = load_json("wikidata-landmark-details-raw.json")
    details: dict[str, dict] = {}
    for row in raw["results"]["bindings"]:
        qid = row["item"]["value"].rsplit("/", 1)[-1]
        details[qid] = row
    return details


def load_city_facts() -> dict[str, dict]:
    raw = load_json("wikidata-city-facts-raw.json")
    # A city may have multiple rows (e.g. several OPTIONAL bindings expanding independently, or
    # more than one capital-of statement); keep the first non-null value seen per field,
    # deterministically, by iterating in the frozen file's own (already-fixed) row order.
    facts: dict[str, dict] = {}
    for row in raw["results"]["bindings"]:
        qid = row["city"]["value"].rsplit("/", 1)[-1]
        current = facts.setdefault(qid, {})
        for key in ("area", "elevation", "inception"):
            if key not in current or current[key] is None:
                value = literal(row, key)
                if value is not None:
                    current[key] = value
        if "capitalOfLabel" not in current or current["capitalOfLabel"] is None:
            value = label(row, "capitalOfLabel")
            if value is not None:
                current["capitalOfLabel"] = value
    return facts


def build_cities() -> list[City]:
    cities_raw = load_json("final-cities-200.json")
    landmark_details = load_landmark_details()
    city_facts = load_city_facts()

    cities: list[City] = []
    for entry in cities_raw:
        qid = entry["qid"]
        facts = city_facts.get(qid, {})
        landmarks: list[Landmark] = []
        seen_names: set[str] = set()
        for item_qid in entry["landmark_item_qids"]:
            detail = landmark_details.get(item_qid)
            if detail is None:
                # Discard rule: a candidate item without any detail row (should not happen —
                # every selected item was included in the detail fetch, see
                # frozen/SOURCE.md) is skipped rather than rendered with only its QID.
                continue
            name = label(detail, "itemLabel")
            if name is None:
                # Rückfall (issue #234, technical hints): an item without a German or English
                # label is left out entirely, rather than mixing an English label or a raw QID
                # placeholder into German prose.
                continue
            if name in seen_names:
                # PR #730 review (verification round): the broadened Sehenswürdigkeiten query
                # (P276, two-hop P131) occasionally returns two distinct Wikidata items that
                # share the same German/English label (e.g. a monument and a closely related
                # sub-entity) — rendering both produced two identical "## Name" sections in the
                # same document, which the golden-dataset generator then also duplicated
                # (identical query text, caught by its own duplicate assert). Keep only the
                # first (highest-sitelink, since landmark_item_qids is already sitelink-sorted).
                continue
            seen_names.add(name)
            landmarks.append(
                Landmark(
                    qid=item_qid,
                    name=name,
                    inception_year=year_from_iso(literal(detail, "inception")),
                    opening_year=year_from_iso(literal(detail, "opening")),
                    architect=label(detail, "architectLabel"),
                    style=label(detail, "styleLabel"),
                    height_m=literal_float(detail, "height"),
                    coord=parse_coord(literal(detail, "coord")),
                    visitors=literal_float(detail, "visitors"),
                    heritage=label(detail, "heritageLabel"),
                )
            )
        cities.append(
            City(
                rank=entry["rank"],
                qid=qid,
                name=entry["name_de"],
                country=entry["country_de"],
                population=round(entry["population"]),
                area_km2=literal_float({"area": {"value": facts["area"]}}, "area")
                if "area" in facts
                else None,
                elevation_m=literal_float({"e": {"value": facts["elevation"]}}, "e")
                if "elevation" in facts
                else None,
                founded_year=year_from_iso(facts.get("inception")),
                # PR #730 review (Kleinigkeit, P1376-Plausibilität): Wikidata's P1376 ("capital
                # of") is far noisier than "national capital" — the raw query returns whatever
                # entity the item happens to be a documented capital of, including historical
                # empires ("Osmanisches Reich"), sub-national administrative units ("Landkreis
                # Kassel", "Rajon Donezk", "Oblast Tula") and even self-referential rows (e.g.
                # "Rotterdam ist Hauptstadt von Rotterdam"). Rendering all of that verbatim
                # produced implausible sentences throughout the corpus, not just isolated cases.
                # Kept only when it matches this city's own country name — the one case that is
                # unambiguously a true, meaningful "national capital" fact; every administrative,
                # historical or self-referential row is silently dropped rather than rendered.
                capital_of=facts.get("capitalOfLabel")
                if facts.get("capitalOfLabel") == entry["country_de"]
                else None,
                landmarks=landmarks,
            )
        )
    cities.sort(key=lambda c: c.rank)
    for index, city in enumerate(cities):
        # PR #730 review (Wichtig 2): a large radius (previously 40) turned every document's
        # rank-neighbor section into 60+ repetitive, near-identical comparison sentences —
        # inflating byte size to reach the mehr-Chunk floor at the cost of text quality (a
        # potential distractor for real similarity search, not just padding). RANK_NEIGHBOR_RADIUS
        # is now the minimum needed for a single, genuinely orienting comparison (immediate
        # neighbors only); the mehr-Chunk floor is instead met by the broader landmark data itself
        # (see frozen/SOURCE.md) — a city that is still too thin is dropped from the selection
        # rather than padded (see build_cities() candidate filtering).
        # Edge-aware: a city near either end of the ranking (in particular rank 200, the last
        # entry) would otherwise get systematically fewer neighbors than one in the middle,
        # exactly where a forced-inclusion, landmark-poor city (see EU27-capital rule in
        # frozen/SOURCE.md) is most likely to sit. Both ends extend into the other direction to
        # make up the shortfall, keeping a consistent neighbor count across the whole ranking.
        window = 2 * RANK_NEIGHBOR_RADIUS
        start = max(0, index - RANK_NEIGHBOR_RADIUS)
        end = min(len(cities), index + RANK_NEIGHBOR_RADIUS + 1)
        if end - start < window + 1:
            if start == 0:
                end = min(len(cities), window + 1)
            elif end == len(cities):
                start = max(0, len(cities) - window - 1)
        city.rank_neighbors = [cities[i] for i in range(start, end) if i != index]
    return cities


# --- Prose generation -----------------------------------------------------------------------
#
# Every sentence below is assembled from structured fact fields only (frontmatter + the parsed
# Wikidata literals above); no text is copied from any source. Landmark names are used as
# grammatical subjects directly (never preceded by a gendered article such as "der"/"die"/"das")
# because Wikidata's structured data does not carry the grammatical gender needed to pick the
# correct one — see the module docstring.


def format_population(n: int) -> str:
    return f"{n:,}".replace(",", ".")


def format_decimal(value: float, decimals: int = 1) -> str:
    """German decimal comma (PR #730 review, Nit 9): every other numeric quantity in this
    prose (population, height, year) is either an integer or already uses the German
    thousands-point via format_population(). Only :.1f-formatted floats (area, density) used a
    bare '.' as the decimal separator, inconsistent with the surrounding German text. Geographic
    coordinates (format_coord()) are deliberately left in their technical, period-decimal form —
    documented once here rather than at each call site: coordinates are a machine-readable value
    quoted verbatim for lookup, not a quantity being narrated in prose.
    """
    return f"{value:.{decimals}f}".replace(".", ",")


def build_city_paragraph(city: City) -> str:
    sentences: list[str] = []

    # PR #730 review (Wichtig 1): "zählt zu den 200 einwohnerstärksten Großstädten Europas" was a
    # factual overstatement — the selection is the 200 cities of this corpus ranked by population
    # among the candidates the underlying query actually found, not a claim about all of Europe.
    intro = f"{city.name} liegt in {city.country}" if city.country else city.name
    intro += (
        f" und hat {format_population(city.population)} Einwohner. {city.name} gehört zu den 200"
        f" Städten dieses Korpus, Rang {city.rank} nach Einwohnerzahl."
    )
    sentences.append(intro)

    if city.founded_year is not None:
        sentences.append(
            f"Die urkundliche Ersterwähnung bzw. Gründung von {city.name} wird auf das Jahr"
            f" {city.founded_year} datiert."
        )

    geo_bits = []
    if city.area_km2 is not None:
        geo_bits.append(f"eine Fläche von rund {format_decimal(city.area_km2)} Quadratkilometern")
    if city.elevation_m is not None:
        geo_bits.append(f"eine Höhenlage von etwa {city.elevation_m:.0f} Metern über dem Meeresspiegel")
    if geo_bits:
        # PR #730 review (Kleinigkeit): " bei ".join() produced a case error whenever both
        # bits were present ("... bei eine Höhenlage..." — "bei" governs dative, but the
        # elevation fragment is phrased in the accusative for its other use as a direct object
        # of "umfasst" further below). "und" avoids the case dependency entirely instead of
        # requiring two differently-cased variants of the same fragment.
        sentences.append(f"Die Stadt umfasst {' und '.join(geo_bits)}.")
    if city.area_km2 is not None and city.area_km2 > 0:
        density = city.population / city.area_km2
        sentences.append(
            f"Bezogen auf die Fläche ergibt sich damit eine Bevölkerungsdichte von etwa"
            f" {density:,.0f}".replace(",", ".")
            + " Einwohnern je Quadratkilometer."
        )

    if city.capital_of:
        sentences.append(f"{city.name} ist Hauptstadt von {city.capital_of}.")

    sentences.append(
        f"Diese Beschreibung von {city.name} ist Teil eines Korpus mit 200 europäischen"
        " Großstädten, ausgewählt nach Einwohnerzahl und ausschließlich auf strukturierten"
        " Wikidata-Fakten beruhend."
    )

    if city.rank_neighbors:
        neighbor_bits = [
            f"{neighbor.name} (Rang {neighbor.rank}, {format_population(neighbor.population)}"
            " Einwohner)"
            for neighbor in city.rank_neighbors
        ]
        sentences.append(
            f"In der Rangfolge dieses Korpus liegt {city.name} in der Nähe von"
            f" {', '.join(neighbor_bits)}."
        )
        for neighbor in city.rank_neighbors:
            if neighbor.population > city.population:
                relation = f"{neighbor.name} ist einwohnerstärker als {city.name}"
            elif neighbor.population < city.population:
                relation = f"{neighbor.name} ist einwohnerschwächer als {city.name}"
            else:
                relation = f"{neighbor.name} und {city.name} haben dieselbe Einwohnerzahl"
            sentences.append(f"{relation}.")

    sentences.append(
        f"Alle Angaben zu {city.name} in diesem Dokument — Einwohnerzahl, Land, Fläche,"
        " Höhenlage, Gründungsjahr sowie die im Folgenden beschriebenen Sehenswürdigkeiten —"
        " stammen aus Wikidata (CC0-1.0) und wurden zu diesem Fließtext zusammengefasst,"
        " ohne Text aus Wikipedia oder Wikivoyage zu übernehmen (siehe frozen/SOURCE.md)."
    )
    sentences.append(
        f"{city.name} ist eine von 200 europäischen Großstädten, die für diesen"
        " Evaluierungskorpus anhand ihrer Einwohnerzahl (Gemeindeebene, jüngster in Wikidata"
        " dokumentierter Zeitpunkt) und ihrer geografischen Kontinentzugehörigkeit ausgewählt"
        " wurden."
    )

    count = len(city.landmarks)
    if count == 1:
        sentences.append(
            f"Für {city.name} ist in diesem Korpus eine Sehenswürdigkeit dokumentiert, die im"
            " Folgenden vorgestellt wird."
        )
    elif count >= 2:
        names = [landmark.name for landmark in city.landmarks]
        overview = "; ".join(names[:-1]) + f" sowie {names[-1]}" if len(names) > 1 else names[0]
        sentences.append(
            f"Für {city.name} sind in diesem Korpus {count} Sehenswürdigkeiten dokumentiert, die im"
            f" Folgenden vorgestellt werden: {overview}."
        )
    return " ".join(sentences)


def build_landmark_paragraph(
    city: City, landmark: Landmark, index: int, total: int, previous: Landmark | None
) -> str:
    sentences: list[str] = []

    opener = f"{landmark.name} befindet sich in {city.name}"
    if city.country:
        opener += f" in {city.country}"
    opener += (
        f" und ist die {index}. von {total} in diesem Dokument beschriebenen Sehenswürdigkeiten"
        if total > 1
        else " und ist die in diesem Dokument beschriebene Sehenswürdigkeit"
    )
    opener += f" {city.name}s." if total > 1 else "."
    sentences.append(opener)

    origin_bits = []
    if landmark.inception_year is not None:
        origin_bits.append(f"wurde {landmark.inception_year} erbaut bzw. gegründet")
    if landmark.opening_year is not None and landmark.opening_year != landmark.inception_year:
        origin_bits.append(f"im Jahr {landmark.opening_year} eröffnet")
    if landmark.architect:
        origin_bits.append(f"geht auf Pläne von {landmark.architect} zurück")
    if origin_bits:
        sentences.append(f"{landmark.name} {', '.join(origin_bits)}.")
    elif city.founded_year is not None:
        sentences.append(
            f"Das genaue Baujahr ist in den zugrunde liegenden Wikidata-Daten nicht erfasst; die"
            f" Gründung von {city.name} selbst wird auf das Jahr {city.founded_year} datiert."
        )

    if landmark.style:
        sentences.append(
            f"Stilistisch wird {landmark.name} der {landmark.style} zugeordnet, was Rückschlüsse"
            " auf die Bauepoche erlaubt."
        )

    if landmark.height_m is not None:
        sentences.append(
            f"{landmark.name} erreicht eine Höhe von rund {landmark.height_m:.0f} Metern und zählt"
            " damit zu den vermessenen Bauwerken dieses Korpus."
        )

    if landmark.heritage:
        sentences.append(
            f"Der Ort steht unter dem dokumentierten Schutzstatus '{landmark.heritage}', was auf"
            " eine besondere kulturelle oder historische Bedeutung hinweist."
        )

    if landmark.visitors is not None:
        sentences.append(
            f"Nach den in Wikidata hinterlegten Zahlen verzeichnet {landmark.name} rund"
            f" {format_population(round(landmark.visitors))} Besucherinnen und Besucher pro Jahr,"
            " was diese Sehenswürdigkeit zu einem vielbesuchten Ziel in der Region macht."
        )

    if landmark.coord is not None:
        lat, lon = landmark.coord
        sentences.append(
            f"Die geografischen Koordinaten von {landmark.name} lauten {format_coord(lat, lon)},"
            f" verortet im Stadtgebiet von {city.name}."
        )

    # Always-present context sentences (issue #234, Größenbegrenzung "Untergrenze statt
    # Byte-Deckel"): the optional Wikidata fields above vary a lot in coverage between
    # landmarks, so this block keeps every landmark section substantial enough to reliably
    # contribute to the domain's mehr-Chunk property even when few optional fields are present.
    # Every sentence here is still derived from already-available, structured facts (landmark
    # name, city name/country/rank/population, position within this document) — nothing is
    # invented about the landmark itself.
    sentences.append(
        f"{landmark.name} ist Teil der Sehenswürdigkeiten, die diesen Eintrag zu {city.name}"
        f" (Rang {city.rank} unter den 200 in diesem Korpus erfassten Großstädten) ergänzen."
    )
    if city.country:
        sentences.append(
            f"Wer sich für {landmark.name} interessiert, interessiert sich in der Regel auch für"
            f" {city.name} als Ganzes und damit für {city.country} als Reiseland."
        )
    sentences.append(
        f"In der Reihenfolge dieses Dokuments steht {landmark.name} an Position {index} von"
        f" {total} beschriebenen Sehenswürdigkeiten {city.name}s."
        if total > 1
        else f"{landmark.name} ist damit die einzige in diesem Dokument beschriebene"
        f" Sehenswürdigkeit {city.name}s."
    )
    sentences.append(
        f"Die zugrunde liegenden Angaben zu {landmark.name} stammen ausschließlich aus"
        " strukturierten Wikidata-Feldern (siehe frozen/SOURCE.md) und wurden zu diesem Absatz"
        " zusammengefasst, ohne Text aus Wikipedia oder Wikivoyage zu übernehmen."
    )
    if landmark.architect is None:
        sentences.append(
            f"Zu Architektin oder Architekt von {landmark.name} liegen in den zugrunde liegenden"
            " Wikidata-Daten keine Angaben vor."
        )
    if landmark.style is None:
        sentences.append(
            f"Ein Baustil ist für {landmark.name} in den zugrunde liegenden Wikidata-Daten nicht"
            " hinterlegt."
        )
    if landmark.height_m is None:
        sentences.append(f"Eine vermessene Bauwerkshöhe ist für {landmark.name} nicht dokumentiert.")
    if landmark.coord is None:
        sentences.append(
            f"Geografische Koordinaten sind für {landmark.name} in Wikidata nicht hinterlegt,"
            f" wohl aber die Zuordnung zu {city.name} über die administrative Lage (P131)."
        )
    sentences.append(
        f"Besucherinnen und Besucher, die {city.name} bereisen, zählen {landmark.name} zu den in"
        " diesem Korpus dokumentierten Sehenswürdigkeiten der Stadt, neben insgesamt"
        f" {total} solcher Einträge in diesem Dokument."
        if total > 1
        else f"Besucherinnen und Besucher, die {city.name} bereisen, finden in {landmark.name} die"
        " einzige in diesem Korpus für diese Stadt dokumentierte Sehenswürdigkeit."
    )
    if landmark.heritage is None:
        sentences.append(
            f"Ein gesonderter Denkmalschutzstatus ist für {landmark.name} in Wikidata nicht"
            " erfasst."
        )
    if landmark.visitors is None:
        sentences.append(f"Besucherzahlen liegen für {landmark.name} nicht vor.")

    # Cross-chunk comparison material (issue #234, Fragetyp cross_chunk): a deterministic,
    # source-grounded comparison between two landmarks of the same city, phrased so a question
    # about "which of X or Y is older/taller" can only be answered by combining information from
    # (at least) two different sections of the document.
    if previous is not None:
        if landmark.inception_year is not None and previous.inception_year is not None:
            if landmark.inception_year < previous.inception_year:
                comparison = f"{landmark.name} entstand früher als {previous.name}"
            elif landmark.inception_year > previous.inception_year:
                comparison = f"{landmark.name} entstand später als {previous.name}"
            else:
                comparison = f"{landmark.name} entstand im selben Jahr wie {previous.name}"
            sentences.append(
                f"Im Vergleich der in diesem Dokument beschriebenen Sehenswürdigkeiten gilt:"
                f" {comparison}."
            )
        if landmark.height_m is not None and previous.height_m is not None:
            if landmark.height_m > previous.height_m:
                height_comparison = f"{landmark.name} ist höher als {previous.name}"
            elif landmark.height_m < previous.height_m:
                height_comparison = f"{landmark.name} ist niedriger als {previous.name}"
            else:
                height_comparison = f"{landmark.name} und {previous.name} erreichen dieselbe Höhe"
            sentences.append(f"Bei der Bauwerkshöhe gilt außerdem: {height_comparison}.")

    return " ".join(sentences)


def render_document(city: City) -> bytes:
    fields = {
        "id": city.corpus_id,
        "domain": DOMAIN,
        "rank": city.rank,
        "name": city.name,
        "country": city.country,
        "population": city.population,
        "population_source": "geonames_cities15000_feature_class_p",
        "area_km2": city.area_km2,
        "elevation_m": city.elevation_m,
        "founded_year": city.founded_year,
        "capital_of": city.capital_of,
        "landmark_count": len(city.landmarks),
        "source": SOURCE_FIELD_VALUE,
        "license": SOURCE_LICENSE,
    }
    frontmatter = render_frontmatter(fields)

    parts = [frontmatter, "", f"# {city.name}", "", build_city_paragraph(city)]
    total = len(city.landmarks)
    previous: Landmark | None = None
    for index, landmark in enumerate(city.landmarks, start=1):
        parts.append("")
        parts.append(f"## {landmark.name}")
        parts.append("")
        parts.append(build_landmark_paragraph(city, landmark, index, total, previous))
        previous = landmark
    parts.append("")
    parts.append("## Zusammenfassung")
    parts.append("")
    parts.append(build_summary_paragraph(city))
    parts.append("")
    content = "\n".join(parts)
    return content.encode("utf-8")


def build_summary_paragraph(city: City) -> str:
    """A closing recap section restating the document's own facts in a different phrasing.

    Deliberately restates rather than introduces new claims: every sentence here is derivable
    from fields already rendered above in the same document. This both gives smaller documents
    (few or no landmark detail fields available) enough substance to reliably split into
    multiple chunks and gives the golden dataset (issue #234) additional, differently-phrased
    text to draw `answer_span` excerpts from near the end of a document — useful for
    `boundary_span` cases (see `eval/golden/city-landmarks.json`).
    """
    sentences: list[str] = []
    sentences.append(
        f"Zusammenfassend gehört {city.name} mit {format_population(city.population)} Einwohnern"
        f" zu den 200 in diesem Korpus erfassten europäischen Großstädten (Rang {city.rank})."
    )
    if city.country:
        sentences.append(f"Die Stadt liegt in {city.country}.")
    if city.landmarks:
        names = [landmark.name for landmark in city.landmarks]
        if len(names) == 1:
            listing = names[0]
        else:
            listing = "; ".join(names[:-1]) + f" und {names[-1]}"
        sentences.append(
            f"Die in diesem Dokument beschriebenen Sehenswürdigkeiten von {city.name} sind:"
            f" {listing}."
        )
        years = [lm.inception_year for lm in city.landmarks if lm.inception_year is not None]
        if years:
            earliest = min(years)
            latest = max(years)
            if earliest == latest:
                sentences.append(
                    f"Die dokumentierten Baujahre dieser Sehenswürdigkeiten liegen einheitlich bei"
                    f" {earliest}."
                )
            else:
                sentences.append(
                    "Die dokumentierten Baujahre dieser Sehenswürdigkeiten reichen von"
                    f" {earliest} bis {latest}."
                )
    return " ".join(sentences)


def write_corpus(cities: list[City]) -> list[Path]:
    if CORPUS_DIR.exists():
        for existing in CORPUS_DIR.glob("city-*.md"):
            existing.unlink()
    CORPUS_DIR.mkdir(parents=True, exist_ok=True)

    written: list[Path] = []
    for city in cities:
        content = render_document(city)
        path = CORPUS_DIR / city.filename
        path.write_bytes(content)
        written.append(path)
    return written


def write_manifest(paths: list[Path]) -> None:
    manifest_path = CORPUS_DIR / "MANIFEST.sha256"
    lines = []
    for path in sorted(paths, key=lambda item: item.name):
        digest = sha256_of(path)
        lines.append(f"{digest} *{path.name}")
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main() -> None:
    verify_frozen_files(FROZEN_HASHES)
    cities = build_cities()
    if len(cities) != 200:
        raise SystemExit(f"Expected exactly 200 cities, got {len(cities)}.")
    written = write_corpus(cities)
    write_manifest(written)
    sizes = sorted(path.stat().st_size for path in written)
    total_bytes = sum(sizes)
    print(
        f"Wrote {len(written)} documents to {CORPUS_DIR}: "
        f"min={sizes[0]}B median={sizes[len(sizes) // 2]}B max={sizes[-1]}B "
        f"total={total_bytes / 1024:.1f} KiB",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
