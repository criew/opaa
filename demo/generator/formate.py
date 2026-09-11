"""Data and rendering for the "Formattest auf S3" library (#1519, #1520).

One document per file extension OPAA admits (docs/handbuch/indexierung.md, "Anhang:
Formatübersicht"), so the demo can show that every supported format really is read rather than
merely listed. Each document names its own format in its text, which is what makes a chat answer
attributable to the format it came from; the content is ordinary Rheinfurt administration around
document formats and long-term archiving, not filler.

Two extensions have no writer in any pinned library and are therefore committed files rather than
generated ones (see generator/README.md, "Formate ohne Writer", and PRESERVED_FILES in
generate_corpus.py): `.doc` (Word 97 binary) and `.msg` (OLE2/MAPI).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from html import escape
from io import BytesIO
from zipfile import ZIP_DEFLATED, ZipFile

import docx
import openpyxl
from docx.shared import Pt
from openpyxl.styles import Font
from openpyxl.writer.excel import ExcelWriter
from pptx import Presentation
from pptx.util import Pt as PptxPt
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer

import odf_utils
from zip_utils import normalize_zip_timestamps

SYNTHETIC_NOTICE = (
    "Synthetisches Musterdokument der fiktiven Stadt Rheinfurt aus der Bibliothek "
    "'Formattest auf S3' des OPAA-Demo-Korpus (siehe SOURCE.md im Wurzelverzeichnis dieses "
    "Korpus). Es zeigt ein unterstütztes Dateiformat mit echtem Inhalt."
)

AUTHOR = "Stadt Rheinfurt, Organisationsstelle (synthetisch)"

# One fixed date for every document property this library writes: a wall-clock value would make
# the package bytes differ between two runs.
FIXED_TIMESTAMP = datetime(2026, 3, 2, 8, 0, 0)
FIXED_ISO = "2026-03-02T08:00:00"


@dataclass(frozen=True)
class RenderedDocument:
    """One corpus file: its name, its bytes, and the plain text the validation pass checks."""

    file_name: str
    content: bytes
    text: str


# --- 01 Markdown -------------------------------------------------------------

_MARKDOWN = """# Formatübersicht der Dokumentenablage im Bürgerbüro Rheinfurt

Dieses Dokument liegt im Format Markdown (`.md`) vor.

## Zweck

Die Organisationsstelle der Stadt Rheinfurt führt eine verbindliche Liste der Dateiformate, die in
der elektronischen Ablage des Bürgerbüros angenommen werden. Jedes Muster dieser Sammlung steht für
genau ein Format und trägt dessen Bezeichnung im Text, damit im Zweifel erkennbar bleibt, aus
welcher Datei eine Auskunft stammt.

## Zugelassene Formate

### Texte und Vorlagen

- Markdown (`.md`) für kurze Handreichungen und Merkblätter
- einfacher Text (`.txt`) für Protokollauszüge aus dem Fachverfahren
- Word (`.docx`) und OpenDocument-Text (`.odt`) für Vermerke und Aktenplanauszüge
- Word 97 (`.doc`) nur noch für Altbestände aus der Zeit vor 2012

### Tabellen

- Excel (`.xlsx`), OpenDocument-Tabelle (`.ods`) und Trennzeichentext (`.csv`)

### Präsentationen, Seiten und Nachrichten

- PowerPoint (`.pptx`) und OpenDocument-Präsentation (`.odp`)
- HTML (`.html`) für Intranetseiten
- PDF (`.pdf`) für abgeschlossene Vorgänge
- E-Mail (`.eml`) und Outlook-Nachricht (`.msg`) für den Schriftverkehr

## Zuständigkeit

Die Formatfreigabe bestätigt die Amtsleitung des Bürgerbüros jährlich zum 1. Februar. Änderungen
beantragt die Organisationsstelle, Rathausplatz 1, 00000 Rheinfurt.

---

{notice}
""".format(notice=SYNTHETIC_NOTICE)


# --- 02 Text -----------------------------------------------------------------

_TEXT = """Ablagehinweise für Formatmuster
Stadt Rheinfurt, Organisationsstelle

Dieses Dokument liegt als einfache Textdatei (.txt) vor.

Ablageschlüssel: AZ 10.4-FORM-2026-002

1. Jedes Formatmuster wird unter dem Ablageschlüssel 10.4-FORM geführt und behält seinen
   Dateinamen unverändert bei. Umbenennungen durch die Sachbearbeitung sind nicht zulässig,
   weil der Dateiname die Nummer des Musters trägt.

2. Ein Muster wird ersetzt, nicht ergänzt: Zu jedem Format existiert genau eine Datei. Wer ein
   Muster austauscht, vermerkt das im Vermerk zur Langzeitarchivierung.

3. Eingehende Dateien ohne erkennbares Format nimmt die Poststelle nicht an. Sie werden mit dem
   Hinweis auf diese Ablagehinweise an die absendende Stelle zurückgegeben.

