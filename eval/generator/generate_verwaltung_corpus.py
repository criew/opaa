#!/usr/bin/env python3
"""Deterministic generator for the "verwaltung" evaluation corpus (issue #1042).

Emits a fully synthetic, German-language corpus of administrative documents (Satzungen,
Gebührenordnungen, Dienstanweisungen, Formularhinweise, sowie zwei organisationsweite
Dokumente) for the fictional municipality "Kalkstadt" — deliberately a different, invented
place than the demo corpus's "Rheinfurt" (`demo/corpus/`), so a cosmetic demo-document
improvement never forces a re-freeze of this measurement corpus and vice versa (see
docs/features/retrieval-benchmark.md, Abschnitt 4 "Verwaltungs-Evaldomäne", "Getrennt von der
Demo").

Design goals (see docs/features/retrieval-benchmark.md, Abschnitt 4):

- Deutschsprachig, Amtssprache, mit einem bewussten Registerunterschied zur Bürgersprache
  (jedes Dokument mit einer Gebührenbefreiungsregel formuliert die Alltagsfrage aus, die ein
  Bürger dazu stellen würde).
- Mehrchunkig: jedes Dokument ist auf eine Mindestlänge angelegt, die bei der produktiven
  Chunking-Konfiguration (`chunkSize=1000`) mindestens 3 Chunks ergibt (siehe
  `VerwaltungChunkSizeDryRunTest`, docker-freier Nachweis).
- Enthält konstruktiv die Fehlerbilder aus Abschnitt 5 der Spezifikation: eine wörtlich
  auffindbare, aber embeddingschwache Passage (`§ 3 Gebührenbefreiung wegen Bedürftigkeit`,
  eingebettet in ein Dokument, dessen Gesamtthema woanders liegt) — bewusst nur bei einem
  einzigen Amt (Kämmerei, `Amt.traegt_gebuehrenbefreiung`), umgeben von neun thematisch nahen,
  aber begriffsfrei formulierten Konkurrenzdokumenten (`paragraph_auskunftsrecht`); wäre der
  Begriff in allen zehn Ämtern vorhanden, läge seine Dokumenthäufigkeit bei 100 % und der Fall
  wäre nicht mehr konstruierbar (siehe `eval/corpus/verwaltung/SOURCE.md`, "Begriffs- und
  Kennungshäufigkeit im Korpus"); konfusionsfähige Kennungen (Aktenzeichen mit benachbarten
  Nummern/Jahren, § 3 vs. § 13, über alle zehn Ämter hinweg erhalten);
  Komposita (Satzungstitel wie "Personalausweisgebührensatzung"); eine organisationsweite
  Vertretungsregelung, die eine Mehrfach-Dokument-Kette für Multi-Hop-Fragen trägt; und
  Fassungspaare (identische Satzung/Dienstanweisung in zwei Ständen) für Metadaten-Filterfragen.
- Vollständig synthetisch, keine Fremdquelle — es gibt daher keinen Rohdaten-Snapshot zu
  verifizieren (anders als bei `comic-characters`/`city-landmarks`); Determinismus folgt allein
  aus der festen Iterationsreihenfolge über `AEMTER` und den festen Textbausteinen unten.
- Deterministisch, keine Zeitstempel, keine Zufallsquelle: zwei Läufe erzeugen byte-identische
  Ausgabe.

Usage:
    python eval/generator/generate_verwaltung_corpus.py

See eval/generator/README.md and eval/corpus/verwaltung/MAINTENANCE.md.
"""

from __future__ import annotations

import hashlib
import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = REPO_ROOT / "eval" / "corpus" / "verwaltung"
DOMAIN = "verwaltung"
GEMEINDE = "Kalkstadt"
SOURCE_VALUE = "synthetic/opaa-eval-verwaltung"
LICENSE_VALUE = "CC0-1.0"
VERTRETUNGSREGELUNG_FILENAME = "verwaltung-vertretungsregelung.md"
GESCHAEFTSVERTEILUNGSPLAN_FILENAME = "verwaltung-geschaeftsverteilungsplan.md"


# --- Fachbereiche (Ämter) --------------------------------------------------------------


@dataclass(frozen=True)
class Amt:
    kuerzel: str
    name: str
    artikel: str  # "der" | "die" | "das"
    satzungstitel: str
    gebuehr_gegenstand: str
    hauptleistung: str
    alltagsfrage: str
    kennung_praefix: str
    # True for exactly one Amt (Kämmerei/KAE, see AEMTER below): only this Amt's documents carry
    # the literal "Gebührenbefreiung wegen Bedürftigkeit" passage (Abschnitt 5a der Spezifikation,
    # the #938-class). Code-Review-Befund (PR #1074): mit dem Begriff in allen zehn Ämtern hatte
    # jedes Dokument dieselbe IDF ≈ 0 und der Fall war nicht mehr konstruierbar — die Spezifikation
    # verlangt einen seltenen, wörtlich auffindbaren Begriff in einem Dokument, umgeben von
    # thematisch nahen, aber sachlich falschen Konkurrenzdokumenten ohne den Begriff.
    traegt_gebuehrenbefreiung: bool = False

    @property
    def amt_mit_artikel(self) -> str:
        return f"{self.artikel} {self.name}"

    @property
    def amt_mit_artikel_grossgeschrieben(self) -> str:
        # str.capitalize() would lowercase "Sozialamt" -> "sozialamt"; only the leading
        # article's first letter needs uppercasing for a sentence-initial position.
        text = self.amt_mit_artikel
        return text[0].upper() + text[1:]

    @property
    def genitiv(self) -> str:
        # Attributive genitive ("Sachbearbeitung des Sozialamts") — by far the most common case
        # in administrative prose ("NOUN of the Amt"), used throughout the templates below for
        # every unprefixed possessive construction. `die Kämmerei` (feminine, the one exception
        # among AEMTER) takes no genitive -s suffix, unlike the neuter/masculine `das`-Ämter.
        if self.artikel == "die":
            return f"der {self.name}"
        return f"des {self.name}s"

    @property
    def dativ(self) -> str:
        # Dative ("bei dem Sozialamt", "von dem Sozialamt") — used only where a preposition or
        # verb governing the dative case (bei, von, entstehen jemandem, stehen jemandem frei)
        # requires it; the far more common attributive genitive is `genitiv` above.
        if self.artikel == "die":
            return f"der {self.name}"
        return f"dem {self.name}"


AEMTER: list[Amt] = [
    Amt(
        "SOZ",
        "Sozialamt",
        "das",
        "Sozialgebührenbefreiungssatzung",
        "Bescheinigungen und Leistungen der sozialen Unterstützung",
        "Sozialleistungsbescheinigung",
        "Muss ich für die Bescheinigung trotzdem zahlen, wenn ich schon Bürgergeld bekomme?",
        "SOZ",
    ),
    Amt(
        "BAU",
        "Bauamt",
        "das",
        "Baugenehmigungsgebührensatzung",
        "die Erteilung von Baugenehmigungen",
        "Baugenehmigung",
        "Muss ich für den Umbau meiner Garage wirklich eine Gebühr bezahlen?",
        "BAU",
    ),
    Amt(
        "ORD",
        "Ordnungsamt",
        "das",
        "Gewerbeanmeldegebührensatzung",
        "die Anmeldung eines Gewerbes",
        "Gewerbeanmeldung",
        "Kostet die Anmeldung meines kleinen Nebengewerbes wirklich extra?",
        "ORD",
    ),
    Amt(
        "STA",
        "Standesamt",
        "das",
        "Personenstandsurkundengebührensatzung",
        "die Ausstellung von Personenstandsurkunden",
        "Personenstandsurkunde",
        "Muss ich für die Geburtsurkunde meines Kindes zahlen, wenn ich wenig verdiene?",
        "STA",
    ),
    Amt(
        "BUE",
        "Bürgeramt",
        "das",
        "Personalausweisgebührensatzung",
        "die Ausstellung eines Personalausweises",
        "Personalausweis",
        "Muss ich das bezahlen, wenn ich Bürgergeld bekomme?",
        "BUE",
    ),
    Amt(
        "KAE",
        "Kämmerei",
        "die",
        "Verwaltungsgebührensatzung",
        "allgemeine Amtshandlungen der Stadtverwaltung",
        "allgemeine Amtshandlung",
        # Matches wörtlich den in der Spezifikation zitierten belegten Produktionsfall (#938):
        # "§ 3 der Verwaltungsgebührensatzung enthält 'Befreiung' und 'Bedürftigkeit' im Klartext".
        "Muss ich das bezahlen, wenn ich Bürgergeld bekomme?",
        "KAE",
        traegt_gebuehrenbefreiung=True,
    ),
    Amt(
        "PER",
        "Personalamt",
        "das",
        "Personalaktenauskunftsgebührensatzung",
        "die Einsichtnahme in Personalakten ehemaliger Beschäftigter",
        "Personalaktenauskunft",
        "Muss ich als ehemalige Mitarbeiterin für eine Kopie meiner Personalakte bezahlen?",
        "PER",
    ),
    Amt(
        "JUG",
        "Jugendamt",
        "das",
        "Kindertagesstättenbeitragssatzung",
        "die Betreuung in städtischen Kindertagesstätten",
        "Kindertagesstättenplatz",
        "Muss ich den Kita-Beitrag zahlen, wenn ich Bürgergeld bekomme?",
        "JUG",
    ),
    Amt(
        "UMW",
        "Umweltamt",
        "das",
        "Abfallgebührensatzung",
        "die Entsorgung von Haushaltsabfällen",
        "Abfallentsorgung",
        "Muss ich die Abfallgebühr auch als Rentnerin mit kleiner Rente voll bezahlen?",
        "UMW",
    ),
    Amt(
        "KUL",
        "Kulturamt",
        "das",
        "Bibliotheksbenutzungsgebührensatzung",
        "die Benutzung der Stadtbibliothek",
        "Bibliotheksausweis",
        "Muss ich als Schüler für den Bibliotheksausweis bezahlen?",
        "KUL",
    ),
]

AEMTER_BY_KUERZEL: dict[str, Amt] = {amt.kuerzel: amt for amt in AEMTER}

# Ämter, deren Satzung als Fassungspaar (2023 abgelöst durch 2024) angelegt wird — genug, um
# die metadata_filter-Fallklasse (Abschnitt 5e der Spezifikation) mit mehreren unabhängigen
# Fassungspaaren zu bedienen, ohne jede Satzung zu verdoppeln.
FASSUNGSPAAR_KUERZEL = {"SOZ", "BAU", "ORD", "BUE", "JUG"}

