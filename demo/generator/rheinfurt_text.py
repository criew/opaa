"""Deterministic München → Rheinfurt text transform for LHM service descriptions.

Turns one raw LHM-Dienstleistungen-Corpus `.txt` file into Rheinfurt-branded
content: place names, authority names, street/district names, postal codes,
bank details, e-mail domains, phone numbers and external URLs are rewritten,
fees are scaled by a per-document deterministic factor, and a synthetic
Aktenzeichen/Formularnummer footer is appended. Nothing here uses `random` or
wall-clock time — every value is derived from the source filename so two runs
produce byte-identical output.

The Munich-specific "Anlaufstellen in Ihrer Nähe" / "Links & Downloads"
sections (real street addresses, GIS widgets, muenchen.de download links) are
dropped entirely rather than rewritten — they carry no service-description
content and rewriting them plausibly would need real Rheinfurt geodata this
project does not have.

Decision on real federal authorities (PR #717 review, WICHTIG 3): named
mentions of real Bundesbehörden (Kraftfahrt-Bundesamt, Bundesdruckerei,
Bundesamt für Justiz, Bundeszentralamt für Steuern, ...) are kept — a
fictional municipality realistically still deals with real federal
authorities for federally regulated matters. Only their URLs, postal
addresses and bank/account data are stripped, never their names. Documented
in demo/corpus/SOURCE.md.
"""

from __future__ import annotations

import hashlib
import re

CUT_MARKER = "Links & Downloads"

# --- Fictional Rheinfurt reference values -----------------------------------
#
# Deliberately NOT a plausible-looking real German value: a real Postleitzahl
# range is 01001-99998 and a real Vorwahl is densely allocated across the
# whole country, so any "realistic-looking" fictional value risks colliding
# with (or being mistaken for) a real one. These use recognizably synthetic,
# repeated-digit patterns instead (PR #717 review, KLEIN a).
RHEINFURT_PLZ = "00000"
RHEINFURT_VORWAHL = "01234"

# A fictional but check-digit-valid IBAN (ISO 7064 mod-97-10, verified) and an
# obviously invented BIC — replaces the real Stadtsparkasse München bank
# details that were carried over unchanged from the LHM source (PR #717
# review, WICHTIG 1). Recomputing/verifying the checksum:
#   BBAN = "88888888" (BLZ) + "8888888888" (Konto); DE + "00" + BBAN,
#   numeric remainder mod 97 -> check digits "58".
RHEINFURT_IBAN = "DE58 8888 8888 8888 8888 88"
RHEINFURT_BIC = "SPRHDEXX"