4. Rückfragen beantwortet die Organisationsstelle unter 01234/44-1040.

{notice}
""".format(notice=SYNTHETIC_NOTICE)


# --- 03 PDF ------------------------------------------------------------------

_PDF_TITLE = "Dienstanweisung zur Annahme elektronischer Dokumente"
_PDF_SUBTITLE = "Aktenzeichen: AZ 10.4-FORM-2026-003 · Dateiformat dieses Musters: PDF (.pdf)"
_PDF_SECTIONS: list[tuple[str, list[str]]] = [
    (
        "§ 1 Geltungsbereich",
        [
            "Diese Dienstanweisung regelt, in welcher Form das Bürgerbüro Rheinfurt elektronische "
            "Dokumente von Bürgerinnen, Bürgern, Unternehmen und anderen Stellen annimmt. Sie gilt "
            "für alle Sachgebiete des Bürgerbüros.",
        ],
    ),
    (
        "§ 2 Zugelassene Formate",
        [
            "Angenommen werden ausschließlich die in der Formatübersicht der Organisationsstelle "
            "aufgeführten Dateiformate. Eine Datei in einem anderen Format gilt als nicht "
            "eingegangen; die einreichende Person wird darauf hingewiesen und um erneute "
            "Einreichung gebeten.",
        ],
    ),
    (
        "§ 3 Größenbegrenzung",
        [
            "Dateien über 50 Megabyte werden nicht per E-Mail angenommen, sondern ausschließlich "
            "über die Austauschplattform der Stadt Rheinfurt. Die Poststelle richtet dafür auf "
            "Anforderung einen befristeten Zugang ein, der nach 14 Tagen erlischt.",
        ],
    ),
    (
        "§ 4 Eingescannte Unterlagen",
        [
            "Eingescannte Unterlagen ohne Textebene werden zurückgewiesen, weil sie weder "
            "durchsuchbar noch maschinell auswertbar sind. Der Scandienstleister der Stadt "
            "Rheinfurt liefert ausschließlich durchsuchbare PDF-Dateien.",
        ],
    ),
    (
        "§ 5 Inkrafttreten",
        [
            "Diese Dienstanweisung tritt am 1. März 2026 in Kraft und ersetzt die Regelung vom "
            "1. Juni 2021.",
        ],
    ),
]


# --- 04 Word (.docx) ---------------------------------------------------------

_DOCX_TITLE = "Aktenplanauszug: Gruppe 10.4 Dokumentenformate"
_DOCX_SUBTITLE = "Dateiformat dieses Musters: Word (.docx) · Stand: 2. März 2026"
_DOCX_SECTIONS: list[tuple[str, list[str]]] = [
    (
        "Einordnung",
        [
            "Die Aktenplangruppe 10.4 führt alle Unterlagen zur Steuerung der elektronischen "
            "Ablage: Formatfreigaben, Formatmuster, Vermerke zur Langzeitarchivierung und die "
            "Verträge mit dem Scandienstleister.",
        ],
    ),
    (
        "Untergruppen",
        [
            "Die Untergruppen werden nicht weiter geteilt. Eine Unterlage, die in keine der vier "
            "Untergruppen passt, gehört in die Gruppe 10.0 Allgemeine Organisation.",
        ],
    ),
    (
        "Aufbewahrung",
        [
            "Die Aufbewahrungsfrist der Formatmuster beträgt zehn Jahre ab dem Ende des Jahres, in "
            "dem das Muster ersetzt wurde. Danach entscheidet das Stadtarchiv Rheinfurt über die "
            "Übernahme.",
        ],
    ),
]
_DOCX_TABLE: list[list[str]] = [
    ["Aktenzeichen", "Bezeichnung", "Aufbewahrung"],
    ["10.4-FORM", "Formatfreigaben und Formatmuster", "10 Jahre"],
    ["10.4-ARCH", "Langzeitarchivierung, Speicherkosten", "dauernd"],
    ["10.4-SCAN", "Scandienstleister, Rahmenvertrag", "10 Jahre nach Vertragsende"],
    ["10.4-POST", "Posteingang, Formatstatistik", "5 Jahre"],
]


# --- 05 PowerPoint (.pptx) ---------------------------------------------------


@dataclass(frozen=True)
class Folie:
    titel: str
    punkte: list[str]


_PPTX_TITLE = "Schulung: Dateiformate im Bürgerbüro Rheinfurt"
_PPTX_FOLIEN: list[Folie] = [
    Folie(_PPTX_TITLE, ["Dateiformat dieser Folien: PowerPoint (.pptx)"]),
    Folie(
        "Warum Formate geregelt werden",
        [
            "Ein Vorgang muss noch in 30 Jahren lesbar sein",
            "Nur durchsuchbare Dateien lassen sich wiederfinden",
            "Jedes zusätzliche Format kostet Prüfaufwand in der Poststelle",
        ],
    ),
    Folie(
        "Was die Poststelle prüft",
        [
            "Trägt die Datei eines der freigegebenen Formate?",
            "Ist der Text enthalten oder nur ein Bild der Seite?",
            "Bleibt die Datei unter 50 Megabyte?",
        ],
    ),
    Folie(
        "Häufige Fehler",
        [
            "Fotografierte Formulare statt eingescannter Unterlagen",
            "Archive mit mehreren Dateien statt Einzeldateien",
            "Tabellen als Bild in einer Präsentation",
        ],
    ),
    Folie(
        "Termine",
        [
            "Nächster Schulungstermin: 14. April 2026, Sitzungssaal Rheinpromenade",
            "Anmeldung über die Organisationsstelle, 01234/44-1040",
        ],
    ),
]


# --- 06 HTML -----------------------------------------------------------------

_HTML_TITLE = "Formathinweise im Intranet der Stadt Rheinfurt"
_HTML_INTRO = (
    "Diese Seite liegt im Format HTML (.html) vor und fasst zusammen, was die Sachbearbeitung "
    "über die zugelassenen Dateiformate wissen muss."
)
_HTML_SECTIONS: list[tuple[str, list[str]]] = [
    (
        "Was sofort angenommen wird",
        [
            "Alle Formate der Formatübersicht der Organisationsstelle, sofern die Datei Text "
            "enthält und unter 50 Megabyte bleibt.",
        ],
    ),
    (
        "Was zurückgewiesen wird",
        [
            "Eingescannte Unterlagen ohne Textebene, Archive mit mehreren Dateien und Formate "
            "außerhalb der Übersicht. Die Poststelle weist sie mit einem Textbaustein zurück.",
        ],
    ),
    (
        "Wer weiterhilft",
        [
            "Rückfragen beantwortet die Organisationsstelle unter 01234/44-1040 oder unter "
            "organisation@stadt-rheinfurt.example.",
        ],
    ),
]
_HTML_TABLE: list[list[str]] = [
    ["Anliegen", "Zuständig", "Erreichbarkeit"],
    ["Formatfreigabe", "Organisationsstelle", "Mo–Do 8–16 Uhr"],
    ["Langzeitarchivierung", "Stadtarchiv Rheinfurt", "Di und Do 9–12 Uhr"],
    ["Scanauftrag", "Poststelle", "Mo–Fr 8–12 Uhr"],
]


# --- 07 Excel (.xlsx) --------------------------------------------------------

_XLSX_SHEET = "Gebühren Formatprüfung"
_XLSX_ROWS: list[list[str]] = [
    ["Tatbestand", "Gebühr", "Rechtsgrundlage"],
    ["Formatprüfung eines Altbestands je angefangene 1000 Seiten", "84,00 Euro", "§ 7 VGS"],
    ["Nachträgliche Umwandlung einer Datei nach PDF/A je Vorgang", "12,50 Euro", "§ 7 VGS"],
    ["Erstellung eines befristeten Zugangs zur Austauschplattform", "0,00 Euro", "§ 2 VGS"],
    ["Auskunft aus der Formatstatistik des Posteingangs", "18,00 Euro", "§ 9 VGS"],
    ["Beglaubigte Abschrift eines archivierten Vorgangs", "6,00 Euro", "§ 4 VGS"],
]


# --- 08 CSV ------------------------------------------------------------------

_CSV_ROWS: list[list[str]] = [
    ["Monat", "Format", "Eingänge", "Anteil in Prozent"],
    ["2026-01", "pdf", "1842", "61,4"],
    ["2026-01", "docx", "402", "13,4"],
    ["2026-01", "eml", "311", "10,4"],
    ["2026-01", "jpg (zurückgewiesen)", "205", "6,8"],
    ["2026-02", "pdf", "1760", "62,1"],
    ["2026-02", "docx", "371", "13,1"],
    ["2026-02", "eml", "298", "10,5"],
    ["2026-02", "xlsx", "119", "4,2"],
    ["2026-03", "pdf", "1903", "63,0"],
    ["2026-03", "docx", "388", "12,8"],
    ["2026-03", "odt", "96", "3,2"],
    ["2026-03", "msg", "74", "2,4"],
    [
        "Hinweis",
        "Dateiformat dieses Musters: Trennzeichentext (.csv)",
        "Trennzeichen Semikolon",
        "Kodierung UTF-8",
    ],
]


# --- 09 OpenDocument-Tabelle (.ods) ------------------------------------------

_ODS_TITLE = "Kosten des Langzeitspeichers im Stadtarchiv Rheinfurt"
_ODS_SHEETS: list[tuple[str, list[list[str]]]] = [
    (
        "Speicherkosten",
        [
            ["Kostenstelle", "Leistung", "Betrag je Jahr"],
            ["10.4-ARCH", "Langzeitspeicher Stadtarchiv Rheinfurt", "12.480,00 Euro"],
            ["10.4-ARCH", "Signaturprüfstelle für archivierte Dokumente", "3.250,00 Euro"],
            ["10.4-SCAN", "Rahmenvertrag Scandienstleister", "9.700,00 Euro"],
            ["10.4-FORM", "Pflege der Formatmuster", "1.100,00 Euro"],
            ["", "Summe", "26.530,00 Euro"],
        ],
    ),
    (
        "Formatanteile",
        [
            ["Format", "Dateien im Langzeitspeicher", "Belegter Speicher"],
            ["pdf", "184.320", "2,9 Terabyte"],
            ["docx", "41.870", "310 Gigabyte"],
            ["odt", "9.412", "62 Gigabyte"],
            ["msg", "7.905", "148 Gigabyte"],
            ["doc", "3.214", "27 Gigabyte"],
        ],
    ),
]
_ODS_CLOSING = (
    "Dateiformat dieses Musters: OpenDocument-Tabelle (.ods). Stand der Zahlen: 2. März 2026."
)


# --- 10 OpenDocument-Text (.odt) ---------------------------------------------

_ODT_TITLE = "Vermerk: Langzeitarchivierung im Format PDF/A"
_ODT_SECTIONS: list[tuple[int, str, list[str]]] = [
    (
        2,
        "Anlass",
        [
            "Das Stadtarchiv Rheinfurt übernimmt seit 2024 alle abgeschlossenen Vorgänge des "
            "Bürgerbüros in den Langzeitspeicher. Dieser Vermerk hält fest, in welchem Format die "
            "Übernahme erfolgt und welche Fristen dabei gelten.",
            "Dateiformat dieses Musters: OpenDocument-Text (.odt).",
        ],
    ),
    (
        2,
        "Format der Übernahme",
        [
            "Übernommen wird ausschließlich PDF/A. Vorgänge, die in einem anderen Format "
            "vorliegen, wandelt die Poststelle vor der Übergabe um; die Ausgangsdatei bleibt bis "
            "zum Abschluss der Prüfung erhalten.",
        ],
    ),
    (
        3,
        "Ausnahmen",
        [
            "E-Mail-Nachrichten werden im Ursprungsformat übernommen, weil eine Umwandlung die "
            "Kopfdaten verlieren würde. Tabellen mit Rechenformeln bleiben ebenfalls im "
            "Ursprungsformat.",
        ],
    ),
    (
        2,
        "Aufbewahrungsfristen",
        [
            "Für Bauakten gilt eine Aufbewahrungsfrist von 30 Jahren. Melderegisterauszüge werden "
            "nach fünf Jahren gelöscht, sofern kein laufendes Verfahren entgegensteht.",
        ],
    ),
]
_ODT_TABLE: list[list[str]] = [
    ["Unterlagenart", "Frist", "Danach"],
    ["Bauakten", "30 Jahre", "Übernahme durch das Stadtarchiv"],
    ["Melderegisterauszüge", "5 Jahre", "Löschung"],
    ["Gebührenbescheide", "10 Jahre", "Übernahme durch das Stadtarchiv"],
]
_ODT_HEADER = "Stadt Rheinfurt – Stadtarchiv"
_ODT_FOOTER = "Vermerk AZ 10.4-ARCH-2026-011"


# --- 11 OpenDocument-Präsentation (.odp) -------------------------------------


@dataclass(frozen=True)
class OdpFolie:
    titel: str
    punkte: list[str]
    notizen: str


_ODP_TITLE = "Archivformate der Stadt Rheinfurt"
_ODP_FOLIEN: list[OdpFolie] = [
    OdpFolie(
        _ODP_TITLE,
        ["Dateiformat dieser Folien: OpenDocument-Präsentation (.odp)"],
        "Vorgestellt im Verwaltungsvorstand am 2. März 2026.",
    ),
    OdpFolie(
        "Was sich 2027 ändert",
        [
            "Ab 2027 nimmt das Stadtarchiv keine Dateien im Format .doc mehr an",
            "Altbestände werden bis dahin umgewandelt",
            "Für Tabellen bleibt .ods weiterhin zugelassen",
        ],
        "Die Umwandlung der Altbestände übernimmt der Scandienstleister im Rahmen des laufenden "
        "Rahmenvertrags.",
    ),
    OdpFolie(
        "Aufwand und Kosten",
        [
            "Langzeitspeicher: 12.480,00 Euro im Jahr",
            "Umwandlung der Altbestände: einmalig rund 9.000 Euro",
        ],
        "Die Zahlen stammen aus der Kostenaufstellung des Stadtarchivs.",
    ),
    OdpFolie(
        "Nächste Schritte",
        [
            "Formatübersicht bis 1. Februar 2027 fortschreiben",
            "Schulung der Poststelle im April 2026",
        ],
        "Die Schulung findet im Sitzungssaal Rheinpromenade statt.",
    ),
]
_ODP_MASTER = "Stadt Rheinfurt – Stadtarchiv"


# --- 12 E-Mail (.eml) --------------------------------------------------------

_EML_SUBJECT = "Umstellung auf PDF/A-2b zum 1. Oktober 2026"
_EML_FROM = "Stadtarchiv Rheinfurt <stadtarchiv@stadt-rheinfurt.example>"
_EML_TO = "Bürgerbüro Rheinfurt <buergerbuero@stadt-rheinfurt.example>"
_EML_CC = "Organisationsstelle <organisation@stadt-rheinfurt.example>"
_EML_DATE = "Mon, 2 Mar 2026 08:00:00 +0100"
_EML_MESSAGE_ID = "<2026030208000010.4-arch-2026-012@stadt-rheinfurt.example>"
_EML_BODY = """Sehr geehrte Kolleginnen und Kollegen,

