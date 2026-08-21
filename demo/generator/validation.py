"""Final validation pass: aborts the generator run if any Rheinfurt document
still carries a real Munich identifier (PR #717 review, WICHTIG 2).

Runs against two kinds of text, per library:

- Leistungen Meldewesen & Ausweise / Kfz-Zulassung: the actually rendered
  `.md`/`.txt` file content (these are plain text, so validating the final
  output costs nothing extra).
- Satzungen, Pressemitteilungen, Interne Dienstanweisungen: the underlying
  Python string content *before* it is handed to reportlab/python-docx/
  python-pptx ("auf Quelltextebene vor dem Rendern" — the two are
  content-identical for these three libraries, since none of their
  hand-authored text mentions a real Munich address to begin with; the check
  exists as a regression guard against a future edit introducing one).

Checked patterns:

1. Forbidden tokens: "München"/"münchen", "KVR", "Pasing", "Landeshauptstadt",
   "Fischerei" — must not occur anywhere.
2. Street name + house number: every match of `<Straßenname> <Hausnummer>`
   must use a name from `rheinfurt_text.ALLOWED_STREET_NAMES`.
3. Postal code: every "<PLZ> Rheinfurt" occurrence must use
   `rheinfurt_text.RHEINFURT_PLZ`.
4. Bank details: every "IBAN: ..." / "BIC: ..." occurrence must use
   `rheinfurt_text.RHEINFURT_IBAN` / `RHEINFURT_BIC`.
"""

from __future__ import annotations

import re

from rheinfurt_text import ALLOWED_STREET_NAMES, RHEINFURT_BIC, RHEINFURT_IBAN, RHEINFURT_PLZ

_FORBIDDEN_TOKEN_RE = re.compile(
    r"München|münchen|\bKVR\b|Pasing|Landeshauptstadt|Fischerei", re.UNICODE
)
_STREET_NUMBER_RE = re.compile(
    r"([A-ZÄÖÜ][\wäöüßÄÖÜ]*(?:straße|Straße|platz|Platz|weg|Weg|allee|Allee|gasse|Gasse"
    r"|promenade|Promenade))\s+(\d+[a-zA-Z]?)\b"
)
_PLZ_CONTEXT_RE = re.compile(r"\b(\d{5})\s+Rheinfurt\b")
_IBAN_LABEL_RE = re.compile(r"IBAN:\s*([A-Z0-9 ]{15,34})")
_BIC_LABEL_RE = re.compile(r"BIC:\s*([A-Z0-9]{8,11})")


def collect_violations(label: str, text: str) -> list[str]:
    violations: list[str] = []

    for match in _FORBIDDEN_TOKEN_RE.finditer(text):
        violations.append(f"{label}: verbotenes Token {match.group()!r}")

    for match in _STREET_NUMBER_RE.finditer(text):
        street = match.group(1)
        if street not in ALLOWED_STREET_NAMES:
            violations.append(f"{label}: Straße außerhalb Whitelist {match.group()!r}")

    for match in _PLZ_CONTEXT_RE.finditer(text):
        if match.group(1) != RHEINFURT_PLZ:
            violations.append(f"{label}: reale PLZ {match.group()!r}")

    for match in _IBAN_LABEL_RE.finditer(text):
        if match.group(1).replace(" ", "") != RHEINFURT_IBAN.replace(" ", ""):
            violations.append(f"{label}: fremde IBAN {match.group()!r}")

    for match in _BIC_LABEL_RE.finditer(text):
        if match.group(1) != RHEINFURT_BIC:
            violations.append(f"{label}: fremde BIC {match.group()!r}")

    return violations


def validate_all(labeled_texts: list[tuple[str, str]]) -> None:
    """Raises SystemExit with every violation found across all inputs, or
    returns silently if the corpus is clean."""
    all_violations: list[str] = []
    for label, text in labeled_texts:
        all_violations.extend(collect_violations(label, text))

    if all_violations:
        details = "\n".join(f"  - {violation}" for violation in all_violations)
        raise SystemExit(
            f"Validation failed: {len(all_violations)} forbidden pattern(s) found in the "
            f"generated Rheinfurt corpus:\n{details}\n"
            "This generator run is aborted — a real Munich identifier (address, postal code, "
            "bank detail or forbidden token) survived the München→Rheinfurt transform. Fix the "
            "underlying replacement rule in rheinfurt_text.py (or the source string in "
            "satzungen.py/presse.py/intern.py) rather than the corpus output."
        )