# --- Place name and authority rewriting -------------------------------------
#
# Order matters where one pattern is a prefix of another (checked explicitly
# below); "Münchner"/"münchner" is not a substring of "München"/"münchen" (the
# two words diverge after the shared "münch" stem), so those two groups may
# run in either order.
_REPLACEMENTS: list[tuple[re.Pattern[str], str]] = [
    # "Stadtsparkasse" -> "Sparkasse" before the München->Rheinfurt rule below,
    # so "Stadtsparkasse München" becomes "Sparkasse Rheinfurt" rather than
    # keeping the Munich-specific "Stadtsparkasse" branding (PR #717 review,
    # WICHTIG 1).
    (re.compile(r"Stadtsparkasse"), "Sparkasse"),
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

# --- Real Munich streets and city districts (PR #717 review, WICHTIG 2) -----
#
# Every real Munich street name (with house number) and every real Munich
# Stadtbezirk/district name found in the 83 selected source files, mapped to
# a fictional Rheinfurt equivalent. House numbers are kept unchanged — they
# are generic small integers, not identifying on their own once the street
# name itself is fictional. This is also the whitelist source for
# generate_corpus.py's final validation pass: only street names that appear
# as *values* of this mapping (plus the small set of place names already
# used elsewhere in the generator, see ALLOWED_STREET_NAMES) may legitimately
# appear before a house number in the generated corpus.
_STREET_AND_DISTRICT_REPLACEMENTS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"Ruppertstraße"), "Rheinauer Straße"),
    (re.compile(r"Rupperstraße"), "Rheinauer Straße"),  # source typo, same street
    (re.compile(r"Winzererstraße"), "Uferpromenade"),
    (re.compile(r"Maxburgstraße"), "Burgstraße"),
    (re.compile(r"Damenstiftstraße"), "Klosterstraße"),
    (re.compile(r"Schönstedtstraße"), "Gartenstraße"),
    (re.compile(r"Eichstätter Straße"), "Feldstraße"),
    (re.compile(r"Landsberger Straße"), "Uferstraße"),
    (re.compile(r"Marienplatz"), "Rathausplatz"),
    # City districts (Stadtbezirke). Individual tokens, not compound phrases,
    # because the source spells them out inconsistently ("Aubing –
    # Lochhausen – Langwied", "Aubing-Lochhausen-Langwied",
    # "Rheinfurt-Pasing", ...) — word-level replacement catches every form.
    (re.compile(r"Pasing"), "Rheinau"),
    (re.compile(r"Allach"), "Westend"),
    (re.compile(r"Untermenzing"), "Nordheim"),
    (re.compile(r"Obermenzing"), "Südheim"),
    (re.compile(r"Lochhausen"), "Altfeld"),
    (re.compile(r"Langwied"), "Feldflur"),
    (re.compile(r"Aubing"), "Wiesengrund"),
]

# Every fictional street/place name used anywhere in the generator (whether
# or not a house number ever follows it in the actual generated text) — the
# whitelist for generate_corpus.py's "Straße + Hausnummer" validation.
ALLOWED_STREET_NAMES: frozenset[str] = frozenset(
    {
        "Rathausplatz",
        "Domplatz",
        "Rheinpromenade",
        "Uferpromenade",
        "Marktstraße",
        "Bahnhofstraße",
        "Bahnhofsvorplatz",
        "Festplatz",
        "Seitengasse",
        "Rheinauer Straße",
        "Burgstraße",
        "Klosterstraße",
        "Gartenstraße",
        "Feldstraße",
        "Uferstraße",
        "Meldeweg",
        "Hauptverkehrsstraße",
        "Vorplatz",
        "Spielplatz",
        "Stellplatz",
        "Gehweg",
        "Radweg",
    }
)

# A handful of Beglaubigung ("certification") source documents mention, in
# passing, that fishing licences are certified by a different authority.
# docs/features/demo-instance.md requires that NO document anywhere in this
# corpus touches the topic "Fischereierlaubnis" (the demo script's
# deliberately unanswerable question), so this passing mention is scrubbed
# rather than merely rewritten to Rheinfurt.
_FISCHEREI_RE = re.compile(r"\s*(?:und\s+)?Fischereischeine?")

# --- Corona-era passages (PR #717 review, NIT 5) ----------------------------
#
# A few source documents carry pandemic-era restrictions ("Antrag nur im
# Bürgerbüro ... möglich, wegen Corona") that read as dated/wrong in a 2026
# demo corpus. Whole sentences are dropped rather than reworded, since the
# restriction itself (not just its cause) no longer applies.
_CORONA_RE = re.compile(r"[^.\n]*\b(?:Corona-Pandemie|Coronavirus|Covid-19)\b[^.\n]*\.")