das Stadtarchiv stellt die Übernahme abgeschlossener Vorgänge zum 1. Oktober 2026 auf PDF/A-2b um.
Bis dahin nehmen wir weiterhin PDF/A-1b entgegen; danach weist die Übernahmeschnittstelle Dateien
im älteren Profil zurück.

Was das für das Bürgerbüro bedeutet:

- Der Scandienstleister liefert ab dem 1. Juli 2026 nur noch PDF/A-2b.
- Bereits übernommene Vorgänge werden nicht nachträglich umgewandelt.
- Für E-Mail-Nachrichten ändert sich nichts; sie bleiben im Ursprungsformat.

Diese Nachricht liegt als E-Mail-Datei (.eml) vor und gehört zum Vorgang AZ 10.4-ARCH-2026-012.

Mit freundlichen Grüßen
Stadtarchiv Rheinfurt
Rathausplatz 1, 00000 Rheinfurt
Telefon 01234/44-1080

{notice}
""".format(notice=SYNTHETIC_NOTICE)


# --- 13 Word 97 (.doc), committed, not generated -----------------------------
#
# No pinned library writes the Word 97 binary format. This text is converted once with LibreOffice
# (make_doc_fixture.py) and the result is committed; generate_corpus.py preserves the file and
# still covers it by MANIFEST.sha256, and the validation pass reads the text declared here.

DOC_FILE_NAME = "13_rahmenvertrag-scandienstleister.doc"
DOC_TITLE = "Rahmenvertrag über Scandienstleistungen (Auszug)"
DOC_SUBTITLE = "Aktenzeichen: AZ 10.4-SCAN-2022-004 · Dateiformat dieses Musters: Word 97 (.doc)"
DOC_SECTIONS: list[tuple[str, list[str]]] = [
    (
        "Vertragsgegenstand",
        [
            "Der Auftragnehmer digitalisiert die Altbestände des Bürgerbüros der Stadt Rheinfurt "
            "aus den Jahren 1998 bis 2012 und übergibt sie an das Stadtarchiv Rheinfurt. Die "
            "Abholung der Papierakten erfolgt monatlich nach Abruf.",
        ],
    ),
    (
        "Laufzeit",
        [
            "Der Rahmenvertrag endet am 31. Dezember 2027 und verlängert sich nicht "
            "stillschweigend. Eine Verlängerung bedarf der Schriftform und des Beschlusses des "
            "Hauptausschusses.",
        ],
    ),
    (
        "Lieferformat",
        [
            "Geliefert wird ausschließlich durchsuchbares PDF/A. Ab dem 1. Juli 2026 liefert der "
            "Auftragnehmer im Profil PDF/A-2b; ältere Lieferungen bleiben unverändert.",
        ],
    ),
    (
        "Vergütung",
        [
            "Die Vergütung beträgt 0,07 Euro je gescannter Seite, mindestens jedoch 250,00 Euro "
            "je Auftrag. Die Rechnungsstellung erfolgt monatlich.",
        ],
    ),
]


def doc_text() -> str:
    """The text the committed `.doc` carries — validated like every generated document."""
    return _plain_sections(DOC_TITLE, DOC_SUBTITLE, DOC_SECTIONS)


def render_doc_source_docx() -> bytes:
    """The Word source make_doc_fixture.py hands to LibreOffice; never part of the corpus."""
    document = docx.Document()
    document.styles["Normal"].font.size = Pt(11)
    document.add_heading(DOC_TITLE, level=1)
    document.add_paragraph().add_run(DOC_SUBTITLE).italic = True
    for heading, paragraphs in DOC_SECTIONS:
        document.add_heading(heading, level=2)
        for text in paragraphs:
            document.add_paragraph(text)
    document.add_paragraph()
    notice_run = document.add_paragraph().add_run(SYNTHETIC_NOTICE)
    notice_run.italic = True
    notice_run.font.size = Pt(9)
    buffer = BytesIO()
    document.save(buffer)
    return buffer.getvalue()


# --- Rendering ---------------------------------------------------------------


def _plain_sections(title: str, subtitle: str, sections: list[tuple[str, list[str]]]) -> str:
    parts = [title, subtitle]
    for heading, paragraphs in sections:
        parts.append(heading)
        parts.extend(paragraphs)
    parts.append(SYNTHETIC_NOTICE)
    return "\n".join(parts)


def _render_pdf() -> bytes:
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle("FormatTitle", parent=styles["Title"], fontSize=15, spaceAfter=10)
    heading_style = ParagraphStyle(
        "FormatHeading", parent=styles["Heading3"], spaceBefore=10, spaceAfter=2
    )
    body_style = ParagraphStyle("FormatBody", parent=styles["BodyText"], spaceAfter=6)
    footer_style = ParagraphStyle(
        "FormatFooter", parent=styles["BodyText"], fontSize=8, textColor=colors.grey
    )

    buffer = BytesIO()
    doc = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=2.2 * cm,
        rightMargin=2.2 * cm,
        topMargin=2 * cm,
        bottomMargin=2 * cm,
        title=_PDF_TITLE,
        author=AUTHOR,
    )
    story = [
        Paragraph(_PDF_TITLE, title_style),
        Paragraph(_PDF_SUBTITLE, footer_style),
        Spacer(1, 0.4 * cm),
    ]
    for heading, paragraphs in _PDF_SECTIONS:
        story.append(Paragraph(heading, heading_style))
        for text in paragraphs:
            story.append(Paragraph(text, body_style))
    story.append(Spacer(1, 0.6 * cm))
    story.append(Paragraph(SYNTHETIC_NOTICE, footer_style))
    doc.build(story)
    return buffer.getvalue()


def _render_docx() -> bytes:
    document = docx.Document()
    document.styles["Normal"].font.size = Pt(11)

    document.add_heading(_DOCX_TITLE, level=1)
    subtitle = document.add_paragraph()
    subtitle.add_run(_DOCX_SUBTITLE).italic = True

    for heading, paragraphs in _DOCX_SECTIONS:
        document.add_heading(heading, level=2)
        for text in paragraphs:
            document.add_paragraph(text)
        if heading == "Untergruppen":
            table = document.add_table(rows=0, cols=len(_DOCX_TABLE[0]))
            table.style = "Table Grid"
            for row in _DOCX_TABLE:
                cells = table.add_row().cells
                for cell, value in zip(cells, row):
                    cell.text = value

    document.add_paragraph()
    notice = document.add_paragraph()
    notice_run = notice.add_run(SYNTHETIC_NOTICE)
    notice_run.italic = True
    notice_run.font.size = Pt(9)

    buffer = BytesIO()
    document.save(buffer)
    return normalize_zip_timestamps(buffer.getvalue())


def _render_pptx() -> bytes:
    presentation = Presentation()
    title_layout = presentation.slide_layouts[0]
    bullet_layout = presentation.slide_layouts[1]

    first = _PPTX_FOLIEN[0]
    title_slide = presentation.slides.add_slide(title_layout)
    title_slide.shapes.title.text = first.titel
    title_slide.placeholders[1].text = first.punkte[0]

    for folie in _PPTX_FOLIEN[1:]:
        slide = presentation.slides.add_slide(bullet_layout)
        slide.shapes.title.text = folie.titel
        body = slide.placeholders[1].text_frame
        body.clear()
        for index, punkt in enumerate(folie.punkte):
            paragraph = body.paragraphs[0] if index == 0 else body.add_paragraph()
            paragraph.text = punkt
            paragraph.font.size = PptxPt(20)

    closing = presentation.slides.add_slide(bullet_layout)
    closing.shapes.title.text = "Hinweis"
    closing_body = closing.placeholders[1].text_frame
    closing_body.clear()
    closing_body.paragraphs[0].text = SYNTHETIC_NOTICE
    closing_body.paragraphs[0].font.size = PptxPt(14)

    buffer = BytesIO()
    presentation.save(buffer)
    return normalize_zip_timestamps(buffer.getvalue())


def _render_html() -> bytes:
    sections = "\n".join(
        "    <h2>{heading}</h2>\n{paragraphs}".format(
            heading=escape(heading),
            paragraphs="\n".join(f"    <p>{escape(text)}</p>" for text in paragraphs),
        )
        for heading, paragraphs in _HTML_SECTIONS
    )
    header_cells = "".join(f"<th>{escape(value)}</th>" for value in _HTML_TABLE[0])
    body_rows = "\n".join(
        "      <tr>" + "".join(f"<td>{escape(value)}</td>" for value in row) + "</tr>"
        for row in _HTML_TABLE[1:]
    )
    html = f"""<!DOCTYPE html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <title>{escape(_HTML_TITLE)} – Stadt Rheinfurt</title>
