"""Data and RSS/HTML rendering for the "Pressemitteilungen Stadt Rheinfurt"
library (RSS_FEED connector, see docs/features/demo-instance.md).

Style is inspired by the press releases of the City of Cologne
(offenedaten-koeln.de, DL-DE-BY-2.0) and the RSS feed structure of the City
of Düsseldorf — used only as a stylistic/structural template, no text is
copied (see docs/features/demo-instance.md, "Quellen und Lizenzen").

All dates are fixed literals, not wall-clock timestamps, so the feed and its
detail pages are byte-identical across generator runs.
"""

from __future__ import annotations

from dataclasses import dataclass
from email.utils import format_datetime
from datetime import datetime, timezone
from html import escape
from xml.sax.saxutils import escape as xml_escape

FEED_BASE_URL = "http://presse.stadt-rheinfurt.example"
FEED_TITLE = "Pressemitteilungen der Stadt Rheinfurt"
FEED_DESCRIPTION = (
    "Aktuelle Pressemitteilungen der Stadt Rheinfurt: Sperrungen, Veranstaltungen, "
    "geänderte Öffnungszeiten und Jubiläen."
)

SYNTHETIC_NOTICE = (
    "Diese Pressemitteilung ist Teil des synthetischen Demo-Korpus der fiktiven Stadt "
    "Rheinfurt (siehe SOURCE.md im Wurzelverzeichnis dieses Korpus). Alle Namen, Orte und "
    "Ereignisse sind frei erfunden."
)


@dataclass
class Pressemitteilung:
    slug: str
    titel: str
    kategorie: str  # Sperrung | Öffnungszeiten | Veranstaltung | Jubiläum
    datum: str  # ISO date, e.g. "2026-06-12"
    teaser: str
    absaetze: list[str]
    kontakt: str = "Presseamt der Stadt Rheinfurt, presse@stadt-rheinfurt.example"