# --- Dangling references into the dropped "Links & Downloads" section ------
#
# (PR #717 review, NIT 8). Matches short, self-contained sentences that end
# in a bare "hier" (optionally followed by up to three more words) right
# before the full stop — the pattern every actually-dangling reference in the
# source ("Diese finden Sie hier.", "... bekommen Sie hier alle
# Informationen.", "Das Formular können Sie hier herunterladen.") shares.
# Ordinary uses of "hier" as a locative adverb are *not* followed directly by
# a sentence end within three words (e.g. "... die hier wohnende Person ..."
# continues well past the three-word lookahead), so they are left alone.
_DANGLING_HIER_RE = re.compile(r"[^.\n]*\bhier\b(?:\s+\w+){0,3}\s*\.")
_DANGLING_LINK_PHRASE_RE = re.compile(r"\s*über den oben stehenden Link\b")
_DANGLING_FORMULARE_RE = re.compile(r"finden Sie unter „Formulare & Links \(als Download\)\.")
_DANGLING_REPLACEMENT = (
    " Nähere Informationen erhalten Sie im Bürgerbüro Rheinfurt oder unter "
    "buergerbuero@stadt-rheinfurt.example."
)

# --- Real external URLs (PR #717 review, WICHTIG 3) -------------------------
#
# Strips any link that points off the fictional stadt-rheinfurt.example
# domain (e.g. the real bzst.de deep link in "Wohnsitz anmelden oder
# ummelden.txt"). The authority's *name* (e.g. "Bundeszentralamt für
# Steuern") is real and stays — only URLs/addresses/account data of real
# authorities are removed, per the coordinator's decision documented in
# demo/corpus/SOURCE.md.
_LINK_INTRO_URL_RE = re.compile(r"\s*unter (?:folgendem |dem )?Link:?\s*https?://\S+")
_BARE_EXTERNAL_URL_RE = re.compile(r"https?://(?:(?!stadt-rheinfurt\.example)\S)+")

_EMAIL_RE = re.compile(r"([\w.\-]+)@muenchen\.de")
_DOMAIN_RE = re.compile(r"[\w\-]*muenchen\.de")
_PHONE_RE = re.compile(r"089[/\s]\d(?:[\d\-\s]*\d)?")
_FEE_RE = re.compile(r"(\d{1,3}(?:\.\d{3})*)(?:,(\d{2}))?\s*(Euro|EUR|€)")

# --- Real bank details (PR #717 review, WICHTIG 1) --------------------------
_IBAN_RE = re.compile(r"IBAN:\s*[A-Z]{2}\d{2}(?:[\s]?\d{4}){3,5}[\s]?\d{0,4}")
_BIC_RE = re.compile(r"BIC:\s*[A-Z0-9]{8,11}")
_VERWENDUNGSZWECK_RE = re.compile(r"Verwendungszweck:\s*\d+")

# --- Postal codes (PR #717 review, WICHTIG 2 / KLEIN a) ---------------------
#
# Every real address in the selected source files follows the pattern
# "<PLZ> Rheinfurt" once München has already been rewritten to Rheinfurt
# above — so a single, narrowly scoped rule ("a 5-digit number immediately
# followed by the city name") normalizes every occurrence, including the
# generator's own contact footer, without risking a false match on an
# unrelated 5-digit number elsewhere in a document (Aktenzeichen and
# Formularnummer use different, dash-separated formats).
_PLZ_RE = re.compile(r"\b\d{5}(?=\s+Rheinfurt\b)")


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
    return f"{RHEINFURT_VORWAHL}/44-{extension:04d}"


def fee_scale_factor(filename: str) -> float:
    """Deterministic per-document scale factor in [0.85, 1.20)."""
    digest = hashlib.sha256(filename.encode("utf-8")).hexdigest()
    n = int(digest[:8], 16)
    return 0.85 + (n % 351) / 1000.0


def format_euro(value: float) -> str:
    cents = round(value * 100)
    euros, rest = divmod(cents, 100)
    euros_text = f"{euros:,}".replace(",", ".")  # German thousands separator
    if rest == 0:
        return f"{euros_text} Euro"
    return f"{euros_text},{rest:02d} Euro"


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
        euros = int(match.group(1).replace(".", ""))
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