</head>
<body>
  <nav><a href="/">Intranet-Startseite</a></nav>
  <article>
    <h1>{escape(_HTML_TITLE)}</h1>
    <p>{escape(_HTML_INTRO)}</p>
{sections}
    <h2>Zuständigkeiten auf einen Blick</h2>
    <table>
      <tr>{header_cells}</tr>
{body_rows}
    </table>
    <hr>
    <p><small>{escape(SYNTHETIC_NOTICE)}</small></p>
  </article>
</body>
</html>
"""
    return html.encode("utf-8")


def _render_xlsx() -> bytes:
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet.title = _XLSX_SHEET
    for row in _XLSX_ROWS:
        sheet.append(row)
    for cell in sheet[1]:
        cell.font = Font(bold=True)
    sheet.column_dimensions["A"].width = 58
    sheet.column_dimensions["B"].width = 14
    sheet.column_dimensions["C"].width = 18
    sheet.append([])
    sheet.append([SYNTHETIC_NOTICE])

    workbook.properties.creator = AUTHOR
    workbook.properties.lastModifiedBy = AUTHOR
    workbook.properties.title = "Gebührenübersicht Formatprüfung – Dateiformat: Excel (.xlsx)"
    workbook.properties.created = FIXED_TIMESTAMP
    workbook.properties.modified = FIXED_TIMESTAMP

    # Written through ExcelWriter instead of Workbook.save: the latter overwrites
    # properties.modified with the current time right before writing, which alone would make two
    # runs produce different bytes.
    buffer = BytesIO()
    archive = ZipFile(buffer, "w", ZIP_DEFLATED, allowZip64=True)
    ExcelWriter(workbook, archive).save()
    return normalize_zip_timestamps(buffer.getvalue(), first_entry="[Content_Types].xml")


def _render_csv() -> bytes:
    return ("\n".join(";".join(row) for row in _CSV_ROWS) + "\n").encode("utf-8")


def _odf_table(name: str, rows: list[list[str]]) -> str:
    cells = []
    for row in rows:
        cells.append("    <table:table-row>")
        for value in row:
            cells.append(
                '     <table:table-cell office:value-type="string">'
                f"<text:p>{escape(value)}</text:p></table:table-cell>"
            )
        cells.append("    </table:table-row>")
    body = "\n".join(cells)
    return f'   <table:table table:name="{escape(name)}">\n{body}\n   </table:table>'


def _render_ods() -> bytes:
    tables = "\n".join(_odf_table(name, rows) for name, rows in _ODS_SHEETS)
    closing = _odf_table("Hinweis", [[_ODS_CLOSING], [SYNTHETIC_NOTICE]])
    content = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:body>
  <office:spreadsheet>
{tables}
{closing}
  </office:spreadsheet>
 </office:body>
</office:document-content>
"""
    return odf_utils.package(
        odf_utils.ODS_MIME,
        content,
        odf_utils.meta_xml(_ODS_TITLE, AUTHOR, FIXED_ISO, FIXED_ISO),
    )


