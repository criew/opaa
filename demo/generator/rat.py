"""Data and Markdown/text rendering for the "Ratsinformationen Stadt Rheinfurt"
library (S3 connector, see docs/features/demo-instance.md and docs/handbuch/konnektor-s3.md).

Council minutes (Niederschriften, .md) and decision papers (Beschlussvorlagen, .txt) of the
Stadtrat and the Hauptausschuss, laid out under one key prefix per year - the shape of a
records archive an administration keeps in an object store. The keys are what the demo's
MinIO bucket is seeded with (docker-compose.yml, service "minio-seed"), so the year prefixes
become folders in the library.

Every subject here is deliberately absent from the other five libraries (no fees, no
Meldewesen rules, no press-office topics): a question about a council decision is only
answerable from this library, which is what the demo smoke run asserts (e2e/demo-smoke).

All dates are fixed literals; the output is byte-identical across generator runs.
"""

from __future__ import annotations

from dataclasses import dataclass

SYNTHETIC_NOTICE = (
    "Dieses Dokument ist Teil des synthetischen Demo-Korpus der fiktiven Stadt Rheinfurt "
    "(siehe SOURCE.md im Wurzelverzeichnis dieses Korpus). Gremien, Personen, Beschlüsse und "
    "Beträge sind frei erfunden."
)

STADTRAT = "Stadtrat der Stadt Rheinfurt"
HAUPTAUSSCHUSS = "Hauptausschuss der Stadt Rheinfurt"
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
    lines.append("Rheinfurt, im Auftrag: Amtsleitung " + v.federfuehrung.split(",")[0])
    lines.append("")
    lines.append(SYNTHETIC_NOTICE)
    lines.append("")
    return "\n".join(lines).encode("utf-8")


def niederschrift_text(n: Niederschrift) -> str:
    return render_niederschrift_md(n).decode("utf-8")


def vorlage_text(v: Beschlussvorlage) -> str:
    return render_vorlage_txt(v).decode("utf-8")