# Ämter, deren erste Dienstanweisung zusätzlich als Fassungspaar (2023 -> 2024) angelegt wird —
# liefert konfusionsfähige Aktenzeichen über zwei Jahre hinweg (exact_identifier-Fallklasse)
# unabhängig von den Satzungs-Fassungspaaren.
DIENSTANWEISUNG_FASSUNGSPAAR_KUERZEL = {"SOZ", "BAU", "ORD"}


# --- YAML-Emission (dieselbe abhängigkeitsfreie Vorgehensweise wie in generate_corpus.py und
#     generate_city_landmarks_corpus.py) ------------------------------------------------------


def yaml_scalar(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    text = str(value).replace("\\", "\\\\").replace('"', '\\"')
    return f'"{text}"'


def yaml_sequence(items: list[str]) -> str:
    if not items:
        return "[]"
    return "[" + ", ".join(yaml_scalar(item) for item in items) + "]"


FRONTMATTER_FIELDS = [
    "id",
    "domain",
    "dokumentart",
    "titel",
    "amt",
    "amt_kuerzel",
    "aktenzeichen",
    "fassung",
    "stand_datum",
    "gueltig_ab",
    "gueltig_bis",
    "ersetzt",
    "ersetzt_durch",
    "schlagworte",
    "source",
    "license",
]


def render_frontmatter(fields: dict) -> str:
    lines = ["---"]
    for key in FRONTMATTER_FIELDS:
        value = fields[key]
        if key == "schlagworte":
            lines.append(f"{key}: {yaml_sequence(value)}")
        else:
            lines.append(f"{key}: {yaml_scalar(value)}")
    lines.append("---")
    return "\n".join(lines)


def slugify(name: str) -> str:
    transliterated = (
        name.replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("Ä", "Ae")
        .replace("Ö", "Oe")
        .replace("Ü", "Ue")
        .replace("ß", "ss")
    )
    normalized = unicodedata.normalize("NFKD", transliterated)
    ascii_only = "".join(ch for ch in normalized if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]+", "-", ascii_only.lower()).strip("-")


# --- Dokumentmodell ---------------------------------------------------------------------


@dataclass
class GeneratedDocument:
    doc_id: str
    filename: str
    dokumentart: str
    titel: str
    amt: Amt | None
    aktenzeichen: str
    fassung: int
    stand_datum: str
    gueltig_ab: str
    gueltig_bis: str | None
    ersetzt: str | None
    ersetzt_durch: str | None
    schlagworte: list[str]
    body: str

    def render(self) -> bytes:
        frontmatter = render_frontmatter(
            {
                "id": self.doc_id,
                "domain": DOMAIN,
                "dokumentart": self.dokumentart,
                "titel": self.titel,
                "amt": self.amt.name if self.amt else None,
                "amt_kuerzel": self.amt.kuerzel if self.amt else None,
                "aktenzeichen": self.aktenzeichen,
                "fassung": self.fassung,
                "stand_datum": self.stand_datum,
                "gueltig_ab": self.gueltig_ab,
                "gueltig_bis": self.gueltig_bis,
                "ersetzt": self.ersetzt,
                "ersetzt_durch": self.ersetzt_durch,
                "schlagworte": self.schlagworte,
                "source": SOURCE_VALUE,
                "license": LICENSE_VALUE,
            }
        )
        return (frontmatter + "\n\n" + self.body.strip() + "\n").encode("utf-8")


class IdAllocator:
    """Sequential, deterministic `verwaltung-NNNN` ids in generation order (issue #1042)."""

    def __init__(self) -> None:
        self._next = 1

    def next_id(self) -> str:
        doc_id = f"verwaltung-{self._next:04d}"
        self._next += 1
        return doc_id


# --- Satzung: gemeinsame §§-Bausteine ----------------------------------------------------

# § 3 und § 13 sind über *alle* Satzungen hinweg an denselben Nummern verankert, aber mit
# unterschiedlichem Regelungsgegenstand — das ist die Kennungs-Verwechslungsgefahr, die die
# exact_identifier-Fallklasse (Abschnitt 5b der Spezifikation) braucht: "§ 3" trifft ohne
# Amtsbezug auf zehn verschiedene Dokumente, nur eines davon behandelt für eine konkrete
# Gebühr tatsächlich eine Befreiung. Der wörtlich auffindbare, aber embeddingschwache Begriff
# aus dem #938-Fall (Abschnitt 5a: "Befreiung", "Bedürftigkeit") steht dagegen **nur** in
# einem einzigen Dokument (Kämmerei/`Verwaltungsgebührensatzung`, `Amt.traegt_gebuehrenbefreiung`)
# — die übrigen neun Satzungen haben an derselben Stelle (§ 3) einen anderen, thematisch nahen,
# aber für den Begriff sachlich falschen Regelungsgegenstand (`paragraph_auskunftsrecht`). Ohne
# diese Seltenheit wäre der Begriff in jedem der 70 Dokumente auffindbar (IDF ≈ 0) und der Fall
# nicht konstruierbar (Code-Review-Befund, PR #1074).


def paragraph_geltungsbereich(amt: Amt) -> str:
    return (
        "§ 1 Geltungsbereich und Zweck\n\n"
        f"(1) Diese Satzung regelt die Erhebung von Gebühren durch {amt.amt_mit_artikel} der Stadt "
        f"{GEMEINDE} für {amt.gebuehr_gegenstand}. Sie gilt für alle natürlichen und juristischen "
        f"Personen, die eine entsprechende Amtshandlung bei {amt.dativ} beantragen oder in "
        "Anspruch nehmen, unabhängig von ihrem Wohnsitz.\n\n"
        f"(2) Zweck dieser Satzung ist es, die Kosten, die {amt.dativ} durch die Bearbeitung "
        f"von Anträgen und die Erbringung von Amtshandlungen entstehen, angemessen auf die "
        "Antragstellenden umzulegen, ohne dabei die Erreichbarkeit der Verwaltungsleistung für "
        f"Personen mit geringem Einkommen zu gefährden. Näheres hierzu regelt § 3 dieser Satzung.\n\n"
        f"(3) Diese Satzung tritt an die Stelle etwaiger früherer Regelungen {amt.genitiv} "
        f"für {amt.gebuehr_gegenstand} und wird vom Stadtrat der Stadt {GEMEINDE} beschlossen."
    )


def paragraph_begriffsbestimmungen(amt: Amt) -> str:
    return (
        "§ 2 Begriffsbestimmungen\n\n"
        "(1) Antragstellende Person im Sinne dieser Satzung ist, wer eine Amtshandlung "
        f"bei {amt.dativ} beantragt, unabhängig vom Ausgang des Verfahrens.\n\n"
        "(2) Gebührenschuldnerin oder Gebührenschuldner ist, wer die Amtshandlung veranlasst hat "
        "oder in wessen Interesse sie vorgenommen wurde. Bei mehreren Personen haften diese als "
        "Gesamtschuldnerinnen und Gesamtschuldner.\n\n"
        f"(3) Amtshandlung im Sinne dieser Satzung ist jede Tätigkeit {amt.genitiv}, die auf "
        f"Antrag oder von Amts wegen für {amt.gebuehr_gegenstand} vorgenommen wird, "
        "einschließlich Auskünften, Bescheinigungen und Bescheiden."
    )


def paragraph_gebuehrenbefreiung(amt: Amt) -> str:
    return (
        "§ 3 Gebührenbefreiung wegen Bedürftigkeit\n\n"
        "(1) Von der Erhebung der in dieser Satzung festgelegten Gebühren für "
        f"{amt.gebuehr_gegenstand} wird auf schriftlichen Antrag ganz oder teilweise befreit, wer "
        "nachweislich bedürftig im Sinne des Zwölften Buches Sozialgesetzbuch (SGB XII) ist oder "
        "laufende Leistungen nach dem Zweiten Buch Sozialgesetzbuch (SGB II, umgangssprachlich "
        "Bürgergeld) bezieht. Die Bedürftigkeit ist durch Vorlage des aktuellen Leistungsbescheids "
        "der zuständigen Behörde nachzuweisen.\n\n"
        "(2) Im Alltag wird diese Regelung häufig als die Frage formuliert: "
        f'"{amt.alltagsfrage}" Die Antwort {amt.genitiv} lautet in diesen Fällen: ja, eine '
        "vollständige oder anteilige Befreiung von der Gebühr ist möglich, sofern der Nachweis der "
        "Bedürftigkeit erbracht wird.\n\n"
        f"(3) Der Antrag auf Befreiung ist vor Fälligkeit der Gebühr bei {amt.dativ} der "
        f"Stadt {GEMEINDE} einzureichen. Über den Antrag entscheidet die zuständige Sachbearbeitung "
        f"{amt.genitiv}; ist diese Sachbearbeitung nicht erreichbar, etwa wegen Urlaub oder "
        "Krankheit, richtet sich die Zuständigkeit nach der Vertretungsregelung der "
        f"Stadtverwaltung {GEMEINDE} (siehe {VERTRETUNGSREGELUNG_FILENAME}).\n\n"
        "(4) Die Befreiung nach Absatz 1 ist von der Gebührenermäßigung für eingetragene Vereine "
        "nach § 13 dieser Satzung zu unterscheiden: Während § 3 die persönliche Bedürftigkeit einer "
        "natürlichen Person betrifft, regelt § 13 eine pauschale Ermäßigung für eingetragene, "
        "gemeinnützige Vereine unabhängig von deren wirtschaftlicher Lage. Beide Regelungen "
        "schließen sich nicht gegenseitig aus, begründen aber unterschiedliche "
        "Anspruchsvoraussetzungen und dürfen nicht verwechselt werden."
    )


def paragraph_auskunftsrecht(amt: Amt) -> str:
    """§ 3 für alle Ämter außer der Kämmerei (`amt.traegt_gebuehrenbefreiung`): dieselbe
    Paragraphennummer wie `paragraph_gebuehrenbefreiung`, aber ein anderer Regelungsgegenstand
    ohne die Begriffe "Bedürftigkeit"/"Befreiung"/"Bürgergeld" — der thematisch nahe, aber für
    den #938-Fall (Abschnitt 5a der Spezifikation) sachlich falsche Konkurrenzparagraph.
    """
    return (
        "§ 3 Auskunftsrecht und Akteneinsicht\n\n"
        "(1) Antragstellende haben das Recht, jederzeit über den Bearbeitungsstand ihres "
        f"Vorgangs bei {amt.dativ} Auskunft zu erhalten. Die Auskunft wird formlos, in der Regel "
        "telefonisch oder per E-Mail, innerhalb von drei Werktagen erteilt.\n\n"
        "(2) Auf schriftlichen Antrag kann Einsicht in die eigene Vorgangsakte gewährt werden, "
        "soweit dem keine schutzwürdigen Interessen Dritter entgegenstehen. Über den Antrag "
        f"entscheidet die zuständige Sachbearbeitung {amt.genitiv}; ist diese Sachbearbeitung "
        "nicht erreichbar, etwa wegen Urlaub oder Krankheit, richtet sich die Zuständigkeit nach "
        f"der Vertretungsregelung der Stadtverwaltung {GEMEINDE} (siehe "
        f"{VERTRETUNGSREGELUNG_FILENAME}).\n\n"
        "(3) Das Auskunftsrecht nach Absatz 1 ist von der Gebührenermäßigung für eingetragene "
        "Vereine nach § 13 dieser Satzung zu unterscheiden: Während § 3 den Zugang zu "
        "Informationen über den eigenen Vorgang betrifft, regelt § 13 eine pauschale Ermäßigung "
        "für eingetragene, gemeinnützige Vereine unabhängig von einer Auskunftsanfrage. Beide "
        "Regelungen stehen unabhängig nebeneinander und dürfen nicht verwechselt werden."
    )


def paragraph_gebuehrenschuldner(amt: Amt) -> str:
    return (
        "§ 4 Gebührenschuldnerschaft\n\n"
        f"(1) Zur Zahlung der Gebühr für {amt.gebuehr_gegenstand} ist verpflichtet, wer die "
        f"Amtshandlung {amt.genitiv} veranlasst oder zu wessen Gunsten sie vorgenommen wird.\n\n"
        "(2) Mehrere Gebührenschuldnerinnen und Gebührenschuldner haften als Gesamtschuldnerinnen "
        f"und Gesamtschuldner. Es steht {amt.dativ} frei, sich an eine "
        "beliebige der haftenden Personen zu wenden."
    )


def paragraph_gebuehrenhoehe(amt: Amt, betraege: list[tuple[str, int]]) -> str:
    zeilen = "; ".join(f"{bezeichnung}: {betrag},00 Euro" for bezeichnung, betrag in betraege)
    return (
        "§ 5 Höhe der Gebühren\n\n"
        f"(1) Die Gebühren für {amt.gebuehr_gegenstand} bemessen sich nach dem in der zugehörigen "
        f"Gebührenordnung {amt.genitiv} festgelegten Gebührenverzeichnis. Zur Übersicht "
        f"gelten für die wichtigsten Amtshandlungen {amt.genitiv} die folgenden Sätze: "
        f"{zeilen}.\n\n"
        "(2) Die Gebührenhöhe wird vom Stadtrat der Stadt "
        f"{GEMEINDE} festgesetzt und in regelmäßigen Abständen an die tatsächlichen "
        "Verwaltungskosten angepasst."
    )


def paragraph_faelligkeit(amt: Amt) -> str:
    return (
        "§ 6 Fälligkeit und Zahlungsweise\n\n"
        "(1) Die Gebühr wird mit Bekanntgabe des Gebührenbescheids fällig und ist innerhalb von "
        f"vier Wochen an die Stadtkasse der Stadt {GEMEINDE} zu entrichten.\n\n"
        "(2) Die Zahlung kann per Überweisung, per Lastschrifteinzug oder in bar am Schalter "
        f"{amt.genitiv} geleistet werden. Bei Ratenzahlung entscheidet die Kämmerei der "
        f"Stadt {GEMEINDE} über die Höhe und Zahl der Raten."
    )


def paragraph_mahnverfahren(amt: Amt) -> str:
    return (
        "§ 7 Mahnverfahren und Säumniszuschläge\n\n"
        "(1) Wird die Gebühr nicht fristgemäß entrichtet, wird eine Mahnung mit einer Nachfrist von "
        "zwei Wochen versandt. Bleibt auch diese Frist erfolglos, wird ein Säumniszuschlag von einem "
        "Prozent des rückständigen Betrags je angefangenem Monat erhoben.\n\n"
        f"(2) {amt.amt_mit_artikel_grossgeschrieben} kann die Vollstreckung der rückständigen Gebühr bei "
        f"der Vollstreckungsstelle der Stadt {GEMEINDE} beantragen, wenn auch nach Mahnung keine "
        "Zahlung erfolgt."
    )


def paragraph_verfahren(amt: Amt) -> str:
    return (
        "§ 8 Verfahren zur Antragstellung\n\n"
        f"(1) Der Antrag auf eine Amtshandlung {amt.genitiv} ist schriftlich, persönlich "
        f"oder über das Online-Formularportal der Stadt {GEMEINDE} zu stellen. Formularhinweise "
        f"{amt.genitiv} enthalten die im Einzelfall erforderlichen Nachweise.\n\n"
        f"(2) {amt.amt_mit_artikel_grossgeschrieben} prüft den Antrag auf Vollständigkeit und fordert "
        "fehlende Unterlagen innerhalb von zwei Wochen nach Antragseingang nach."
    )


def paragraph_zustaendigkeit(amt: Amt) -> str:
    return (
        "§ 9 Zuständigkeit und Bearbeitung\n\n"
        f"(1) Zuständig für die Bearbeitung von Anträgen auf {amt.gebuehr_gegenstand} ist die "
        f"Sachbearbeitung {amt.genitiv} der Stadt {GEMEINDE}. Die genaue Zuständigkeit "
        f"innerhalb {amt.genitiv} ergibt sich aus dem Geschäftsverteilungsplan der "
        f"Stadtverwaltung {GEMEINDE} (siehe {GESCHAEFTSVERTEILUNGSPLAN_FILENAME}).\n\n"
        "(2) Ist die zuständige Sachbearbeitung nicht erreichbar, gilt die Vertretungsregelung der "
        f"Stadtverwaltung {GEMEINDE} (siehe {VERTRETUNGSREGELUNG_FILENAME})."
    )


def paragraph_widerspruch(amt: Amt) -> str:
    return (
        "§ 10 Widerspruchsverfahren\n\n"
        f"(1) Gegen einen Gebührenbescheid {amt.genitiv} kann innerhalb eines Monats nach "
        f"Bekanntgabe schriftlich Widerspruch bei {amt.dativ} eingelegt werden.\n\n"
        f"(2) Über den Widerspruch entscheidet {amt.amt_mit_artikel}; hilft es dem Widerspruch nicht "
        "ab, wird der Vorgang der Widerspruchsstelle der Stadt "
        f"{GEMEINDE} zur abschließenden Entscheidung vorgelegt."
    )


def paragraph_datenschutz(amt: Amt) -> str:
    return (
        "§ 11 Datenschutz und Aktenführung\n\n"
        f"(1) Die im Rahmen des Verfahrens {amt.genitiv} erhobenen personenbezogenen Daten "
        "werden ausschließlich zum Zweck der Gebührenerhebung und -überwachung verarbeitet und nach "
        "Ablauf der gesetzlichen Aufbewahrungsfristen gelöscht.\n\n"
        f"(2) Die Vorgänge werden von {amt.dativ} in Papier- und elektronischer Form geführt "
        "und sind vor unbefugtem Zugriff zu schützen."
    )


def paragraph_haertefall(amt: Amt) -> str:
    if amt.traegt_gebuehrenbefreiung:
        einleitung = (
            "(1) In Fällen, die von § 3 nicht erfasst sind, aber eine vergleichbare "
            "wirtschaftliche Notlage begründen, kann "
        )
    else:
        # Für die neun Ämter ohne die #938-Bedürftigkeitsklausel (siehe paragraph_auskunftsrecht)
        # darf § 12 nicht mehr voraussetzen, dass § 3 eine Bedürftigkeitsprüfung regelt.
        einleitung = (
            "(1) In besonderen Härtefällen, die über das Auskunftsrecht nach § 3 und die "
            "Gebührenermäßigung für eingetragene Vereine nach § 13 hinausgehen, kann "
        )
    return (
        "§ 12 Sonderregelungen für Härtefälle\n\n"
        f"{einleitung}{amt.amt_mit_artikel} auf begründeten Antrag eine Stundung oder "
        "eine anteilige Gebührenermäßigung gewähren.\n\n"
        "(2) Über den Härtefallantrag entscheidet die Amtsleitung "
        f"{amt.genitiv}, nicht die für den Regelfall zuständige Sachbearbeitung."
    )


def paragraph_vereinsermaessigung(amt: Amt) -> str:
    if amt.traegt_gebuehrenbefreiung:
        abgrenzung = (
            "(3) Diese Ermäßigung ist unabhängig von der Gebührenbefreiung wegen Bedürftigkeit "
            "nach § 3 dieser Satzung zu beantragen; ein gleichzeitiger Anspruch aus § 3 und § 13 "
            "für dieselbe Amtshandlung besteht nicht, da § 3 natürliche Personen in einer "
            "persönlichen Notlage betrifft und § 13 juristische Personen in Vereinsform "
            "unabhängig von deren wirtschaftlicher Lage."
        )
    else:
        # Für die neun Ämter ohne die #938-Bedürftigkeitsklausel verweist § 13 stattdessen auf
        # das Auskunftsrecht aus paragraph_auskunftsrecht, nicht auf eine Bedürftigkeitsprüfung.
        abgrenzung = (
            "(3) Diese Ermäßigung ist unabhängig vom Auskunftsrecht nach § 3 dieser Satzung zu "
            "beantragen; ein gleichzeitiger Anspruch aus § 3 und § 13 für dieselbe Amtshandlung "
            "besteht nicht, da § 3 den Zugang zu Informationen über einen eigenen Vorgang "
            "betrifft und § 13 eine pauschale Gebührenermäßigung für Vereine regelt, unabhängig "
            "von einer Auskunftsanfrage."
        )
    return (
        "§ 13 Gebührenermäßigung für eingetragene Vereine\n\n"
        f"(1) Eingetragene, gemeinnützige Vereine mit Sitz in der Stadt {GEMEINDE} erhalten auf "
        f"schriftlichen Antrag eine Ermäßigung von 50 Prozent auf die nach dieser Satzung für "
        f"{amt.gebuehr_gegenstand} zu entrichtenden Gebühren.\n\n"
        "(2) Der Nachweis der Gemeinnützigkeit erfolgt durch Vorlage des aktuellen "
        "Freistellungsbescheids des Finanzamts. Die Ermäßigung gilt nicht rückwirkend für bereits "
        "bestandskräftig festgesetzte Gebühren.\n\n"
        f"{abgrenzung}"
    )


def paragraph_uebergang(amt: Amt, fassung: int, vorgaenger_fassung: int | None) -> str:
    if vorgaenger_fassung is not None:
        vorgaenger_satz = (
            f" Sie ersetzt die Fassung {vorgaenger_fassung} dieser Satzung, deren Regelungen für "
            f"vor dem Inkrafttreten entstandene Gebührenansprüche {amt.genitiv} "
            "weiterhin maßgeblich bleiben."
        )
    else:
        vorgaenger_satz = ""
    return (
        "§ 14 Übergangsregelung und Inkrafttreten\n\n"
        f"(1) Diese Satzung in der Fassung {fassung} tritt am 1. Januar {fassung} in Kraft."
        f"{vorgaenger_satz}\n\n"
        f"(2) Für vor dem Inkrafttreten gestellte, aber noch nicht abschließend bearbeitete Anträge "
        f"{amt.genitiv} gilt die zum Zeitpunkt der Antragstellung geltende Fassung dieser "
        "Satzung."
    )


def satzung_zusammenfassung(amt: Amt, fassung: int) -> str:
    if amt.traegt_gebuehrenbefreiung:
        paragraphen_hinweis = (
            "einschließlich der Gebührenbefreiung wegen Bedürftigkeit nach § 3, der "
            "Gebührenermäßigung für eingetragene Vereine nach § 13 sowie Verfahren, Zuständigkeit "
            "und Rechtsmittel"
        )
    else:
        paragraphen_hinweis = (
            "einschließlich des Auskunftsrechts nach § 3, der Gebührenermäßigung für "
            "eingetragene Vereine nach § 13 sowie Verfahren, Zuständigkeit und Rechtsmittel"
        )
    return (
        "## Zusammenfassung\n\n"
        f"Die {amt.satzungstitel} der Stadt {GEMEINDE} in der Fassung {fassung} regelt die "
        f"Gebühren {amt.genitiv} für {amt.gebuehr_gegenstand}, {paragraphen_hinweis}. Die "
        f"Zuständigkeit im Vertretungsfall regelt die Vertretungsregelung der Stadtverwaltung "
        f"{GEMEINDE} ({VERTRETUNGSREGELUNG_FILENAME})."
    )


def compute_fee(amt: Amt, index: int) -> int:
    """Deterministic, non-random fee schedule (no real fee data exists to draw from — see
    SOURCE.md, "Gebührenbeträge sind deterministisch, aber nicht real"). Two illustrative,
    plausibility-motivated factors, both fixed and reproducible: the fee rises with `index`
    (later `AKTIONEN` entries — e.g. "Eilbearbeitung", "Bearbeitung eines Widerspruchs" — are
    plausibly costlier than a routine "Erteilung"), and a per-Amt offset derived from the Amt's
    Kürzel gives each Amt a distinct, stable base level instead of all ten sharing one schedule.
    """
    return 12 + index * 5 + (sum(ord(c) for c in amt.kuerzel) % 17)


AKTIONEN = [
    "Erteilung",
    "Verlängerung",
    "Änderung",
    "Zweitausfertigung",
    "Rücknahme auf Antrag",
    "Bearbeitung eines Widerspruchs",
    "Eilbearbeitung",
    "postalische Zustellung des Bescheids",
]


def build_satzung_body(amt: Amt, fassung: int, vorgaenger_fassung: int | None) -> str:
    betraege = [
        (f"{aktion} ({amt.hauptleistung})", compute_fee(amt, index))
        for index, aktion in enumerate(AKTIONEN[:4], start=1)
    ]
    paragraph_3 = (
        paragraph_gebuehrenbefreiung(amt)
        if amt.traegt_gebuehrenbefreiung
        else paragraph_auskunftsrecht(amt)
    )
    paragraphen = [
        paragraph_geltungsbereich(amt),
        paragraph_begriffsbestimmungen(amt),
        paragraph_3,
        paragraph_gebuehrenschuldner(amt),
        paragraph_gebuehrenhoehe(amt, betraege),
        paragraph_faelligkeit(amt),
        paragraph_mahnverfahren(amt),
        paragraph_verfahren(amt),
        paragraph_zustaendigkeit(amt),
        paragraph_widerspruch(amt),
        paragraph_datenschutz(amt),
        paragraph_haertefall(amt),
        paragraph_vereinsermaessigung(amt),
        paragraph_uebergang(amt, fassung, vorgaenger_fassung),
    ]
    praeambel = (
        f"# {amt.satzungstitel}\n\n"
        f"Satzung {amt.genitiv} der Stadt {GEMEINDE} über die Erhebung von Gebühren für "
        f"{amt.gebuehr_gegenstand} (Fassung {fassung}), beschlossen vom Stadtrat der Stadt "
        f"{GEMEINDE} auf Grundlage des Kommunalabgabengesetzes."
    )
    return "\n\n".join([praeambel] + [f"## {p}" for p in paragraphen] + [satzung_zusammenfassung(amt, fassung)])


# --- Gebührenordnung ---------------------------------------------------------------------


def build_gebuehrenordnung_body(amt: Amt) -> str:
    praeambel = (
        f"# Gebührenordnung {amt.genitiv} der Stadt {GEMEINDE}\n\n"
        f"Diese Gebührenordnung konkretisiert die {amt.satzungstitel} der Stadt {GEMEINDE} und "
        f"legt das vollständige Gebührenverzeichnis {amt.genitiv} für "
        f"{amt.gebuehr_gegenstand} fest. Sie wird jährlich von der Kämmerei der Stadt {GEMEINDE} "
        "auf ihre Kostendeckung hin überprüft."
    )
    if amt.traegt_gebuehrenbefreiung:
        ausnahme_satz = (
            "unterliegt unverändert der Gebührenbefreiung wegen Bedürftigkeit nach § 3 und der "
            f"Gebührenermäßigung für eingetragene Vereine nach § 13 der {amt.satzungstitel}"
        )
    else:
        ausnahme_satz = (
            f"unterliegt unverändert den in § 3 und § 13 der {amt.satzungstitel} geregelten "
            "Ausnahmen"
        )
    positionen = []
    for index, aktion in enumerate(AKTIONEN, start=1):
        fee = compute_fee(amt, index)
        bezeichnung = f"{aktion} ({amt.hauptleistung})"
        positionen.append(
            f"### Gebührenposition {index}: {bezeichnung}\n\n"
            # German nominalizations (Erteilung, Verlängerung, ...) stay capitalized regardless
            # of sentence position, unlike English gerunds — no .lower() here.
            f"Für die {aktion} in Bezug auf {amt.gebuehr_gegenstand} durch "
            f"{amt.amt_mit_artikel} wird eine Gebühr in Höhe von {fee},00 Euro erhoben. Die "
            f"Berechnungsgrundlage ist der durchschnittliche Bearbeitungsaufwand {amt.genitiv} "
            f"für diese Amtshandlung, ermittelt durch eine Zeit- und Kostenerfassung der Kämmerei. "
            f"Die Gebührenposition {index} {ausnahme_satz}. Bei mehrfacher Inanspruchnahme "
            f"derselben Amtshandlung innerhalb eines Kalenderjahres wird die Gebühr für jede "
            "einzelne Amtshandlung erneut fällig; eine Sammelrechnung ist auf Antrag möglich."
        )
    schluss = (
        "## Änderungen dieser Gebührenordnung\n\n"
        f"Änderungen dieser Gebührenordnung werden vom Stadtrat der Stadt {GEMEINDE} beschlossen "
        f"und treten frühestens zwei Monate nach Beschlussfassung in Kraft. Die jeweils aktuelle "
        f"Fassung wird im Amtsblatt der Stadt {GEMEINDE} und im Formularportal {amt.genitiv} "
        "veröffentlicht."
    )
    return "\n\n".join([praeambel] + positionen + [schluss])


# --- Dienstanweisung ----------------------------------------------------------------------


DIENSTANWEISUNGS_THEMEN_STANDARD = [
    (
        "Bearbeitung von Amtshandlungsanträgen",
        "die Prüfung und Entscheidung über eingehende Anträge im Zuständigkeitsbereich des "
        "Amtes",
    ),
    (
        "Aktenführung und Fristenkontrolle",
        "die einheitliche Führung der Vorgangsakten sowie die Überwachung gesetzlicher und "
        "satzungsrechtlicher Bearbeitungsfristen",
    ),
]

# Nur für die Kämmerei (`amt.traegt_gebuehrenbefreiung`) — sonst stünde "Bedürftigkeit"/
# "Gebührenbefreiung" in allen zehn Ämtern und der #938-Fall wäre nicht konstruierbar (siehe
# Kommentar über `paragraph_gebuehrenbefreiung`).
DIENSTANWEISUNGS_THEMEN_GEBUEHRENBEFREIUNG = [
    (
        "Bearbeitung von Anträgen auf Gebührenbefreiung",
        "die Prüfung und Entscheidung über Anträge nach § 3 der Verwaltungsgebührensatzung",
    ),
    DIENSTANWEISUNGS_THEMEN_STANDARD[1],
]


def dienstanweisungs_themen(amt: Amt) -> list[tuple[str, str]]:
    return (
        DIENSTANWEISUNGS_THEMEN_GEBUEHRENBEFREIUNG
        if amt.traegt_gebuehrenbefreiung
        else DIENSTANWEISUNGS_THEMEN_STANDARD
    )


def build_dienstanweisung_body(
    amt: Amt, nr: int, jahr: int, thema_titel: str, thema_beschreibung: str, vorgaenger_az: str | None
) -> str:
    aktenzeichen = f"{amt.kennung_praefix}-DA-{nr}/{jahr}"
    vorgaenger_satz = ""
    if vorgaenger_az:
        vorgaenger_satz = (
            f" Diese Fassung ersetzt die Dienstanweisung {vorgaenger_az}, deren Regelungen für "
            "bereits abgeschlossene Vorgänge unberührt bleiben."
        )
    if amt.traegt_gebuehrenbefreiung:
        pruefung_zusatz = (
            " Bei Anträgen, die sich auf § 3 oder § 13 der jeweils einschlägigen "
            "Gebührensatzung berufen, ist zusätzlich zu prüfen, ob die vorgelegten Nachweise "
            "noch innerhalb ihrer Gültigkeitsdauer liegen; ein abgelaufener Leistungsbescheid "
            "gilt nicht als ausreichender Nachweis der Bedürftigkeit."
        )
        entscheidung_satz = (
            "Bei Anträgen auf Gebührenbefreiung nach § 3 ist die geprüfte Bedürftigkeit "
            "ausdrücklich in der Akte zu vermerken, ebenso das Ergebnis eines etwaigen "
            "Härtefallantrags nach § 12. Bei Anträgen auf Gebührenermäßigung nach § 13 ist der "
            "vorgelegte Freistellungsbescheid des Finanzamts in Kopie zur Akte zu nehmen, damit "
            "eine spätere Prüfung durch die Kämmerei möglich ist."
        )
    else:
        # Für die neun Ämter ohne die #938-Bedürftigkeitsklausel referenzieren § 5/§ 6 dasselbe
        # § 3/§ 13-Zahlenpaar wie bei der Kämmerei (exact_identifier-Fallklasse bleibt erhalten),
        # aber ohne die Begriffe "Bedürftigkeit"/"Gebührenbefreiung" (literal_term-Fallklasse).
        pruefung_zusatz = (
            " Bei Anträgen, die sich auf § 3 oder § 13 der jeweils einschlägigen "
            "Gebührensatzung berufen, ist zusätzlich zu prüfen, ob die vorgelegten Nachweise "
            "noch innerhalb ihrer Gültigkeitsdauer liegen."
        )
        entscheidung_satz = (
            "Bei Anträgen auf Auskunft nach § 3 ist der geprüfte Sachverhalt ausdrücklich in "
            "der Akte zu vermerken, ebenso das Ergebnis eines etwaigen Härtefallantrags nach "
            "§ 12. Bei Anträgen auf Gebührenermäßigung nach § 13 ist der vorgelegte "
            "Freistellungsbescheid des Finanzamts in Kopie zur Akte zu nehmen, damit eine "
            "spätere Prüfung durch die Kämmerei möglich ist."
        )
    abschnitte = [
        f"# Dienstanweisung {aktenzeichen}: {thema_titel}\n\n"
        f"Dienstanweisung {amt.genitiv} der Stadt {GEMEINDE}, Aktenzeichen {aktenzeichen}, "
        f"gültig ab dem 1. Januar {jahr}.{vorgaenger_satz} Diese Dienstanweisung ist ausschließlich "
        f"für den internen Dienstgebrauch {amt.genitiv} bestimmt und ergänzt, ohne sie zu "
        f"ersetzen, die {amt.satzungstitel} sowie die zugehörige Gebührenordnung.",
        "## 1. Zweck\n\n"
        f"Diese Dienstanweisung regelt {thema_beschreibung} innerhalb {amt.genitiv} der "
        f"Stadt {GEMEINDE} und stellt eine einheitliche Bearbeitung sicher, unabhängig davon, "
        "welche Sachbearbeitung einen Vorgang bearbeitet. Ziel ist es, dass zwei unterschiedliche "
        "Sachbearbeitungen bei identischer Ausgangslage zum selben Ergebnis kommen, und dass ein "
        "Wechsel der zuständigen Person keine Auswirkung auf die Bearbeitungsdauer oder das "
        "Ergebnis eines Vorgangs hat.",
        "## 2. Anwendungsbereich\n\n"
        f"Diese Dienstanweisung gilt für alle Beschäftigten {amt.genitiv}, die für "
        f"{amt.gebuehr_gegenstand} zuständig sind, einschließlich Auszubildender und "
        "Vertretungskräften. Sie gilt nicht für Beschäftigte anderer Ämter der Stadtverwaltung "
        f"{GEMEINDE}, auch wenn diese im Vertretungsfall Aufgaben {amt.genitiv} "
        "übernehmen — für diesen Fall ist ausschließlich die Vertretungsregelung maßgeblich, nicht "
        "diese Dienstanweisung.",
        "## 3. Zuständigkeit\n\n"
        f"Zuständig ist die im Geschäftsverteilungsplan der Stadtverwaltung {GEMEINDE} "
        f"({GESCHAEFTSVERTEILUNGSPLAN_FILENAME}) benannte Sachbearbeitung {amt.genitiv}. "
        "Ist diese Sachbearbeitung wegen Urlaub, Krankheit oder Fortbildung nicht erreichbar, "
        f"gilt die Vertretungsregelung der Stadtverwaltung {GEMEINDE} "
        f"({VERTRETUNGSREGELUNG_FILENAME}). Die Amtsleitung {amt.genitiv} bleibt in jedem "
        "Fall zur Selbsteintrittsbefugnis berechtigt, wenn ein Vorgang von besonderer Bedeutung "
        "oder besonderer Schwierigkeit ist.",
        "## 4. Verfahrensschritt: Eingang und Erfassung\n\n"
        f"Jeder eingehende Vorgang {amt.genitiv} wird am Tag des Eingangs im "
        "Vorgangsverzeichnis erfasst und mit einem laufenden Geschäftszeichen versehen, das sich "
        f"aus dem Amtskürzel {amt.kuerzel}, dem Kalenderjahr und einer fortlaufenden Nummer "
        "zusammensetzt. Digital eingehende Vorgänge über das Formularportal der Stadt "
        f"{GEMEINDE} werden automatisch erfasst; postalisch eingehende Vorgänge werden am "
        "Folgetag von der Poststelle an die zuständige Sachbearbeitung weitergeleitet.",
        "## 5. Verfahrensschritt: Prüfung\n\n"
        f"Die Sachbearbeitung {amt.genitiv} prüft den Vorgang auf Vollständigkeit der "
        "Nachweise. Fehlen Unterlagen, wird eine Nachfrist von zwei Wochen gesetzt; nach "
        f"fruchtlosem Ablauf wird nach Aktenlage entschieden.{pruefung_zusatz}",
        f"## 6. Verfahrensschritt: Entscheidung und Dokumentation\n\n"
        f"Die Entscheidung wird schriftlich begründet und in der Vorgangsakte dokumentiert. "
        f"{entscheidung_satz}",
        "## 7. Vertretungsfall und Eskalation\n\n"
        "Kann eine Entscheidung wegen Abwesenheit der zuständigen Sachbearbeitung nicht fristgemäß "
        "getroffen werden, entscheidet die nach der Vertretungsregelung zuständige Vertretung. "
        "Ist auch diese nicht erreichbar, entscheidet die Amtsleitung "
        f"{amt.genitiv}. Ein Vertretungsfall ist stets im Vorgangsverzeichnis zu vermerken, "
        "damit bei einer späteren Rückfrage nachvollziehbar bleibt, welche Person tatsächlich "
        "entschieden hat und aus welchem Grund die eigentlich zuständige Sachbearbeitung nicht "
        "verfügbar war.",
        "## 8. Dokumentationspflichten\n\n"
        "Alle Entscheidungen sind revisionssicher zu dokumentieren und mindestens zehn Jahre "
        f"aufzubewahren, sofern nicht die Datenschutzregelung nach § 11 der einschlägigen Satzung "
        "eine kürzere Frist vorsieht. Die Dokumentation umfasst mindestens den Antrag, die "
        "vorgelegten Nachweise, den Entscheidungstext sowie, im Vertretungsfall, den Namen der "
        "entscheidenden Vertretung.",
        "## 9. Schulung und Einarbeitung\n\n"
        f"Neue Beschäftigte {amt.genitiv} werden vor der ersten eigenständigen Bearbeitung "
        "eines Vorgangs nach dieser Dienstanweisung in die Verfahrensschritte eingewiesen. Die "
        "Einweisung wird von der Amtsleitung dokumentiert und ist Voraussetzung für die "
        "Übertragung der Zeichnungsbefugnis. Eine Auffrischung der Einweisung erfolgt, wenn diese "
        "Dienstanweisung inhaltlich geändert wird oder wenn eine Beschäftigte oder ein "
        "Beschäftigter länger als zwölf Monate nicht mit Vorgängen dieser Art befasst war.",
        "## 10. Qualitätssicherung\n\n"
        f"Die Amtsleitung {amt.genitiv} prüft stichprobenartig, mindestens einmal je "
        "Quartal, abgeschlossene Vorgänge auf die korrekte Anwendung dieser Dienstanweisung. "
        "Auffälligkeiten, insbesondere uneinheitliche Entscheidungen bei vergleichbarer "
        "Ausgangslage zwischen verschiedenen Sachbearbeitungen, werden dokumentiert und fließen in "
        "die nächste Überarbeitung dieser Dienstanweisung ein.",
        "## 11. Verhältnis zu anderen Dienstanweisungen\n\n"
        f"Bestehen für {amt.amt_mit_artikel} weitere Dienstanweisungen zu verwandten "
        "Sachverhalten, gehen deren speziellere Regelungen im jeweiligen Anwendungsbereich vor. Im "
        "Zweifel entscheidet die Amtsleitung, welche Dienstanweisung im Einzelfall vorrangig "
        "anzuwenden ist, und dokumentiert diese Entscheidung zur Klarstellung für künftige, "
        "vergleichbare Fälle.",
        "## 12. Umgang mit Beschwerden\n\n"
        f"Beschwerden über die Bearbeitung eines Vorgangs {amt.genitiv}, die sich auf "
        "diese Dienstanweisung beziehen, werden von der Amtsleitung entgegengenommen und "
        "innerhalb von zwei Wochen beantwortet. Eine Beschwerde führt nicht automatisch zur "
        "Aussetzung der ursprünglichen Entscheidung; eine Aussetzung ist nur bei offensichtlicher "
        "Fehlanwendung dieser Dienstanweisung vorgesehen und wird von der Amtsleitung im "
        "Einzelfall angeordnet.",
        "## 13. Inkrafttreten\n\n"
        f"Diese Dienstanweisung tritt am 1. Januar {jahr} in Kraft und gilt bis zu ihrer "
        "ausdrücklichen Aufhebung oder Ersetzung durch eine neue Fassung. Sie wird jährlich von "
        f"der Amtsleitung {amt.genitiv} auf Aktualität überprüft und bei Bedarf "
        "fortgeschrieben.",
    ]
    return "\n\n".join(abschnitte)


# --- Formularhinweis ----------------------------------------------------------------------


FORMULARHINWEIS_THEMEN_STANDARD = [
    ("Antragsformular", "den regulären Antrag auf eine Amtshandlung"),
    (
        "Änderungs- und Ermäßigungsformular",
        "den Antrag auf eine nachträgliche Änderung der Antragsdaten oder auf "
        "Gebührenermäßigung nach § 13",
    ),
]

# Nur für die Kämmerei (`amt.traegt_gebuehrenbefreiung`) — siehe Kommentar über
# `paragraph_gebuehrenbefreiung`.
FORMULARHINWEIS_THEMEN_GEBUEHRENBEFREIUNG = [
    FORMULARHINWEIS_THEMEN_STANDARD[0],
    (
        "Befreiungs- und Ermäßigungsformular",
        "den Antrag auf Gebührenbefreiung nach § 3 oder Gebührenermäßigung nach § 13",
    ),
]


def formularhinweis_themen(amt: Amt) -> list[tuple[str, str]]:
    return (
        FORMULARHINWEIS_THEMEN_GEBUEHRENBEFREIUNG
        if amt.traegt_gebuehrenbefreiung
        else FORMULARHINWEIS_THEMEN_STANDARD
    )


def build_formularhinweis_body(amt: Amt, nr: int, thema_titel: str, thema_beschreibung: str) -> str:
    formularnummer = f"Formular {amt.kennung_praefix}-{nr:02d}"
    if amt.traegt_gebuehrenbefreiung:
        angaben_satz = (
            "Bei Anträgen auf Gebührenbefreiung ist zusätzlich der aktuelle Leistungsbescheid "
            "nach § 3 der einschlägigen Gebührensatzung beizufügen; bei Anträgen auf "
            "Gebührenermäßigung nach § 13 ist stattdessen der Freistellungsbescheid des "
            "Finanzamts anzugeben."
        )
        nachweis_satz = (
            f"Je nach Anliegen sind Identitätsnachweis, Nachweis der Bedürftigkeit oder Nachweis "
            f"der Gemeinnützigkeit beizufügen. {amt.amt_mit_artikel_grossgeschrieben} akzeptiert "
            "sowohl beglaubigte Kopien als auch das Original zur Einsichtnahme vor Ort."
        )
        rueckfrage_satz = (
            f'"{amt.alltagsfrage}" — die Antwort richtet sich nach § 3 der {amt.satzungstitel} '
            'und wird im Formular unter „Gebührenbefreiung“ abgefragt.'
        )
        rechtsgrundlage_satz = (
            "insbesondere die dortigen Regelungen zu Gebührenbefreiung (§ 3) und "
            "Gebührenermäßigung (§ 13)"
        )
        aufbewahrung_satz = (
            "abschließend beschieden und die Gebühr, sofern keine Befreiung nach § 3 oder "
            "Ermäßigung nach § 13 greift, beglichen wurde."
        )
        widerruf_satz = (
            "Eine bereits getroffene Entscheidung über die Gebührenbefreiung nach § 3 wird durch "
            "einen Widerruf nachfolgender, davon unabhängiger Angaben nicht berührt."
        )
    else:
        # Für die neun Ämter ohne die #938-Bedürftigkeitsklausel referenzieren §§ 2/3/6/9/11
        # dasselbe § 3/§ 13-Zahlenpaar (exact_identifier bleibt erhalten), aber ohne die Begriffe
        # "Bedürftigkeit"/"Gebührenbefreiung" (literal_term-Fallklasse, siehe Kommentar über
        # `paragraph_gebuehrenbefreiung`).
        angaben_satz = (
            "Bei Anträgen auf Auskunft nach § 3 ist kein zusätzlicher Nachweis erforderlich; bei "
            "Anträgen auf Gebührenermäßigung nach § 13 ist der Freistellungsbescheid des "
            "Finanzamts anzugeben."
        )
        nachweis_satz = (
            f"Je nach Anliegen ist ein Identitätsnachweis oder ein Nachweis der Gemeinnützigkeit "
            f"beizufügen. {amt.amt_mit_artikel_grossgeschrieben} akzeptiert sowohl beglaubigte "
            "Kopien als auch das Original zur Einsichtnahme vor Ort."
        )
        rueckfrage_satz = (
            f'"{amt.alltagsfrage}" — Auskunft dazu erteilt die Sachbearbeitung {amt.genitiv} im '
            "Rahmen des Auskunftsrechts nach § 3."
        )
        rechtsgrundlage_satz = (
            "insbesondere die dortigen Regelungen zum Auskunftsrecht (§ 3) und zur "
            "Gebührenermäßigung (§ 13)"
        )
        aufbewahrung_satz = (
            "abschließend beschieden und die Gebühr, sofern keine Ermäßigung nach § 13 greift, "
            "beglichen wurde."
        )
        widerruf_satz = (
            "Eine bereits erteilte Auskunft nach § 3 wird durch einen Widerruf nachfolgender, "
            "davon unabhängiger Angaben nicht berührt."
        )
    abschnitte = [
        f"# Formularhinweis: {formularnummer} — {thema_titel}\n\n"
        f"Hinweise {amt.genitiv} der Stadt {GEMEINDE} zum Ausfüllen von "
        f"{formularnummer}, das für {thema_beschreibung} verwendet wird. Dieser Formularhinweis "
        f"ergänzt die {amt.satzungstitel} und die zugehörige Gebührenordnung um praktische "
        "Ausfüllhilfen für Antragstellende und richtet sich sowohl an Bürgerinnen und Bürger als "
        "auch an die Sachbearbeitung selbst.",
        "## 1. Zweck des Formulars\n\n"
        f"{formularnummer} dient der einheitlichen Erfassung der für die Bearbeitung "
        f"{amt.genitiv} notwendigen Angaben für {amt.gebuehr_gegenstand}. Ohne "
        f"vollständig ausgefülltes Formular kann der Vorgang nicht bearbeitet werden. Ein "
        "unvollständiges Formular wird mit einer Rückfrage an die antragstellende Person "
        "zurückgesandt, was die Bearbeitungsdauer erfahrungsgemäß um mehrere Wochen verlängert.",
        "## 2. Erforderliche Angaben\n\n"
        "Anzugeben sind Name, Anschrift, Geburtsdatum und, sofern zutreffend, das Aktenzeichen "
        f"eines bereits laufenden Vorgangs. {angaben_satz} Beide Angaben schließen sich "
        "gegenseitig nicht aus, betreffen aber unterschiedliche Anspruchsgrundlagen. Ergänzend "
        "ist eine gültige Telefonnummer oder E-Mail-Adresse anzugeben, unter der bei Rückfragen "
        "zum Vorgang eine Erreichbarkeit innerhalb der üblichen Bearbeitungsdauer sichergestellt "
        "ist; fehlt eine solche Angabe, erfolgt jede Rückfrage ausschließlich auf dem Postweg, "
        "was die Bearbeitung verzögert.",
        "## 3. Erforderliche Nachweise\n\n"
        f"{nachweis_satz} Fremdsprachige Nachweise sind mit einer amtlich beglaubigten "
        "Übersetzung ins Deutsche einzureichen, sofern der Inhalt nicht ohnehin standardisierten "
        "Formularen einer deutschen Behörde entspricht.",
        "## 4. Einreichung\n\n"
        f"{formularnummer} kann persönlich, postalisch oder über das Online-Formularportal der "
        f"Stadt {GEMEINDE} eingereicht werden. Bei postalischer Einreichung ist das Aktenzeichen "
        f"{amt.kennung_praefix}-{nr:02d} auf dem Umschlag zu vermerken, damit eine eindeutige "
        "Zuordnung möglich ist. Bei elektronischer Einreichung über das Formularportal wird der "
        "Eingang automatisch mit Datum und Uhrzeit bestätigt.",
        "## 5. Bearbeitungsdauer\n\n"
        f"Die reguläre Bearbeitungsdauer {amt.genitiv} beträgt zwei bis vier Wochen ab "
        "vollständigem Eingang aller Nachweise. Eine Eilbearbeitung ist gegen die entsprechende "
        "Gebühr aus der Gebührenordnung möglich und verkürzt die Bearbeitungsdauer in der Regel "
        "auf drei bis fünf Werktage. Während gesetzlicher Feiertage und in der Zeit zwischen "
        "Weihnachten und Neujahr kann sich die Bearbeitungsdauer verlängern.",
        "## 6. Häufige Rückfragen\n\n"
        f"Häufig gestellte Frage im Zusammenhang mit {formularnummer}: {rueckfrage_satz} Eine "
        "weitere häufige Frage betrifft die Gültigkeitsdauer eingereichter Nachweise: Ein "
        "Leistungsbescheid darf zum Zeitpunkt der Antragstellung nicht älter als drei Monate "
        "sein.",
        "## 7. Rechtsgrundlage\n\n"
        f"Rechtsgrundlage für {formularnummer} ist die {amt.satzungstitel} der Stadt {GEMEINDE} "
        f"in der jeweils aktuell gültigen Fassung, {rechtsgrundlage_satz}. Ergänzend gilt die "
        f"Gebührenordnung {amt.genitiv} für die konkrete Gebührenhöhe der jeweiligen "
        "Amtshandlung.",
        "## 8. Kontakt\n\n"
        f"Bei Rückfragen zu {formularnummer} steht die Sachbearbeitung {amt.genitiv} "
        f"während der Öffnungszeiten der Stadtverwaltung {GEMEINDE} zur Verfügung; im "
        "Vertretungsfall gilt die Vertretungsregelung der Stadtverwaltung "
        f"({VERTRETUNGSREGELUNG_FILENAME}). Telefonische Auskünfte werden ausschließlich zu "
        "allgemeinen Verfahrensfragen erteilt, keine verbindliche Zusage zum Ausgang eines "
        "konkreten Antrags.",
        "## 9. Aufbewahrung durch die Antragstellenden\n\n"
        f"Antragstellenden wird empfohlen, eine Kopie des ausgefüllten {formularnummer} sowie "
        "aller eingereichten Nachweise für die eigenen Unterlagen aufzubewahren, bis der Vorgang "
        f"{aufbewahrung_satz}",
        "## 10. Barrierefreiheit und Unterstützung beim Ausfüllen\n\n"
        f"{amt.amt_mit_artikel_grossgeschrieben} bietet auf Anfrage eine persönliche "
        f"Ausfüllhilfe für {formularnummer} an, insbesondere für Personen mit "
        "Seheinschränkung, eingeschränkten Deutschkenntnissen oder ohne Zugang zu einem eigenen "
        "Drucker. Ein barrierefreies elektronisches Formular steht ergänzend über das "
        f"Formularportal der Stadt {GEMEINDE} zur Verfügung.",
        "## 11. Widerruf und Änderung eines bereits gestellten Antrags\n\n"
        f"Ein über {formularnummer} gestellter Antrag kann bis zur abschließenden Entscheidung "
        f"{amt.genitiv} formlos schriftlich widerrufen oder in einzelnen Angaben berichtigt "
        f"werden. {widerruf_satz}",
        "## 12. Verweis auf verwandte Formulare\n\n"
        f"Für Anliegen, die nicht unmittelbar {amt.gebuehr_gegenstand} betreffen, aber im "
        f"Rahmen desselben Vorgangs entstehen können, verweist {amt.amt_mit_artikel} auf die "
        "jeweils einschlägigen weiteren Formularhinweise anderer Ämter der Stadtverwaltung "
        f"{GEMEINDE}, insbesondere auf den Geschäftsverteilungsplan "
        f"({GESCHAEFTSVERTEILUNGSPLAN_FILENAME}) zur Klärung der Zuständigkeit.",
        "## 13. Typische Fehler beim Ausfüllen\n\n"
        f"Erfahrungsgemäß wird {formularnummer} am häufigsten unvollständig eingereicht, weil das "
        "Feld für das Aktenzeichen eines bereits laufenden Vorgangs leer bleibt, obwohl bereits "
        "ein Vorgang existiert, oder weil der beigefügte Leistungsbescheid nicht mehr innerhalb "
        "der geforderten Gültigkeitsdauer von drei Monaten liegt. Beide Fehler führen zu einer "
        "vermeidbaren Rückfrage und verlängern die Bearbeitungsdauer.",
        "## 14. Verhältnis zur elektronischen Akte\n\n"
        f"Ein über das Formularportal eingereichtes {formularnummer} wird automatisch der "
        f"elektronischen Akte {amt.genitiv} zugeordnet. Eine postalisch oder persönlich "
        "eingereichte Fassung wird eingescannt und ergänzt dieselbe elektronische Akte, sodass "
        "unabhängig vom gewählten Einreichungsweg dieselbe Sachbearbeitung Zugriff auf den "
        "vollständigen Vorgang hat.",
    ]
    return "\n\n".join(abschnitte)


# --- Organisationsweite Dokumente: Vertretungsregelung, Geschäftsverteilungsplan -----------


def build_vertretungsregelung_body() -> str:
    # Code-Review-Befund (PR #1074): Diese beiden Organisationsdokumente dürfen ausschließlich
    # die Vertretungs-/Zuständigkeitshälfte der multi_hop-Fallklasse (Abschnitt 5d der
    # Spezifikation) tragen — keine Aussage darüber, WORÜBER ein Amt in der Sache entscheidet
    # (das steht in der jeweiligen Satzung). Andernfalls wäre die dortige Beispielfrage ("Wer
    # entscheidet über die Gebührenbefreiung, wenn die zuständige Sachbearbeitung im Urlaub
    # ist?") aus diesem einen Dokument allein beantwortbar, und die Kette über zwei Dokumente
    # entfiele.
    abschnitte = [
        f"# Vertretungsregelung der Stadtverwaltung {GEMEINDE}\n\n"
        f"Diese Vertretungsregelung legt fest, wer die Sachbearbeitung eines Amtes der "
        f"Stadtverwaltung {GEMEINDE} vertritt, wenn diese wegen Urlaub, Krankheit, Fortbildung "
        "oder aus anderen Gründen nicht erreichbar ist. Sie gilt amtsübergreifend und "
        "unabhängig vom fachlichen Gegenstand einer Entscheidung — welches Amt wofür fachlich "
        f"zuständig ist, regelt ausschließlich der Geschäftsverteilungsplan der Stadtverwaltung "
        f"{GEMEINDE} ({GESCHAEFTSVERTEILUNGSPLAN_FILENAME}) sowie die jeweilige Satzung.",
    ]
    for index, amt in enumerate(AEMTER):
        vertreter = AEMTER[(index + 1) % len(AEMTER)]
        abschnitte.append(
            f"## Vertretung für {amt.name}\n\n"
            f"Ist die zuständige Sachbearbeitung {amt.genitiv} der Stadt {GEMEINDE} nicht "
            f"erreichbar, entscheidet ersatzweise die Sachbearbeitung {vertreter.genitiv}. "
            f"Diese Vertretung gilt ausnahmslos für alle Amtshandlungen {amt.genitiv}, ohne "
            "Rücksicht darauf, welchen konkreten Antrag oder welche konkrete Gebühr die "
            f"Amtshandlung betrifft. Ist auch die Vertretung {vertreter.genitiv} nicht "
            f"erreichbar, entscheidet die Amtsleitung {amt.genitiv} persönlich oder benennt eine "
            "weitere Vertretung aus dem eigenen Amt. Der Vertretungsfall ist von der "
            "übernehmenden Sachbearbeitung im Vorgangsverzeichnis zu vermerken, damit "
            "nachvollziehbar bleibt, wer anstelle der eigentlich zuständigen Person entschieden "
            "hat."
        )
    abschnitte.append(
        "## Geltungsdauer\n\n"
        f"Diese Vertretungsregelung gilt bis zu ihrer ausdrücklichen Aufhebung oder Ersetzung durch "
        f"den Stadtrat der Stadt {GEMEINDE} und wird jährlich auf Aktualität überprüft."
    )
    return "\n\n".join(abschnitte)


def build_geschaeftsverteilungsplan_body() -> str:
    # Siehe Kommentar über build_vertretungsregelung_body: auch dieses Dokument trägt nur die
    # Zuständigkeitshälfte (welches Amt ist wofür allgemein zuständig), keine Sachaussage über
    # § 3/§ 13 einer konkreten Satzung.
    abschnitte = [
        f"# Geschäftsverteilungsplan der Stadtverwaltung {GEMEINDE}\n\n"
        f"Dieser Geschäftsverteilungsplan weist jedem Amt der Stadtverwaltung {GEMEINDE} seine "
        "Zuständigkeiten sowie die für die einzelnen Aufgabenbereiche verantwortliche "
        "Sachbearbeitung zu. Er ist die Grundlage dafür, welche Stelle für welche Entscheidung "
        "innerhalb der Stadtverwaltung fachlich zuständig ist; wer eine konkrete Sachbearbeitung "
        f"im Abwesenheitsfall vertritt, regelt ausschließlich die Vertretungsregelung der "
        f"Stadtverwaltung {GEMEINDE} ({VERTRETUNGSREGELUNG_FILENAME}).",
    ]
    for amt in AEMTER:
        abschnitte.append(
            f"## Zuständigkeit {amt.genitiv}\n\n"
            f"{amt.amt_mit_artikel_grossgeschrieben} der Stadt {GEMEINDE} ist zuständig für "
            f"{amt.gebuehr_gegenstand}. Innerhalb {amt.genitiv} liegt die Entscheidung bei der "
            "jeweils benannten Sachbearbeitung; im Vertretungsfall gilt die Vertretungsregelung "
            f"der Stadtverwaltung {GEMEINDE} ({VERTRETUNGSREGELUNG_FILENAME}). Grundlage der "
            f"Gebührenerhebung {amt.genitiv} ist die {amt.satzungstitel} in Verbindung mit der "
            "zugehörigen Gebührenordnung, in der die Gebührenhöhe je Amtshandlung "
            f"({amt.hauptleistung}) im Einzelnen festgelegt ist. Ansprechpartnerin oder "
            f"Ansprechpartner für die konkrete Zuordnung eines Vorgangs innerhalb {amt.genitiv} "
            "ist die Amtsleitung, die die Verteilung auf die einzelnen Sachbearbeitungen selbst "
            "festlegt und bei Bedarf anpasst."
        )
    abschnitte.append(
        "## Änderungen\n\n"
        f"Änderungen dieses Geschäftsverteilungsplans werden von der Verwaltungsleitung der Stadt "
        f"{GEMEINDE} beschlossen und im Amtsblatt veröffentlicht. Eine Änderung wird jeweils zum "
        "Monatsersten wirksam, damit laufende Vorgänge nicht während der Bearbeitung die "
        "Zuständigkeit wechseln."
    )
    return "\n\n".join(abschnitte)


# --- Orchestrierung: Dokumentliste in fester, deterministischer Reihenfolge ----------------


def satzung_schlagworte(amt: Amt) -> list[str]:
    if amt.traegt_gebuehrenbefreiung:
        return [amt.name, "Satzung", "Gebühren", "Gebührenbefreiung", "Bedürftigkeit"]
    # Code-Review-Befund (PR #1074): "Gebührenbefreiung"/"Bedürftigkeit" nur bei der Kämmerei —
    # sonst tragen alle zehn Satzungen dieselben Schlagworte und der #938-Fall (Abschnitt 5a)
    # verliert seine Seltenheit (IDF ≈ 0).
    return [amt.name, "Satzung", "Gebühren", "Auskunftsrecht"]


def build_documents() -> list[GeneratedDocument]:
    allocator = IdAllocator()
    documents: list[GeneratedDocument] = []

    for amt in AEMTER:
        # Satzung — Fassungspaar für ausgewählte Ämter (metadata_filter-Fallklasse, Abschnitt 5e).
        satzung_slug = slugify(amt.satzungstitel)
        if amt.kuerzel in FASSUNGSPAAR_KUERZEL:
            alt_id = allocator.next_id()
            alt_filename = f"{alt_id}_{satzung_slug}-fassung-2023.md"
            neu_id = allocator.next_id()
            neu_filename = f"{neu_id}_{satzung_slug}-fassung-2024.md"
            documents.append(
                GeneratedDocument(
                    doc_id=alt_id,
                    filename=alt_filename,
                    dokumentart="satzung",
                    titel=f"{amt.satzungstitel} (Fassung 2023)",
                    amt=amt,
                    aktenzeichen=f"{amt.kennung_praefix}-S-2023",
                    fassung=2023,
                    stand_datum="2023-01-01",
                    # Code-Review-Befund (PR #1074): muss mit § 14 im Fließtext übereinstimmen
                    # ("tritt am 1. Januar 2023 in Kraft", siehe paragraph_uebergang) — die
                    # vorherige Fassung widersprach dem mit gueltig_ab=2020-01-01.
                    gueltig_ab="2023-01-01",
                    gueltig_bis="2023-12-31",
                    ersetzt=None,
                    ersetzt_durch=neu_filename,
                    schlagworte=satzung_schlagworte(amt),
                    body=build_satzung_body(amt, 2023, None),
                )
            )
            documents.append(
                GeneratedDocument(
                    doc_id=neu_id,
                    filename=neu_filename,
                    dokumentart="satzung",
                    titel=f"{amt.satzungstitel} (Fassung 2024)",
                    amt=amt,
                    aktenzeichen=f"{amt.kennung_praefix}-S-2024",
                    fassung=2024,
                    stand_datum="2024-01-01",
                    gueltig_ab="2024-01-01",
                    gueltig_bis=None,
                    ersetzt=alt_filename,
                    ersetzt_durch=None,
                    schlagworte=satzung_schlagworte(amt),
                    body=build_satzung_body(amt, 2024, 2023),
                )
            )
        else:
            doc_id = allocator.next_id()
            filename = f"{doc_id}_{satzung_slug}.md"
            documents.append(
                GeneratedDocument(
                    doc_id=doc_id,
                    filename=filename,
                    dokumentart="satzung",
                    titel=amt.satzungstitel,
                    amt=amt,
                    aktenzeichen=f"{amt.kennung_praefix}-S-2024",
                    fassung=2024,
                    stand_datum="2024-01-01",
                    gueltig_ab="2024-01-01",
                    gueltig_bis=None,
                    ersetzt=None,
                    ersetzt_durch=None,
                    schlagworte=satzung_schlagworte(amt),
                    body=build_satzung_body(amt, 2024, None),
                )
            )

        # Gebührenordnung — ein Dokument je Amt, ohne Fassungspaar.
        go_id = allocator.next_id()
        go_slug = slugify(f"Gebuehrenordnung {amt.name}")
        documents.append(
            GeneratedDocument(
                doc_id=go_id,
                filename=f"{go_id}_{go_slug}.md",
                dokumentart="gebuehrenordnung",
                titel=f"Gebührenordnung {amt.name}",
                amt=amt,
                aktenzeichen=f"{amt.kennung_praefix}-GO",
                fassung=2024,
                stand_datum="2024-01-01",
                gueltig_ab="2024-01-01",
                gueltig_bis=None,
                ersetzt=None,
                ersetzt_durch=None,
                schlagworte=[amt.name, "Gebührenordnung", "Gebühren", amt.hauptleistung],
                body=build_gebuehrenordnung_body(amt),
            )
        )

        # Dienstanweisungen — zwei Nummern je Amt; Nr. 1 als Fassungspaar für ausgewählte Ämter
        # (exact_identifier-Fallklasse: benachbarte Aktenzeichen über zwei Jahre, Abschnitt 5b).
        for nr, (thema_titel, thema_beschreibung) in enumerate(dienstanweisungs_themen(amt), start=1):
            if nr == 1 and amt.kuerzel in DIENSTANWEISUNG_FASSUNGSPAAR_KUERZEL:
                alt_az = f"{amt.kennung_praefix}-DA-{nr}/2023"
                neu_az = f"{amt.kennung_praefix}-DA-{nr}/2024"
                alt_id = allocator.next_id()
                alt_slug = slugify(f"Dienstanweisung {amt.name} {nr} 2023")
                alt_filename = f"{alt_id}_{alt_slug}.md"
                neu_id = allocator.next_id()
                neu_slug = slugify(f"Dienstanweisung {amt.name} {nr} 2024")
                neu_filename = f"{neu_id}_{neu_slug}.md"
                documents.append(
                    GeneratedDocument(
                        doc_id=alt_id,
                        filename=alt_filename,
                        dokumentart="dienstanweisung",
                        titel=f"Dienstanweisung {alt_az}: {thema_titel}",
                        amt=amt,
                        aktenzeichen=alt_az,
                        fassung=2023,
                        stand_datum="2023-01-01",
                        gueltig_ab="2023-01-01",
                        gueltig_bis="2023-12-31",
                        ersetzt=None,
                        ersetzt_durch=neu_filename,
                        schlagworte=[amt.name, "Dienstanweisung", thema_titel],
                        body=build_dienstanweisung_body(amt, nr, 2023, thema_titel, thema_beschreibung, None),
                    )
                )
                documents.append(
                    GeneratedDocument(
                        doc_id=neu_id,
                        filename=neu_filename,
                        dokumentart="dienstanweisung",
                        titel=f"Dienstanweisung {neu_az}: {thema_titel}",
                        amt=amt,
                        aktenzeichen=neu_az,
                        fassung=2024,
                        stand_datum="2024-01-01",
                        gueltig_ab="2024-01-01",
                        gueltig_bis=None,
                        # ersetzt/ersetzt_durch referenzieren immer Dateinamen (wie bei der
                        # Satzung), nicht das Aktenzeichen — Code-Review-Befund (PR #1074): die
                        # frühere Fassung mischte hier alt_az (Aktenzeichen) mit alt_filename
                        # (Dateiname) an anderer Stelle.
                        ersetzt=alt_filename,
                        ersetzt_durch=None,
                        schlagworte=[amt.name, "Dienstanweisung", thema_titel],
                        body=build_dienstanweisung_body(amt, nr, 2024, thema_titel, thema_beschreibung, alt_az),
                    )
                )
            else:
                az = f"{amt.kennung_praefix}-DA-{nr}/2024"
                doc_id = allocator.next_id()
                slug = slugify(f"Dienstanweisung {amt.name} {nr} 2024")
                filename = f"{doc_id}_{slug}.md"
                documents.append(
                    GeneratedDocument(
                        doc_id=doc_id,
                        filename=filename,
                        dokumentart="dienstanweisung",
                        titel=f"Dienstanweisung {az}: {thema_titel}",
                        amt=amt,
                        aktenzeichen=az,
                        fassung=2024,
                        stand_datum="2024-01-01",
                        gueltig_ab="2024-01-01",
                        gueltig_bis=None,
                        ersetzt=None,
                        ersetzt_durch=None,
                        schlagworte=[amt.name, "Dienstanweisung", thema_titel],
                        body=build_dienstanweisung_body(amt, nr, 2024, thema_titel, thema_beschreibung, None),
                    )
                )

        # Formularhinweise — zwei benachbarte Formularnummern je Amt (exact_identifier).
        for nr, (thema_titel, thema_beschreibung) in enumerate(formularhinweis_themen(amt), start=7):
            doc_id = allocator.next_id()
            slug = slugify(f"Formularhinweis {amt.name} {nr}")
            filename = f"{doc_id}_{slug}.md"
            formularnummer = f"Formular {amt.kennung_praefix}-{nr:02d}"
            documents.append(
                GeneratedDocument(
                    doc_id=doc_id,
                    filename=filename,
                    dokumentart="formularhinweis",
                    titel=f"Formularhinweis: {formularnummer} — {thema_titel}",
                    amt=amt,
                    aktenzeichen=formularnummer,
                    fassung=2024,
                    stand_datum="2024-01-01",
                    gueltig_ab="2024-01-01",
                    gueltig_bis=None,
                    ersetzt=None,
                    ersetzt_durch=None,
                    schlagworte=[amt.name, "Formular", thema_titel],
                    body=build_formularhinweis_body(amt, nr, thema_titel, thema_beschreibung),
                )
            )

    # Organisationsweite Dokumente — tragen die Multi-Hop-Kette (Abschnitt 5d): keine Satzung
    # nennt die Vertretung selbst, sondern verweist auf diese beiden Dokumente.
    vertretung_id = allocator.next_id()
    documents.append(
        GeneratedDocument(
            doc_id=vertretung_id,
            filename=VERTRETUNGSREGELUNG_FILENAME,
            dokumentart="vertretungsregelung",
            titel=f"Vertretungsregelung der Stadtverwaltung {GEMEINDE}",
            amt=None,
            aktenzeichen="ORG-VERTRETUNG",
            fassung=2024,
            stand_datum="2024-01-01",
            gueltig_ab="2024-01-01",
            gueltig_bis=None,
            ersetzt=None,
            ersetzt_durch=None,
            schlagworte=["Vertretungsregelung", "Zuständigkeit", "Organisation"],
            body=build_vertretungsregelung_body(),
        )
    )
    geschaeftsverteilung_id = allocator.next_id()
    documents.append(
        GeneratedDocument(
            doc_id=geschaeftsverteilung_id,
            filename=GESCHAEFTSVERTEILUNGSPLAN_FILENAME,
            dokumentart="geschaeftsverteilungsplan",
            titel=f"Geschäftsverteilungsplan der Stadtverwaltung {GEMEINDE}",
            amt=None,
            aktenzeichen="ORG-GVP",
            fassung=2024,
            stand_datum="2024-01-01",
            gueltig_ab="2024-01-01",
            gueltig_bis=None,
            ersetzt=None,
            ersetzt_durch=None,
            schlagworte=["Geschäftsverteilungsplan", "Zuständigkeit", "Organisation"],
            body=build_geschaeftsverteilungsplan_body(),
        )
    )
    return documents


def write_corpus(documents: list[GeneratedDocument]) -> list[Path]:
    if CORPUS_DIR.exists():
        for existing in CORPUS_DIR.glob("verwaltung-*.md"):
            existing.unlink()
    CORPUS_DIR.mkdir(parents=True, exist_ok=True)

    written: list[Path] = []
    for document in documents:
        path = CORPUS_DIR / document.filename
        path.write_bytes(document.render())
        written.append(path)
    return written


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def write_manifest(paths: list[Path]) -> None:
    manifest_path = CORPUS_DIR / "MANIFEST.sha256"
    lines = [f"{sha256_of(path)} *{path.name}" for path in sorted(paths, key=lambda item: item.name)]
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main() -> None:
    documents = build_documents()
    filenames = [doc.filename for doc in documents]
    if len(filenames) != len(set(filenames)):
        raise SystemExit("Duplicate filenames generated — a filename collision would silently overwrite a document.")
    written = write_corpus(documents)
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