PRESSEMITTEILUNGEN: list[Pressemitteilung] = [
    Pressemitteilung(
        "buergerbuero-geschlossen-stadtfest",
        "Bürgerbüro am 19. Juni wegen Stadtfest geschlossen",
        "Sperrung",
        "2026-06-08",
        "Das Bürgerbüro Rheinfurt bleibt am Freitag, 19. Juni 2026, wegen des Rheinfurter "
        "Stadtfests ganztägig geschlossen.",
        [
            "Anlässlich des Rheinfurter Stadtfests bleibt das Bürgerbüro Rheinfurt am Freitag, "
            "19. Juni 2026, ganztägig geschlossen. Der Rathausplatz und die angrenzenden "
            "Straßen werden ab Donnerstagabend für den Aufbau der Bühnen und Stände gesperrt.",
            "Bereits gebuchte Termine für diesen Tag werden automatisch auf den folgenden "
            "Werktag verschoben; betroffene Bürgerinnen und Bürger erhalten eine gesonderte "
            "Benachrichtigung per E-Mail.",
            "Ab Montag, 22. Juni 2026, sind die Dienststellen des Bürgerbüros wieder zu den "
            "regulären Öffnungszeiten erreichbar (das Stadtfest selbst läuft am Wochenende, "
            "20. und 21. Juni, ohnehin außerhalb der Bürgerbüro-Öffnungszeiten weiter). Für "
            "dringende Anliegen steht während der Schließung die Leitstelle des Ordnungsamts "
            "telefonisch zur Verfügung.",
        ],
    ),
    Pressemitteilung(
        "stadtfest-2026-programm",
        "Programm für das Rheinfurter Stadtfest 2026 steht fest",
        "Veranstaltung",
        "2026-05-20",
        "Vom 19. bis 21. Juni 2026 feiert Rheinfurt sein traditionelles Stadtfest rund um den "
        "Rathausplatz und die Rheinpromenade.",
        [
            "Die Stadt Rheinfurt lädt vom 19. bis 21. Juni 2026 zum diesjährigen Stadtfest ein. "
            "Auf drei Bühnen entlang der Rheinpromenade treten Musikgruppen aus der Region auf, "
            "ergänzt um ein Kinderprogramm auf dem Domplatz und einen Kunsthandwerkermarkt in "
            "der Marktstraße.",
            "Der Eintritt ist wie in den Vorjahren frei. Anwohnerinnen und Anwohner der "
            "Innenstadt werden gebeten, ihre Fahrzeuge während der Festtage außerhalb der "
            "gesperrten Bereiche zu parken; ein kostenloser Pendelbus verkehrt ab dem "
            "Festplatz Nord.",
            "Das vollständige Bühnenprogramm liegt ab sofort im Bürgerbüro aus und ist über die "
            "städtische Internetseite abrufbar.",
        ],
    ),
    Pressemitteilung(
        "oeffnungszeiten-kfz-zulassung-sommer",
        "Kfz-Zulassungsstelle: Angepasste Öffnungszeiten in den Sommerferien",
        "Öffnungszeiten",
        "2026-07-01",
        "Während der Sommerferien öffnet die Kfz-Zulassungsstelle Rheinfurt mittwochs "
        "nachmittags nicht.",
        [
            "Vom 27. Juli bis 7. September 2026 entfällt die Mittwochnachmittag-Öffnung der "
            "Kfz-Zulassungsstelle im Bürgerbüro Rheinfurt. Grund ist der urlaubsbedingt "
            "reduzierte Personalstand in den Sommerferien.",
            "Termine, die in diesem Zeitraum bereits gebucht waren, bleiben davon unberührt. "
            "Alle übrigen Öffnungszeiten – montags bis freitags vormittags sowie dienstags und "
            "donnerstags nachmittags – gelten unverändert.",
        ],
    ),
    Pressemitteilung(
        "brunnen-marktplatz-jubilaeum",
        "150 Jahre Marktbrunnen: Stadt Rheinfurt feiert Jubiläum",
        "Jubiläum",
        "2026-04-14",
        "Der Marktbrunnen auf dem Rathausplatz wird 2026 anlässlich seines 150-jährigen "
        "Bestehens saniert und neu eingeweiht.",
        [
            "Der historische Marktbrunnen auf dem Rathausplatz feiert 2026 sein 150-jähriges "
            "Bestehen. Anlässlich des Jubiläums lässt die Stadt Rheinfurt den Brunnen im Mai "
            "denkmalgerecht sanieren.",
            "Die feierliche Neueinweihung ist für den 30. Mai 2026 um 17 Uhr auf dem "
            "Rathausplatz vorgesehen, mit einer kurzen Ansprache der Oberbürgermeisterin und "
            "einem Rückblick auf die Geschichte des Brunnens im Stadtarchiv.",
        ],
    ),
    Pressemitteilung(
        "sperrung-rheinbruecke-bauarbeiten",
        "Rheinbrücke wegen Bauarbeiten halbseitig gesperrt",
        "Sperrung",
        "2026-03-03",
        "Vom 9. bis 27. März 2026 ist die Rheinbrücke wegen Fahrbahnsanierung halbseitig "
        "gesperrt.",
        [
            "Wegen Sanierungsarbeiten an der Fahrbahndecke ist die Rheinbrücke vom 9. bis "
            "27. März 2026 in Richtung Innenstadt halbseitig gesperrt. Der Verkehr wird "
            "einspurig mit Ampelregelung geführt.",
            "Der Busverkehr der Linien 3 und 7 wird für den Zeitraum der Bauarbeiten über die "
            "Nordbrücke umgeleitet; es kann zu Verzögerungen von bis zu 15 Minuten kommen.",
        ],
    ),
    Pressemitteilung(
        "stadtbibliothek-sonntagsoeffnung",
        "Stadtbibliothek testet Sonntagsöffnung ab April",
        "Öffnungszeiten",
        "2026-03-18",
        "Die Stadtbibliothek Rheinfurt öffnet ab April 2026 versuchsweise auch sonntags von "
        "13 bis 17 Uhr.",
        [
            "Ab dem 5. April 2026 öffnet die Stadtbibliothek Rheinfurt an jedem ersten Sonntag "
            "im Monat zusätzlich von 13 bis 17 Uhr. Der Testbetrieb läuft zunächst bis "
            "Jahresende.",
            "Ausleihe, Rückgabe und der Lesesaal stehen an diesen Terminen wie gewohnt zur "
            "Verfügung; die Auskunftstheke ist reduziert besetzt.",
        ],
    ),
    Pressemitteilung(
        "weihnachtsmarkt-2026-termine",
        "Rheinfurter Weihnachtsmarkt öffnet ab 27. November",
        "Veranstaltung",
        "2026-10-30",
        "Der Weihnachtsmarkt auf dem Rathausplatz öffnet 2026 vom 27. November bis "
        "23. Dezember.",
        [
            "Vom 27. November bis 23. Dezember 2026 verwandelt sich der Rathausplatz erneut in "
            "den Rheinfurter Weihnachtsmarkt. Rund 40 Stände bieten Kunsthandwerk, Glühwein und "
            "regionale Spezialitäten an.",
            "Für die Zeit des Weihnachtsmarkts gilt eine Sondernutzungsregelung für den "
            "Rathausplatz; der reguläre Wochenmarkt weicht in dieser Zeit auf den Domplatz aus.",
        ],
    ),
    Pressemitteilung(
        "radweg-rheinufer-eroeffnung",
        "Neuer Radweg entlang der Rheinpromenade eröffnet",
        "Veranstaltung",
        "2026-05-05",
        "Der neue, 3,2 Kilometer lange Radweg entlang der Rheinpromenade wird am 12. Mai 2026 "
        "eröffnet.",
        [
            "Nach neunmonatiger Bauzeit eröffnet die Stadt Rheinfurt am 12. Mai 2026 den neuen "
            "Radweg entlang der Rheinpromenade. Er verbindet die Altstadt durchgehend mit dem "
            "Freizeitgelände im Stadtteil Rheinau.",
            "Zur Eröffnung lädt das Tiefbauamt zu einer gemeinsamen Radtour ab dem Rathausplatz "
            "ein, Start ist um 10 Uhr.",
        ],
    ),
    Pressemitteilung(
        "stellenausschreibung-sachbearbeitung-meldewesen",
        "Stadt Rheinfurt sucht Sachbearbeitung für das Meldewesen",
        "Veranstaltung",
        "2026-02-10",
        "Das Bürgerbüro Rheinfurt schreibt zum 1. Mai 2026 eine Stelle in der Sachbearbeitung "
        "Meldewesen aus.",
        [
            "Zum 1. Mai 2026 besetzt das Bürgerbüro Rheinfurt eine Vollzeitstelle in der "
            "Sachbearbeitung Meldewesen und Ausweisangelegenheiten neu.",
            "Bewerbungen werden bis zum 20. März 2026 über das Karriereportal der Stadt "
            "Rheinfurt entgegengenommen.",
        ],
    ),
    Pressemitteilung(
        "feuerwehr-tag-der-offenen-tuer",
        "Feuerwehr Rheinfurt lädt zum Tag der offenen Tür",
        "Veranstaltung",
        "2026-08-01",
        "Am 6. September 2026 öffnet die Feuerwache Rheinfurt-Mitte ihre Tore für die "
        "Öffentlichkeit.",
        [
            "Am 6. September 2026 lädt die Freiwillige Feuerwehr Rheinfurt von 10 bis 16 Uhr "
            "zum Tag der offenen Tür in die Feuerwache Rheinfurt-Mitte ein.",
            "Neben Fahrzeugvorführungen gibt es einen Löschangriff-Wettbewerb für Kinder und "
            "Informationsstände zum vorbeugenden Brandschutz.",
        ],
    ),
    Pressemitteilung(
        "sperrung-marktstrasse-kanalbau",
        "Marktstraße wegen Kanalbauarbeiten bis Ende Oktober gesperrt",
        "Sperrung",
        "2026-09-01",
        "Die Marktstraße ist zwischen Rathausplatz und Domplatz seit 1. September wegen "
        "Kanalbauarbeiten voll gesperrt.",
        [
            "Im Rahmen der Erneuerung des Abwasserkanals ist die Marktstraße zwischen "
            "Rathausplatz und Domplatz seit dem 1. September 2026 voll gesperrt. Die "
            "Bauarbeiten sollen bis Ende Oktober abgeschlossen sein.",
            "Anliegende Geschäfte bleiben über die Seiteneingänge erreichbar; eine "
            "Umleitungsbeschilderung führt den Durchgangsverkehr über die Bahnhofstraße.",
        ],
    ),
    Pressemitteilung(
        "buergerbuero-onlinetermine-erweitert",
        "Bürgerbüro erweitert Online-Terminvergabe auf alle Sachgebiete",
        "Öffnungszeiten",
        "2026-01-15",
        "Ab sofort lassen sich auch Termine für die Kfz-Zulassung online buchen.",
        [
            "Das Bürgerbüro Rheinfurt hat die Online-Terminvergabe auf sämtliche Sachgebiete "
            "ausgeweitet. Termine für die Kfz-Zulassung können ab sofort ebenso online gebucht "
            "werden wie für Ausweis- und Meldeangelegenheiten.",
            "Die telefonische Terminvergabe bleibt für Bürgerinnen und Bürger ohne "
            "Internetzugang weiterhin bestehen.",
        ],
    ),
    Pressemitteilung(
        "stadtarchiv-40-jahre",
        "40 Jahre Stadtarchiv Rheinfurt: Tag der offenen Archivtür",
        "Jubiläum",
        "2026-09-20",
        "Das Stadtarchiv Rheinfurt feiert am 10. Oktober 2026 sein 40-jähriges Bestehen.",
        [
            "Am 10. Oktober 2026 feiert das Stadtarchiv Rheinfurt sein 40-jähriges Bestehen mit "
            "einem Tag der offenen Archivtür. Besucherinnen und Besucher können Originaldokumente "
            "zur Stadtgeschichte seit der Stadtrechtsverleihung im 19. Jahrhundert einsehen.",
            "Um 15 Uhr hält die Stadtarchivarin einen Vortrag zur Geschichte der Rheinbrücke.",
        ],
    ),
    Pressemitteilung(
        "winterdienst-hinweise",
        "Winterdienst: Stadt Rheinfurt erinnert an Räum- und Streupflicht",
        "Sperrung",
        "2026-11-15",
        "Mit Beginn der Frostperiode weist die Stadt Rheinfurt auf die Räum- und Streupflicht "
        "der Anliegerinnen und Anlieger hin.",
        [
            "Wie in jedem Winter erinnert die Stadt Rheinfurt an die Räum- und Streupflicht für "
            "Gehwege gemäß der Straßenreinigungssatzung. Anliegerinnen und Anlieger sind werktags "
            "von 7 bis 20 Uhr, sonn- und feiertags von 9 bis 20 Uhr zum Räumen verpflichtet.",
            "Der städtische Winterdienst konzentriert sich auf Hauptverkehrsstraßen, "
            "ÖPNV-Strecken und Steigungslagen.",
        ],
    ),
    Pressemitteilung(
        "spielplatz-rheinau-neueroeffnung",
        "Neu gestalteter Spielplatz Rheinau wieder geöffnet",
        "Veranstaltung",
        "2026-06-25",
        "Nach dreimonatiger Umbauzeit ist der Spielplatz im Stadtteil Rheinau seit 1. Juli "
        "wieder geöffnet.",
        [
            "Der Spielplatz im Stadtteil Rheinau wurde grundlegend erneuert und ist seit "
            "1. Juli 2026 wieder für Kinder und Familien geöffnet. Neu hinzugekommen sind ein "
            "Wasserspielbereich und ein barrierefreies Karussell.",
            "Die Umbaukosten von rund 180.000 Euro trug überwiegend ein Förderprogramm des "
            "Freistaats zur Aufwertung öffentlicher Spielflächen.",
        ],
    ),
    Pressemitteilung(
        "gastspiel-stadttheater-jubilaeumsspielzeit",
        "Stadttheater Rheinfurt eröffnet Jubiläumsspielzeit",
        "Jubiläum",
        "2026-08-25",
        "Mit einer Festveranstaltung eröffnet das Stadttheater Rheinfurt am 12. September 2026 "
        "seine 75. Spielzeit.",
        [
            "Das Stadttheater Rheinfurt feiert 2026 sein 75-jähriges Bestehen. Die Jubiläumsspielzeit "
            "wird am 12. September 2026 mit einer Festveranstaltung und einem Gastspiel des "
            "Landestheaters eröffnet.",
            "Karten für die Eröffnungsveranstaltung sind ab sofort an der Theaterkasse und über "
            "die Internetseite der Stadt Rheinfurt erhältlich.",
        ],
    ),
    Pressemitteilung(
        "oeffnungszeiten-feiertage-jahreswechsel",
        "Öffnungszeiten des Bürgerbüros über den Jahreswechsel",
        "Öffnungszeiten",
        "2026-12-10",
        "Zwischen den Feiertagen bleibt das Bürgerbüro Rheinfurt an zwei Tagen geschlossen.",
        [
            "Zwischen Weihnachten und Neujahr bleibt das Bürgerbüro Rheinfurt am 28. und "
            "29. Dezember 2026 geschlossen. Am 30. Dezember 2026 ist mit eingeschränktem "
            "Personal von 9 bis 12 Uhr geöffnet.",
            "Ab dem 2. Januar 2027 gelten wieder die regulären Öffnungszeiten.",
        ],
    ),
    Pressemitteilung(
        "sperrung-domplatz-filmdreh",
        "Domplatz wegen Filmaufnahmen zwei Tage gesperrt",
        "Sperrung",
        "2026-04-28",
        "Am 6. und 7. Mai 2026 ist der Domplatz für eine Filmproduktion gesperrt.",
        [
            "Für Außenaufnahmen einer Filmproduktion ist der Domplatz am 6. und 7. Mai 2026 "
            "jeweils von 7 bis 22 Uhr gesperrt. Zufahrten zu angrenzenden Grundstücken bleiben "
            "über die Seitengassen möglich.",
            "Die Stadt Rheinfurt hat die Drehgenehmigung im Rahmen der Wirtschaftsförderung für "
            "die Kreativwirtschaft erteilt.",
        ],
    ),
    Pressemitteilung(
        "digitalisierung-baugenehmigungen",
        "Bauanträge künftig vollständig digital einreichbar",
        "Öffnungszeiten",
        "2026-02-25",
        "Ab März 2026 können Bauanträge bei der Stadt Rheinfurt vollständig digital "
        "eingereicht werden.",
        [
            "Ab dem 1. März 2026 stellt die Stadt Rheinfurt ein digitales Antragsverfahren für "
            "Baugenehmigungen bereit. Anträge und Anlagen können vollständig online über das "
            "Serviceportal eingereicht werden.",
            "Die postalische Antragstellung bleibt weiterhin möglich; das Bauamt empfiehlt "
            "jedoch aus Bearbeitungsgründen den digitalen Weg.",
        ],
    ),
    Pressemitteilung(
        "rheinfurt-partnerstadt-jubilaeum",
        "20 Jahre Städtepartnerschaft mit Vézelay",
        "Jubiläum",
        "2026-05-30",
        "Rheinfurt feiert im Juni 2026 das 20-jährige Bestehen seiner Partnerschaft mit der "
        "französischen Stadt Vézelay.",
        [
            "Seit 20 Jahren pflegt Rheinfurt eine Städtepartnerschaft mit der französischen "
            "Stadt Vézelay. Zum Jubiläum reist im Juni 2026 eine Delegation aus Vézelay nach "
            "Rheinfurt.",
            "Höhepunkt der Feierlichkeiten ist ein Festakt im Rathaussaal am 18. Juni 2026, zu "
            "dem auch die Bevölkerung eingeladen ist.",
        ],
    ),
    Pressemitteilung(
        "sperrung-hauptbahnhofsvorplatz-umbau",
        "Bahnhofsvorplatz wird ab Herbst umgebaut",
        "Sperrung",
        "2026-08-10",
        "Ab 1. Oktober 2026 wird der Vorplatz des Rheinfurter Hauptbahnhofs für rund vier "
        "Monate umgebaut.",
        [
            "Ab dem 1. Oktober 2026 baut die Stadt Rheinfurt den Vorplatz des Hauptbahnhofs "
            "um; die Bauzeit ist mit rund vier Monaten veranschlagt. Ziel ist eine bessere "
            "Anbindung für Busse, Fahrräder und Fußgänger.",
            "Der Zugang zum Bahnhofsgebäude bleibt während der gesamten Bauzeit über einen "
            "provisorischen Weg entlang der Bahnhofstraße gewährleistet.",
        ],
    ),
    Pressemitteilung(
        "oeffnungszeiten-standesamt-erweitert",
        "Standesamt bietet ab April Trausamstage im Monatstakt an",
        "Öffnungszeiten",
        "2026-03-10",
        "Ab April 2026 bietet das Standesamt Rheinfurt einmal im Monat einen Trausamstag an.",
        [
            "Ab April 2026 können sich Paare in Rheinfurt einmal im Monat auch samstags das "
            "Ja-Wort geben: Das Standesamt bietet an jedem ersten Samstag im Monat "
            "Trautermine im historischen Rathaussaal an.",
            "Die Terminvergabe erfolgt wie gewohnt über das Standesamt; die Nachfrage der "
            "vergangenen Jahre hatte die Einführung des Trausamstags nahegelegt.",
        ],
    ),
    Pressemitteilung(
        "stadtfuehrungen-sommerprogramm",
        "Kostenlose Stadtführungen im Sommer 2026",
        "Veranstaltung",
        "2026-05-12",
        "Von Juni bis August bietet die Stadt Rheinfurt jeden Samstag eine kostenlose "
        "Altstadtführung an.",
        [
            "Von Juni bis August 2026 bietet die Stadt Rheinfurt jeden Samstag um 11 Uhr eine "
            "kostenlose Führung durch die historische Altstadt an. Treffpunkt ist der Brunnen "
            "auf dem Rathausplatz.",
            "Die Führungen dauern rund 90 Minuten und behandeln unter anderem die Geschichte "
            "des Doms, des Rathauses und der Rheinbrücke.",
        ],
    ),
    Pressemitteilung(
        "energieberatung-buergerbuero",
        "Kostenlose Energieberatung jetzt auch im Bürgerbüro",
        "Öffnungszeiten",
        "2026-01-28",
        "Ab Februar 2026 bietet die Verbraucherzentrale einmal wöchentlich eine kostenlose "
        "Energieberatung im Bürgerbüro Rheinfurt an.",
        [
            "Ab dem 4. Februar 2026 bietet die Verbraucherzentrale in Kooperation mit der Stadt "
            "Rheinfurt jeden Mittwochnachmittag eine kostenlose Energieberatung im Bürgerbüro "
            "an.",
            "Interessierte können Termine über die Verbraucherzentrale oder direkt am "
            "Empfang des Bürgerbüros vereinbaren.",
        ],
    ),
    Pressemitteilung(
        "sperrung-uferpromenade-hochwasser",
        "Uferpromenade wegen erhöhtem Rheinpegel gesperrt",
        "Sperrung",
        "2026-02-02",
        "Wegen erhöhten Wasserstands ist die Uferpromenade seit 1. Februar bis auf Weiteres "
        "gesperrt.",
        [
            "Aufgrund des erhöhten Rheinpegels hat das Ordnungsamt die Uferpromenade zwischen "
            "der Rheinbrücke und dem Stadtpark seit dem 1. Februar 2026 bis auf Weiteres "
            "gesperrt.",
            "Die Stadt Rheinfurt bittet, die Absperrungen zu beachten und rät von "
            "eigenmächtigem Betreten der Uferbereiche ab.",
        ],
    ),
    Pressemitteilung(
        "digitalratsschule-abschluss",
        "Erster Jahrgang der 'Digitallotsen' verabschiedet",
        "Veranstaltung",
        "2026-11-05",
        "20 ehrenamtliche Digitallotsinnen und -lotsen haben ihre Schulung im Bürgerbüro "
        "Rheinfurt abgeschlossen.",
        [
            "Im Bürgerbüro Rheinfurt haben 20 ehrenamtliche Digitallotsinnen und -lotsen ihre "
            "sechswöchige Schulung abgeschlossen. Sie unterstützen künftig einmal wöchentlich "
            "Bürgerinnen und Bürger bei Online-Anträgen.",
            "Das Angebot findet jeden Donnerstagnachmittag im Servicebereich des Bürgerbüros "
            "statt und ist kostenlos.",
        ],
    ),
    Pressemitteilung(
        "gruenanlagen-herbstpflege",
        "Herbstliche Pflegearbeiten in den städtischen Grünanlagen",
        "Sperrung",
        "2026-10-05",
        "Von 12. bis 23. Oktober 2026 finden Pflegearbeiten im Stadtpark statt; einzelne Wege "
        "sind zeitweise gesperrt.",
        [
            "Vom 12. bis 23. Oktober 2026 führt das Grünflächenamt Rheinfurt Baumschnitt- und "
            "Pflegearbeiten im Stadtpark durch. Einzelne Wege werden dafür tageweise gesperrt.",
            "Anwohnerinnen und Anwohner werden gebeten, ausgeschilderte Umleitungen zu "
            "beachten.",
        ],
    ),
]


