#!/usr/bin/env python3
"""Deterministic golden-dataset generator for the "city-landmarks" domain (issue #234, PR #730
review). Parses the generated `eval/corpus/city-landmarks/*.md` documents directly (regular
expressions against the generator's own, known sentence templates) and a Docker-free chunk map
(`backend/build/eval-reports/chunk-map-city-landmarks-dryrun.json`, produced by
`io.opaa.eval.CityLandmarksChunkSizeDryRunTest`) to curate `boundary_span` cases near real chunk
boundaries.

Unlike `generate_golden_dataset.py` (comic-characters), this script does not derive cases from
YAML frontmatter alone — city-landmarks documents carry most testable facts in Fließtext
sentences, not frontmatter fields, so the ground truth is extracted from the document text itself
via the generator's own fixed sentence templates.

Usage (from repo root, after the corpus and the dry-run chunk map both exist):
    python eval/generator/generate_city_landmarks_golden.py

See eval/golden/README.md for the schema and category descriptions.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = REPO_ROOT / "eval" / "corpus" / "city-landmarks"
CHUNK_MAP = REPO_ROOT / "backend" / "build" / "eval-reports" / "chunk-map-city-landmarks-dryrun.json"
OUT_PATH = REPO_ROOT / "eval" / "golden" / "city-landmarks.json"

# Must match RANK_NEIGHBOR_RADIUS in generate_city_landmarks_corpus.py — multi_city pairs are
# required to be further apart in rank than this (PR #730 review, Wichtig 3): otherwise a future
# radius change could silently turn a multi_city pair into two documents that also cross-reference
# each other via the rank-neighbor comparison sentences, weakening what the case actually tests.
RANK_NEIGHBOR_RADIUS = 40


def load_doc(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def frontmatter_field(text: str, key: str) -> str | None:
    match = re.search(rf"^{key}: (.*)$", text, re.MULTILINE)
    if not match:
        return None
    value = match.group(1).strip()
    if value == "null":
        return None
    if value.startswith('"') and value.endswith('"'):
        return value[1:-1]
    return value


def extract_landmark_headers(text: str) -> list[str]:
    """Landmark names in document order, read from '## ' section headers — never derived from
    sentence text (PR #730 review, Wichtig 5: a regex split on '.' previously truncated names
    containing abbreviations like 'St.', e.g. 'Kathedrale St. Josef' became 'Kathedrale St').
    """
    return re.findall(r"^## (.+)$", text, re.MULTILINE)


def build_query_subject(landmark_name: str, city_name: str) -> str:
    """PR #730 review, Wichtig 5: several Wikidata landmark labels already embed the city name
    (e.g. "Admiralität in Sankt Petersburg"), producing a duplicated "... in Sankt Petersburg in
    Sankt Petersburg?" when the query template unconditionally appended it. Suppressed here."""
    if city_name and city_name in landmark_name:
        return landmark_name
    return f"{landmark_name} in {city_name}" if city_name else landmark_name


