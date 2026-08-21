"""Data and PDF rendering for the "Satzungen & Gebührenordnungen" library.

Every satzung is a short, synthetic municipal statute in the structural style
of real German Satzungen (§§, Geltungsbereich, Gebührenverzeichnis as
Anlage) — content and paragraph numbers are invented for the fictional city
of Rheinfurt, not copied from any real municipality (see
docs/features/demo-instance.md, "Quellen und Lizenzen": kommunale Satzungen
are used only as a structural template, § 5 Abs. 1 UrhG).

PDFs are rendered with reportlab (see demo/generator/README.md, "Werkzeugwahl"
for the rationale) so the whole pipeline stays a pure-Python, network-free,
container-friendly dependency.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from io import BytesIO

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from rheinfurt_text import scale_and_format_fee

STYLES = getSampleStyleSheet()
TITLE_STYLE = ParagraphStyle(
    "SatzungTitle", parent=STYLES["Title"], fontSize=15, spaceAfter=12
)
PARAGRAPH_HEADING_STYLE = ParagraphStyle(
    "ParagraphHeading", parent=STYLES["Heading3"], spaceBefore=10, spaceAfter=4
)
BODY_STYLE = ParagraphStyle("SatzungBody", parent=STYLES["BodyText"], spaceAfter=6)
FOOTER_STYLE = ParagraphStyle(
    "SatzungFooter", parent=STYLES["BodyText"], fontSize=8, textColor=colors.grey
)

SYNTHETIC_NOTICE = (
    "Diese Satzung ist Teil des synthetischen Demo-Korpus der fiktiven Stadt Rheinfurt "
    "(siehe SOURCE.md im Wurzelverzeichnis dieses Korpus). Paragraphen, Beträge und "
    "Aktenzeichen sind frei erfunden."
)


@dataclass
class Paragraf:
    nummer: int
    ueberschrift: str
    text: list[str]


@dataclass
class Gebuehrenzeile:
    tatbestand: str
    betrag: str


@dataclass
class Satzung:
    slug: str
    titel: str
    kurzbezeichnung: str
    aktenzeichen: str
    praeambel: list[str]
    paragrafen: list[Paragraf]
    gebuehren: list[Gebuehrenzeile] = field(default_factory=list)
    inkrafttreten: str = "1. Januar 2026"


def _standard_paragrafen(
    geltungsbereich: str,
    regelungsgegenstand: list[str],
    gebuehren_hinweis: str | None,
    inkrafttreten: str,
) -> list[Paragraf]:
    paragrafen = [
        Paragraf(1, "Geltungsbereich", [geltungsbereich]),
        Paragraf(2, "Regelungsgegenstand", regelungsgegenstand),
    ]
    if gebuehren_hinweis:
        paragrafen.append(Paragraf(3, "Gebühren", [gebuehren_hinweis]))
        letzter = 4
    else:
        letzter = 3
    paragrafen.append(
        Paragraf(
            letzter,
            "Ordnungswidrigkeiten",
            [
                "Wer vorsätzlich oder fahrlässig gegen eine vollziehbare Anordnung nach dieser "
                "Satzung verstößt, handelt ordnungswidrig im Sinne von Art. 24 der Gemeindeordnung "
                "und kann mit einem Bußgeld bis zu 1.000 Euro belegt werden."
            ],
        )
    )
    paragrafen.append(
        Paragraf(
            letzter + 1,
            "Inkrafttreten",
            [f"Diese Satzung tritt am {inkrafttreten} in Kraft."],
        )
    )
    return paragrafen


def simple_satzung(
    slug: str,
    titel: str,
    kurzbezeichnung: str,
    aktenzeichen: str,
    geltungsbereich: str,
    regelungsgegenstand: list[str],
    gebuehren: list[Gebuehrenzeile] | None = None,
    gebuehren_hinweis: str | None = None,
    inkrafttreten: str = "1. Januar 2026",
) -> Satzung:
    return Satzung(
        slug=slug,
        titel=titel,
        kurzbezeichnung=kurzbezeichnung,
        aktenzeichen=aktenzeichen,
        praeambel=[
            f"Aufgrund von Art. 23 und 24 der Gemeindeordnung für den Freistaat erlässt die "
            f"Stadt Rheinfurt folgende Satzung – {kurzbezeichnung}:"
        ],
        paragrafen=_standard_paragrafen(
            geltungsbereich, regelungsgegenstand, gebuehren_hinweis, inkrafttreten
        ),
        gebuehren=gebuehren or [],
        inkrafttreten=inkrafttreten,
    )


def _verwaltungsgebuehrensatzung() -> Satzung:
    gebuehren = [
        Gebuehrenzeile(
            "Personalausweis (Antragstellende unter 24 Jahren)",
            scale_and_format_fee(22.80, "Personalausweis.txt"),
        ),
        Gebuehrenzeile(
            "Personalausweis (Antragstellende ab 24 Jahren)",
            scale_and_format_fee(37.00, "Personalausweis.txt"),
        ),
        Gebuehrenzeile(
            "Vorläufiger Personalausweis", scale_and_format_fee(10.00, "Personalausweis.txt")
        ),
        Gebuehrenzeile("Reisepass (regulär, 32 Seiten)", scale_and_format_fee(60.00, "Reisepass.txt")),
        Gebuehrenzeile("Führungszeugnis", scale_and_format_fee(13.00, "Führungszeugnis.txt")),
        Gebuehrenzeile(
            "Beglaubigung je Unterschrift", scale_and_format_fee(20.00, "Beglaubigung von Unterschriften.txt")
        ),
        Gebuehrenzeile("Melderegisterauskunft, einfach", scale_and_format_fee(10.00, "Melderegisterauskunft.txt")),
        Gebuehrenzeile("Anmeldung/Ummeldung des Wohnsitzes", "gebührenfrei"),
        Gebuehrenzeile("Reservierung Wunschkennzeichen", scale_and_format_fee(12.80, "Wunschkennzeichen.txt")),
    ]
    paragrafen = [
        Paragraf(
            1,
            "Geltungsbereich",
            [
                "Diese Satzung regelt die Erhebung von Verwaltungsgebühren für individuell "
                "zurechenbare öffentliche Leistungen des Bürgerbüros Rheinfurt sowie der "
                "Kfz-Zulassungsbehörde der Stadt Rheinfurt."
            ],
        ),
        Paragraf(
            2,
            "Gebührenpflicht",
            [
                "Gebührenpflichtig ist, wer die Amtshandlung veranlasst hat oder in dessen "
                "Interesse sie vorgenommen wird.",
                "Die Höhe der Gebühren ergibt sich aus dem Gebührenverzeichnis (Anlage zu § 2).",
            ],
        ),
        Paragraf(
            3,
            "Ermäßigung und Befreiung wegen Bedürftigkeit",
            [
                "Auf schriftlichen Antrag kann die Verwaltungsgebühr ganz oder teilweise erlassen "
                "werden, wenn die Einziehung nach den persönlichen und wirtschaftlichen "
                "Verhältnissen der gebührenpflichtigen Person eine unbillige Härte bedeuten würde. "
                "Als Nachweis genügt in der Regel die Vorlage eines aktuellen Bescheids über den "
                "Bezug von Bürgergeld, Grundsicherung im Alter oder bei Erwerbsminderung, "
                "Wohngeld oder Leistungen nach dem Asylbewerberleistungsgesetz.",
                "Über den Antrag entscheidet die Leitung des zuständigen Sachgebiets im "
                "Bürgerbüro Rheinfurt. Die Entscheidung ist aktenkundig zu machen und der "
                "antragstellenden Person schriftlich mitzuteilen.",
                "Näheres zum Verfahren regelt die Dienstanweisung 'Gebührenermäßigung und "
                "-befreiung wegen Bedürftigkeit' der Sachgebietsleitung Meldewesen.",
            ],
        ),
        Paragraf(
            4,
            "Fälligkeit",
            [
                "Die Gebühr wird mit Bekanntgabe des Gebührenbescheids fällig, spätestens jedoch "
                "bei Aushändigung der beantragten Urkunde oder Bescheinigung."
            ],
        ),
        Paragraf(
            5,
            "Ordnungswidrigkeiten",
            [
                "Wer vorsätzlich oder fahrlässig gegen eine vollziehbare Anordnung nach dieser "
                "Satzung verstößt, handelt ordnungswidrig im Sinne von Art. 24 der Gemeindeordnung "
                "und kann mit einem Bußgeld bis zu 1.000 Euro belegt werden."
            ],
        ),
        Paragraf(6, "Inkrafttreten", ["Diese Satzung tritt am 1. Januar 2026 in Kraft."]),
    ]
    return Satzung(
        slug="verwaltungsgebuehrensatzung",
        titel="Verwaltungsgebührensatzung der Stadt Rheinfurt",
        kurzbezeichnung="Verwaltungsgebührensatzung (VGS)",
        aktenzeichen="AZ 20.1-2026-0001",
        praeambel=[
            "Aufgrund von Art. 23 und 24 der Gemeindeordnung für den Freistaat sowie Art. 5 des "
            "Kommunalabgabengesetzes erlässt die Stadt Rheinfurt folgende Satzung "
            "(Verwaltungsgebührensatzung – VGS):"
        ],
        paragrafen=paragrafen,
        gebuehren=gebuehren,
    )


SATZUNGEN: list[Satzung] = [
    _verwaltungsgebuehrensatzung(),
    simple_satzung(
        "gebuehrenordnung-kfz-zulassung",
        "Gebührenordnung der Kfz-Zulassungsbehörde Rheinfurt",
        "Kfz-Gebührenordnung (KfzGebO)",
        "AZ 20.3-2026-0002",
        "Diese Gebührenordnung gilt für Amtshandlungen der Kfz-Zulassungsbehörde der Stadt "
        "Rheinfurt nach der Fahrzeug-Zulassungsverordnung und der Fahrerlaubnis-Verordnung.",
        [
            "Sie regelt insbesondere die Gebühren für Neuzulassung, Ummeldung, Umschreibung, "
            "Ausgabe von Wunsch- und Wechselkennzeichen sowie Kurzzeit- und Saisonkennzeichen.",
        ],
        gebuehren=[
            Gebuehrenzeile("Neuzulassung eines Fahrzeugs", scale_and_format_fee(27.90, "Fabrikneues Fahrzeug anmelden.txt")),
            Gebuehrenzeile("Ummeldung innerhalb Rheinfurts", scale_and_format_fee(20.20, "Fahrzeug umschreiben innerhalb Münchens.txt")),
            Gebuehrenzeile("Wunschkennzeichen, Reservierung", scale_and_format_fee(12.80, "Wunschkennzeichen.txt")),
            Gebuehrenzeile("Wechselkennzeichen, Ausgabe", scale_and_format_fee(19.10, "Wechselkennzeichen.txt")),
            Gebuehrenzeile("Kurzzeitkennzeichen", scale_and_format_fee(13.20, "Kurzzeitkennzeichen beantragen.txt")),
        ],
    ),
    simple_satzung(
        "friedhofssatzung",
        "Friedhofssatzung der Stadt Rheinfurt",
        "Friedhofssatzung (FriedhofS)",
        "AZ 20.5-2026-0003",
        "Diese Satzung gilt für alle städtischen Friedhöfe im Gebiet der Stadt Rheinfurt.",
        [
            "Sie regelt Bestattungsarten, Grabnutzungsrechte, Ruhezeiten sowie die Bewilligung "
            "gewerblicher Tätigkeiten auf dem Friedhofsgelände.",
            "Die Ruhezeit für Erdbestattungen beträgt 25 Jahre, für Urnenbeisetzungen 20 Jahre.",
        ],
        gebuehren=[
            Gebuehrenzeile("Grabnutzungsrecht, Erdgrab (25 Jahre)", "1.240 Euro"),
            Gebuehrenzeile("Grabnutzungsrecht, Urnengrab (20 Jahre)", "610 Euro"),
            Gebuehrenzeile("Verlängerung des Nutzungsrechts, je Jahr", "42 Euro"),
        ],
    ),
    simple_satzung(
        "strassenreinigungssatzung",
        "Straßenreinigungssatzung der Stadt Rheinfurt",
        "Straßenreinigungssatzung (StrReinS)",
        "AZ 20.6-2026-0004",
        "Diese Satzung regelt die Reinigung, den Winterdienst und die Kostenerhebung für die "
        "öffentlichen Straßen im Gebiet der Stadt Rheinfurt.",
        [
            "Die Straßenreinigung obliegt der Stadt Rheinfurt; sie kann die Pflicht durch Satzung "
            "auf die Anliegerinnen und Anlieger übertragen (§ 2 Abs. 2).",
            "Der Winterdienst auf Gehwegen obliegt den Anliegerinnen und Anliegern werktags in der "
            "Zeit von 7:00 bis 20:00 Uhr, sonn- und feiertags von 9:00 bis 20:00 Uhr.",
        ],
        gebuehren=[
            Gebuehrenzeile("Straßenreinigungsgebühr, je laufendem Meter Grundstücksfront und Jahr", "4,60 Euro"),
        ],
    ),
    simple_satzung(
        "marktsatzung",
        "Marktsatzung der Stadt Rheinfurt",
        "Marktsatzung (MarktS)",
        "AZ 20.7-2026-0005",
        "Diese Satzung regelt die Durchführung des Wochenmarkts und weiterer öffentlicher Märkte "
        "im Gebiet der Stadt Rheinfurt.",
        [
            "Der Wochenmarkt findet dienstags und freitags von 7:00 bis 13:00 Uhr auf dem "
            "Rathausplatz statt.",
            "Standplätze werden auf Antrag durch das städtische Marktamt zugewiesen.",
        ],
        gebuehren=[
            Gebuehrenzeile("Standgebühr, je laufendem Meter und Markttag", "2,30 Euro"),
        ],
    ),
    simple_satzung(
        "hundesteuersatzung",
        "Hundesteuersatzung der Stadt Rheinfurt",
        "Hundesteuersatzung (HundeStS)",
        "AZ 20.8-2026-0006",
        "Diese Satzung regelt die Erhebung der Hundesteuer im Gebiet der Stadt Rheinfurt.",
        [
            "Steuerpflichtig ist, wer im Gebiet der Stadt Rheinfurt einen Hund hält.",
            "Von der Steuer befreit sind unter anderem Assistenzhunde, Diensthunde und Hunde aus "
            "dem städtischen Tierheim im ersten Jahr nach der Vermittlung.",
        ],
        gebuehren=[
            Gebuehrenzeile("Ersterer Hund, je Jahr", "84 Euro"),
            Gebuehrenzeile("Jeder weitere Hund, je Jahr", "156 Euro"),
            Gebuehrenzeile("Gefährlicher Hund im Sinne § 4, je Jahr", "612 Euro"),
        ],
    ),
    simple_satzung(
        "zweitwohnungsteuersatzung",
        "Zweitwohnungsteuersatzung der Stadt Rheinfurt",
        "Zweitwohnungsteuersatzung (ZwStS)",
        "AZ 20.9-2026-0007",
        "Diese Satzung regelt die Erhebung einer Steuer auf das Innehaben einer Zweitwohnung im "
        "Gebiet der Stadt Rheinfurt.",
        [
            "Der Steuersatz beträgt 12 Prozent der jährlichen Nettokaltmiete.",
            "Befreiungen bestehen unter anderem für Zweitwohnungen aus zwingenden beruflichen "
            "Gründen während einer Übergangszeit von einem Jahr.",
        ],
    ),
    simple_satzung(
        "fundsachensatzung",
        "Satzung über die Erhebung von Gebühren im Fundwesen",
        "Fundgebührensatzung (FundGebS)",
        "AZ 20.10-2026-0008",
        "Diese Satzung regelt die Gebühren für die Verwahrung und Herausgabe von Fundsachen "
        "durch das Fundbüro der Stadt Rheinfurt.",
        [
            "Fundsachen werden sechs Monate verwahrt; danach kann die Stadt Rheinfurt sie "
            "verwerten oder versteigern.",
        ],
        gebuehren=[
            Gebuehrenzeile("Verwahrgebühr bis zu einem Warenwert von 50 Euro", "gebührenfrei"),
            Gebuehrenzeile("Verwahrgebühr, Warenwert über 50 Euro, je angefangenem Monat", "3,50 Euro"),
        ],
    ),
    simple_satzung(
        "obdachlosensatzung",
        "Satzung über die Unterbringung wohnungsloser Personen",
        "Obdachlosensatzung (ObdS)",
        "AZ 20.11-2026-0009",
        "Diese Satzung regelt die vorübergehende Unterbringung wohnungsloser Personen in "
        "städtischen Notunterkünften.",
        [
            "Die Unterbringung erfolgt auf Grundlage einer ordnungsbehördlichen Einweisung durch "
            "das Sozialreferat der Stadt Rheinfurt.",
        ],
        gebuehren=[
            Gebuehrenzeile("Nutzungsentgelt Notunterkunft, je Person und Nacht", "6,80 Euro"),
        ],
    ),
    simple_satzung(
        "sondernutzungssatzung",
        "Sondernutzungssatzung der Stadt Rheinfurt",
        "Sondernutzungssatzung (SoNutzS)",
        "AZ 20.12-2026-0010",
        "Diese Satzung regelt die Sondernutzung öffentlicher Straßen, Wege und Plätze im Gebiet "
        "der Stadt Rheinfurt, etwa durch Freischankflächen, Warenauslagen oder Baustelleneinrichtung.",
        [
            "Eine Sondernutzung bedarf der vorherigen Erlaubnis durch das Ordnungsamt.",
        ],
        gebuehren=[
            Gebuehrenzeile("Freischankfläche, je m² und Monat", "5,10 Euro"),
            Gebuehrenzeile("Baustelleneinrichtung im öffentlichen Raum, je m² und Woche", "1,90 Euro"),
        ],
    ),
    simple_satzung(
        "gestaltungssatzung-altstadt",
        "Gestaltungssatzung für die Altstadt Rheinfurt",
        "Gestaltungssatzung Altstadt (GestS)",
        "AZ 20.13-2026-0011",
        "Diese Satzung regelt die äußere Gestaltung baulicher Anlagen im Geltungsbereich der "
        "historischen Altstadt Rheinfurts zwischen Rheinufer, Marktstraße und Domplatz.",
        [
            "Werbeanlagen, Fassadenfarben und Dachdeckungen bedürfen im Geltungsbereich einer "
            "gesonderten Genehmigung des Stadtplanungsamts.",
        ],
    ),
    simple_satzung(
        "stellplatzsatzung",
        "Stellplatzsatzung der Stadt Rheinfurt",
        "Stellplatzsatzung (StellplS)",
        "AZ 20.14-2026-0012",
        "Diese Satzung regelt die Zahl der bei Neubauten nachzuweisenden Kraftfahrzeug- und "
        "Fahrradstellplätze im Gebiet der Stadt Rheinfurt.",
        [
            "Kann der Stellplatznachweis nicht auf dem Baugrundstück erbracht werden, ist eine "
            "Ablösesumme an die Stadt Rheinfurt zu entrichten.",
        ],
        gebuehren=[
            Gebuehrenzeile("Ablösesumme je fehlendem Kfz-Stellplatz", "9.200 Euro"),
        ],
    ),
    simple_satzung(
        "baumschutzverordnung",
        "Baumschutzverordnung der Stadt Rheinfurt",
        "Baumschutzverordnung (BaumSchV)",
        "AZ 20.15-2026-0013",
        "Diese Verordnung schützt Bäume ab einem Stammumfang von 80 cm (gemessen in 1 m Höhe) im "
        "Gebiet der Stadt Rheinfurt vor Fällung ohne Genehmigung.",
        [
            "Die Fällung eines geschützten Baumes bedarf einer Genehmigung des Umweltamts und ist "
            "in der Regel mit einer Ersatzpflanzung verbunden.",
        ],
        gebuehren=[
            Gebuehrenzeile("Fällgenehmigung, Bearbeitungsgebühr", "65 Euro"),
        ],
    ),
    simple_satzung(
        "entwaesserungssatzung",
        "Entwässerungssatzung der Stadt Rheinfurt",
        "Entwässerungssatzung (EWS)",
        "AZ 20.16-2026-0014",
        "Diese Satzung regelt den Anschluss an die öffentliche Abwasseranlage und die Benutzung "
        "dieser Anlage im Gebiet der Stadt Rheinfurt.",
        [
            "Grundstücke im Geltungsbereich unterliegen dem Anschluss- und Benutzungszwang.",
        ],
        gebuehren=[
            Gebuehrenzeile("Schmutzwassergebühr, je m³", "2,85 Euro"),
            Gebuehrenzeile("Niederschlagswassergebühr, je m² versiegelter Fläche und Jahr", "0,68 Euro"),
        ],
    ),
    simple_satzung(
        "abfallwirtschaftssatzung",
        "Abfallwirtschaftssatzung der Stadt Rheinfurt",
        "Abfallwirtschaftssatzung (AbfWS)",
        "AZ 20.17-2026-0015",
        "Diese Satzung regelt die Abfallentsorgung, insbesondere die Anmeldung von Rest-, "
        "Bio- und Papiertonnen, im Gebiet der Stadt Rheinfurt.",
        [
            "Grundstücke im Geltungsbereich unterliegen dem Anschluss- und Benutzungszwang an die "
            "städtische Abfallentsorgung.",
        ],
        gebuehren=[
            Gebuehrenzeile("Restmülltonne 120 l, je Jahr", "186 Euro"),
            Gebuehrenzeile("Biotonne 120 l, je Jahr", "58 Euro"),
        ],
    ),
    simple_satzung(
        "personenstandsgebuehrensatzung",
        "Satzung über die Erhebung von Gebühren im Standesamt",
        "Personenstandsgebührensatzung (PStGebS)",
        "AZ 20.18-2026-0016",
        "Diese Satzung regelt die Gebühren für Amtshandlungen des Standesamts Rheinfurt nach dem "
        "Personenstandsgesetz.",
        [
            "Sie gilt für Eheschließungen, Urkundenausstellungen und Beurkundungen des "
            "Standesamts Rheinfurt.",
        ],
        gebuehren=[
            Gebuehrenzeile("Eheschließung im Standesamt (Regeltermin)", scale_and_format_fee(50.00, "Anmeldung einer Eheschließung.txt")),
            Gebuehrenzeile("Eheurkunde, weitere Ausfertigung", "12 Euro"),
        ],
    ),
    simple_satzung(
        "gruenanlagensatzung",
        "Satzung über die Nutzung städtischer Grünanlagen",
        "Grünanlagensatzung (GrünAS)",
        "AZ 20.19-2026-0017",
        "Diese Satzung regelt die Nutzung der öffentlichen Grün- und Parkanlagen im Gebiet der "
        "Stadt Rheinfurt, einschließlich des Rheinufer-Parks.",
        [
            "Veranstaltungen und gewerbliche Nutzungen in Grünanlagen bedürfen einer vorherigen "
            "Anmeldung beim Grünflächenamt.",
        ],
    ),
    simple_satzung(
        "sperrzeitensatzung",
        "Satzung zur Regelung der Sperrzeiten für Gaststätten",
        "Sperrzeitensatzung (SperrzS)",
        "AZ 20.20-2026-0018",
        "Diese Satzung regelt die allgemeine Sperrzeit für Gaststätten im Gebiet der Stadt "
        "Rheinfurt.",
        [
            "Die allgemeine Sperrzeit beginnt um 5:00 Uhr und endet um 6:00 Uhr.",
            "Für Veranstaltungen von besonderem öffentlichen Interesse (z. B. Stadtfest) kann das "
            "Ordnungsamt die Sperrzeit auf Antrag verkürzen oder aufheben.",
        ],
    ),
    simple_satzung(
        "anwohnerparkausweissatzung",
        "Satzung über Anwohnerparkausweise",
        "Anwohnerparkausweissatzung (AnwPS)",
        "AZ 20.21-2026-0019",
        "Diese Satzung regelt die Ausgabe von Anwohnerparkausweisen für die "
        "Bewohnerparkzonen im Gebiet der Stadt Rheinfurt.",
        [
            "Anspruchsberechtigt sind Personen mit Hauptwohnsitz und Fahrzeughalterschaft "
            "innerhalb der jeweiligen Bewohnerparkzone.",
        ],
        gebuehren=[
            Gebuehrenzeile("Anwohnerparkausweis, je Jahr", "30,70 Euro"),
        ],
    ),
]


def render_satzung_pdf(satzung: Satzung) -> bytes:
    buffer = BytesIO()
    doc = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=2.2 * cm,
        rightMargin=2.2 * cm,
        topMargin=2 * cm,
        bottomMargin=2 * cm,
        title=satzung.titel,
        author="Stadt Rheinfurt (synthetisch)",
    )
    story = [
        Paragraph(satzung.titel, TITLE_STYLE),
        Paragraph(f"Aktenzeichen (Muster): {satzung.aktenzeichen}", FOOTER_STYLE),
        Spacer(1, 0.4 * cm),
    ]
    for line in satzung.praeambel:
        story.append(Paragraph(line, BODY_STYLE))
    story.append(Spacer(1, 0.2 * cm))

    for paragraf in satzung.paragrafen:
        story.append(Paragraph(f"§ {paragraf.nummer} {paragraf.ueberschrift}", PARAGRAPH_HEADING_STYLE))
        for absatz in paragraf.text:
            story.append(Paragraph(absatz, BODY_STYLE))

    if satzung.gebuehren:
        story.append(Paragraph("Anlage: Gebührenverzeichnis", PARAGRAPH_HEADING_STYLE))
        data = [["Gebührentatbestand", "Betrag"]]
        for zeile in satzung.gebuehren:
            data.append([zeile.tatbestand, zeile.betrag])
        table = Table(data, colWidths=[11 * cm, 4 * cm])
        table.setStyle(
            TableStyle(
                [
                    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#2f3e4e")),
                    ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                    ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                    ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
                    ("ALIGN", (1, 0), (1, -1), "RIGHT"),
                    ("VALIGN", (0, 0), (-1, -1), "TOP"),
                    ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f2f4f6")]),
                    ("FONTSIZE", (0, 0), (-1, -1), 9),
                    ("TOPPADDING", (0, 0), (-1, -1), 4),
                    ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
                ]
            )
        )
        story.append(table)

    story.append(Spacer(1, 0.6 * cm))
    story.append(Paragraph(SYNTHETIC_NOTICE, FOOTER_STYLE))

    doc.build(story)
    return buffer.getvalue()
