#!/usr/bin/env python3
"""Deterministic generator for the "comic-characters" golden dataset.

Derives golden retrieval queries from the YAML frontmatter of
`eval/corpus/comic-characters/*.md` (see #225 / `generate_corpus.py`). The
frontmatter is the ground truth: every query's `expected_documents` is
computed by filtering the parsed frontmatter fields, never guessed by an LLM
and never hand-typed against a document the author merely skimmed.

Design goals (docs/features/search-quality-evaluation.md, "Golden Dataset";
ADR-0011; issue #226 and its two review comments; issue #274, the follow-up
review that corrected and hardened several of the below):

- Deterministic: candidates are built by iterating entities and fixed
  parameter lists in a stable order (corpus id order, then a fixed field/
  threshold order) — no `random`, no wall-clock, no LLM. Two runs against the
  same corpus produce byte-identical output.
- Sentinel values (a placeholder the source dataset uses for "no real value
  here" that still parses as something numeric-shaped) are excluded from the
  base population *of the specific field they sentinel*, before threshold or
  window selection — never as a post-hoc filter on an already-fixed result
  set. See the comment block above `OVERALL_SCORE_BELOW_THRESHOLDS` for the
  full rule and this domain's sentinel table (`overall_score: null` and
  `overall_score: "∞"`), and issue #226's second review comment for why the
  `null` exclusion additionally, deliberately crosses into the five
  attribute-score fields (a distinct, cross-field rule, not the same thing).
- `first_appearance` and `occupation` are excluded from `attribute_lookup`/
  `entity_description` once they exceed a length threshold, per issue #226's
  first review comment: a number of rows carry text bled over from another
  source column (e.g. `comic-0226_brainiac-5.md`'s `first_appearance` is a
  force-field description; `comic-0498_gambit.md`'s `occupation` is an
  address list).
- Filter/range categories (`multi_attribute_filter`, `numeric_range`) keep
  only candidates whose result set has between 2 and 15 documents
  (docs/features/search-quality-evaluation.md, "Ableitung aus dem
  Frontmatter"; issue acceptance criteria).
- All free-text comparisons (`entity_description`'s uniqueness check,
  `multi_attribute_filter`'s alignment/creator/ability match) are
  case-insensitive (`_ci_eq`/`_ci_in`), because the corpus carries some
  values in two casings for the same fact (issue #274: `eye_color` has both
  `"Brown"` and `"brown"`) and no embedding distinguishes the two.

Output: this script writes the candidate pool and the curated selection —
not a third "discarded" file (issue #274: an earlier version wrote one, but
every entry carried the same constant, uninformative reason string, and the
file was fully reconstructible as `candidates − curated` anyway; see
eval/golden/README.md for how to reconstruct it on demand instead of paying
its ~370 KB in every clone).

- `eval/golden/comic-characters.candidates.json` — every candidate this
  script's rules produced, before manual curation.
- `eval/golden/comic-characters.json` — the manually curated selection (see
  `CURATED_CASES` near the bottom of this file), by (`natural_key`, `query`)
  pair rather than by sequential `id` — see the comment on `CURATED_CASES`
  for why `id`-based selection (the original, issue #226 version of this
  list) was unsafe.

Usage:
    python eval/generator/generate_golden_dataset.py

See eval/golden/README.md for the curation log and rationale.
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = REPO_ROOT / "eval" / "corpus" / "comic-characters"
GOLDEN_DIR = REPO_ROOT / "eval" / "golden"
DOMAIN = "comic-characters"

# --- Review-mandated thresholds (see module docstring) ----------------------

# issue #226, first review comment: fields observed contaminated with
# free text from another source column above these lengths (15 documents
# for first_appearance, 27 for occupation in the current corpus).
FIRST_APPEARANCE_MAX_LEN = 100
OCCUPATION_MAX_LEN = 120

# issue #226, second review comment: the reliable "unrated character" test
# is `overall_score is not null`, not a test on the five attribute scores
# themselves (see the comment on `parse_score()` in generate_corpus.py and
# `Entity.is_scored` / `ATTRIBUTE_LABELS` below, which enumerate the five
# fields this applies to).

FILTER_RESULT_MIN = 2
FILTER_RESULT_MAX = 15


# --- Frontmatter parsing -----------------------------------------------------
#
# Mirrors the emitter in generate_corpus.py: every string scalar is
# double-quoted, numbers/`null` are bare, `teams` is a real YAML flow
# sequence of quoted strings, everything else is either `null` or a bare
# scalar. This is a matching minimal parser, not a general YAML parser.

_QUOTED_ITEM = re.compile(r'"((?:[^"\\]|\\.)*)"')


def _unescape(text: str) -> str:
    return text.replace('\\"', '"').replace("\\\\", "\\")


def _parse_scalar(raw: str) -> object:
    raw = raw.strip()
    if raw == "null":
        return None
    if raw.startswith("["):
        return [_unescape(match) for match in _QUOTED_ITEM.findall(raw)]
    if raw.startswith('"'):
        inner = raw[1:-1]
        return _unescape(inner)
    try:
        return int(raw)
    except ValueError:
        return raw  # unquoted, non-numeric: not expected, kept verbatim


def parse_frontmatter(text: str) -> dict:
    body = text.split("---\n", 2)[1]
    fields: dict[str, object] = {}
    for line in body.splitlines():
        if not line.strip():
            continue
        key, _, raw_value = line.partition(":")
        fields[key.strip()] = _parse_scalar(raw_value)
    return fields


@dataclass
class Entity:
    filename: str
    fields: dict

    def __getitem__(self, key: str):
        return self.fields.get(key)

    @property
    def is_scored(self) -> bool:
        """`overall_score` is a real number for this entity — `False` for
        both the 105 "unrated" characters (`overall_score: null`) and the 18
        omnipotent characters (`overall_score: "∞"`, the only non-int string
        value this field takes). `isinstance(..., int)` catches both in one
        check without needing a separate `!= "∞"` test.

        This same property backs two different rules with two different
        scopes — see the sentinel-rule comment block above
        `OVERALL_SCORE_BELOW_THRESHOLDS` in this module for the distinction:
        used directly for `overall_score`'s own sentinel rule, and reused
        (deliberately, per issue #226) for a *cross-field* exclusion when
        building numeric_range candidates on the five attribute scores.
        """
        return isinstance(self.fields.get("overall_score"), int)

    @property
    def occupation_is_plausible(self) -> bool:
        value = self.fields.get("occupation")
        return value is not None and len(value) <= OCCUPATION_MAX_LEN

    @property
    def first_appearance_is_plausible(self) -> bool:
        value = self.fields.get("first_appearance")
        return value is not None and len(value) <= FIRST_APPEARANCE_MAX_LEN


def load_corpus() -> list[Entity]:
    entities = []
    for path in sorted(CORPUS_DIR.glob("comic-*.md")):
        fields = parse_frontmatter(path.read_text(encoding="utf-8"))
        entities.append(Entity(filename=path.name, fields=fields))
    entities.sort(key=lambda entity: entity["id"])
    return entities


# --- Candidate model ----------------------------------------------------------


@dataclass
class Candidate:
    id: str
    query: str
    expected_documents: list[str]
    category: str
    difficulty: str
    language: str
    type: str
    note: str  # human-readable audit trail; not part of the published schema
    # A key derived purely from the *generating parameters* (field name,
    # entity filename, threshold, ...), never from this candidate's position
    # in any list. Two runs of this script always assign the same
    # `natural_key` to "the same" candidate even if an unrelated change
    # upstream (corpus edit, a stricter filter) makes some *other* candidate
    # newly appear or disappear and every candidate after it in `id`-space
    # renumbers. `CURATED_CASES` below selects by `natural_key`, not by `id`,
    # for exactly that reason (see issue #274, finding 3).
    natural_key: str
    # Structured version of the same audit information, used by
    # `generate_crosslingual()` to build a German candidate from an already-
    # validated English one. Deliberately *not* re-parsed out of `note`
    # (a free-text string with values that can themselves contain spaces,
    # e.g. "Dark Horse Comics" or "Mind Control Resistance") — string-parsing
    # `note` previously truncated such values at the first space.
    meta: dict = field(default_factory=dict)


CASE_COUNTER: dict[str, int] = {}


def next_case_id(category: str) -> str:
    short = {
        "attribute_lookup": "attr",
        "entity_description": "desc",
        "multi_attribute_filter": "filter",
        "numeric_range": "range",
        "crosslingual": "de",
    }[category]
    CASE_COUNTER[short] = CASE_COUNTER.get(short, 0) + 1
    return f"comic-{short}-{CASE_COUNTER[short]:03d}"


# --- Category 1: attribute_lookup --------------------------------------------
#
# "Welche {field} hat {name}?" -> exactly one document. Field order is fixed
# so the run is deterministic; occupation/first_appearance apply the
# contamination guard from the module docstring.

ATTRIBUTE_LOOKUP_FIELDS = [
    ("eye_color", "What eye color does {name} have?"),
    ("hair_color", "What hair color does {name} have?"),
    ("creator", "Which company or creator created {name}?"),
    ("real_name", "What is {name}'s real name?"),
    ("place_of_birth", "Where was {name} born?"),
    ("occupation", "What is {name}'s occupation?"),
    # Neutral on purpose (issue #274): `first_appearance` is not always a
    # comic issue — e.g. comic-0099_atom-cw.md's is "Arrow Season 3: Episode
    # 1", a TV episode. The ground truth (that one document) stays correct
    # either way, but "In which comic did ... first appear?" is a factually
    # loaded question for such entities. "Where did ... first appear?" holds
    # for both media types.
    ("first_appearance", "Where did {name} first appear?"),
    ("alignment", "Is {name} good, bad, or neutral?"),
    ("type_race", "What species or race is {name}?"),
    ("height_cm", "How tall is {name}, in centimeters?"),
]


# Cap on raw candidates per field: the corpus has 1,448 entities and most
# fields are populated on the large majority of them, so an uncapped scan
# would emit thousands of near-identical candidates ("Welche Augenfarbe hat
# X?" for every X with a known eye color) that no curation round could
# meaningfully review. Taking the first N valid entities keeps the
# raw-candidate file reviewable while remaining fully deterministic.
MAX_CANDIDATES_PER_FIELD = 20

# The corpus is sorted alphabetically by name (see generate_corpus.py's
# id assignment), so scanning entities in plain corpus-id order would draw
# every field's candidates from the same narrow "A..." slice at the front
# of the file list. A fixed stride spreads candidates across the whole
# corpus instead of that narrow slice — but a *plain* stride starting at
# index 0 for every field still lands on the same handful of entities for
# most fields, because the first few entities at stride-distance apart tend
# to have most fields populated (issue #274: 60 single-document cases
# concentrated on only 29 entities, 43% of them on just 3 characters).
# `_spread()` therefore also rotates its starting point per caller — see
# `offset` below — so different fields/templates sample different regions
# of the corpus. Still fully deterministic: a fixed rotation, not a random
# sample.
SPREAD_STRIDE = 7


def _spread(entities: list[Entity], offset: int = 0) -> list[Entity]:
    offset %= len(entities)
    rotated = entities[offset:] + entities[:offset]
    return rotated[::SPREAD_STRIDE]


def generate_attribute_lookup(entities: list[Entity]) -> list[Candidate]:
    candidates: list[Candidate] = []
    # Evenly spaced starting points around the whole corpus, one per field,
    # so that e.g. the eye_color and creator candidates are drawn from
    # different neighborhoods of the corpus instead of both starting at
    # entity 0.
    field_offset_step = len(entities) // len(ATTRIBUTE_LOOKUP_FIELDS)
    for field_index, (field_name, template) in enumerate(ATTRIBUTE_LOOKUP_FIELDS):
        emitted = 0
        for entity in _spread(entities, offset=field_index * field_offset_step):
            if emitted >= MAX_CANDIDATES_PER_FIELD:
                break
            value = entity[field_name]
            if value is None or value == "":
                continue
            if field_name == "occupation" and not entity.occupation_is_plausible:
                continue
            if field_name == "first_appearance" and not entity.first_appearance_is_plausible:
                continue
            query = template.format(name=entity["name"])
            candidates.append(
                Candidate(
                    id=next_case_id("attribute_lookup"),
                    query=query,
                    expected_documents=[entity.filename],
                    category="attribute_lookup",
                    difficulty="easy",
                    language="en",
                    type="factual",
                    note=f"field={field_name} value={value!r}",
                    natural_key=f"attr::{field_name}::{entity.filename}",
                    meta={"field": field_name, "entity_name": entity["name"]},
                )
            )
            emitted += 1
    return candidates


# --- Category 2: entity_description ------------------------------------------
#
# A paraphrase of the generated prose that never uses the entity's name: a
# combination of 3-4 distinguishing attributes, phrased as a description.
# Ground truth is computed, not assumed: a candidate is only kept if the
# exact same attribute combination does not also match a second entity.

DESCRIPTION_TEMPLATES = [
    (
        "Which character created by {creator} is {alignment_lc}-aligned, has {eye_color} eyes "
        "and can use {ability}?",
        ["creator", "alignment", "eye_color", "superpower"],
    ),
    (
        "Which {alignment_lc} {type_race} character is affiliated with {team} and has {hair_color} "
        "hair?",
        ["alignment", "type_race", "team", "hair_color"],
    ),
    (
        "Which character born in {place_of_birth} works as {occupation_lc} and has {eye_color} "
        "eyes?",
        ["place_of_birth", "occupation", "eye_color"],
    ),
]


# Same sentinel set as generate_corpus.py's BALD_HAIR_VALUES.
_BALD_HAIR_VALUES = {"no hair", "none", "bald"}


def _lowercase_first_word(text: str) -> str:
    """Lowercase the leading word of `text` for mid-sentence embedding,
    unless that word is itself an acronym (all-uppercase, e.g. "CEO") —
    naive `text[0].lower() + text[1:]` turns "CEO" into "cEO"."""
    first_word = text.split(" ", 1)[0]
    if first_word.isupper() and len(first_word) > 1:
        return text
    return text[0].lower() + text[1:] if text else text


def _ci_eq(a: object, b: object) -> bool:
    """Case- and whitespace-insensitive equality for the free-text source
    columns. Issue #274: the corpus carries some columns (observed:
    `eye_color`) in two casings for the same value — e.g. `eye_color` is
    `"Brown"` 271 times and `"brown"` 3 times. No embedding distinguishes
    "brown" from "Brown", so a case-sensitive uniqueness/filter check
    silently misses fachlich identical matches. Non-string values (there are
    none among the fields this is used for today, but the entity model in
    general holds ints too) fall back to plain equality."""
    if isinstance(a, str) and isinstance(b, str):
        return a.strip().casefold() == b.strip().casefold()
    return a == b


def _ci_in(item: str, items: list[str]) -> bool:
    return any(_ci_eq(item, other) for other in items)


def _matches_description(entity: Entity, constraints: dict) -> bool:
    for key, expected in constraints.items():
        if key == "team":
            teams = entity["teams"] or []
            if not _ci_in(expected, teams):
                return False
        elif key == "superpower":
            powers = (entity["superpowers"] or "").split(", ")
            if not _ci_in(expected, powers):
                return False
        else:
            if not _ci_eq(entity[key], expected):
                return False
    return True


MAX_CANDIDATES_PER_DESCRIPTION_TEMPLATE = 20


def generate_entity_description(entities: list[Entity]) -> list[Candidate]:
    candidates: list[Candidate] = []
    # Same rotation reasoning as generate_attribute_lookup() (issue #274):
    # three templates all starting their spread at index 0 concentrated
    # heavily on the same few entities.
    template_offset_step = len(entities) // len(DESCRIPTION_TEMPLATES)
    for template_index, (template, fields_used) in enumerate(DESCRIPTION_TEMPLATES):
        emitted = 0
        for entity in _spread(entities, offset=template_index * template_offset_step):
            if emitted >= MAX_CANDIDATES_PER_DESCRIPTION_TEMPLATE:
                break
            constraints: dict = {}
            format_args: dict = {}
            ok = True
            for f in fields_used:
                if f == "superpower":
                    powers = (entity["superpowers"] or "").split(", ") if entity["superpowers"] else []
                    if not powers or powers == [""]:
                        ok = False
                        break
                    ability = powers[0]
                    constraints["superpower"] = ability
                    format_args["ability"] = ability
                elif f == "team":
                    teams = entity["teams"] or []
                    if not teams:
                        ok = False
                        break
                    constraints["team"] = teams[0]
                    format_args["team"] = teams[0]
                elif f == "occupation":
                    value = entity["occupation"]
                    if value is None or not entity.occupation_is_plausible:
                        ok = False
                        break
                    constraints["occupation"] = value
                    format_args["occupation_lc"] = _lowercase_first_word(value)
                elif f == "hair_color":
                    value = entity["hair_color"]
                    # "No Hair"/"None"/"Bald" is a sentinel, not a color (see
                    # generate_corpus.py's BALD_HAIR_VALUES) — using it here
                    # would produce the nonsensical "has No Hair hair", so
                    # this template simply skips characters without an
                    # actual reported hair color rather than special-casing
                    # a "is bald" clause for a single, non-prose query field.
                    if value is None or value.strip().lower() in _BALD_HAIR_VALUES:
                        ok = False
                        break
                    constraints[f] = value
                    format_args[f] = value
                else:
                    value = entity[f]
                    if value is None:
                        ok = False
                        break
                    constraints[f] = value
                    format_args[f] = value
            if not ok:
                continue
            if entity["alignment"] is not None:
                format_args["alignment_lc"] = entity["alignment"].lower()
            matches = [e.filename for e in entities if _matches_description(e, constraints)]
            if len(matches) != 1:
                continue
            query = template.format(**format_args)
            candidates.append(
                Candidate(
                    id=next_case_id("entity_description"),
                    query=query,
                    expected_documents=[entity.filename],
                    category="entity_description",
                    difficulty="medium",
                    language="en",
                    type="factual",
                    note=f"constraints={constraints}",
                    natural_key=f"desc::{template_index}::{entity.filename}",
                )
            )
            emitted += 1
    return candidates


# --- Category 3: multi_attribute_filter --------------------------------------
#
# "Which {alignment} characters created by {creator} can {superpower}?" — all
# matching documents are the ground truth. Kept only if the result window is
# [2, 15] documents (docs/features/search-quality-evaluation.md).

ALIGNMENTS = ["Good", "Bad", "Neutral"]
TOP_CREATORS = ["Marvel Comics", "DC Comics", "Shueisha", "Dark Horse Comics", "Lego"]
ABILITIES_SAMPLE = [
    "Reality Warping",
    "Matter Manipulation",
    "Mind Control Resistance",
    "Energy Constructs",
    "Dimensional Travel",
    "Mind Control",
    "Telepathy Resistance",
    "Size Changing",
    "Illusions",
    "Electrokinesis",
    "Energy Beams",
    "Fire Control",
    "Telekinesis",
    "Magic",
    "Element Control",
    "Shapeshifting",
    "Self-Sustenance",
    "Force Fields",
    "Teleportation",
    "Energy Absorption",
    "Immortality",
    "Regeneration",
    "Cold Resistance",
    "Heat Resistance",
    "Super Speed",
    "Flight",
]


def generate_multi_attribute_filter(entities: list[Entity]) -> list[Candidate]:
    candidates: list[Candidate] = []
    for alignment in ALIGNMENTS:
        for creator in TOP_CREATORS:
            for ability in ABILITIES_SAMPLE:
                matches = [
                    e.filename
                    for e in entities
                    if _ci_eq(e["alignment"], alignment)
                    and _ci_eq(e["creator"], creator)
                    and _ci_in(ability, (e["superpowers"] or "").split(", "))
                ]
                if not (FILTER_RESULT_MIN <= len(matches) <= FILTER_RESULT_MAX):
                    continue
                query = (
                    f"Which {alignment.lower()}-aligned characters created by {creator} "
                    f"have the ability {ability}?"
                )
                candidates.append(
                    Candidate(
                        id=next_case_id("multi_attribute_filter"),
                        query=query,
                        expected_documents=matches,
                        category="multi_attribute_filter",
                        difficulty="hard",
                        language="en",
                        type="filter",
                        note=f"alignment={alignment} creator={creator} ability={ability} n={len(matches)}",
                        natural_key=f"filter::{alignment}::{creator}::{ability}",
                        meta={"alignment": alignment, "creator": creator, "ability": ability},
                    )
                )
    return candidates


# --- Category 4: numeric_range ------------------------------------------------
#
# "Which characters have an {attribute} score {operator} {threshold}?" — all
# matching documents (excluding `overall_score: null` entities, per the
# issue's second review comment) are the ground truth, kept only if the
# result window is [2, 15].

ATTRIBUTE_LABELS = {
    "intelligence_score": "intelligence",
    "strength_score": "strength",
    "speed_score": "speed",
    "durability_score": "durability",
    "combat_score": "combat",
}

# Thresholds below were chosen by scanning every integer threshold 0-100
# (0-240 for `overall_score`) against the current corpus and keeping only
# the ones whose result window falls in [2, 15]. The five 0-100 attribute
# scores are heavily plateaued near their maximum (hundreds of characters
# tie at round numbers like 90/95/100), so no ">" threshold on any of them
# ever produces a small enough window — only "<" thresholds do. Reproduce
# with: for each field, `[t for t in range(100) if 2 <= count(field < t) <= 15]`.
# `overall_score` sits on an independent, much wider 1-237 scale (see
# generate_corpus.py's `parse_score()`), so both directions work there.
BELOW_THRESHOLDS_BY_ATTRIBUTE = {
    "intelligence_score": [35, 40, 45, 50],
    "strength_score": [5],
    "speed_score": [10],
    "durability_score": [5, 10],
    "combat_score": [10, 15],
}

# --- The general sentinel rule (issue #274) ---------------------------------
#
# Every field used in a numeric_range candidate can carry a *sentinel* value
# that is not a real measurement — a placeholder the source dataset uses for
# "no value here", which happens to parse as something numeric-shaped (or,
# for `overall_score`, as the literal string "∞"). Two properties this rule
# always has, independent of the domain (this generalizes beyond
# comic-characters — docs/features/search-quality-evaluation.md tracks a
# sentinel table per domain and per numerically-used field, "keine" included
# as an explicit, checked answer, not an implicit default):
#
# 1. **Field-scoped, not document-scoped.** A sentinel on field X excludes an
#    entity from numeric_range candidates *about field X* — not from every
#    other candidate about that same entity. Excluding a whole *document*
#    once *any* of its numeric fields hits a sentinel would needlessly shrink
#    corpus coverage (in this domain: 123 of ~1,450 entities carry some
#    sentinel — 105 for `overall_score: null` plus 18 for `"∞"` — over a
#    quarter of the domain if that scope were document-wide).
# 2. **Applied to the candidate *base population*, before threshold or
#    window selection** — never as a filter on an already-fixed result set.
#    Otherwise a query built for a 16-document window could silently drift
#    to a 3-document one once sentinels are subtracted afterwards, which
#    would no longer be the query that was actually validated.
#
# Sentinel table for `comic-characters` (six numerically-used fields):
#
# | Field                  | Sentinel(s)   | Scope                                          |
# |-------------------------|--------------|-------------------------------------------------|
# | `intelligence_score`    | keine        | s. `overall_score`-Zeile für die Cross-Field-Regel unten |
# | `strength_score`        | keine        | s. u. |
# | `speed_score`            | keine        | s. u. |
# | `durability_score`       | keine        | s. u. |
# | `combat_score`           | keine        | s. u. |
# | `overall_score`          | `null`, `"∞"` | numeric_range auf `overall_score` selbst: beide vor Fenster-/Schwellenwertbestimmung ausgeschlossen (`Entity.is_scored` schließt `null` UND `"∞"` aus — beide sind kein `int`) |
#
# The five attribute scores have *no sentinel of their own* — but they are
# still gated by `Entity.is_scored` here (`scored_entities` below), which is
# a **separate, additional, cross-field rule**, not this sentinel rule: per
# issue #226's second review comment, `overall_score: null` correlates with
# contaminated prose on the *five attribute fields* ("scores 0 for
# intelligence, 0 for strength, ..." — the literal sentence embedded in the
# corpus for exactly the 105 unrated entities). That contamination has
# nothing to do with the attribute fields having their own sentinel; it is
# about what the corpus generator wrote into the prose for the entities that
# already have the `overall_score` sentinel. `overall_score: "∞"` carries no
# such cross-field contamination — an omnipotent character's five attribute
# scores are ordinary, trustworthy numbers — so `"∞"` entities are correctly
# *not* excluded from the five-attribute queries below.
#
# Known, out-of-scope residual (documented, not fixed here — belongs to the
# corpus generator, #225): excluding these entities from the *ground truth*
# does not remove the contaminated sentences from the corpus text itself.
# "scores 0 for intelligence, ..." and "his overall score is ∞" remain in
# the vector space and can legitimately be found by an embedding search —
# see eval/golden/README.md for the note aimed at #227's evaluators.
OVERALL_SCORE_BELOW_THRESHOLDS = [2, 3]
OVERALL_SCORE_ABOVE_THRESHOLDS = [120, 150, 180, 210]


def _indefinite_article(word: str) -> str:
    return "an" if word[:1].lower() in "aeiou" else "a"


def generate_numeric_range(entities: list[Entity]) -> list[Candidate]:
    candidates: list[Candidate] = []
    # Cross-field rule (see the comment block above `OVERALL_SCORE_BELOW_...`
    # for why this is scoped to the five attribute fields specifically, and
    # is not the same thing as the `overall_score` sentinel rule).
    scored_entities = [e for e in entities if e.is_scored]

    for score_field, thresholds in BELOW_THRESHOLDS_BY_ATTRIBUTE.items():
        label = ATTRIBUTE_LABELS[score_field]
        for threshold in thresholds:
            matches = [
                e.filename
                for e in scored_entities
                if e[score_field] is not None and e[score_field] < threshold
            ]
            if not (FILTER_RESULT_MIN <= len(matches) <= FILTER_RESULT_MAX):
                continue
            query = f"Which characters have {_indefinite_article(label)} {label} score below {threshold}?"
            candidates.append(
                Candidate(
                    id=next_case_id("numeric_range"),
                    query=query,
                    expected_documents=matches,
                    category="numeric_range",
                    difficulty="medium",
                    language="en",
                    type="numeric",
                    note=f"field={score_field} op=< threshold={threshold} n={len(matches)} "
                    "overall_score_null_excluded=true",
                    natural_key=f"range::{score_field}::<::{threshold}",
                    meta={"field": score_field, "op": "<", "threshold": threshold},
                )
            )

    # `overall_score` sentinel rule: `Entity.is_scored` excludes both `null`
    # and `"∞"` (neither is an `int`), applied to the base population before
    # any threshold/window is picked — see the comment block above.
    overall_ints = [e for e in entities if e.is_scored]
    for threshold in OVERALL_SCORE_BELOW_THRESHOLDS:
        matches = [e.filename for e in overall_ints if e["overall_score"] < threshold]
        if FILTER_RESULT_MIN <= len(matches) <= FILTER_RESULT_MAX:
            query = f"Which characters have an overall score below {threshold}?"
            candidates.append(
                Candidate(
                    id=next_case_id("numeric_range"),
                    query=query,
                    expected_documents=matches,
                    category="numeric_range",
                    difficulty="medium",
                    language="en",
                    type="numeric",
                    note=f"field=overall_score op=< threshold={threshold} n={len(matches)}",
                    natural_key=f"range::overall_score::<::{threshold}",
                    meta={"field": "overall_score", "op": "<", "threshold": threshold},
                )
            )
    for threshold in OVERALL_SCORE_ABOVE_THRESHOLDS:
        matches = [e.filename for e in overall_ints if e["overall_score"] > threshold]
        if FILTER_RESULT_MIN <= len(matches) <= FILTER_RESULT_MAX:
            query = f"Which characters have an overall score above {threshold}?"
            candidates.append(
                Candidate(
                    id=next_case_id("numeric_range"),
                    query=query,
                    expected_documents=matches,
                    category="numeric_range",
                    difficulty="medium",
                    language="en",
                    type="numeric",
                    note=f"field=overall_score op=> threshold={threshold} n={len(matches)}",
                    natural_key=f"range::overall_score::>::{threshold}",
                    meta={"field": "overall_score", "op": ">", "threshold": threshold},
                )
            )
    return candidates


# --- Category 5: crosslingual -------------------------------------------------
#
# A German-phrased question against the English-language corpus, reusing an
# already-validated candidate from one of the four categories above so the
# ground truth is "wie oben" (docs/features/search-quality-evaluation.md)
# rather than independently derived. Translations below are hand-written
# templates driven by each source candidate's structured `meta`, not
# machine-translated and not string-parsed out of the human-readable `note`
# (an earlier version of this function did the latter and silently
# truncated multi-word values like "Dark Horse Comics" or "Mind Control
# Resistance" at the first space).

GERMAN_ATTRIBUTE_TEMPLATES = {
    "eye_color": "Welche Augenfarbe hat {name}?",
    "hair_color": "Welche Haarfarbe hat {name}?",
    "creator": "Von welchem Verlag oder Schöpfer stammt {name}?",
    "real_name": "Wie lautet der echte Name von {name}?",
    "place_of_birth": "Wo wurde {name} geboren?",
    "occupation": "Welchen Beruf übt {name} aus?",
    # Neutral like the English template (issue #274) — `first_appearance`
    # is not always a comic issue (e.g. "Arrow Season 3: Episode 1").
    "first_appearance": "Wo trat {name} zuerst auf?",
    "alignment": "Ist {name} gut, böse oder neutral?",
    "type_race": "Welcher Spezies gehört {name} an?",
    "height_cm": "Wie groß ist {name} in Zentimetern?",
}


def _sample_across_groups(items: list[Candidate], group_key, total: int) -> list[Candidate]:
    """Deterministically sample up to `total` items, spread evenly across
    the groups `group_key` partitions `items` into, instead of a single
    positional stride over the flat list.

    Issue #274: `filter_candidates[::step][:12]` and
    `range_candidates[::step][:12]` did not achieve the "evenly spread"
    comment above them — with 16 range candidates, `step` rounded down to 1,
    so the "sample" was just the first 12 items in iteration order, and
    iteration order happens to put every "below" candidate before any
    "above" one (10 attribute-below-threshold candidates, then 2
    overall_score-below, then 4 overall_score-above): all 12 selected were
    "below". With 167 filter candidates, `step=13` never reaches the last
    ~23 indices, so the alignment group iterated last (`Neutral`) is
    under-represented. Grouping first and sampling within each group avoids
    both failure modes and stays fully deterministic (fixed grouping order,
    fixed per-group stride)."""
    groups: dict[object, list[Candidate]] = {}
    for item in items:
        groups.setdefault(group_key(item), []).append(item)
    group_keys = sorted(groups, key=repr)
    per_group = max(1, total // len(group_keys))
    selected: list[Candidate] = []
    for key in group_keys:
        group_items = groups[key]
        step = max(1, len(group_items) // per_group)
        selected.extend(group_items[::step][:per_group])
    if len(selected) < total:
        selected_ids = {id(item) for item in selected}
        for item in items:
            if len(selected) >= total:
                break
            if id(item) not in selected_ids:
                selected.append(item)
                selected_ids.add(id(item))
    return selected[:total]


def generate_crosslingual(
    attribute_candidates: list[Candidate],
    filter_candidates: list[Candidate],
    range_candidates: list[Candidate],
) -> list[Candidate]:
    candidates: list[Candidate] = []

    # Translated attribute_lookup candidates: one German question per field,
    # reusing the first English candidate generated for that field.
    by_field: dict[str, Candidate] = {}
    for candidate in attribute_candidates:
        field_name = candidate.meta["field"]
        by_field.setdefault(field_name, candidate)
    for field_name, template in GERMAN_ATTRIBUTE_TEMPLATES.items():
        source = by_field.get(field_name)
        if source is None:
            continue
        candidates.append(
            Candidate(
                id=next_case_id("crosslingual"),
                query=template.format(name=source.meta["entity_name"]),
                expected_documents=source.expected_documents,
                category="crosslingual",
                difficulty="easy",
                language="de",
                type="factual",
                note=f"translated_from={source.id}",
                natural_key=f"de::{source.natural_key}",
            )
        )

    # Translated multi_attribute_filter candidates: grouped by alignment so
    # all three alignments are represented, not just the first ones in
    # iteration order (issue #274).
    for source in _sample_across_groups(filter_candidates, lambda c: c.meta["alignment"], 12):
        alignment = source.meta["alignment"]
        creator = source.meta["creator"]
        ability = source.meta["ability"]
        # "verfügen über die Fähigkeit" instead of "beherrschen die
        # Fähigkeit" (issue #274): several sampled abilities are passive
        # resistances or traits (e.g. "Mind Control Resistance",
        # "Self-Sustenance") that nobody "masters" ("beherrscht") — "have/
        # possess the ability" holds for both active powers and passive
        # resistances/traits.
        query = (
            f"Welche {_german_alignment(alignment)} Figuren von {creator} verfügen über "
            f"die Fähigkeit {ability}?"
        )
        candidates.append(
            Candidate(
                id=next_case_id("crosslingual"),
                query=query,
                expected_documents=source.expected_documents,
                category="crosslingual",
                difficulty="hard",
                language="de",
                type="filter",
                note=f"translated_from={source.id}",
                natural_key=f"de::{source.natural_key}",
            )
        )

    # Translated numeric_range candidates: grouped by comparison operator so
    # both "below/unter" and "above/über" are represented (issue #274).
    for source in _sample_across_groups(range_candidates, lambda c: c.meta["op"], 12):
        label = _german_attribute_label(source.meta["field"])
        op = source.meta["op"]
        threshold = source.meta["threshold"]
        comparison = "über" if op == ">" else "unter"
        query = f"Welche Figuren haben einen {label} {comparison} {threshold}?"
        candidates.append(
            Candidate(
                id=next_case_id("crosslingual"),
                query=query,
                expected_documents=source.expected_documents,
                category="crosslingual",
                difficulty="medium",
                language="de",
                type="numeric",
                note=f"translated_from={source.id}",
                natural_key=f"de::{source.natural_key}",
            )
        )

    return candidates


GERMAN_ALIGNMENT = {"Good": "guten", "Bad": "bösen", "Neutral": "neutralen"}


def _german_alignment(alignment: str) -> str:
    return GERMAN_ALIGNMENT[alignment]


_ALL_SCORE_LABELS = {**ATTRIBUTE_LABELS, "overall_score": "overall"}

GERMAN_ATTRIBUTE_LABEL = {
    "intelligence": "Intelligenzwert",
    "strength": "Stärkewert",
    "speed": "Geschwindigkeitswert",
    "durability": "Widerstandsfähigkeitswert",
    "combat": "Kampfwert",
    "overall": "Gesamtwert",
}


def _german_attribute_label(score_field: str) -> str:
    return GERMAN_ATTRIBUTE_LABEL[_ALL_SCORE_LABELS[score_field]]


# --- Curation ------------------------------------------------------------
#
# "Vollautomatisch generierte Fälle sind ein Silver Dataset" (issue #226 /
# docs/features/search-quality-evaluation.md, "Kuratierung"): the generators
# above already apply every *automatic* filter this issue mandates
# (contamination-length guards, the sentinel rule, the [2, 15] filter-result
# window, entity_description/filter uniqueness under case-insensitive
# comparison). What is still missing is a human decision about which of the
# resulting several hundred still-valid candidates are worth publishing —
# since almost all of them are individually correct, "curation" here is
# mostly about trimming near-duplicates (the same field asked about many
# similar entities) down to a set that is diverse across entities, fields
# and difficulty, plus rejecting the handful of candidates that read
# awkwardly even though their ground truth is correct.
#
# This list is the manual review's *output*, not a re-derivable computation:
# it is spot-checked against the corpus by a human (see eval/golden/README.md
# for the review log) and is itself the reviewable artifact in this file's
# diff.
#
# Selection is by (`natural_key`, `query`) pair, not by the sequential `id`
# a candidate happens to get in a given run (issue #274, finding 3): `id`s
# are assigned in generation order, so a field that used to yield exactly
# `MAX_CANDIDATES_PER_FIELD` candidates but yields one fewer after a corpus
# update silently renumbers every candidate after it — `comic-attr-101`
# would keep existing, but now name a different entity and possibly a
# different field, while `CURATED_CASE_IDS` (the old, id-based version of
# this list) kept pointing at that position and picked up the swap without
# any error. `natural_key` is derived purely from generating parameters
# (field, entity, threshold, ...), so it identifies "the same" candidate
# regardless of how many other candidates exist around it. The paired
# `query` string is a second, independent, human-readable check: if a
# `natural_key` still resolves to a candidate after a change but that
# candidate's *query text* differs from what was curated, something about
# the generating logic changed underneath this selection, and `main()`
# refuses to proceed silently (see the lookup loop below) rather than
# publish a different question under an unchanged-looking selection.
CURATED_CASES: list[tuple[str, str]] = (
    []
    # attribute_lookup: 3 per field (10 fields), varied entities.
    + [
        ("attr::eye_color::comic-0008_abin-sur.md", "What eye color does Abin Sur have?"),
        ("attr::eye_color::comic-0022_agent-13.md", "What eye color does Agent 13 have?"),
        ("attr::eye_color::comic-0043_alta-r-ibn-la-ahad.md", "What eye color does Altaïr Ibn-La'Ahad have?"),
        ("attr::hair_color::comic-0145_batwoman.md", "What hair color does Batwoman have?"),
        ("attr::hair_color::comic-0173_black-adam-pre-crisis.md", "What hair color does Black Adam (Pre-Crisis) have?"),
        ("attr::hair_color::comic-0180_black-canary-injustice.md", "What hair color does Black Canary (Injustice) have?"),
        ("attr::creator::comic-0289_cheshire.md", "Which company or creator created Cheshire?"),
        ("attr::creator::comic-0296_chromos.md", "Which company or creator created Chromos?"),
        ("attr::creator::comic-0303_clock-king.md", "Which company or creator created Clock King?"),
        ("attr::real_name::comic-0433_el-diablo.md", "What is El Diablo's real name?"),
        ("attr::real_name::comic-0447_eradicator.md", "What is Eradicator's real name?"),
        ("attr::real_name::comic-0454_evil-nya.md", "What is Evil Nya's real name?"),
        ("attr::place_of_birth::comic-0591_hellfire-mcu.md", "Where was Hellfire (MCU) born?"),
        ("attr::place_of_birth::comic-0598_hiruzen-sarutobi.md", "Where was Hiruzen Sarutobi born?"),
        ("attr::place_of_birth::comic-0612_hulk-2099.md", "Where was Hulk 2099 born?"),
        ("attr::occupation::comic-0721_karnak.md", "What is Karnak's occupation?"),
        ("attr::occupation::comic-0728_kenshiro.md", "What is Kenshiro's occupation?"),
        ("attr::occupation::comic-0735_kid-flash-ii.md", "What is Kid Flash II's occupation?"),
        ("attr::first_appearance::comic-0865_maximus-mcu.md", "Where did Maximus (MCU) first appear?"),
        ("attr::first_appearance::comic-0879_metron.md", "Where did Metron first appear?"),
        ("attr::first_appearance::comic-0886_mind-flayer.md", "Where did Mind Flayer first appear?"),
        ("attr::alignment::comic-1009_plastic-man.md", "Is Plastic Man good, bad, or neutral?"),
        ("attr::alignment::comic-1016_polaris.md", "Is Polaris good, bad, or neutral?"),
        ("attr::alignment::comic-1023_preeminent.md", "Is Preeminent good, bad, or neutral?"),
        ("attr::type_race::comic-1153_sharon-carter.md", "What species or race is Sharon Carter?"),
        ("attr::type_race::comic-1160_shin-godzilla.md", "What species or race is Shin Godzilla?"),
        ("attr::type_race::comic-1181_skales.md", "What species or race is Skales?"),
        ("attr::height_cm::comic-1297_the-ray-cw.md", "How tall is The Ray (CW), in centimeters?"),
        ("attr::height_cm::comic-1304_the-thing-fox.md", "How tall is The Thing (FOX), in centimeters?"),
        ("attr::height_cm::comic-1332_trickster.md", "How tall is Trickster, in centimeters?"),
    ]
    # entity_description: 8 per creator/eye/ability template, 8 per
    # alignment/race/team/hair template, 4 of the weaker place/occupation/eye
    # template — occupation-field prose reads more awkwardly in general (see
    # eval/golden/README.md's curation log), so it is deliberately
    # under-represented rather than dropped outright, and the four kept here
    # are hand-picked for readable occupation text specifically (the
    # template's raw candidates include some occupation values that are
    # themselves messy in the source dataset, e.g. "Cyrus borg his helper" —
    # correct ground truth, but not worth curating in).
    + [
        ("desc::0::comic-0043_alta-r-ibn-la-ahad.md", "Which character created by Ubisoft is good-aligned, has Hazel eyes and can use Agility?"),
        ("desc::0::comic-0078_aragorn.md", "Which character created by J. R. R. Tolkien is good-aligned, has Grey eyes and can use Accelerated Healing?"),
        ("desc::0::comic-0113_bane-dark-knight.md", "Which character created by DC Comics is bad-aligned, has Hazel eyes and can use Accelerated Healing?"),
        ("desc::0::comic-0274_castiel.md", "Which character created by Wildstorm is good-aligned, has Green eyes and can use Accelerated Healing?"),
        ("desc::0::comic-0428_edward-kenway.md", "Which character created by Ubisoft is good-aligned, has Green eyes and can use Agility?"),
        ("desc::0::comic-0526_golden-ninja.md", "Which character created by Lego is good-aligned, has White eyes and can use Accelerated Healing?"),
        ("desc::0::comic-0568_harry-potter.md", "Which character created by J. K. Rowling is good-aligned, has Green eyes and can use Accelerated Healing?"),
        ("desc::0::comic-0631_impossible-man.md", "Which character created by Marvel Comics is neutral-aligned, has Purple eyes and can use Dimensional Travel?"),
        ("desc::1::comic-0483_flash-cw.md", "Which good Metahuman character is affiliated with Flash Family and has Brown / Black hair?"),
        ("desc::1::comic-0518_gilotina.md", "Which bad God / Eternal character is affiliated with Female Furies and has Blond hair?"),
        ("desc::1::comic-0532_granny-goodness.md", "Which bad New God character is affiliated with Female Furies and has White hair?"),
        ("desc::1::comic-0609_howard-the-duck.md", "Which good Animal character is affiliated with Marvel Knights and has Yellow hair?"),
        ("desc::1::comic-0616_hulkling.md", "Which good Alien character is affiliated with Young Avengers and has Blond hair?"),
        ("desc::1::comic-0651_iron-man.md", "Which good Human character is affiliated with Hulkbusters and has Black hair?"),
        ("desc::1::comic-0693_johnny-quick.md", "Which bad Human character is affiliated with Flash Family and has Blond hair?"),
        ("desc::1::comic-0707_jyn-erso.md", "Which good Human character is affiliated with Rogue One and has Brown hair?"),
        ("desc::2::comic-1014_poison-ivy.md", "Which character born in Seattle, Washington works as criminal, Botanist and has Green eyes?"),
        ("desc::2::comic-1056_raphael-tmnt-2012.md", "Which character born in New York City works as ninja and has Green eyes?"),
        ("desc::2::comic-1224_starfire.md", "Which character born in Tamaran works as model and has Green eyes?"),
        ("desc::2::comic-1252_superman-2006.md", "Which character born in Krypton works as reporter and has Blue eyes?"),
    ]
    # multi_attribute_filter: every 8th candidate (of 167) — the old
    # positional stride is fine *here* because it was only ever used to
    # decide the selection, not to look it up again; the resulting 21
    # (alignment, creator, ability) triples are what carries forward,
    # already spread across all three alignments and five creators.
    + [
        ("filter::Good::Marvel Comics::Reality Warping", "Which good-aligned characters created by Marvel Comics have the ability Reality Warping?"),
        ("filter::Good::DC Comics::Telepathy Resistance", "Which good-aligned characters created by DC Comics have the ability Telepathy Resistance?"),
        ("filter::Good::Shueisha::Mind Control", "Which good-aligned characters created by Shueisha have the ability Mind Control?"),
        ("filter::Good::Shueisha::Shapeshifting", "Which good-aligned characters created by Shueisha have the ability Shapeshifting?"),
        ("filter::Good::Shueisha::Heat Resistance", "Which good-aligned characters created by Shueisha have the ability Heat Resistance?"),
        ("filter::Good::Dark Horse Comics::Regeneration", "Which good-aligned characters created by Dark Horse Comics have the ability Regeneration?"),
        ("filter::Good::Lego::Shapeshifting", "Which good-aligned characters created by Lego have the ability Shapeshifting?"),
        ("filter::Bad::Marvel Comics::Mind Control Resistance", "Which bad-aligned characters created by Marvel Comics have the ability Mind Control Resistance?"),
        ("filter::Bad::DC Comics::Reality Warping", "Which bad-aligned characters created by DC Comics have the ability Reality Warping?"),
        ("filter::Bad::DC Comics::Electrokinesis", "Which bad-aligned characters created by DC Comics have the ability Electrokinesis?"),
        ("filter::Bad::DC Comics::Heat Resistance", "Which bad-aligned characters created by DC Comics have the ability Heat Resistance?"),
        ("filter::Bad::Shueisha::Telekinesis", "Which bad-aligned characters created by Shueisha have the ability Telekinesis?"),
        ("filter::Bad::Shueisha::Regeneration", "Which bad-aligned characters created by Shueisha have the ability Regeneration?"),
        ("filter::Bad::Dark Horse Comics::Super Speed", "Which bad-aligned characters created by Dark Horse Comics have the ability Super Speed?"),
        ("filter::Neutral::Marvel Comics::Mind Control", "Which neutral-aligned characters created by Marvel Comics have the ability Mind Control?"),
        ("filter::Neutral::Marvel Comics::Magic", "Which neutral-aligned characters created by Marvel Comics have the ability Magic?"),
        ("filter::Neutral::Marvel Comics::Regeneration", "Which neutral-aligned characters created by Marvel Comics have the ability Regeneration?"),
        ("filter::Neutral::DC Comics::Dimensional Travel", "Which neutral-aligned characters created by DC Comics have the ability Dimensional Travel?"),
        ("filter::Neutral::DC Comics::Element Control", "Which neutral-aligned characters created by DC Comics have the ability Element Control?"),
        ("filter::Neutral::DC Comics::Cold Resistance", "Which neutral-aligned characters created by DC Comics have the ability Cold Resistance?"),
        ("filter::Neutral::Shueisha::Force Fields", "Which neutral-aligned characters created by Shueisha have the ability Force Fields?"),
    ]
    # numeric_range: all 16 automatically-generated candidates are kept —
    # each already required a dedicated threshold search to land in the
    # [2, 15] window (see BELOW_THRESHOLDS_BY_ATTRIBUTE), so none are
    # redundant with another.
    + [
        ("range::intelligence_score::<::35", "Which characters have an intelligence score below 35?"),
        ("range::intelligence_score::<::40", "Which characters have an intelligence score below 40?"),
        ("range::intelligence_score::<::45", "Which characters have an intelligence score below 45?"),
        ("range::intelligence_score::<::50", "Which characters have an intelligence score below 50?"),
        ("range::strength_score::<::5", "Which characters have a strength score below 5?"),
        ("range::speed_score::<::10", "Which characters have a speed score below 10?"),
        ("range::durability_score::<::5", "Which characters have a durability score below 5?"),
        ("range::durability_score::<::10", "Which characters have a durability score below 10?"),
        ("range::combat_score::<::10", "Which characters have a combat score below 10?"),
        ("range::combat_score::<::15", "Which characters have a combat score below 15?"),
        ("range::overall_score::<::2", "Which characters have an overall score below 2?"),
        ("range::overall_score::<::3", "Which characters have an overall score below 3?"),
        ("range::overall_score::>::120", "Which characters have an overall score above 120?"),
        ("range::overall_score::>::150", "Which characters have an overall score above 150?"),
        ("range::overall_score::>::180", "Which characters have an overall score above 180?"),
        ("range::overall_score::>::210", "Which characters have an overall score above 210?"),
    ]
    # crosslingual: all 34 kept, for the same reason as numeric_range — each
    # is a distinct field, filter or range constraint translated to German,
    # not a near-duplicate of another crosslingual candidate. Both filter
    # alignments (Good/Bad/Neutral) and both range directions (below/above)
    # are represented (issue #274).
    + [
        ("de::attr::eye_color::comic-0008_abin-sur.md", "Welche Augenfarbe hat Abin Sur?"),
        ("de::attr::hair_color::comic-0145_batwoman.md", "Welche Haarfarbe hat Batwoman?"),
        ("de::attr::creator::comic-0289_cheshire.md", "Von welchem Verlag oder Schöpfer stammt Cheshire?"),
        ("de::attr::real_name::comic-0433_el-diablo.md", "Wie lautet der echte Name von El Diablo?"),
        ("de::attr::place_of_birth::comic-0591_hellfire-mcu.md", "Wo wurde Hellfire (MCU) geboren?"),
        ("de::attr::occupation::comic-0721_karnak.md", "Welchen Beruf übt Karnak aus?"),
        ("de::attr::first_appearance::comic-0865_maximus-mcu.md", "Wo trat Maximus (MCU) zuerst auf?"),
        ("de::attr::alignment::comic-1009_plastic-man.md", "Ist Plastic Man gut, böse oder neutral?"),
        ("de::attr::type_race::comic-1153_sharon-carter.md", "Welcher Spezies gehört Sharon Carter an?"),
        ("de::attr::height_cm::comic-1297_the-ray-cw.md", "Wie groß ist The Ray (CW) in Zentimetern?"),
        ("de::filter::Bad::Marvel Comics::Reality Warping", "Welche bösen Figuren von Marvel Comics verfügen über die Fähigkeit Reality Warping?"),
        ("de::filter::Bad::DC Comics::Dimensional Travel", "Welche bösen Figuren von DC Comics verfügen über die Fähigkeit Dimensional Travel?"),
        ("de::filter::Bad::DC Comics::Heat Resistance", "Welche bösen Figuren von DC Comics verfügen über die Fähigkeit Heat Resistance?"),
        ("de::filter::Bad::Shueisha::Teleportation", "Welche bösen Figuren von Shueisha verfügen über die Fähigkeit Teleportation?"),
        ("de::filter::Good::Marvel Comics::Reality Warping", "Welche guten Figuren von Marvel Comics verfügen über die Fähigkeit Reality Warping?"),
        ("de::filter::Good::Shueisha::Mind Control Resistance", "Welche guten Figuren von Shueisha verfügen über die Fähigkeit Mind Control Resistance?"),
        ("de::filter::Good::Shueisha::Force Fields", "Welche guten Figuren von Shueisha verfügen über die Fähigkeit Force Fields?"),
        ("de::filter::Good::Dark Horse Comics::Immortality", "Welche guten Figuren von Dark Horse Comics verfügen über die Fähigkeit Immortality?"),
        ("de::filter::Neutral::Marvel Comics::Reality Warping", "Welche neutralen Figuren von Marvel Comics verfügen über die Fähigkeit Reality Warping?"),
        ("de::filter::Neutral::Marvel Comics::Shapeshifting", "Welche neutralen Figuren von Marvel Comics verfügen über die Fähigkeit Shapeshifting?"),
        ("de::filter::Neutral::DC Comics::Mind Control", "Welche neutralen Figuren von DC Comics verfügen über die Fähigkeit Mind Control?"),
        ("de::filter::Neutral::DC Comics::Cold Resistance", "Welche neutralen Figuren von DC Comics verfügen über die Fähigkeit Cold Resistance?"),
        ("de::range::intelligence_score::<::35", "Welche Figuren haben einen Intelligenzwert unter 35?"),
        ("de::range::intelligence_score::<::45", "Welche Figuren haben einen Intelligenzwert unter 45?"),
        ("de::range::strength_score::<::5", "Welche Figuren haben einen Stärkewert unter 5?"),
        ("de::range::durability_score::<::5", "Welche Figuren haben einen Widerstandsfähigkeitswert unter 5?"),
        ("de::range::combat_score::<::10", "Welche Figuren haben einen Kampfwert unter 10?"),
        ("de::range::overall_score::<::2", "Welche Figuren haben einen Gesamtwert unter 2?"),
        ("de::range::overall_score::>::120", "Welche Figuren haben einen Gesamtwert über 120?"),
        ("de::range::overall_score::>::150", "Welche Figuren haben einen Gesamtwert über 150?"),
        ("de::range::overall_score::>::180", "Welche Figuren haben einen Gesamtwert über 180?"),
        ("de::range::overall_score::>::210", "Welche Figuren haben einen Gesamtwert über 210?"),
        ("de::range::intelligence_score::<::40", "Welche Figuren haben einen Intelligenzwert unter 40?"),
        ("de::range::intelligence_score::<::50", "Welche Figuren haben einen Intelligenzwert unter 50?"),
    ]
)


# --- Dedup, curation & output --------------------------------------------------


def to_json(candidate: Candidate) -> dict:
    return {
        "id": candidate.id,
        "domain": DOMAIN,
        "query": candidate.query,
        "expected_documents": candidate.expected_documents,
        "category": candidate.category,
        "difficulty": candidate.difficulty,
        "language": candidate.language,
        "type": candidate.type,
    }


def validate_curated(curated: list[Candidate]) -> None:
    """Defensive, automatic checks mirroring the issue's acceptance criteria.
    The curation review above is what actually decides quality; this is the
    machine-checkable subset of it, re-verified on every run so a future
    edit to `CURATED_CASES` cannot silently violate it."""
    problems: list[str] = []

    if len(curated) < 100:
        problems.append(f"only {len(curated)} curated cases, need >= 100")

    ids = [c.id for c in curated]
    if len(set(ids)) != len(ids):
        problems.append("duplicate case ids in the curated set")

    queries = [c.query for c in curated]
    if len(set(queries)) != len(queries):
        problems.append("duplicate query text in the curated set")

    by_category: dict[str, int] = {}
    by_language: dict[str, int] = {}
    for c in curated:
        by_category[c.category] = by_category.get(c.category, 0) + 1
        by_language[c.language] = by_language.get(c.language, 0) + 1
    for category in ("attribute_lookup", "entity_description", "multi_attribute_filter", "numeric_range", "crosslingual"):
        if by_category.get(category, 0) < 10:
            problems.append(f"category {category!r} has {by_category.get(category, 0)} cases, need >= 10")
    if by_language.get("de", 0) < 30:
        problems.append(f"only {by_language.get('de', 0)} German-language cases, need >= 30")

    for c in curated:
        if not c.expected_documents:
            problems.append(f"{c.id}: expected_documents is empty")
            continue
        for filename in c.expected_documents:
            if not (CORPUS_DIR / filename).exists():
                problems.append(f"{c.id}: expected document {filename!r} does not exist in the corpus")
        if c.category in ("multi_attribute_filter", "numeric_range"):
            n = len(c.expected_documents)
            if not (FILTER_RESULT_MIN <= n <= FILTER_RESULT_MAX):
                problems.append(f"{c.id}: result window {n} outside [{FILTER_RESULT_MIN}, {FILTER_RESULT_MAX}]")

    if problems:
        raise SystemExit("Golden dataset validation failed:\n- " + "\n- ".join(problems))


def write_json(path: Path, payload) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> None:
    entities = load_corpus()

    attribute_candidates = generate_attribute_lookup(entities)
    description_candidates = generate_entity_description(entities)
    filter_candidates = generate_multi_attribute_filter(entities)
    range_candidates = generate_numeric_range(entities)
    crosslingual_candidates = generate_crosslingual(
        attribute_candidates, filter_candidates, range_candidates
    )

    all_candidates = (
        attribute_candidates
        + description_candidates
        + filter_candidates
        + range_candidates
        + crosslingual_candidates
    )

    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    write_json(
        GOLDEN_DIR / "comic-characters.candidates.json",
        [to_json(c) for c in all_candidates],
    )

    # Collision guard: `natural_key` is only a safe lookup key if it is
    # actually unique across all candidates. It is derived to be unique by
    # construction (field/entity, template/entity, alignment/creator/ability,
    # field/op/threshold are each unique combinations per category and
    # categories use disjoint key prefixes) — checked here rather than
    # trusted, since a future template or field addition could violate that.
    natural_key_counts: dict[str, int] = {}
    for c in all_candidates:
        natural_key_counts[c.natural_key] = natural_key_counts.get(c.natural_key, 0) + 1
    colliding = {key: n for key, n in natural_key_counts.items() if n > 1}
    if colliding:
        raise SystemExit(f"Duplicate natural_key values across candidates: {colliding}")

    by_key = {c.natural_key: c for c in all_candidates}
    missing: list[str] = []
    changed: list[tuple[str, str, str]] = []
    curated: list[Candidate] = []
    for natural_key, expected_query in CURATED_CASES:
        candidate = by_key.get(natural_key)
        if candidate is None:
            missing.append(natural_key)
            continue
        if candidate.query != expected_query:
            changed.append((natural_key, expected_query, candidate.query))
            continue
        curated.append(candidate)
    if missing or changed:
        problems = []
        if missing:
            problems.append(
                f"{len(missing)} natural_key(s) in CURATED_CASES no longer exist in the "
                f"generated candidates (corpus or generator changed?): {missing[:10]}"
            )
        if changed:
            problems.append(
                f"{len(changed)} natural_key(s) in CURATED_CASES still exist but now generate "
                f"a different query than what was curated (generator logic changed underneath "
                f"an unchanged-looking selection?): {changed[:5]}"
            )
        raise SystemExit(
            "Golden dataset curation is stale:\n- "
            + "\n- ".join(problems)
            + "\nRe-run the curation review (see eval/golden/README.md) before updating "
            "CURATED_CASES."
        )

    validate_curated(curated)

    write_json(GOLDEN_DIR / "comic-characters.json", [to_json(c) for c in curated])

    def _tally(items: list[Candidate]) -> tuple[dict, dict]:
        by_category: dict[str, int] = {}
        by_language: dict[str, int] = {}
        for c in items:
            by_category[c.category] = by_category.get(c.category, 0) + 1
            by_language[c.language] = by_language.get(c.language, 0) + 1
        return by_category, by_language

    raw_by_category, raw_by_language = _tally(all_candidates)
    curated_by_category, curated_by_language = _tally(curated)

    print(f"Generated {len(all_candidates)} raw candidates.", file=sys.stderr)
    print(f"  By category: {raw_by_category}", file=sys.stderr)
    print(f"  By language: {raw_by_language}", file=sys.stderr)
    print(f"Curated {len(curated)} cases into comic-characters.json.", file=sys.stderr)
    print(f"  By category: {curated_by_category}", file=sys.stderr)
    print(f"  By language: {curated_by_language}", file=sys.stderr)
    print(
        f"{len(all_candidates) - len(curated)} candidates were not selected in curation "
        "(candidates.json minus comic-characters.json; see eval/golden/README.md).",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