def _render_odt() -> bytes:
    parts = [f'   <text:h text:outline-level="1">{escape(_ODT_TITLE)}</text:h>']
    for level, heading, paragraphs in _ODT_SECTIONS:
        parts.append(f'   <text:h text:outline-level="{level}">{escape(heading)}</text:h>')
        for text in paragraphs:
            parts.append(f"   <text:p>{escape(text)}</text:p>")
        if heading == "Aufbewahrungsfristen":
            parts.append(_odf_table("Fristen", _ODT_TABLE))
    parts.append(f"   <text:p>{escape(SYNTHETIC_NOTICE)}</text:p>")
    body = "\n".join(parts)
    content = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:body>
  <office:text>
{body}
  </office:text>
 </office:body>
</office:document-content>
"""
    styles = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:master-styles>
  <style:master-page style:name="Standard">
   <style:header><text:p>{escape(_ODT_HEADER)}</text:p></style:header>
   <style:footer><text:p>{escape(_ODT_FOOTER)}</text:p></style:footer>
  </style:master-page>
 </office:master-styles>
</office:document-styles>
"""
    return odf_utils.package(
        odf_utils.ODT_MIME,
        content,
        odf_utils.meta_xml(_ODT_TITLE, AUTHOR, FIXED_ISO, FIXED_ISO),
        styles,
    )


