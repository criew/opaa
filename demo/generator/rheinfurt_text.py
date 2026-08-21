"""Deterministic München → Rheinfurt text transform for LHM service descriptions.

Turns one raw LHM-Dienstleistungen-Corpus `.txt` file into Rheinfurt-branded
content: place names, authority names, e-mail domains and phone numbers are
rewritten, fees are scaled by a per-document deterministic factor, and a
synthetic Aktenzeichen/Formularnummer footer is appended. Nothing here uses
`random` or wall-clock time — every value is derived from the source filename
so two runs produce byte-identical output.

The Munich-specific "Anlaufstellen in Ihrer Nähe" / "Links & Downloads"
sections (real street addresses, GIS widgets, muenchen.de download links) are
dropped entirely rather than rewritten — they carry no service-description
content and rewriting them plausibly would need real Rheinfurt geodata this
project does not have.
"""

from __future__ import annotations

import hashlib
import re

CUT_MARKER = "Links & Downloads"

# --- Place name and authority rewriting -------------------------------------
#
# Order matters where one pattern is a prefix of another (checked explicitly
# below); "Münchner"/"münchner" is not a substring of "München"/"münchen" (the
# two words diverge after the shared "münch" stem), so those two groups may
# run in either order.
_REPLACEMENTS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"Landeshauptstadt"), "Stadt"),
    (re.compile(r"Kreisverwaltungsreferat \(KVR\)"), "Bürgerbüro Rheinfurt"),
    (re.compile(r"Kreisverwaltungsreferat"), "Bürgerbüro Rheinfurt"),
    # Specific compounds before the generic \bKVR\b rule below, so
    # "KVR-Bürgerbüros" doesn't turn into the redundant "Bürgerbüro-Bürgerbüros".
    (re.compile(r"KVR-Bürgerbüros"), "Bürgerbüros"),
    (re.compile(r"KVR-Bürgerbüro\b"), "Bürgerbüro"),
    (re.compile(r"\bKVR\b"), "Bürgerbüro"),
    # Munich's vehicle registration plate prefix letter ("M"), quoted inline
    # in the Wunschkennzeichen source text — rewritten to Rheinfurt's
    # fictional prefix so no Munich-identifying detail survives.
    (re.compile(r"„M“"), "„RF“"),
    (re.compile(r"Münchner"), "Rheinfurter"),
    (re.compile(r"münchner"), "rheinfurter"),
    (re.compile(r"München"), "Rheinfurt"),
    (re.compile(r"münchen"), "rheinfurt"),
]

# A handful of Beglaubigung ("certification") source documents mention, in
# passing, that fishing licences are certified by a different authority.
# docs/features/demo-instance.md requires that NO document anywhere in this
# corpus touches the topic "Fischereierlaubnis" (the demo script's
# deliberately unanswerable question), so this passing mention is scrubbed
# rather than merely rewritten to Rheinfurt.
_FISCHEREI_RE = re.compile(r"\s*(?:und\s+)?Fischereischeine?")

_EMAIL_RE = re.compile(r"([\w.\-]+)@muenchen\.de")
_DOMAIN_RE = re.compile(r"[\w\-]*muenchen\.de")
_PHONE_RE = re.compile(r"089[/\s]\d(?:[\d\-\s]*\d)?")
_FEE_RE = re.compile(r"(\d+)(?:,(\d{2}))?\s*(Euro|EUR|€)")


def _replace_email(match: re.Match[str]) -> str:
    local = match.group(1)
    local = re.sub(r"\.?kvr\.?", ".", local, flags=re.IGNORECASE).strip(".")
    if not local:
        local = "buergerbuero"
    return f"{local}@stadt-rheinfurt.example"


def _replace_phone(match: re.Match[str]) -> str:
    # Deterministic per distinct original number: the same Munich number
    # always maps to the same fictional Rheinfurt number, which keeps
    # repeated references (e.g. the same service hotline cited from several
    # documents) internally consistent.
    digest = hashlib.sha256(match.group(0).encode("utf-8")).hexdigest()
    extension = int(digest[:8], 16) % 10000
    return f"02351/44-{extension:04d}"


def fee_scale_factor(filename: str) -> float:
    """Deterministic per-document scale factor in [0.85, 1.20)."""
    digest = hashlib.sha256(filename.encode("utf-8")).hexdigest()
    n = int(digest[:8], 16)
    return 0.85 + (n % 351) / 1000.0


def format_euro(value: float) -> str:
    cents = round(value * 100)
    euros, rest = divmod(cents, 100)
    if rest == 0:
        return f"{euros} Euro"
    return f"{euros},{rest:02d} Euro"


def scale_and_format_fee(base_euro: float, filename: str) -> str:
    """Scale a known base fee (as extracted from the raw LHM source) by the
    same per-document factor `transform_service` uses, so a fee quoted both
    in a Leistungen document and in a Satzungen PDF (e.g. the
    Verwaltungsgebührensatzung's Gebührenverzeichnis) shows the identical
    number."""
    factor = fee_scale_factor(filename)
    scaled = round(base_euro * factor * 10) / 10  # nearest 0.10
    return format_euro(scaled)


def _scale_fees(text: str, factor: float) -> str:
    def replace(match: re.Match[str]) -> str:
        euros = int(match.group(1))
        cents = int(match.group(2)) if match.group(2) else 0
        value = euros + cents / 100
        scaled = round(value * factor * 10) / 10  # nearest 0.10
        return format_euro(scaled)

    return _FEE_RE.sub(replace, text)


def extract_body(raw_text: str) -> str:
    """Return the raw text up to (excluding) the Munich-specific
    "Links & Downloads" / "Anlaufstellen" boilerplate that closes every file
    in the source corpus."""
    return raw_text.split(CUT_MARKER, 1)[0].rstrip()


def rewrite_place_names(text: str) -> str:
    for pattern, replacement in _REPLACEMENTS:
        text = pattern.sub(replacement, text)
    return text


def rewrite_contacts(text: str) -> str:
    text = _EMAIL_RE.sub(_replace_email, text)
    text = _DOMAIN_RE.sub("stadt-rheinfurt.example", text)
    text = _PHONE_RE.sub(_replace_phone, text)
    return text


def redact_fischerei(text: str) -> str:
    return _FISCHEREI_RE.sub("", text)


def aktenzeichen(library_code: str, sequence: int, year: int = 2026) -> str:
    return f"AZ {library_code}-{year}-{sequence:04d}"


def formularnummer(library_code: str, sequence: int) -> str:
    return f"RF-{library_code}-{sequence:03d}"


def transform_service(raw_text: str, filename: str) -> tuple[str, str]:
    """Transform one raw LHM file into (title, body) for Rheinfurt.

    `body` excludes the title line (already split off) and the dropped
    Munich-specific closing sections; place names, authority names, contact
    details and fee amounts are rewritten/scaled.
    """
    body = extract_body(raw_text)
    lines = body.split("\n", 1)
    title = lines[0].strip()
    remainder = lines[1] if len(lines) > 1 else ""

    factor = fee_scale_factor(filename)
    title = rewrite_place_names(title)
    title = rewrite_contacts(title)
    remainder = rewrite_place_names(remainder)
    remainder = rewrite_contacts(remainder)
    remainder = redact_fischerei(remainder)
    remainder = _scale_fees(remainder, factor)

    return title, remainder.strip()