def main() -> None:
    with open(CHUNK_MAP, encoding="utf-8") as f:
        chunk_maps = {entry["fileName"]: entry for entry in json.load(f)}

    md_files = sorted(CORPUS_DIR.glob("city-*.md"))
    docs = {path.name: load_doc(path) for path in md_files}

    cases: list[dict] = []
    counters: Counter[str] = Counter()

    def add_case(query: str, expected_documents: list[str], category: str, answer_span: str | None = None) -> dict:
        counters[category] += 1
        case = {
            "id": f"city-{category}-{counters[category]:03d}",
            "domain": "city-landmarks",
            "query": query,
            "expected_documents": expected_documents,
            "category": category,
            "difficulty": "easy" if category in ("city_overview", "multi_city") else "medium",
            "language": "de",
            "type": "factual",
            "answer_span": answer_span,
        }
        cases.append(case)
        return case

    # ---- city_overview: population/country facts, doc-level, straightforward -----------------
    overview_targets: list[tuple[str, str]] = []
    hardcoded_filenames = {"city-0001_istanbul.md", "city-0004_berlin.md", "city-0002_moskau.md"}
    for filename in sorted(hardcoded_filenames):
        if filename not in docs:
            continue
        name = frontmatter_field(docs[filename], "name")
        overview_targets.append((filename, f"Wie viele Einwohner hat {name} laut diesem Korpus?"))
    for filename, query in overview_targets:
        add_case(query, [filename], "city_overview")

    # NIT 7 fix: sample every 20th document by position, but skip any filename already used above
    # so the same city never appears twice under city_overview.
    used_filenames = set(hardcoded_filenames)
    for path in md_files[::20]:
        if path.name in used_filenames:
            continue
        text = docs[path.name]
        name = frontmatter_field(text, "name")
        country = frontmatter_field(text, "country")
        population = frontmatter_field(text, "population")
        if name and population:
            add_case(f"Wie viele Einwohner hat {name} laut diesem Korpus?", [path.name], "city_overview")
            used_filenames.add(path.name)
        if country and name and len(overview_targets) < 20:
            add_case(f"In welchem Land liegt {name}?", [path.name], "city_overview")

    # ---- landmark_detail / boundary_span: pick sentences from landmark sections ---------------
    SENTENCE_SPLIT = re.compile(r"(?<=\.)\s+(?=[A-ZÄÖÜ])")
    FACT_KEYWORDS = [
        (re.compile(r" wurde (\d{3,4}) erbaut"), "erbaut"),
        (re.compile(r" erreicht eine Höhe von rund (\d+) Metern"), "hoehe"),
        (re.compile(r" steht unter dem dokumentierten Schutzstatus '([^']+)'"), "schutzstatus"),
        (re.compile(r" verzeichnet [A-ZÄÖÜ][^.]*? rund ([\d.]+) Besucherinnen"), "besucher"),
    ]

    def find_chunk_index(chunk_map: dict, start_char: int):
        for chunk in chunk_map["chunks"]:
            if chunk["startChar"] <= start_char < chunk["endChar"]:
                return chunk["index"], chunk["startChar"], chunk["endChar"]
        return None, None, None

    landmark_detail_count = 0
    boundary_span_count = 0

    for path in md_files:
        filename = path.name
        text = docs[filename]
        chunk_map = chunk_maps.get(filename)
        if chunk_map is None:
            continue
        name = frontmatter_field(text, "name")

        sections = re.split(r"\n## ", text)
        for section in sections[1:]:
            header_end = section.find("\n")
            landmark_name = section[:header_end].strip()
            body = section[header_end:]

            sentences = [s.strip() for s in SENTENCE_SPLIT.split(body.strip()) if s.strip()]
            matched_this_landmark = False
            for sentence in sentences:
                if matched_this_landmark:
                    break
                for keyword_pattern, kind in FACT_KEYWORDS:
                    match = keyword_pattern.search(sentence)
                    if not match:
                        continue
                    char_pos = text.find(sentence)
                    if char_pos < 0:
                        continue
                    idx, start, end = find_chunk_index(chunk_map, char_pos)
                    if idx is None:
                        continue

                    near_boundary = (char_pos - start) < 120 or (end - (char_pos + len(sentence))) < 120
                    subject = build_query_subject(landmark_name, name)

                    if kind == "erbaut":
                        query = f"In welchem Jahr wurde {subject} erbaut?"
                    elif kind == "hoehe":
                        query = f"Wie hoch ist {subject}?"
                    elif kind == "schutzstatus":
                        query = f"Unter welchem Schutzstatus steht {subject}?"
                    else:
                        query = f"Wie viele Besucherinnen und Besucher hat {subject} pro Jahr?"

                    if near_boundary and boundary_span_count < 20:
                        add_case(query, [filename], "boundary_span", answer_span=sentence)
                        boundary_span_count += 1
                    elif landmark_detail_count < 25:
                        add_case(query, [filename], "landmark_detail", answer_span=sentence)
                        landmark_detail_count += 1
                    matched_this_landmark = True
                    break

    # ---- cross_chunk: deterministic comparison sentences, names from headers ------------------
    COMPARISON_PATTERN = re.compile(
        r"(Im Vergleich der in diesem Dokument beschriebenen Sehenswürdigkeiten gilt: "
        r"(.+? entstand (?:früher|später|im selben Jahr wie) .+?)\.)"
    )
    cross_chunk_count = 0
    for path in md_files:
        filename = path.name
        text = docs[filename]
        name = frontmatter_field(text, "name")
        headers = extract_landmark_headers(text)
        if cross_chunk_count >= 15:
            break
        match = COMPARISON_PATTERN.search(text)
        if not match:
            continue
        sentence = match.group(1).strip()
        # Wichtig 5: identify the two landmark names by matching against the known header list
        # (exact strings), not by splitting the comparison sentence's own text on any delimiter —
        # this is what previously truncated names containing "St." at the embedded period.
        comparison_text = match.group(2)
        matched_headers = [h for h in headers if h in comparison_text]
        if len(matched_headers) < 2:
            continue
        a, b = matched_headers[0], matched_headers[1]
        query = f"Welche Sehenswürdigkeit in {name} entstand früher — {a} oder {b}?"
        add_case(query, [filename], "cross_chunk", answer_span=sentence)
        cross_chunk_count += 1

    # ---- multi_city: rank-neighbor population comparisons, distance > RANK_NEIGHBOR_RADIUS -----
    filename_by_rank: dict[int, str] = {}
    for path in md_files:
        rank = int(frontmatter_field(docs[path.name], "rank"))
        filename_by_rank[rank] = path.name

    multi_city_pairs = [
        (1, 45), (2, 50), (3, 60), (4, 70), (5, 80), (6, 90), (7, 100), (8, 110),
    ]
    for rank_a, rank_b in multi_city_pairs:
        assert abs(rank_a - rank_b) > RANK_NEIGHBOR_RADIUS, (
            f"multi_city pair (rank {rank_a}, rank {rank_b}) is within RANK_NEIGHBOR_RADIUS="
            f"{RANK_NEIGHBOR_RADIUS} of each other — the two documents would already reference "
            "each other via the generator's own rank-neighbor comparison sentences, so the case "
            "would not require combining independent documents (PR #730 review, Wichtig 3)."
        )
        file_a = filename_by_rank.get(rank_a)
        file_b = filename_by_rank.get(rank_b)
        if not file_a or not file_b:
            continue
        name_a = frontmatter_field(docs[file_a], "name")
        name_b = frontmatter_field(docs[file_b], "name")
        query = f"Welche Stadt hat mehr Einwohner — {name_a} oder {name_b}?"
        add_case(query, sorted([file_a, file_b]), "multi_city")

    print("total cases:", len(cases))
    print(dict(counters))
    boundary_and_cross = sum(1 for c in cases if c["category"] in ("boundary_span", "cross_chunk"))
    print("boundary_span + cross_chunk:", boundary_and_cross)

    missing = []
    duplicate_check: dict[tuple, str] = {}
    duplicates = []
    for c in cases:
        if c["answer_span"] is not None:
            doc_text = docs[c["expected_documents"][0]]
            if c["answer_span"] not in doc_text:
                missing.append(c["id"])
        key = (c["category"], c["query"])
        if key in duplicate_check:
            duplicates.append((c["id"], duplicate_check[key]))
        else:
            duplicate_check[key] = c["id"]
    print("answer_span not found verbatim in doc:", missing)
    print("duplicate (category, query) pairs:", duplicates)
    assert not missing, "unresolved answer_span(s) found — see above"
    assert not duplicates, "duplicate golden cases found — see above"

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(cases, f, ensure_ascii=False, indent=2)
    print("written to", OUT_PATH)


if __name__ == "__main__":
    main()