def _render_odp() -> bytes:
    pages = []
    for index, folie in enumerate(_ODP_FOLIEN, start=1):
        punkte = "\n".join(f"      <text:p>{escape(punkt)}</text:p>" for punkt in folie.punkte)
        pages.append(
            f"""   <draw:page draw:name="Folie{index}">
    <draw:frame presentation:class="title" draw:name="Titel {index}">
     <draw:text-box><text:p>{escape(folie.titel)}</text:p></draw:text-box>
    </draw:frame>
    <draw:frame presentation:class="outline" draw:name="Inhalt {index}">
     <draw:text-box>
{punkte}
     </draw:text-box>
    </draw:frame>
    <presentation:notes>
     <draw:frame presentation:class="notes" draw:name="Notizen {index}">
      <draw:text-box><text:p>{escape(folie.notizen)}</text:p></draw:text-box>
     </draw:frame>
    </presentation:notes>
   </draw:page>"""
        )
    body = "\n".join(pages)
    content = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
 xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:automatic-styles/>
 <office:body>
  <office:presentation>
{body}
  </office:presentation>
 </office:body>
</office:document-content>
"""
    styles = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
 xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
 xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:master-styles>
  <style:master-page style:name="Standard">
   <draw:frame draw:name="Fußzeile">
    <draw:text-box><text:p>{escape(_ODP_MASTER)}</text:p></draw:text-box>
   </draw:frame>
  </style:master-page>
 </office:master-styles>
</office:document-styles>
"""
    return odf_utils.package(
        odf_utils.ODP_MIME,
        content,
        odf_utils.meta_xml(_ODP_TITLE, AUTHOR, FIXED_ISO, FIXED_ISO),
        styles,
    )