def rewrite_streets_and_districts(text: str) -> str:
    for pattern, replacement in _STREET_AND_DISTRICT_REPLACEMENTS:
        text = pattern.sub(replacement, text)
    return text


def rewrite_contacts(text: str) -> str:
    text = _EMAIL_RE.sub(_replace_email, text)
    text = _DOMAIN_RE.sub("stadt-rheinfurt.example", text)
    text = _PHONE_RE.sub(_replace_phone, text)
    return text


def rewrite_bank_details(text: str) -> str:
    text = _IBAN_RE.sub(f"IBAN: {RHEINFURT_IBAN}", text)
    text = _BIC_RE.sub(f"BIC: {RHEINFURT_BIC}", text)
    text = _VERWENDUNGSZWECK_RE.sub("Verwendungszweck: Ihr Aktenzeichen (siehe oben)", text)
    return text


def rewrite_postal_codes(text: str) -> str:
    return _PLZ_RE.sub(RHEINFURT_PLZ, text)


def strip_external_links(text: str) -> str:
    text = _LINK_INTRO_URL_RE.sub(".", text)
    text = _BARE_EXTERNAL_URL_RE.sub("", text)
    # The sentence that follows a stripped link sometimes continues in
    # lowercase in the source (it originally read on from the URL itself,
    # e.g. "...node.html. damit ..."); capitalize it so the now-terminated
    # sentence reads correctly.
    text = re.sub(r"\.\s+damit\b", ". Damit", text)
    return text


def redact_corona(text: str) -> str:
    return _CORONA_RE.sub("", text)


def redact_dangling_links(text: str) -> str:
    text = _DANGLING_LINK_PHRASE_RE.sub("", text)
    text = _DANGLING_FORMULARE_RE.sub("erhalten Sie im Bürgerbüro Rheinfurt.", text)
    text = _DANGLING_HIER_RE.sub(_DANGLING_REPLACEMENT, text)
    return text


def redact_fischerei(text: str) -> str:
    return _FISCHEREI_RE.sub("", text)


def _collapse_whitespace(text: str) -> str:
    text = re.sub(r"[ \t]{2,}", " ", text)
    # A redacted sentence (Corona passage, dangling-link phrase) can leave a
    # leading space at the start of the following line — trim it per line
    # rather than globally, so intentional leading "- " bullet markers stay
    # untouched.
    text = re.sub(r"(?m)^[ \t]+", "", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def aktenzeichen(library_code: str, sequence: int, year: int = 2026) -> str:
    return f"AZ {library_code}-{year}-{sequence:04d}"


def formularnummer(library_code: str, sequence: int) -> str:
    return f"RF-{library_code}-{sequence:03d}"


def transform_service(raw_text: str, filename: str) -> tuple[str, str]:
    """Transform one raw LHM file into (title, body) for Rheinfurt.

    `body` excludes the title line (already split off) and the dropped
    Munich-specific closing sections; place names, authority names, street/
    district names, postal codes, bank details, contact details, external
    links and fee amounts are rewritten/scaled.
    """
    body = extract_body(raw_text)
    lines = body.split("\n", 1)
    title = lines[0].strip()
    remainder = lines[1] if len(lines) > 1 else ""

    factor = fee_scale_factor(filename)
    title = rewrite_place_names(title)
    title = rewrite_streets_and_districts(title)
    title = rewrite_contacts(title)

    remainder = redact_corona(remainder)
    remainder = rewrite_place_names(remainder)
    remainder = rewrite_streets_and_districts(remainder)
    remainder = strip_external_links(remainder)
    remainder = redact_dangling_links(remainder)
    remainder = rewrite_contacts(remainder)
    remainder = rewrite_bank_details(remainder)
    remainder = rewrite_postal_codes(remainder)
    remainder = redact_fischerei(remainder)
    remainder = _scale_fees(remainder, factor)
    remainder = _collapse_whitespace(remainder)

    return title, remainder.strip()