def detail_url(meldung: Pressemitteilung) -> str:
    return f"{FEED_BASE_URL}/{meldung.slug}.html"


def _rfc822(iso_date: str) -> str:
    dt = datetime.strptime(iso_date, "%Y-%m-%d").replace(
        hour=8, minute=0, second=0, tzinfo=timezone.utc
    )
    return format_datetime(dt, usegmt=True)


def render_rss(meldungen: list[Pressemitteilung]) -> bytes:
    items = []
    # Newest first, as is conventional for a press RSS feed; sort is stable
    # and purely a function of the (fixed) `datum` field, so this is
    # deterministic across runs.
    ordered = sorted(meldungen, key=lambda m: m.datum, reverse=True)
    for meldung in ordered:
        items.append(
            "    <item>\n"
            f"      <title>{xml_escape(meldung.titel)}</title>\n"
            f"      <link>{xml_escape(detail_url(meldung))}</link>\n"
            f"      <guid isPermaLink=\"true\">{xml_escape(detail_url(meldung))}</guid>\n"
            f"      <pubDate>{_rfc822(meldung.datum)}</pubDate>\n"
            f"      <category>{xml_escape(meldung.kategorie)}</category>\n"
            f"      <description>{xml_escape(meldung.teaser)}</description>\n"
            "    </item>\n"
        )
    body = "".join(items)
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<rss version="2.0">\n'
        "  <channel>\n"
        f"    <title>{xml_escape(FEED_TITLE)}</title>\n"
        f"    <link>{xml_escape(FEED_BASE_URL)}</link>\n"
        f"    <description>{xml_escape(FEED_DESCRIPTION)}</description>\n"
        "    <language>de-de</language>\n"
        f"{body}"
        "  </channel>\n"
        "</rss>\n"
    )
    return xml.encode("utf-8")


def render_html(meldung: Pressemitteilung) -> bytes:
    paragraphs = "\n".join(f"    <p>{escape(absatz)}</p>" for absatz in meldung.absaetze)
    html = f"""<!DOCTYPE html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <title>{escape(meldung.titel)} – Presse Stadt Rheinfurt</title>
</head>
<body>
  <article>
    <p><a href="/rss.xml">← Alle Pressemitteilungen</a></p>
    <h1>{escape(meldung.titel)}</h1>
    <p><strong>Kategorie:</strong> {escape(meldung.kategorie)} &middot; <strong>Datum:</strong> {escape(meldung.datum)}</p>
    <p><em>{escape(meldung.teaser)}</em></p>
{paragraphs}
    <hr>
    <p>Kontakt: {escape(meldung.kontakt)}</p>
    <p><small>{escape(SYNTHETIC_NOTICE)}</small></p>
  </article>
</body>
</html>
"""
    return html.encode("utf-8")