def _render_eml() -> bytes:
    headers = [
        "MIME-Version: 1.0",
        f"Date: {_EML_DATE}",
        f"Message-ID: {_EML_MESSAGE_ID}",
        f"From: {_EML_FROM}",
        f"To: {_EML_TO}",
        f"Cc: {_EML_CC}",
        f"Subject: {_EML_SUBJECT}",
        'Content-Type: text/plain; charset="utf-8"',
        "Content-Transfer-Encoding: 8bit",
    ]
    return ("\n".join(headers) + "\n\n" + _EML_BODY).encode("utf-8")


def _ods_text(sheets: list[tuple[str, list[list[str]]]]) -> str:
    return "\n".join(
        "\n".join([name] + [" ".join(row) for row in rows]) for name, rows in sheets
    )


def build_documents() -> list[RenderedDocument]:
    """Every generated document of this library, in the order their file names sort."""
    return [
        RenderedDocument("01_formatuebersicht-buergerbuero.md", _MARKDOWN.encode("utf-8"), _MARKDOWN),
        RenderedDocument("02_ablagehinweise-formatmuster.txt", _TEXT.encode("utf-8"), _TEXT),
        RenderedDocument(
            "03_dienstanweisung-elektronische-dokumente.pdf",
            _render_pdf(),
            _plain_sections(_PDF_TITLE, _PDF_SUBTITLE, _PDF_SECTIONS),
        ),
        RenderedDocument(
            "04_aktenplanauszug-dokumentenformate.docx",
            _render_docx(),
            _plain_sections(_DOCX_TITLE, _DOCX_SUBTITLE, _DOCX_SECTIONS)
            + "\n"
            + "\n".join(" ".join(row) for row in _DOCX_TABLE),
        ),
        RenderedDocument(
            "05_schulung-dateiformate.pptx",
            _render_pptx(),
            "\n".join(
                "\n".join([folie.titel, *folie.punkte]) for folie in _PPTX_FOLIEN
            )
            + "\n"
            + SYNTHETIC_NOTICE,
        ),
        RenderedDocument(
            "06_intranet-formathinweise.html",
            _render_html(),
            _plain_sections(_HTML_TITLE, _HTML_INTRO, _HTML_SECTIONS)
            + "\n"
            + "\n".join(" ".join(row) for row in _HTML_TABLE),
        ),
        RenderedDocument(
            "07_gebuehrenuebersicht-formatpruefung.xlsx",
            _render_xlsx(),
            _XLSX_SHEET + "\n" + "\n".join(" ".join(row) for row in _XLSX_ROWS),
        ),
        RenderedDocument(
            "08_posteingang-formatstatistik.csv",
            _render_csv(),
            "\n".join(" ".join(row) for row in _CSV_ROWS),
        ),
        RenderedDocument(
            "09_kosten-langzeitspeicher.ods",
            _render_ods(),
            _ODS_TITLE + "\n" + _ods_text(_ODS_SHEETS) + "\n" + _ODS_CLOSING,
        ),
        RenderedDocument(
            "10_vermerk-langzeitarchivierung.odt",
            _render_odt(),
            "\n".join(
                [_ODT_TITLE, _ODT_HEADER, _ODT_FOOTER]
                + [heading for _, heading, _ in _ODT_SECTIONS]
                + [text for _, _, texts in _ODT_SECTIONS for text in texts]
                + [" ".join(row) for row in _ODT_TABLE]
            ),
        ),
        RenderedDocument(
            "11_folien-archivformate.odp",
            _render_odp(),
            "\n".join(
                [_ODP_TITLE, _ODP_MASTER]
                + [
                    "\n".join([folie.titel, *folie.punkte, folie.notizen])
                    for folie in _ODP_FOLIEN
                ]
            ),
        ),
        RenderedDocument(
            "12_anfrage-formatumstellung.eml",
            _render_eml(),
            "\n".join([_EML_SUBJECT, _EML_FROM, _EML_TO, _EML_CC, _EML_BODY]),
        ),
    ]
