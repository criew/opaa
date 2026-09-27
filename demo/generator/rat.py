"""Data and rendering for the "Ratsinformationen Stadt Rheinfurt" library (S3 connector, see
docs/features/demo-instance.md and docs/handbuch/konnektor-s3.md).

Council minutes (Niederschriften, .md) and decision papers (Beschlussvorlagen, .txt) of the
Stadtrat, the Hauptausschuss and the Bauausschuss, laid out under one key prefix per year and one
per committee below it (`<jahr>/<Gremium>/`) - the shape of a records archive an administration
keeps in an object store. The keys are what the demo's object store bucket is seeded with
(docker-compose.yml, service "objectstore-seed"), so both prefix levels become folders.

A Beschlussvorlage with Anlagen is stored as the council information system's dispatch mail
(.eml) instead: the Vorlage text is the mail body and every Anlage a PDF attachment. In an S3
library only mail objects carry attachments (konnektor-s3.md, "Anhänge"), so this is the one
shape in which the Anlagen appear as attachments of their Vorlage.

The council decisions here (budgets, the digitalisation strategy, the mobile citizen office, the
fire station, the school bus, heat planning, the town hall fountain) are covered by no other
library - a question about one of them is only answerable from here. Shared background such as the
town festival or the citizen office's staffing may appear elsewhere; the demo smoke run does not
rely on exclusivity but scopes its question to this library with an @-reference (e2e/demo-smoke).

All dates are fixed literals; the output is byte-identical across generator runs.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from io import BytesIO
from xml.sax.saxutils import escape as xml_escape

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from mail_utils import base64_lines, header_value

SYNTHETIC_NOTICE = (
    "Dieses Dokument ist Teil des synthetischen Demo-Korpus der fiktiven Stadt Rheinfurt "
    "(siehe SOURCE.md im Wurzelverzeichnis dieses Korpus). Gremien, Personen, Beschlüsse und "
    "Beträge sind frei erfunden."
)

STADTRAT = "Stadtrat der Stadt Rheinfurt"
HAUPTAUSSCHUSS = "Hauptausschuss der Stadt Rheinfurt"
BAUAUSSCHUSS = "Bauausschuss der Stadt Rheinfurt"
# The committee's folder below the year prefix, a single key segment each.
GREMIUM_FOLDERS = {
    STADTRAT: "Stadtrat",
    HAUPTAUSSCHUSS: "Hauptausschuss",
    BAUAUSSCHUSS: "Bauausschuss",
}
SITZUNGSORT = "Großer Sitzungssaal, Rathaus Rheinfurt, Rathausplatz 1"


@dataclass
class Tagesordnungspunkt:
    nummer: int
    titel: str
    vortrag: list[str]
    beschluss: str | None = None
    abstimmung: str | None = None


@dataclass
class Niederschrift:
    slug: str
    gremium: str
    datum: str  # ISO date
    beginn: str
    ende: str
    vorsitz: str
    anwesend: int
    entschuldigt: list[str]
    tops: list[Tagesordnungspunkt]


@dataclass
class Abschnitt:
    ueberschrift: str
    absaetze: list[str]
    tabelle: list[list[str]] | None = None  # first row is the header


@dataclass
class Anlage:
    nummer: int
    slug: str  # file name part after "<vorlage>-anlage-<nummer>-"
    titel: str
    abschnitte: list[Abschnitt]


@dataclass
class Versand:
    """The dispatch mail a Vorlage with Anlagen is stored as."""

    datum: str  # RFC 5322 date, a fixed literal
    an: str


@dataclass
class Beschlussvorlage:
    slug: str
    gremium: str
    sitzungsdatum: str  # ISO date
    vorlagennummer: str
    betreff: str
    federfuehrung: str
    beschlussvorschlag: str
    begruendung: list[str]
    finanzielle_auswirkungen: str
    beteiligung: str
    anlagen: list[Anlage] = field(default_factory=list)
    versand: Versand | None = None  # required exactly when anlagen is non-empty


NIEDERSCHRIFTEN: list[Niederschrift] = [
    Niederschrift(
        "2024-02-27-stadtrat-niederschrift",
        STADTRAT,
        "2024-02-27",
        "18:00 Uhr",
        "21:10 Uhr",
        "Oberbürgermeisterin Dr. Karin Lindhoff",
        34,
        ["Ratsmitglied Jonas Pfeil", "Ratsmitglied Ruth Amberg"],
        [
            Tagesordnungspunkt(
                1,
                "Haushaltssatzung und Haushaltsplan 2024",
                [
                    "Die Kämmerin Frau Dagmar Roth erläutert den Entwurf. Der Ergebnishaushalt "
                    "schließt mit einem Fehlbetrag von 1,8 Millionen Euro ab, der aus der "
                    "Ausgleichsrücklage gedeckt wird. Die Investitionsschwerpunkte liegen beim "
                    "Schulzentrum Rheinau und bei der Digitalisierung der Verwaltung.",
                    "In der Aussprache wird die Höhe der Personalkosten im Bürgerbüro angesprochen. "
                    "Die Verwaltung verweist auf die gestiegenen Fallzahlen im Meldewesen seit 2022.",
                ],
                "Der Stadtrat beschließt die Haushaltssatzung 2024 mit dem Haushaltsplan und dem "
                "Stellenplan in der Fassung der Vorlage 2024/007.",
                "28 Ja-Stimmen, 4 Nein-Stimmen, 2 Enthaltungen",
            ),
            Tagesordnungspunkt(
                2,
                "Sanierung des Brunnens auf dem Rathausplatz",
                [
                    "Das Tiefbauamt berichtet über die Schäden am Brunnenbecken. Die Kostenschätzung "
                    "beläuft sich auf 145.000 Euro; ein Zuschuss aus dem Landesprogramm "
                    "Ortskernsanierung in Höhe von 40 Prozent ist beantragt.",
                ],
                "Der Stadtrat beauftragt die Verwaltung, die Sanierung des Rathausplatzbrunnens "
                "auszuschreiben. Der Baubeginn soll nach dem Stadtfest 2024 liegen.",
                "einstimmig",
            ),
            Tagesordnungspunkt(
                3,
                "Neubesetzung des Seniorenbeirats",
                [
                    "Nach dem Ausscheiden von zwei Mitgliedern schlägt der Seniorenbeirat Frau "
                    "Hedwig Sommer und Herrn Ekkehard Blum als Nachfolger vor.",
                ],
                "Der Stadtrat bestellt Frau Hedwig Sommer und Herrn Ekkehard Blum für die "
                "verbleibende Amtszeit bis 2027 in den Seniorenbeirat.",
                "einstimmig",
            ),
            Tagesordnungspunkt(
                4,
                "Anfragen und Mitteilungen",
                [
                    "Ratsmitglied Timo Vahle fragt nach dem Stand der Fahrradabstellanlage am "
                    "Bahnhofsvorplatz. Die Verwaltung sagt eine schriftliche Antwort bis zur "
                    "nächsten Sitzung zu.",
                ],
            ),
        ],
    ),
    Niederschrift(
        "2024-09-17-stadtrat-niederschrift",
        STADTRAT,
        "2024-09-17",
        "18:00 Uhr",
        "20:35 Uhr",
        "Oberbürgermeisterin Dr. Karin Lindhoff",
        35,
        ["Ratsmitglied Ruth Amberg"],
        [
            Tagesordnungspunkt(
                1,
                "Digitalisierungsstrategie der Stadtverwaltung 2024 bis 2028",
                [
                    "Der Leiter des Amts für Organisation und IT, Herr Malte Brenner, stellt die "
                    "Strategie vor. Vier Handlungsfelder: elektronische Aktenführung, "
                    "Online-Dienste für Bürgerinnen und Bürger, Datensicherheit und "
                    "Qualifizierung der Beschäftigten.",
                    "Als erste Maßnahme soll 2025 ein Ratsinformationssystem mit elektronischer "
                    "Sitzungsverwaltung eingeführt werden; die Niederschriften und Vorlagen des "
                    "Stadtrats werden dann in einem zentralen Dokumentenarchiv abgelegt.",
                ],
                "Der Stadtrat beschließt die Digitalisierungsstrategie 2024 bis 2028 als "
                "Handlungsrahmen und beauftragt die Verwaltung, jährlich über den Umsetzungsstand "
                "zu berichten.",
                "31 Ja-Stimmen, 0 Nein-Stimmen, 4 Enthaltungen",
            ),
            Tagesordnungspunkt(
                2,
                "Zuschuss für das Rheinfurter Stadtfest 2025",
                [
                    "Der Verein Rheinfurter Stadtfest e. V. beantragt einen Zuschuss von 25.000 Euro "
                    "für Bühnentechnik und Sicherheitskonzept.",
                ],
                "Der Stadtrat bewilligt dem Verein Rheinfurter Stadtfest e. V. einen Zuschuss von "
                "20.000 Euro für das Stadtfest 2025.",
                "27 Ja-Stimmen, 6 Nein-Stimmen, 2 Enthaltungen",
            ),
            Tagesordnungspunkt(
                3,
                "Bürgerhaushalt: Auswertung der Vorschläge 2024",
                [
                    "Von 212 eingereichten Vorschlägen erfüllen 148 die Zulassungskriterien. Die "
                    "drei bestbewerteten Vorschläge betreffen einen Trinkwasserbrunnen im "
                    "Stadtpark, mehr Sitzbänke an der Rheinpromenade und ein "
                    "Sommerferienprogramm für Grundschulkinder.",
                ],
                "Der Stadtrat nimmt die Auswertung zur Kenntnis und beauftragt die Verwaltung, die "
                "drei bestbewerteten Vorschläge im Haushalt 2025 zu veranschlagen.",
                "einstimmig",
            ),
        ],
    ),
    Niederschrift(
        "2025-02-11-hauptausschuss-niederschrift",
        HAUPTAUSSCHUSS,
        "2025-02-11",
        "17:00 Uhr",
        "19:20 Uhr",
        "Oberbürgermeisterin Dr. Karin Lindhoff",
        11,
        [],
        [
            Tagesordnungspunkt(
                1,
                "Mobiles Bürgerbüro: Standorte und Sprechtage",
                [
                    "Die Leiterin des Bürgerbüros, Frau Andrea Vogt, berichtet über den "
                    "Probebetrieb des Bürgerkoffers in zwei Pflegeeinrichtungen. In vier Monaten "
                    "wurden 86 Anliegen bearbeitet, überwiegend Ausweisangelegenheiten.",
                    "Vorgeschlagen werden feste Sprechtage: jeden ersten Dienstag im Monat im "
                    "Stadtteilzentrum Rheinau, jeden dritten Donnerstag im Bürgertreff Weststadt.",
                ],
                "Der Hauptausschuss beschließt die Fortführung des mobilen Bürgerbüros mit den "
                "beiden Sprechtagen in Rheinau und Weststadt ab April 2025.",
                "einstimmig",
            ),
            Tagesordnungspunkt(
                2,
                "Vergabe: Ratsinformationssystem und elektronische Sitzungsverwaltung",
                [
                    "Die Verwaltung stellt das Ergebnis der Ausschreibung vor. Drei Angebote wurden "
                    "gewertet; das wirtschaftlichste Angebot liegt bei 96.400 Euro für fünf Jahre "
                    "einschließlich Betrieb.",
                ],
                "Der Hauptausschuss beschließt die Vergabe des Ratsinformationssystems an den "
                "wirtschaftlichsten Bieter gemäß Vergabevermerk 2025/V-03.",
                "10 Ja-Stimmen, 0 Nein-Stimmen, 1 Enthaltung",
            ),
        ],
    ),
    Niederschrift(
        "2025-06-24-stadtrat-niederschrift",
        STADTRAT,
        "2025-06-24",
        "18:00 Uhr",
        "21:40 Uhr",
        "Oberbürgermeisterin Dr. Karin Lindhoff",
        33,
        ["Ratsmitglied Jonas Pfeil", "Ratsmitglied Beate Cordes", "Ratsmitglied Ali Demirci"],
        [
            Tagesordnungspunkt(
                1,
                "Einrichtung eines Jugendparlaments",
                [
                    "Auf Antrag mehrerer Fraktionen soll ein Jugendparlament mit 15 Sitzen für "
                    "Jugendliche zwischen 14 und 18 Jahren eingerichtet werden. Die Wahl soll "
                    "an den weiterführenden Schulen stattfinden.",
                ],
                "Der Stadtrat beschließt die Einrichtung eines Jugendparlaments mit 15 Sitzen. Die "
                "erste Wahl findet im Frühjahr 2026 statt; das Jugendparlament erhält ein "
                "Antragsrecht gegenüber dem Stadtrat.",
                "30 Ja-Stimmen, 2 Nein-Stimmen, 1 Enthaltung",
            ),
            Tagesordnungspunkt(
                2,
                "Fahrradstraße Uferstraße",
                [
                    "Das Tiefbauamt legt die Planung für die Umwidmung der Uferstraße zwischen "
                    "Rheinpromenade und Bahnhofstraße zur Fahrradstraße vor. Anliegerverkehr "
                    "bleibt zulässig.",
                    "In der Aussprache werden Bedenken zur Erreichbarkeit der Gewerbebetriebe "
                    "geäußert; die Verwaltung sagt eine Evaluation nach einem Jahr zu.",
                ],
                "Der Stadtrat beschließt die Einrichtung der Fahrradstraße Uferstraße ab Herbst "
                "2025 mit Evaluation nach zwölf Monaten.",
                "22 Ja-Stimmen, 9 Nein-Stimmen, 2 Enthaltungen",
            ),
            Tagesordnungspunkt(
                3,
                "Öffnungszeiten des Freibads in der Sommersaison 2025",
                [
                    "Die Stadtwerke schlagen wegen des gestiegenen Besucheraufkommens eine "
                    "Verlängerung der Öffnungszeit an Wochenenden bis 20 Uhr vor.",
                ],
                "Der Stadtrat stimmt der verlängerten Öffnungszeit des Freibads an Wochenenden bis "
                "20 Uhr für die Saison 2025 zu.",
                "einstimmig",
            ),
        ],
    ),
    Niederschrift(
        "2025-12-09-stadtrat-niederschrift",
        STADTRAT,
        "2025-12-09",
        "18:00 Uhr",
        "22:05 Uhr",
        "Oberbürgermeisterin Dr. Karin Lindhoff",
        36,
        [],
        [
            Tagesordnungspunkt(
                1,
                "Haushaltssatzung und Haushaltsplan 2026",
                [
                    "Die Kämmerin Frau Dagmar Roth stellt den Entwurf vor. Der Ergebnishaushalt "
                    "2026 ist mit einem Überschuss von 0,4 Millionen Euro ausgeglichen. Die "
                    "größten Investitionen sind der Neubau der Feuerwache Süd und die "
                    "Photovoltaikanlagen auf städtischen Dächern.",
                ],
                "Der Stadtrat beschließt die Haushaltssatzung 2026 mit dem Haushaltsplan und dem "
                "Stellenplan in der Fassung der Vorlage 2025/041.",
                "29 Ja-Stimmen, 5 Nein-Stimmen, 2 Enthaltungen",
            ),
            Tagesordnungspunkt(
                2,
                "Städtepartnerschaft mit Sainte-Aurélie",
                [
                    "Nach dreijährigem Schüleraustausch schlägt die Verwaltung eine förmliche "
                    "Städtepartnerschaft mit der Gemeinde Sainte-Aurélie vor. Die "
                    "Partnerschaftsurkunde soll beim Stadtfest 2026 unterzeichnet werden.",
                ],
                "Der Stadtrat beschließt die Städtepartnerschaft mit Sainte-Aurélie und ermächtigt "
                "die Oberbürgermeisterin zur Unterzeichnung der Partnerschaftsurkunde.",
                "einstimmig",
            ),
            Tagesordnungspunkt(
                3,
                "Bericht zur Digitalisierungsstrategie 2025",
                [
                    "Das Ratsinformationssystem ist seit Oktober 2025 in Betrieb; alle "
                    "Niederschriften und Vorlagen seit 2024 sind im zentralen Dokumentenarchiv "
                    "abgelegt. Die elektronische Aktenführung im Bürgerbüro startet im ersten "
                    "Quartal 2026.",
                ],
                "Der Stadtrat nimmt den Bericht zur Kenntnis.",
                None,
            ),
        ],
    ),
    Niederschrift(
        "2026-04-21-hauptausschuss-niederschrift",
        HAUPTAUSSCHUSS,
        "2026-04-21",
        "17:00 Uhr",
        "19:45 Uhr",
        "Oberbürgermeisterin Dr. Karin Lindhoff",
        11,
        [],
        [
            Tagesordnungspunkt(
                1,
                "Evaluation des mobilen Bürgerbüros",
                [
                    "Nach einem Jahr fester Sprechtage wurden 412 Anliegen bearbeitet, davon "
                    "61 Prozent Ausweisangelegenheiten und 24 Prozent Meldeangelegenheiten. Die "
                    "Zufriedenheit der Befragten liegt bei 94 Prozent.",
                ],
                "Der Hauptausschuss beschließt, das mobile Bürgerbüro unbefristet fortzuführen und "
                "um einen Sprechtag im Ortsteil Nordfeld zu erweitern.",
                "einstimmig",
            ),
            Tagesordnungspunkt(
                2,
                "Photovoltaik auf städtischen Dächern: Ausbaustufe 2",
                [
                    "Die Stadtwerke berichten über die erste Ausbaustufe (Rathaus, Schulzentrum "
                    "Rheinau, Feuerwache Mitte) mit einer Leistung von 310 Kilowatt-Peak. Für die "
                    "zweite Ausbaustufe sind vier weitere Dächer vorgesehen.",
                ],
                "Der Hauptausschuss beauftragt die Stadtwerke mit der Planung der zweiten "
                "Ausbaustufe; der Baubeschluss wird dem Stadtrat vorgelegt.",
                "10 Ja-Stimmen, 1 Nein-Stimme, 0 Enthaltungen",
            ),
            Tagesordnungspunkt(
                3,
                "Stadtfest 2026: Sicherheitskonzept",
                [
                    "Das Ordnungsamt stellt das mit Polizei und Feuerwehr abgestimmte "
                    "Sicherheitskonzept vor. Neu sind Zufahrtssperren an vier Zugängen zum "
                    "Rathausplatz und ein Sanitätsdienst mit zwei Stationen.",
                ],
                "Der Hauptausschuss nimmt das Sicherheitskonzept zustimmend zur Kenntnis.",
                None,
            ),
        ],
    ),
    Niederschrift(
        "2025-05-20-bauausschuss-niederschrift",
        BAUAUSSCHUSS,
        "2025-05-20",
        "16:30 Uhr",
        "18:40 Uhr",
        "Bürgermeisterin Sabine Hartung",
        13,
        [],
        [
            Tagesordnungspunkt(
                1,
                "Fahrradstraße Uferstraße: Vorberatung",
                [
                    "Das Tiefbauamt stellt die Planung für die Umwidmung der Uferstraße zwischen "
                    "Rheinpromenade und Bahnhofstraße vor: Beschilderung, rote Markierung der "
                    "Einmündungen und zwei zusätzliche Querungshilfen. Die Kosten betragen 86.000 "
                    "Euro.",
                    "Aus dem Ausschuss wird angeregt, die Lieferzonen der Gewerbebetriebe vor "
                    "Beginn der Umwidmung mit den Anliegern abzustimmen.",
                ],
                "Der Bauausschuss empfiehlt dem Stadtrat, die Einrichtung der Fahrradstraße "
                "Uferstraße zu beschließen.",
                "9 Ja-Stimmen, 3 Nein-Stimmen, 1 Enthaltung",
            ),
            Tagesordnungspunkt(
                2,
                "Sanierung des Marktbrunnens auf dem Rathausplatz: Zwischenbericht",
                [
                    "Das Tiefbauamt berichtet, dass die restauratorische Befunduntersuchung "
                    "abgeschlossen ist. Die Untere Denkmalbehörde hat der Bemusterung der "
                    "Natursteine zugestimmt. Die Einfassungssteine des Beckenrands werden ab Herbst "
                    "2025 einzeln abgenommen und in der Werkstatt aufgearbeitet; das Becken bleibt "
                    "bis zum Beginn der Arbeiten am Brunnen im April 2026 gefüllt.",
                    "Ein schadhafter Zulaufschieber muss zusätzlich ersetzt werden. Die Mehrkosten "
                    "von 2.700 Euro wurden der Förderstelle vor der Ausführung angezeigt und sind "
                    "damit förderfähig.",
                    "Die Arbeiten am Brunnen selbst bleiben für April und Mai 2026 vorgesehen. Der "
                    "Brunnen besteht 2026 seit 150 Jahren und soll zum Jubiläum am 30. Mai 2026 neu "
                    "eingeweiht werden.",
                ],
                "Der Bauausschuss nimmt den Zwischenbericht zur Kenntnis.",
                None,
            ),
            Tagesordnungspunkt(
                3,
                "Dachsanierung der Sporthalle am Schulzentrum Rheinau",
                [
                    "Das Hochbauamt legt die Entwurfsplanung vor. Die Dachabdichtung der Sporthalle "
                    "ist nach 31 Jahren an mehreren Stellen undicht; die Kostenberechnung beläuft "
                    "sich auf 612.000 Euro. Die Ausführung ist für die Sommerferien 2025 vorgesehen, "
                    "damit der Schulsport nicht ausfällt.",
                    "Die Tragfähigkeit des neuen Dachaufbaus wird so bemessen, dass später eine "
                    "Photovoltaikanlage aufgesetzt werden kann.",
                ],
                "Der Bauausschuss beschließt die Entwurfsplanung und beauftragt die Verwaltung mit "
                "der Ausschreibung der Dachsanierung.",
                "einstimmig",
            ),
        ],
    ),
    Niederschrift(
        "2026-05-12-bauausschuss-niederschrift",
        BAUAUSSCHUSS,
        "2026-05-12",
        "16:30 Uhr",
        "18:15 Uhr",
        "Bürgermeisterin Sabine Hartung",
        12,
        ["Ratsmitglied Beate Cordes"],
        [
            Tagesordnungspunkt(
                1,
                "Neubau der Feuerwache Süd: Auslobung des Architektenwettbewerbs",
                [
                    "Nach dem Grundsatzbeschluss des Stadtrats vom 24. Februar 2026 legt das "
                    "Hochbauamt den Entwurf der Auslobung vor: ein nichtoffener "
                    "Realisierungswettbewerb mit 15 Teilnehmenden und einem Preisgericht aus sieben "
                    "Fach- und sechs Sachpreisrichtern, darunter der Leiter der Freiwilligen "
                    "Feuerwehr Rheinfurt.",
                    "Die Wettbewerbsarbeiten sind im Oktober 2026 abzugeben; die Sitzung des "
                    "Preisgerichts ist für November 2026 vorgesehen.",
                ],
                "Der Bauausschuss stimmt der Auslobung des Architektenwettbewerbs für die "
                "Feuerwache Süd in der vorgelegten Fassung zu.",
                "11 Ja-Stimmen, 0 Nein-Stimmen, 1 Enthaltung",
            ),
            Tagesordnungspunkt(
                2,
                "Photovoltaik auf städtischen Dächern, Ausbaustufe 2: statische Prüfung",
                [
                    "Von den vier vorgesehenen Dächern (Grundschule Weststadt, Stadtbibliothek, "
                    "Bauhof, Sporthalle Nordfeld) sind drei ohne Verstärkung geeignet. Das Dach der "
                    "Stadtbibliothek braucht eine Ertüchtigung der Dachträger für rund 48.000 Euro.",
                ],
                "Der Bauausschuss empfiehlt, die zweite Ausbaustufe mit allen vier Dächern dem "
                "Stadtrat zum Baubeschluss vorzulegen.",
                "einstimmig",
            ),
            Tagesordnungspunkt(
                3,
                "Anfragen und Mitteilungen",
                [
                    "Ratsmitglied Timo Vahle fragt nach der Evaluation der Fahrradstraße "
                    "Uferstraße. Die Verwaltung kündigt den Bericht für die Sitzung im Oktober "
                    "2026 an.",
                    "Die Verwaltung teilt mit, dass die Sanierung des Marktbrunnens im Zeitplan "
                    "liegt. Die Neueinweihung zum 150-jährigen Bestehen findet wie geplant am "
                    "30. Mai 2026 statt.",
                ],
            ),
        ],
    ),
]


BESCHLUSSVORLAGEN: list[Beschlussvorlage] = [
    Beschlussvorlage(
        "2024-05-14-hauptausschuss-vorlage-buergerkoffer",
        HAUPTAUSSCHUSS,
        "2024-05-14",
        "2024/019",
        "Anschaffung eines Bürgerkoffers für die mobile Beratung in Pflegeeinrichtungen",
        "Bürgerbüro Rheinfurt",
        "Der Hauptausschuss beschließt die Anschaffung eines Bürgerkoffers (mobiles "
        "Erfassungsgerät für Ausweisanträge mit Fingerabdruckscanner und Signaturpad) und einen "
        "viermonatigen Probebetrieb in zwei Pflegeeinrichtungen ab September 2024.",
        [
            "Bewohnerinnen und Bewohner von Pflegeeinrichtungen können das Bürgerbüro häufig nicht "
            "persönlich aufsuchen. Ein Bürgerkoffer erlaubt die Aufnahme von Ausweisanträgen und "
            "Meldevorgängen vor Ort mit derselben Technik wie am Schalter.",
            "Vergleichbare Geräte sind in mehreren Mittelstädten im Einsatz. Die Verwaltung "
            "empfiehlt einen zeitlich befristeten Probebetrieb mit anschließender Auswertung.",
        ],
        "Einmalig 9.800 Euro für das Gerät und 1.200 Euro jährlich für Wartung und "
        "Datenverbindung; Deckung aus dem Ansatz Bürgerbüro-Sachaufwand 2024.",
        "Amt für Organisation und IT, Datenschutzbeauftragte",
    ),
    Beschlussvorlage(
        "2024-10-15-bauausschuss-vorlage-brunnen-rathausplatz",
        BAUAUSSCHUSS,
        "2024-10-15",
        "2024/044",
        "Sanierung des Brunnens auf dem Rathausplatz: Vergabe der Bauleistungen",
        "Tiefbauamt",
        "Der Bauausschuss beschließt, die Bauleistungen zur Sanierung des Rathausplatzbrunnens an "
        "den wirtschaftlichsten Bieter der öffentlichen Ausschreibung zum Angebotspreis von "
        "138.600 Euro zu vergeben. Die Arbeiten beginnen im November 2024 mit der restauratorischen "
        "Befunduntersuchung; die Arbeiten am Brunnen selbst werden so gelegt, dass der "
        "Marktbrunnen zu seinem 150-jährigen Bestehen am 30. Mai 2026 neu eingeweiht werden kann.",
        [
            "Der Stadtrat hat die Verwaltung am 27. Februar 2024 beauftragt, die Sanierung "
            "auszuschreiben; der Baubeginn sollte nach dem Stadtfest 2024 liegen. Auf die "
            "öffentliche Ausschreibung sind vier Angebote eingegangen. Das wirtschaftlichste liegt "
            "6.400 Euro unter der Kostenschätzung von 145.000 Euro.",
            "Die Untere Denkmalbehörde verlangt vor der Ausführung eine restauratorische "
            "Befunduntersuchung und eine Bemusterung der Natursteine. Die Arbeiten am Brunnen "
            "selbst folgen deshalb erst im April und Mai 2026 und enden rechtzeitig zum "
            "Jubiläum: Der Marktbrunnen besteht 2026 seit 150 Jahren.",
            "Die Einzelpositionen, die Finanzierung und der Bauzeitenplan stehen in Anlage 1.",
        ],
        "Auftragssumme 138.600 Euro. Der Zuschuss aus dem Landesprogramm Ortskernsanierung "
        "(40 Prozent der förderfähigen Kosten) ist bewilligt; der Eigenanteil ist im Haushalt 2024 "
        "veranschlagt.",
        "Untere Denkmalbehörde, Stadtwerke Rheinfurt (Wasserversorgung)",
        anlagen=[
            Anlage(
                1,
                "kostenaufstellung-bauzeitenplan",
                "Kostenaufstellung und Bauzeitenplan: Sanierung des Marktbrunnens",
                [
                    Abschnitt(
                        "Kosten nach Leistungsbereichen",
                        [
                            "Angebot des wirtschaftlichsten Bieters, Bruttobeträge einschließlich "
                            "Umsatzsteuer.",
                        ],
                        [
                            ["Leistungsbereich", "Betrag"],
                            ["Baustelleneinrichtung und Verkehrssicherung", "9.800 Euro"],
                            ["Rückbau der schadhaften Beckenauskleidung", "14.200 Euro"],
                            ["Abdichtung des Brunnenbeckens", "38.500 Euro"],
                            ["Natursteinarbeiten am Beckenrand", "41.300 Euro"],
                            ["Brunnentechnik (Pumpe, Filter, Steuerung)", "26.900 Euro"],
                            ["Beleuchtung und Elektroinstallation", "7.900 Euro"],
                            ["Summe", "138.600 Euro"],
                        ],
                    ),
                    Abschnitt(
                        "Finanzierung",
                        [
                            "Der Zuschuss wurde am 12. August 2024 bewilligt. Mehrkosten über die "
                            "Auftragssumme hinaus sind nur zu 40 Prozent förderfähig, wenn sie vor "
                            "der Ausführung angezeigt werden.",
                        ],
                        [
                            ["Position", "Betrag"],
                            ["Förderfähige Kosten", "138.600 Euro"],
                            ["Zuschuss Landesprogramm Ortskernsanierung (40 Prozent)", "55.440 Euro"],
                            ["Eigenanteil der Stadt Rheinfurt", "83.160 Euro"],
                        ],
                    ),
                    Abschnitt(
                        "Bauzeitenplan",
                        [
                            "November 2024 bis März 2025: restauratorische Befunduntersuchung und "
                            "Bemusterung der Natursteine mit der Unteren Denkmalbehörde; der "
                            "Brunnen bleibt in dieser Zeit in Betrieb.",
                            "Herbst 2025 bis Frühjahr 2026: Aufarbeitung der Einfassungssteine des "
                            "Beckenrands in der Werkstatt; sie werden dafür einzeln abgenommen, "
                            "das Becken bleibt bis April 2026 gefüllt.",
                            "April und Mai 2026: Baustelleneinrichtung, Entleerung des Beckens, "
                            "Rückbau der alten Auskleidung, Abdichtung, Versetzen der Natursteine "
                            "und Einbau der Brunnentechnik.",
                            "30. Mai 2026: Neueinweihung des Marktbrunnens zu seinem 150-jährigen "
                            "Bestehen.",
                        ],
                    ),
                    Abschnitt(
                        "Vergabevorschlag",
                        [
                            "Eingegangen sind vier Angebote über 138.600 Euro, 149.200 Euro, "
                            "152.750 Euro und 171.400 Euro. Gewertet wurde allein nach dem Preis. "
                            "Der Bieter mit dem niedrigsten Angebot hat seine Eignung mit zwei "
                            "vergleichbaren Brunnensanierungen aus den letzten fünf Jahren "
                            "nachgewiesen.",
                        ],
                    ),
                ],
            ),
        ],
        versand=Versand(
            "Tue, 1 Oct 2024 09:00:00 +0200",
            "Mitglieder des Bauausschusses <bauausschuss@stadt-rheinfurt.example>",
        ),
    ),
    Beschlussvorlage(
        "2024-11-26-stadtrat-vorlage-stellenplan-buergerbuero",
        STADTRAT,
        "2024-11-26",
        "2024/052",
        "Stellenplan 2025: Zwei zusätzliche Stellen im Bürgerbüro",
        "Amt für Personal und Organisation",
        "Der Stadtrat beschließt, im Stellenplan 2025 zwei zusätzliche Stellen der "
        "Entgeltgruppe 9a für das Sachgebiet Meldewesen und Ausweise des Bürgerbüros "
        "auszuweisen.",
        [
            "Die Fallzahlen im Meldewesen sind seit 2022 um 18 Prozent gestiegen; die "
            "Wartezeit auf einen Termin beträgt im Durchschnitt 19 Werktage.",
            "Mit zwei zusätzlichen Stellen kann die Wartezeit nach Berechnung der Verwaltung auf "
            "unter zehn Werktage gesenkt und der Sprechtag des mobilen Bürgerbüros dauerhaft "
            "besetzt werden.",
        ],
        "Rund 128.000 Euro jährlich; im Haushaltsentwurf 2025 berücksichtigt.",
        "Personalrat (Zustimmung liegt vor)",
    ),
    Beschlussvorlage(
        "2025-04-08-stadtrat-vorlage-dokumentenarchiv",
        STADTRAT,
        "2025-04-08",
        "2025/014",
        "Zentrales Dokumentenarchiv für Niederschriften und Vorlagen",
        "Amt für Organisation und IT",
        "Der Stadtrat beschließt, alle Niederschriften und Beschlussvorlagen des Stadtrats und "
        "seiner Ausschüsse ab dem Jahrgang 2024 in einem zentralen, revisionssicheren "
        "Dokumentenarchiv abzulegen und dieses Archiv als Quelle für den Wissensassistenten der "
        "Verwaltung freizugeben.",
        [
            "Die Digitalisierungsstrategie 2024 bis 2028 sieht ein Ratsinformationssystem vor. "
            "Dessen Dokumente sollen nicht nur im System selbst, sondern in einem "
            "systemunabhängigen Archiv nach Jahrgängen abgelegt werden, damit sie auch nach einem "
            "Systemwechsel auffindbar bleiben.",
            "Die Ablage nach Jahrgängen ermöglicht es, einzelne Jahrgänge gezielt freizugeben "
            "oder zu sperren.",
        ],
        "Keine zusätzlichen Kosten; Speicher und Betrieb sind im Vertrag über das "
        "Ratsinformationssystem enthalten.",
        "Datenschutzbeauftragte, Stadtarchiv",
    ),
    Beschlussvorlage(
        "2025-09-23-hauptausschuss-vorlage-waermeplanung",
        HAUPTAUSSCHUSS,
        "2025-09-23",
        "2025/033",
        "Kommunale Wärmeplanung: Zwischenbericht und weiteres Vorgehen",
        "Stadtwerke Rheinfurt",
        "Der Hauptausschuss nimmt den Zwischenbericht zur kommunalen Wärmeplanung zur Kenntnis "
        "und beauftragt die Stadtwerke, bis zum Sommer 2026 Eignungsgebiete für Wärmenetze in "
        "der Innenstadt und in Rheinau auszuweisen.",
        [
            "Die Bestandsanalyse ist abgeschlossen: 62 Prozent der Gebäude werden mit Erdgas "
            "beheizt, 21 Prozent mit Heizöl. Die Potenzialanalyse weist Abwärme aus dem "
            "Gewerbegebiet Nordfeld und Flusswasserwärme als größte Quellen aus.",
            "Für die nächste Phase ist eine Beteiligung der Öffentlichkeit mit zwei "
            "Informationsveranstaltungen vorgesehen.",
        ],
        "Die Planungskosten von 180.000 Euro werden zu 90 Prozent gefördert; der Eigenanteil ist "
        "im Haushalt 2025 veranschlagt.",
        "Umweltamt, Stadtplanungsamt",
    ),
    Beschlussvorlage(
        "2026-02-24-stadtrat-vorlage-feuerwache-sued",
        STADTRAT,
        "2026-02-24",
        "2026/006",
        "Neubau der Feuerwache Süd: Grundsatzbeschluss",
        "Amt für Brand- und Katastrophenschutz",
        "Der Stadtrat fasst den Grundsatzbeschluss zum Neubau der Feuerwache Süd am Standort "
        "Festplatz und beauftragt die Verwaltung mit der Durchführung eines "
        "Architektenwettbewerbs.",
        [
            "Die bestehende Feuerwache Süd aus dem Jahr 1968 erfüllt die Anforderungen an "
            "Schwarz-Weiß-Trennung, Fahrzeughallenhöhe und Ausrückzeiten nicht mehr. Der "
            "Brandschutzbedarfsplan 2025 weist für den Süden der Stadt eine Hilfsfrist-"
            "Überschreitung in zwölf Prozent der Einsätze aus.",
            "Der Standort Festplatz liegt im Eigentum der Stadt und erlaubt eine Ausrückzeit "
            "unter acht Minuten für alle südlichen Ortsteile.",
        ],
        "Kostenrahmen 14,5 Millionen Euro; Förderung aus dem Landesprogramm Feuerwehrhäuser "
        "beantragt. Mittel für den Wettbewerb (220.000 Euro) sind im Haushalt 2026 veranschlagt.",
        "Freiwillige Feuerwehr Rheinfurt, Stadtplanungsamt",
        anlagen=[
            Anlage(
                1,
                "lageplan-erlaeuterung",
                "Lageplan-Erläuterung: Standort Festplatz",
                [
                    Abschnitt(
                        "Lage und Grundstück",
                        [
                            "Das Baufeld umfasst den nördlichen Teil des Festplatzes mit rund 6.200 "
                            "Quadratmetern; das Grundstück steht im Eigentum der Stadt Rheinfurt. "
                            "Der südliche Teil bleibt mit rund 9.000 Quadratmetern als "
                            "Veranstaltungsfläche für Stadtfest und Märkte erhalten.",
                            "Die Haltestelle des Pendelbusses zum Stadtfest, bisher Festplatz Nord, "
                            "wird an die Südseite des Festplatzes verlegt.",
                        ],
                    ),
                    Abschnitt(
                        "Erschließung und Alarmausfahrt",
                        [
                            "Die Alarmausfahrt liegt an der Nordseite des Baufelds zur "
                            "Hauptverkehrsstraße hin und berührt die Veranstaltungsfläche nicht; "
                            "eine Vorrangschaltung der Ampel hält die Kreuzung bei Alarm frei.",
                            "Die Einsatzkräfte erreichen die Wache über eine getrennte Zufahrt von "
                            "Westen mit 40 Stellplätzen. Anfahrende Einsatzkräfte und ausrückende "
                            "Fahrzeuge kreuzen sich damit nicht.",
                        ],
                    ),
                    Abschnitt(
                        "Baukörper",
                        [
                            "Fahrzeughalle mit sechs Stellplätzen in Durchfahrtstellung und einer "
                            "lichten Höhe von 5,5 Metern.",
                            "Zweigeschossiger Sozial- und Verwaltungstrakt mit "
                            "Schwarz-Weiß-Trennung: getrennte Umkleiden für Einsatz- und "
                            "Privatkleidung, Stiefelwäsche und Atemschutzwerkstatt.",
                            "Übungshof mit Übungsturm an der Ostseite des Baufelds.",
                        ],
                    ),
                    Abschnitt(
                        "Erreichbarkeit der südlichen Ortsteile",
                        [
                            "Fahrzeiten ab Ausrücken nach der Berechnung des "
                            "Brandschutzbedarfsplans 2025. Vom Standort Festplatz aus wird jeder "
                            "südliche Ortsteil in weniger als acht Minuten erreicht.",
                        ],
                        [
                            ["Ortsteil", "Standort Festplatz", "bisherige Feuerwache Süd"],
                            ["Weidenau", "4,5 Minuten", "6,0 Minuten"],
                            ["Rheinbogen", "5,5 Minuten", "8,5 Minuten"],
                            ["Auenfeld", "6,5 Minuten", "9,5 Minuten"],
                            ["Lindenhof", "7,5 Minuten", "10,0 Minuten"],
                        ],
                    ),
                    Abschnitt(
                        "Hinweis",
                        [
                            "Der Lageplan selbst (Maßstab 1:500) liegt im Ratsinformationssystem "
                            "als Zeichnung vor. Diese Erläuterung gibt seinen Inhalt in Textform "
                            "wieder.",
                        ],
                    ),
                ],
            ),
            Anlage(
                2,
                "kostenaufstellung",
                "Kostenaufstellung: Kostenrahmen Neubau Feuerwache Süd",
                [
                    Abschnitt(
                        "Kostenrahmen nach DIN 276",
                        [
                            "Bruttokosten einschließlich Umsatzsteuer, Preisstand Januar 2026. Die "
                            "Einsatzfahrzeuge sind nicht enthalten; sie sind im "
                            "Brandschutzbedarfsplan gesondert veranschlagt.",
                        ],
                        [
                            ["Kostengruppe", "Bezeichnung", "Betrag"],
                            ["200", "Vorbereitende Maßnahmen", "400.000 Euro"],
                            ["300", "Bauwerk, Baukonstruktionen", "7.600.000 Euro"],
                            ["400", "Bauwerk, Technische Anlagen", "2.900.000 Euro"],
                            ["500", "Außenanlagen und Freiflächen", "900.000 Euro"],
                            ["600", "Ausstattung", "600.000 Euro"],
                            ["700", "Baunebenkosten", "2.100.000 Euro"],
                            ["", "Kostenrahmen gesamt", "14.500.000 Euro"],
                        ],
                    ),
                    Abschnitt(
                        "Baunebenkosten im Einzelnen",
                        [],
                        [
                            ["Leistung", "Betrag"],
                            ["Architektenwettbewerb", "220.000 Euro"],
                            ["Planung Architektur und Tragwerk", "1.310.000 Euro"],
                            ["Fachplanung Technische Gebäudeausrüstung", "380.000 Euro"],
                            ["Gutachten, Vermessung, Genehmigungen", "190.000 Euro"],
                            ["Baunebenkosten gesamt", "2.100.000 Euro"],
                        ],
                    ),
                    Abschnitt(
                        "Mittelabfluss und Finanzierung",
                        [
                            "Baubeginn ist für das Frühjahr 2028 vorgesehen, die Inbetriebnahme "
                            "für den Sommer 2030. Aus dem Landesprogramm Feuerwehrhäuser ist ein "
                            "Zuschuss von bis zu 3,0 Millionen Euro beantragt; der Bescheid wird "
                            "für den Herbst 2026 erwartet. Die Verpflichtungsermächtigungen ab 2027 "
                            "werden mit dem Haushalt 2027 beantragt.",
                        ],
                        [
                            ["Haushaltsjahr", "Mittelabfluss"],
                            ["2026", "220.000 Euro"],
                            ["2027", "1.100.000 Euro"],
                            ["2028", "5.200.000 Euro"],
                            ["2029", "6.400.000 Euro"],
                            ["2030", "1.580.000 Euro"],
                        ],
                    ),
                ],
            ),
        ],
        versand=Versand(
            "Tue, 10 Feb 2026 09:00:00 +0100",
            "Mitglieder des Stadtrats <stadtrat@stadt-rheinfurt.example>",
        ),
    ),
    Beschlussvorlage(
        "2026-07-07-stadtrat-vorlage-stadtbus-schueler",
        STADTRAT,
        "2026-07-07",
        "2026/021",
        "Kostenfreie Nutzung des Stadtbusses für Schülerinnen und Schüler ab 2027",
        "Stadtwerke Rheinfurt, Amt für Schulen und Sport",
        "Der Stadtrat beschließt, dass Schülerinnen und Schüler mit Wohnsitz in Rheinfurt den "
        "Stadtbus ab dem 1. Januar 2027 kostenfrei nutzen können, und beauftragt die "
        "Stadtwerke, das Verfahren zur Ausgabe der Schülerkarte zu regeln.",
        [
            "Im Schuljahr 2025/2026 nutzen 2.300 Schülerinnen und Schüler den Stadtbus; die "
            "Einnahmen aus Schülertickets betragen rund 410.000 Euro jährlich.",
            "Die kostenfreie Nutzung soll den Anteil des Busverkehrs am Schulweg erhöhen und den "
            "Elternverkehr vor den Schulen verringern.",
        ],
        "Einnahmeausfall von rund 410.000 Euro jährlich zuzüglich 30.000 Euro für die "
        "Schülerkarte; Deckung im Haushaltsentwurf 2027 vorzusehen.",
        "Elternbeiräte der Schulen, Jugendparlament",
    ),
]


def _german_date(iso_date: str) -> str:
    year, month, day = iso_date.split("-")
    return f"{int(day)}. {_MONTHS[int(month)]} {year}"


_MONTHS = {
    1: "Januar",
    2: "Februar",
    3: "März",
    4: "April",
    5: "Mai",
    6: "Juni",
    7: "Juli",
    8: "August",
    9: "September",
    10: "Oktober",
    11: "November",
    12: "Dezember",
}


def year_of(iso_date: str) -> str:
    return iso_date[:4]


def render_niederschrift_md(n: Niederschrift) -> bytes:
    lines = [
        f"# Niederschrift: {n.gremium}, Sitzung vom {_german_date(n.datum)}",
        "",
        f"**Sitzungsort:** {SITZUNGSORT}  ",
        f"**Beginn:** {n.beginn} · **Ende:** {n.ende}  ",
        f"**Vorsitz:** {n.vorsitz}  ",
        f"**Anwesend:** {n.anwesend} stimmberechtigte Mitglieder  ",
        "**Entschuldigt:** " + (", ".join(n.entschuldigt) if n.entschuldigt else "keine"),
        "",
        "Die Vorsitzende stellt die ordnungsgemäße Ladung und die Beschlussfähigkeit fest. Gegen "
        "die Tagesordnung werden keine Einwände erhoben.",
        "",
        "## Tagesordnung",
        "",
    ]
    for top in n.tops:
        lines.append(f"{top.nummer}. {top.titel}")
    lines.append("")
    for top in n.tops:
        lines.append(f"## TOP {top.nummer}: {top.titel}")
        lines.append("")
        for absatz in top.vortrag:
            lines.append(absatz)
            lines.append("")
        if top.beschluss:
            lines.append(f"**Beschluss:** {top.beschluss}")
            lines.append("")
        if top.abstimmung:
            lines.append(f"**Abstimmungsergebnis:** {top.abstimmung}")
            lines.append("")
    lines.append("---")
    lines.append("")
    lines.append(
        f"Für die Richtigkeit der Niederschrift: Schriftführerin Petra Hollmann, "
        f"Rheinfurt, {_german_date(n.datum)}."
    )
    lines.append("")
    lines.append(f"*{SYNTHETIC_NOTICE}*")
    lines.append("")
    return "\n".join(lines).encode("utf-8")


def render_vorlage_txt(v: Beschlussvorlage) -> bytes:
    lines = [
        "STADT RHEINFURT",
        f"Beschlussvorlage Nr. {v.vorlagennummer}",
        "",
        f"Gremium:        {v.gremium}",
        f"Sitzung am:     {_german_date(v.sitzungsdatum)}",
        f"Federführung:   {v.federfuehrung}",
        f"Beteiligt:      {v.beteiligung}",
        "Status:         öffentlich",
        "",
        f"Betreff: {v.betreff}",
        "",
        "Beschlussvorschlag",
        "------------------",
        v.beschlussvorschlag,
        "",
        "Begründung",
        "----------",
    ]
    for absatz in v.begruendung:
        lines.append(absatz)
        lines.append("")
    lines.append("Finanzielle Auswirkungen")
    lines.append("------------------------")
    lines.append(v.finanzielle_auswirkungen)
    lines.append("")
    if v.anlagen:
        lines.append("Anlagen")
        lines.append("-------")
        for anlage in v.anlagen:
            lines.append(f"Anlage {anlage.nummer}: {anlage.titel}")
        lines.append("")
    lines.append("Rheinfurt, im Auftrag: Amtsleitung " + v.federfuehrung.split(",")[0])
    lines.append("")
    lines.append(SYNTHETIC_NOTICE)
    lines.append("")
    return "\n".join(lines).encode("utf-8")


# --- Vorlagen with Anlagen: dispatch mail (.eml) with PDF attachments ---------

_VERSAND_FROM = "Sitzungsdienst der Stadt Rheinfurt <sitzungsdienst@stadt-rheinfurt.example>"

_STYLES = getSampleStyleSheet()
_ANLAGE_TITLE_STYLE = ParagraphStyle(
    "AnlageTitle", parent=_STYLES["Title"], fontSize=15, spaceAfter=12
)
_ANLAGE_HEADING_STYLE = ParagraphStyle(
    "AnlageHeading", parent=_STYLES["Heading3"], spaceBefore=10, spaceAfter=4
)
_ANLAGE_BODY_STYLE = ParagraphStyle("AnlageBody", parent=_STYLES["BodyText"], spaceAfter=6)
_ANLAGE_FOOTER_STYLE = ParagraphStyle(
    "AnlageFooter", parent=_STYLES["BodyText"], fontSize=8, textColor=colors.grey
)


def gremium_folder(gremium: str) -> str:
    return GREMIUM_FOLDERS[gremium]


def vorlage_file_name(v: Beschlussvorlage) -> str:
    return f"{v.slug}.eml" if v.anlagen else f"{v.slug}.txt"


def anlage_file_name(v: Beschlussvorlage, anlage: Anlage) -> str:
    return f"{v.slug}-anlage-{anlage.nummer}-{anlage.slug}.pdf"


def _anlage_kopf(v: Beschlussvorlage, anlage: Anlage) -> str:
    return (
        f"Anlage {anlage.nummer} zur Beschlussvorlage Nr. {v.vorlagennummer} "
        f"({v.gremium}, Sitzung am {_german_date(v.sitzungsdatum)})"
    )


def render_anlage_pdf(v: Beschlussvorlage, anlage: Anlage) -> bytes:
    buffer = BytesIO()
    doc = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=2.2 * cm,
        rightMargin=2.2 * cm,
        topMargin=2 * cm,
        bottomMargin=2 * cm,
        title=anlage.titel,
        author="Stadt Rheinfurt (synthetisch)",
    )
    story = [
        Paragraph(xml_escape(anlage.titel), _ANLAGE_TITLE_STYLE),
        Paragraph(xml_escape(_anlage_kopf(v, anlage)), _ANLAGE_FOOTER_STYLE),
        Paragraph(xml_escape(f"Betreff der Vorlage: {v.betreff}"), _ANLAGE_FOOTER_STYLE),
        Spacer(1, 0.4 * cm),
    ]
    for abschnitt in anlage.abschnitte:
        story.append(Paragraph(xml_escape(abschnitt.ueberschrift), _ANLAGE_HEADING_STYLE))
        for absatz in abschnitt.absaetze:
            story.append(Paragraph(xml_escape(absatz), _ANLAGE_BODY_STYLE))
        if abschnitt.tabelle:
            table = Table(abschnitt.tabelle, hAlign="LEFT")
            table.setStyle(
                TableStyle(
                    [
                        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#2f3e4e")),
                        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
                        ("ALIGN", (-1, 0), (-1, -1), "RIGHT"),
                        ("VALIGN", (0, 0), (-1, -1), "TOP"),
                        ("FONTSIZE", (0, 0), (-1, -1), 9),
                        ("TOPPADDING", (0, 0), (-1, -1), 4),
                        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
                    ]
                )
            )
            story.append(table)
            story.append(Spacer(1, 0.3 * cm))
    story.append(Spacer(1, 0.6 * cm))
    story.append(Paragraph(xml_escape(SYNTHETIC_NOTICE), _ANLAGE_FOOTER_STYLE))
    doc.build(story)
    return buffer.getvalue()


def _versand_text(v: Beschlussvorlage) -> str:
    return (
        "Sehr geehrte Damen und Herren,\n"
        "\n"
        f"zur Sitzung am {_german_date(v.sitzungsdatum)} ist im Ratsinformationssystem der Stadt "
        "Rheinfurt die folgende Beschlussvorlage freigegeben. Ihre Anlagen sind dieser Nachricht "
        "als PDF-Dateien beigefügt.\n"
        "\n"
        "Sitzungsdienst der Stadt Rheinfurt\n"
        "\n"
        "========================================================================\n"
        "\n"
    ) + render_vorlage_txt(v).decode("utf-8")


def render_vorlage_eml(v: Beschlussvorlage) -> bytes:
    """The dispatch mail: the Vorlage text as body, every Anlage a PDF attachment."""
    if v.versand is None:
        raise ValueError(f"Vorlage {v.vorlagennummer} has Anlagen but no Versand")
    nummer = v.vorlagennummer.replace("/", "-")
    boundary = f"=_rheinfurt-ratsinfo-{nummer}"
    parts = [
        "MIME-Version: 1.0",
        f"Date: {v.versand.datum}",
        f"Message-ID: <vorlage-{nummer}@ratsinfo.stadt-rheinfurt.example>",
        f"From: {_VERSAND_FROM}",
        f"To: {v.versand.an}",
        "Subject: " + header_value(f"Beschlussvorlage Nr. {v.vorlagennummer}: {v.betreff}"),
        f'Content-Type: multipart/mixed; boundary="{boundary}"',
        "",
        f"--{boundary}",
        'Content-Type: text/plain; charset="utf-8"',
        "Content-Transfer-Encoding: 8bit",
        "",
        _versand_text(v),
    ]
    for anlage in v.anlagen:
        file_name = anlage_file_name(v, anlage)
        parts += [
            f"--{boundary}",
            f'Content-Type: application/pdf; name="{file_name}"',
            "Content-Transfer-Encoding: base64",
            f'Content-Disposition: attachment; filename="{file_name}"',
            "",
            base64_lines(render_anlage_pdf(v, anlage)),
        ]
    parts += [f"--{boundary}--", ""]
    return "\n".join(parts).encode("utf-8")


def render_vorlage(v: Beschlussvorlage) -> bytes:
    return render_vorlage_eml(v) if v.anlagen else render_vorlage_txt(v)


def niederschrift_text(n: Niederschrift) -> str:
    return render_niederschrift_md(n).decode("utf-8")


def vorlage_text(v: Beschlussvorlage) -> str:
    return _versand_text(v) if v.anlagen else render_vorlage_txt(v).decode("utf-8")


def anlage_text(v: Beschlussvorlage, anlage: Anlage) -> str:
    parts = [anlage.titel, _anlage_kopf(v, anlage)]
    for abschnitt in anlage.abschnitte:
        parts.append(abschnitt.ueberschrift)
        parts.extend(abschnitt.absaetze)
        for row in abschnitt.tabelle or []:
            parts.append(" ".join(row))
    return "\n".join(parts)
