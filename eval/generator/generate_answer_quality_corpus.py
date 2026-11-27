#!/usr/bin/env python3
"""Generator for the answer-quality domain (eval/corpus/answer-quality/SOURCE.md).

Writes the synthetic corpus (Markdown, PDF, DOCX, XLSX) of the fictional Gemeinde Birkenstein, the
manifest and the golden cases eval/golden/answer-quality.json. Fully deterministic: PDF, DOCX and
XLSX are written by hand with fixed timestamps, so two runs give byte-identical files.

    python eval/generator/generate_answer_quality_corpus.py

Python standard library only. The state of the format layer is declared here (FORMAT_GAPS); the
state of the context layer is measured by the Docker harness and kept from the existing golden file
(eval/corpus/answer-quality/SOURCE.md).
"""

from __future__ import annotations

import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = ROOT / "eval" / "corpus" / "answer-quality"
GOLDEN_FILE = ROOT / "eval" / "golden" / "answer-quality.json"

# --- Shared building blocks -------------------------------------------------------------------

# Neutral standard sentences that pad long documents; none of them states a fact a case asks for.
FILLER = [
    "Die Gemeinde kann Ausnahmen zulassen, wenn der Zweck dieser Regelung dadurch nicht gefährdet wird.",
    "Auskünfte zu dieser Regelung erteilt die Hauptverwaltung während der Öffnungszeiten.",
    "Bei Zweifeln über die Auslegung entscheidet die Verwaltungsleitung im Einzelfall.",
    "Die Regelung gilt für alle Beteiligten gleichermaßen und ohne Ansehen der Person.",
    "Änderungen werden im Amtsblatt der Gemeinde bekannt gemacht und treten am Tag nach der Bekanntmachung in Kraft.",
    "Die Beteiligten sind verpflichtet, die für die Durchführung erforderlichen Angaben wahrheitsgemäß zu machen.",
    "Personenbezogene Daten werden nur im erforderlichen Umfang und nach den geltenden Datenschutzvorschriften verarbeitet.",
    "Die Gemeinde haftet nur für Schäden, die sie vorsätzlich oder grob fahrlässig verursacht hat.",
]


def padded(text: str, seed: int, count: int = 2) -> str:
    extra = [FILLER[(seed + i * 3) % len(FILLER)] for i in range(count)]
    return text + " " + " ".join(extra)


def md_document(title: str, preamble: str, sections: list[tuple[str, str]]) -> str:
    parts = [f"# {title}", "", preamble, ""]
    for heading, text in sections:
        parts += [f"## {heading}", "", text, ""]
    return "\n".join(parts).rstrip() + "\n"


def numbered(
    prefix: str, entries: list[tuple[str, str]], start: int = 1, filler: int = 6
) -> list[tuple[str, str]]:
    return [
        (f"{prefix} {start + i} {heading}", padded(text, start + i, filler))
        for i, (heading, text) in enumerate(entries)
    ]


# --- Markdown documents -----------------------------------------------------------------------


def marktsatzung() -> str:
    entries = [
        ("Geltungsbereich", "Diese Satzung gilt für den Wochenmarkt auf dem Rathausplatz der Gemeinde Birkenstein."),
        ("Marktträger", "Träger des Wochenmarktes ist die Gemeinde. Sie kann die Durchführung auf Dritte übertragen."),
        ("Markttage und Öffnungszeiten", "Der Wochenmarkt findet jeden Mittwoch und jeden Samstag von 7 bis 13 Uhr statt. Fällt ein Markttag auf einen gesetzlichen Feiertag, entfällt er."),
        ("Warenangebot", "Zugelassen sind frische Lebensmittel, Blumen, Pflanzen und regionale Handwerkserzeugnisse."),
        ("Auf- und Abbau", "Die Stände dürfen ab 5:30 Uhr aufgebaut werden. Der Abbau muss bis 14:30 Uhr abgeschlossen sein."),
        ("Standplätze", "Die Standplätze werden von der Marktaufsicht zugewiesen. Ein Anspruch auf einen bestimmten Platz besteht nicht."),
        ("Zulassung", "Die Zulassung wird auf Antrag für ein Kalenderjahr oder für einzelne Markttage erteilt."),
        ("Versagung der Zulassung", "Die Zulassung kann versagt werden, wenn der Platz nicht ausreicht oder der Antragsteller unzuverlässig ist."),
        ("Verkaufseinrichtungen", "Verkaufsstände müssen standsicher sein und dürfen die Nachbarstände nicht beeinträchtigen."),
        ("Sauberkeit", "Der Standinhaber hat seinen Platz während des Marktes und nach dem Abbau sauber zu halten."),
        ("Preisangaben", "Waren sind nach den Vorschriften der Preisangabenverordnung auszuzeichnen."),
        ("Lebensmittelhygiene", "Es gelten die lebensmittelrechtlichen Hygienevorschriften. Kontrollen führt die Lebensmittelüberwachung durch."),
        ("Leergut und Verpackung", "Verpackungsmaterial ist vom Standinhaber mitzunehmen oder in den bereitgestellten Behältern zu entsorgen."),
        ("Fahrzeuge", "Fahrzeuge dürfen nur zum Be- und Entladen auf den Platz fahren und müssen danach den Platz verlassen."),
        ("Marktaufsicht", "Die Marktaufsicht überwacht die Einhaltung dieser Satzung und ist weisungsbefugt."),
        ("Ausschluss vom Markt", "Wer wiederholt gegen die Satzung verstößt, kann zeitweise oder dauerhaft vom Markt ausgeschlossen werden."),
        ("Gebührenpflicht", "Für die Nutzung der Standplätze werden Gebühren nach den folgenden Absätzen erhoben. Gebührenschuldner ist der Standinhaber."),
        ("Gebührenhöhe", "Die Standgebühr beträgt 4,50 Euro je laufendem Meter Standfläche und Markttag. Für einen Dauerstandplatz wird anstelle der Tagesgebühr eine Monatspauschale von 36 Euro je Standplatz erhoben. Der Stromanschluss kostet 2,00 Euro je Markttag, die Müllpauschale 5 Euro je Markttag und Stand."),
        ("Fälligkeit", "Die Tagesgebühr wird am Markttag bei der Marktaufsicht bar oder per Karte entrichtet, die Monatspauschale bis zum fünften Werktag des Monats."),
        ("Ordnungswidrigkeiten", "Ordnungswidrig handelt, wer vorsätzlich oder fahrlässig gegen die Vorschriften dieser Satzung verstößt."),
        ("Inkrafttreten", "Diese Satzung tritt am 1. Januar 2026 in Kraft."),
    ]
    sections = numbered("§", entries)
    return md_document(
        "Marktsatzung der Gemeinde Birkenstein",
        "Satzung über die Durchführung des Wochenmarktes und die Erhebung von Gebühren (Stand 1. Januar 2026).",
        sections,
    )


def geschaeftsordnung() -> str:
    entries = [
        ("Zusammensetzung", "Der Gemeinderat besteht aus der oder dem Vorsitzenden und 18 gewählten Mitgliedern."),
        ("Aufgaben des Gemeinderats", "Der Gemeinderat entscheidet über alle wichtigen Angelegenheiten der Gemeinde."),
        ("Einberufung", "Die oder der Vorsitzende beruft den Gemeinderat schriftlich oder elektronisch ein."),
        ("Ladungsfrist", "Die Ladung mit Tagesordnung ergeht mindestens 7 Tage vor der Sitzung. In dringenden Fällen kann die Frist verkürzt werden."),
        ("Tagesordnung", "Die Tagesordnung wird von der oder dem Vorsitzenden aufgestellt. Anträge aus der Mitte des Gemeinderats werden aufgenommen, wenn sie rechtzeitig vorliegen."),
        ("Öffentlichkeit", "Die Sitzungen sind öffentlich, soweit nicht das Wohl der Allgemeinheit oder berechtigte Interessen Einzelner entgegenstehen."),
        ("Beschlussfähigkeit", "Der Gemeinderat ist beschlussfähig, wenn mehr als die Hälfte der Mitglieder anwesend ist."),
        ("Befangenheit", "Wer befangen ist, darf an Beratung und Abstimmung nicht teilnehmen."),
        ("Leitung der Sitzung", "Die oder der Vorsitzende leitet die Sitzung, handhabt die Ordnung und übt das Hausrecht aus."),
        ("Rederecht", "Das Wort wird in der Reihenfolge der Meldungen erteilt. Redezeiten können durch Beschluss begrenzt werden."),
        ("Anträge zur Geschäftsordnung", "Anträge zur Geschäftsordnung gehen allen anderen Wortmeldungen vor."),
        ("Abstimmung", "Abgestimmt wird in der Regel durch Handzeichen. Auf Antrag eines Viertels der Mitglieder wird namentlich abgestimmt."),
        ("Wahlen", "Wahlen werden mit Stimmzetteln durchgeführt, wenn ein Mitglied dies verlangt."),
        ("Ausschüsse", "Der Gemeinderat bildet beratende und beschließende Ausschüsse."),
        ("Ausschusssitzungen", "Für Ausschusssitzungen gelten die Bestimmungen dieser Geschäftsordnung entsprechend."),
        ("Anfragen", "Mitglieder können schriftliche Anfragen an die Verwaltung richten, die in der nächsten Sitzung beantwortet werden."),
        ("Haushaltsberatung", "Der Haushaltsentwurf wird in zwei Lesungen beraten."),
        ("Dringlichkeitsentscheidungen", "In unaufschiebbaren Angelegenheiten entscheidet die oder der Vorsitzende; der Gemeinderat wird unverzüglich unterrichtet."),
        ("Fraktionen", "Mitglieder können sich zu Fraktionen zusammenschließen; die Bildung ist der oder dem Vorsitzenden anzuzeigen."),
        ("Aufwandsentschädigung", "Die Mitglieder erhalten eine Aufwandsentschädigung nach der Entschädigungssatzung."),
        ("Verschwiegenheit", "Über nichtöffentliche Sitzungsinhalte ist Stillschweigen zu bewahren."),
        ("Bürgerbeteiligung", "Der Gemeinderat kann Bürgerversammlungen einberufen und Einwohnerinnen und Einwohner anhören."),
        ("Einwohnerfragestunde", "Vor Beginn jeder öffentlichen Sitzung findet eine Fragestunde von höchstens 30 Minuten statt."),
        ("Sitzungsunterlagen", "Die Unterlagen werden den Mitgliedern elektronisch zur Verfügung gestellt."),
        ("Ton- und Bildaufnahmen", "Aufnahmen sind nur mit Zustimmung des Gemeinderats zulässig."),
        ("Verfahren bei Störungen", "Wer die Ordnung stört, kann von der oder dem Vorsitzenden zur Ordnung gerufen werden."),
        ("Niederschrift", "Über jede Sitzung wird eine Niederschrift gefertigt. Einwendungen gegen die Niederschrift können innerhalb von 14 Tagen nach ihrem Zugang erhoben werden. Über Einwendungen entscheidet der Gemeinderat in der nächsten Sitzung."),
        ("Änderung der Geschäftsordnung", "Änderungen dieser Geschäftsordnung bedürfen der Mehrheit aller Mitglieder."),
        ("Inkrafttreten", "Diese Geschäftsordnung tritt am Tag nach der Beschlussfassung in Kraft."),
    ]
    return md_document(
        "Geschäftsordnung des Gemeinderats Birkenstein",
        "Geschäftsordnung für den Gemeinderat der Gemeinde Birkenstein in der Fassung vom 1. März 2025.",
        numbered("§", entries),
    )


def dienstanweisung_vergabe() -> str:
    entries = [
        ("Zweck", "Diese Dienstanweisung regelt die Vergabe von Liefer- und Dienstleistungen unterhalb der EU-Schwellenwerte."),
        ("Grundsätze", "Aufträge werden wirtschaftlich, transparent und diskriminierungsfrei vergeben."),
        ("Zuständigkeit", "Die Fachbereiche führen die Vergabe selbst durch; die zentrale Vergabestelle berät."),
        ("Anlage AB-01 Bedarfsmeldung", "Jeder Bedarf wird über die Bedarfsmeldung angezeigt."),
        ("Anlage AB-02 Kostenschätzung", "Vor der Vergabe wird der Auftragswert netto geschätzt und dokumentiert."),
        ("Anlage AB-05 Dokumentation", "Jede Vergabe wird im Vergabevermerk festgehalten."),
        ("Anlage AB-07 Verpflichtungserklärung", "Beteiligte Beschäftigte geben eine Erklärung zu Interessenkonflikten ab."),
        ("Anlage AB-08 Direktvergabe", "Die Ablaufbeschreibung AB-08 regelt die Direktvergabe: Aufträge bis zu einem Auftragswert von 1.000 Euro netto dürfen ohne Vergleichsangebote vergeben werden. Die Freigabe erteilt die Fachbereichsleitung. Die Vergabe wird mit dem Formular FB-12 dokumentiert."),
        ("Anlage AB-09 Verhandlungsvergabe", "Die Ablaufbeschreibung AB-09 regelt die Verhandlungsvergabe bis 15.000 Euro netto. Es sind mindestens drei Vergleichsangebote einzuholen."),
        ("Anlage AB-10 Öffentliche Ausschreibung", "Ab einem Auftragswert von 15.000 Euro netto ist öffentlich auszuschreiben."),
        ("Anlage AB-11 Zuschlag", "Der Zuschlag wird schriftlich erteilt und den unterlegenen Bietern mitgeteilt."),
        ("Anlage AB-12 Archivierung", "Vergabeunterlagen werden fünf Jahre aufbewahrt."),
    ]
    sections = [(h, padded(t, i + 1, 1)) for i, (h, t) in enumerate(entries)]
    return md_document(
        "Dienstanweisung zur Vergabe von Aufträgen",
        "Dienstanweisung der Gemeinde Birkenstein mit den Ablaufbeschreibungen AB-01 bis AB-12 (Stand 1. Juli 2025).",
        sections,
    )


def formularverzeichnis() -> str:
    entries = [
        ("Formular FB10 Urlaubsantrag", "Das Formular FB10 wird für Anträge auf Erholungsurlaub verwendet. Es ist der Personalverwaltung vorzulegen."),
        ("Formular FB11 Dienstreiseantrag", "Das Formular FB11 dient der Genehmigung einer Dienstreise vor deren Antritt."),
        ("Formular FB12 Bestellformular Direktvergabe", "Das Formular FB12 dokumentiert eine Direktvergabe. Es ist mit der Rechnung an die Kämmerei zu geben."),
        ("Formular FB13 Raumbuchung", "Das Formular FB13 wird für die Buchung von Besprechungsräumen im Rathaus genutzt."),
        ("Formular FB14 Fahrzeugnutzung", "Das Formular FB14 regelt die Nutzung der Dienstfahrzeuge."),
        ("Formular FB15 Schadensmeldung", "Mit dem Formular FB15 werden Schäden an Gebäuden und Geräten gemeldet."),
        ("Formular FB16 Fortbildung", "Das Formular FB16 beantragt die Teilnahme an einer Fortbildung."),
    ]
    sections = [(h, padded(t, i + 2, 1)) for i, (h, t) in enumerate(entries)]
    return md_document(
        "Formularverzeichnis der Gemeinde Birkenstein",
        "Verzeichnis der internen Formulare mit Kennung und Verwendungszweck.",
        sections,
    )


def urlaubsordnung() -> str:
    entries = [
        ("Geltungsbereich", "Diese Urlaubsordnung gilt für alle Beschäftigten der Gemeinde Birkenstein."),
        ("Erholungsurlaub", "Beschäftigte haben Anspruch auf 30 Arbeitstage Erholungsurlaub im Kalenderjahr bei einer Fünftagewoche."),
        ("Zusatzurlaub", "Schwerbehinderte Beschäftigte erhalten fünf zusätzliche Arbeitstage Urlaub."),
        ("Antragstellung", "Urlaub ist mindestens 14 Tage vor Beginn mit dem Formular FB10 zu beantragen."),
        ("Genehmigung", "Die Genehmigung erteilt die Fachbereichsleitung unter Berücksichtigung der dienstlichen Belange."),
        ("Sonderurlaub", "Für den eigenen Umzug aus dienstlichem Anlass wird ein Arbeitstag, für die eigene Eheschließung werden zwei Arbeitstage Sonderurlaub gewährt."),
        ("Übertragung", "Nicht genommener Urlaub kann bis zum 31. März des Folgejahres übertragen werden."),
        ("Vertretung", "Vor Urlaubsantritt ist die Vertretung zu regeln und im Kalender einzutragen."),
        ("Erkrankung im Urlaub", "Wird ein Beschäftigter im Urlaub krank, werden die ärztlich bescheinigten Tage nicht angerechnet."),
    ]
    sections = [(h, padded(t, i + 4, 1)) for i, (h, t) in enumerate(entries)]
    return md_document(
        "Urlaubsordnung der Gemeinde Birkenstein",
        "Regelungen zu Erholungsurlaub, Zusatzurlaub und Sonderurlaub (Stand 1. Januar 2025).",
        sections,
    )


def reisekostenordnung() -> str:
    entries = [
        ("Geltungsbereich", "Diese Ordnung regelt die Erstattung von Auslagen bei Dienstreisen."),
        ("Genehmigung", "Dienstreisen sind vor Antritt mit dem Formular FB11 zu genehmigen."),
        ("Wegstreckenentschädigung", "Wird für eine Dienstreise der private Pkw genutzt, beträgt die Wegstreckenentschädigung 0,30 Euro je gefahrenem Kilometer."),
        ("Bahnfahrten", "Fahrkarten der zweiten Klasse werden gegen Beleg erstattet."),
        ("Tagegeld", "Dauert die Dienstreise mehr als 8 Stunden, wird ein Tagegeld von 14 Euro gezahlt. Bei einer Abwesenheit von 24 Stunden beträgt das Tagegeld 28 Euro je Kalendertag."),
        ("Übernachtungskosten", "Übernachtungskosten werden gegen Beleg bis zu 80 Euro je Nacht erstattet."),
        ("Nebenkosten", "Notwendige Nebenkosten wie Parkgebühren werden gegen Beleg erstattet."),
        ("Abrechnung", "Die Reisekosten sind innerhalb von sechs Wochen nach Ende der Reise abzurechnen."),
    ]
    sections = [(h, padded(t, i + 5, 1)) for i, (h, t) in enumerate(entries)]
    return md_document(
        "Reisekostenordnung der Gemeinde Birkenstein",
        "Erstattung von Fahrt-, Übernachtungs- und Verpflegungskosten (Stand 1. Januar 2025).",
        sections,
    )


def raumnutzung() -> str:
    entries = [
        ("Geltungsbereich", "Diese Ordnung gilt für die Vermietung des Bürgerhauses an Vereine, Gewerbetreibende und Privatpersonen."),
        ("Buchung", "Räume werden über das Formular FB13 oder bei der Hausverwaltung gebucht. Die Entgelte stehen im Entgeltverzeichnis."),
        ("Nutzungszeiten", "Die Räume können täglich von 8 bis 22 Uhr genutzt werden. An Freitagen und Samstagen sowie vor Feiertagen verlängert sich die Nutzungszeit bis 24 Uhr."),
        ("Kaution", "Für jede Buchung wird eine Kaution von 150 Euro erhoben, die nach ordnungsgemäßer Übergabe zurückgezahlt wird."),
        ("Reinigung", "Die Endreinigung erfolgt durch die Gemeinde gegen eine Pauschale von 40 Euro je Buchung."),
        ("Stornierung", "Bis 14 Tage vor dem Termin kann die Buchung kostenfrei storniert werden. Danach werden 50 Prozent des vereinbarten Entgelts fällig; die Kaution wird in voller Höhe zurückgezahlt."),
        ("Bewirtung", "Speisen und Getränke dürfen mitgebracht werden. Die Küche kann gesondert gebucht werden."),
        ("Lärmschutz", "Ab 22 Uhr ist auf die Nachbarschaft Rücksicht zu nehmen; Türen und Fenster bleiben geschlossen."),
        ("Brandschutz", "Fluchtwege sind freizuhalten. Offenes Feuer ist untersagt."),
        ("Haftung", "Der Nutzer haftet für Schäden, die während der Nutzung entstehen."),
    ]
    sections = [(h, padded(t, i + 6, 1)) for i, (h, t) in enumerate(entries)]
    return md_document(
        "Nutzungsordnung für das Bürgerhaus Birkenstein",
        "Ordnung über die Nutzung des Bürgerhauses und seiner Räume (Stand 1. Januar 2026).",
        sections,
    )


def kita_beitraege() -> str:
    entries = [
        ("Geltungsbereich", "Diese Satzung regelt die Elternbeiträge für die kommunalen Kindertageseinrichtungen."),
        ("Beitragspflicht", "Beitragspflichtig sind die Personensorgeberechtigten. Der Beitrag wird monatlich erhoben."),
        ("Beitragshöhe für Kinder unter drei Jahren", "Der monatliche Beitrag je Kind beträgt bei einer Betreuungszeit von 25 Wochenstunden 210 Euro, bei 35 Wochenstunden 285 Euro und bei 45 Wochenstunden 360 Euro."),
        ("Beitragshöhe für Kinder ab drei Jahren", "Der monatliche Beitrag je Kind beträgt bei einer Betreuungszeit von 25 Wochenstunden 120 Euro, bei 35 Wochenstunden 160 Euro und bei 45 Wochenstunden 205 Euro."),
        ("Geschwisterermäßigung", "Für das zweite und jedes weitere Kind einer Familie, das gleichzeitig eine Einrichtung der Gemeinde besucht, ermäßigt sich der Beitrag um 50 Prozent."),
        ("Verpflegung", "Das Mittagessen kostet 3,20 Euro je Essen und wird gesondert zum Monatsende abgerechnet."),
        ("Schließzeiten", "In den Schließzeiten wird der Beitrag weiter erhoben."),
        ("Beitragsfreies Jahr", "Im letzten Jahr vor der Einschulung ist die Betreuung beitragsfrei; die Verpflegung bleibt kostenpflichtig."),
        ("Zahlung", "Der Beitrag ist bis zum dritten Werktag des Monats fällig."),
    ]
    sections = [(h, padded(t, i + 7, 1)) for i, (h, t) in enumerate(entries)]
    return md_document(
        "Beitragssatzung für die Kindertageseinrichtungen",
        "Satzung der Gemeinde Birkenstein über die Erhebung von Elternbeiträgen (Stand 1. August 2025).",
        sections,
    )


# --- PDF --------------------------------------------------------------------------------------


def wrap(text: str, width: int) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in text.split(" "):
        candidate = word if not current else current + " " + word
        if len(candidate) > width and current:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


def pdf_string(text: str) -> bytes:
    raw = text.encode("cp1252")
    return b"(" + raw.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)") + b")"


def pdf_document(title: str, paragraphs: list[str]) -> bytes:
    content = [b"BT", b"/F1 16 Tf", b"72 770 Td", b"18 TL", pdf_string(title) + b" Tj", b"T*",
               b"/F1 11 Tf", b"14 TL"]
    for text in paragraphs:
        content.append(b"T*")
        for line in wrap(text, 88):
            content.append(pdf_string(line) + b" Tj")
            content.append(b"T*")
    content.append(b"ET")
    stream = b"\n".join(content) + b"\n"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"endstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    ]
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
    xref_offset = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode()
    out += b"0000000000 65535 f \n"
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n"
    ).encode()
    return bytes(out)


def bibliothek_pdf() -> bytes:
    return pdf_document(
        "Benutzungsordnung der Gemeindebibliothek Birkenstein",
        [
            "1. Anmeldung. Die Bibliothek steht allen Einwohnerinnen und Einwohnern offen. Für die Anmeldung ist ein Lichtbildausweis vorzulegen.",
            "2. Jahresgebühr. Der Jahresausweis kostet für Erwachsene 18 Euro, ermäßigt 9 Euro. Kinder und Jugendliche bis 14 Jahre sind gebührenfrei. Ein Ersatzausweis kostet 3 Euro.",
            "3. Leihfristen. Bücher werden für 28 Tage entliehen. DVDs und andere Medien werden für 7 Tage entliehen. Die Leihfrist kann zweimal um je 14 Tage verlängert werden, wenn das Medium nicht vorgemerkt ist.",
            "4. Säumnisgebühr. Wird die Leihfrist überschritten, beträgt die Säumnisgebühr 0,50 Euro je Medium und angefangener Woche. Die Gebühr ist auf 10 Euro je Medium begrenzt.",
            "5. Verlust und Beschädigung. Für verlorene oder beschädigte Medien ist Ersatz zu leisten; die Höhe richtet sich nach dem Wiederbeschaffungswert.",
            "6. Öffnungszeiten. Die Bibliothek ist dienstags bis freitags von 10 bis 18 Uhr und samstags von 10 bis 13 Uhr geöffnet.",
        ],
    )


# --- DOCX -------------------------------------------------------------------------------------

DOCX_CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" '
    'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    "</Types>"
)

DOCX_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" '
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
    'Target="word/document.xml"/>'
    "</Relationships>"
)


def xml_escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def docx_paragraph(text: str, bold: bool = False) -> str:
    run_properties = "<w:rPr><w:b/></w:rPr>" if bold else ""
    return f'<w:p><w:r>{run_properties}<w:t xml:space="preserve">{xml_escape(text)}</w:t></w:r></w:p>'


def docx_table(rows: list[list[str]]) -> str:
    body = ""
    for row in rows:
        cells = "".join(f"<w:tc><w:tcPr><w:tcW w:w=\"2200\" w:type=\"dxa\"/></w:tcPr>{docx_paragraph(c)}</w:tc>" for c in row)
        body += f"<w:tr>{cells}</w:tr>"
    return f"<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>{body}</w:tbl>"


def zip_bytes(parts: list[tuple[str, str]]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, text in parts:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, text.encode("utf-8"))
    return buffer.getvalue()


def friedhof_docx() -> bytes:
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        "<w:body>"
        + docx_paragraph("Friedhofsgebührensatzung der Gemeinde Birkenstein", bold=True)
        + docx_paragraph("§ 1 Gebührenpflicht. Für die Benutzung der gemeindlichen Friedhöfe werden Gebühren nach dieser Satzung erhoben.")
        + docx_paragraph("§ 2 Grabnutzungsgebühren. Die Gebühr für das Nutzungsrecht an einer Grabstätte für die gesamte Nutzungsdauer ergibt sich aus der folgenden Tabelle.")
        + docx_table(
            [
                ["Grabart", "Nutzungsdauer in Jahren", "Gebühr in Euro je Grabstätte"],
                ["Reihengrab", "25", "640"],
                ["Wahlgrab", "30", "980"],
                ["Urnenwahlgrab", "20", "780"],
                ["Urnenreihengrab", "20", "410"],
            ]
        )
        + docx_paragraph("§ 3 Verlängerung. Wird das Nutzungsrecht an einem Wahlgrab verlängert, beträgt die Gebühr 15 Euro je Jahr der Verlängerung. Eine Verlängerung ist höchstens um 10 Jahre möglich.")
        + docx_paragraph("§ 4 Trauerhalle. Die Benutzung der Trauerhalle kostet 120 Euro je Trauerfeier.")
        + docx_paragraph("§ 5 Inkrafttreten. Diese Satzung tritt am 1. Januar 2026 in Kraft.")
        + "</w:body></w:document>"
    )
    return zip_bytes(
        [
            ("[Content_Types].xml", DOCX_CONTENT_TYPES),
            ("_rels/.rels", DOCX_RELS),
            ("word/document.xml", document),
        ]
    )


# --- XLSX -------------------------------------------------------------------------------------

XLSX_CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/xl/workbook.xml" '
    'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    '<Override PartName="/xl/worksheets/sheet1.xml" '
    'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    "</Types>"
)

XLSX_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" '
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
    'Target="xl/workbook.xml"/>'
    "</Relationships>"
)

XLSX_WORKBOOK_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" '
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
    'Target="worksheets/sheet1.xml"/>'
    "</Relationships>"
)


def column_letter(index: int) -> str:
    return "ABCDEFGHIJKLMNOPQRSTUVWXYZ"[index]


def xlsx_workbook(sheet_name: str, rows: list[list[object]]) -> bytes:
    """One sheet; None leaves a cell out, numbers become numeric cells, text inline strings."""
    sheet_rows = []
    for row_number, row in enumerate(rows, start=1):
        cells = []
        for column, value in enumerate(row):
            if value is None:
                continue
            ref = f"{column_letter(column)}{row_number}"
            if isinstance(value, (int, float)):
                cells.append(f'<c r="{ref}"><v>{value}</v></c>')
            else:
                cells.append(f'<c r="{ref}" t="inlineStr"><is><t>{xml_escape(str(value))}</t></is></c>')
        sheet_rows.append(f'<row r="{row_number}">{"".join(cells)}</row>')
    sheet = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f'<sheetData>{"".join(sheet_rows)}</sheetData></worksheet>'
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f'<sheets><sheet name="{xml_escape(sheet_name)}" sheetId="1" r:id="rId1"/></sheets></workbook>'
    )
    return zip_bytes(
        [
            ("[Content_Types].xml", XLSX_CONTENT_TYPES),
            ("_rels/.rels", XLSX_RELS),
            ("xl/workbook.xml", workbook),
            ("xl/_rels/workbook.xml.rels", XLSX_WORKBOOK_RELS),
            ("xl/worksheets/sheet1.xml", sheet),
        ]
    )


def sondernutzung_xlsx() -> bytes:
    """A title row and a blank row above the header row."""
    return xlsx_workbook(
        "Entgelte 2026",
        [
            ["Entgelte für Sondernutzungen öffentlicher Wege 2026 (Stand 1. Januar 2026)"],
            [],
            ["Nutzungsart", "Entgelt in Euro", "Bezugseinheit", "Mindestentgelt in Euro"],
            ["Warenauslage", "3,00", "je qm und Monat", 15],
            ["Außengastronomie", "6,50", "je qm und Monat", 40],
            ["Werbeaufsteller", "12,00", "je Stück und Monat", 12],
            ["Bauzaun", "0,40", "je lfd. m und Tag", 20],
            ["Gerüst", "0,25", "je qm und Woche", 25],
            ["Verkaufswagen", "18,00", "je Tag", 18],
        ],
    )


def raumentgelte_xlsx() -> bytes:
    """A small table whose answer needs the row and the column."""
    return xlsx_workbook(
        "Raumentgelte",
        [
            ["Raum", "Vereine (Euro je Stunde)", "Gewerbe (Euro je Stunde)", "Auswärtige (Euro je Stunde)", "Kaution (Euro je Buchung)"],
            ["Saal", 25, 85, 60, 200],
            ["Sitzungszimmer", 10, 30, 20, 50],
            ["Foyer", 15, 45, 35, 100],
            ["Küche", 8, 20, 15, 80],
        ],
    )


ZUSTAENDIG_AMTS = ["Hauptamt", "Ordnungsamt", "Bauamt", "Sozialamt", "Kämmerei", "Bürgerbüro"]
ZUSTAENDIG_TOPICS = [
    "Gewerbeanzeige", "Fundsache", "Hundesteuer", "Sondernutzung", "Baulast", "Wohngeld",
    "Meldebescheinigung", "Vereinsregisterauskunft", "Marktzulassung", "Beitragsbescheid",
]
ZUSTAENDIG_ROLES = ["Sachbearbeitung", "Teamleitung", "Fachbereichsleitung"]


def zustaendigkeiten_xlsx() -> bytes:
    """Over 100 data rows: more than one row group, so later chunks must repeat the header."""
    rows: list[list[object]] = [["Kennung", "Aufgabe", "Amt", "Zuständige Stelle", "Bearbeitungsfrist in Tagen"]]
    for n in range(1, 131):
        topic = ZUSTAENDIG_TOPICS[n % len(ZUSTAENDIG_TOPICS)]
        amt = ZUSTAENDIG_AMTS[n % len(ZUSTAENDIG_AMTS)]
        role = ZUSTAENDIG_ROLES[n % len(ZUSTAENDIG_ROLES)]
        days = 5 + (n * 3) % 20
        rows.append([f"ZV-{n:03d}", f"{topic} Vorgang {n}", amt, role, days])
    # The row cases ask for; fixed values so that the answer stays stable.
    rows[117] = ["ZV-117", "Sondernutzungserlaubnis Straßenfest", "Ordnungsamt", "Teamleitung", 21]
    rows[98] = ["ZV-098", "Fundsachenversteigerung", "Bürgerbüro", "Sachbearbeitung", 9]
    return xlsx_workbook("Zuständigkeiten", rows)


# --- Documents and cases ----------------------------------------------------------------------

DOCUMENTS: list[tuple[str, object]] = [
    ("aq-01_marktsatzung.md", marktsatzung),
    ("aq-02_dienstanweisung-vergabe.md", dienstanweisung_vergabe),
    ("aq-03_formularverzeichnis.md", formularverzeichnis),
    ("aq-04_urlaubsordnung.md", urlaubsordnung),
    ("aq-05_reisekostenordnung.md", reisekostenordnung),
    ("aq-06_nutzungsordnung-buergerhaus.md", raumnutzung),
    ("aq-07_kita-beitragssatzung.md", kita_beitraege),
    ("aq-08_geschaeftsordnung-gemeinderat.md", geschaeftsordnung),
    ("aq-09_benutzungsordnung-bibliothek.pdf", bibliothek_pdf),
    ("aq-10_friedhofsgebuehren.docx", friedhof_docx),
    ("aq-11_sondernutzungsentgelte.xlsx", sondernutzung_xlsx),
    ("aq-12_raumentgelte.xlsx", raumentgelte_xlsx),
    ("aq-13_zustaendigkeiten.xlsx", zustaendigkeiten_xlsx),
]

REFUSAL_NOT_IN_SOURCES = "nicht in den Quellen"

# Case classes (golden field "class").
FALSE_NEGATIVE = "false_negative"
NEEDLESS_CAVEAT = "needless_caveat"
WRONG_REFERENCE = "wrong_reference"

D = {name.split("_")[0]: name for name, _ in DOCUMENTS}
MARKT, VERGABE, FORM, URLAUB, REISE, RAUM, KITA, GO, BIB, FRIEDHOF, SONDER, RAUMENTGELT, ZUSTAENDIG = (
    D[f"aq-{n:02d}"] for n in range(1, 14)
)


def case(
    case_id: str,
    klass: str,
    question: str,
    documents: list[str],
    evidence: list[str],
    must_match: list[str],
    forbidden: list[str] | None = None,
    variants: list[str] | None = None,
    tags: list[str] | None = None,
) -> dict:
    entry = {
        "id": case_id,
        "class": klass,
        "question": question,
        "documents": documents,
        "evidence": evidence,
        "answer_must_match": must_match,
        "answer_must_not_match": forbidden or [],
        "tags": tags or [],
    }
    if variants:
        entry["question_variants"] = variants
    return entry


EURO = r"(Euro|EUR|€)"

CASES: list[dict] = [
    # --- Falsche Fehlanzeige: the answer is in the corpus, the model must not say it is not -----
    case("aq-fn-01", FALSE_NEGATIVE, "Wie hoch ist die Müllpauschale auf dem Wochenmarkt?", [MARKT],
         ["Müllpauschale", "5 Euro je Markttag und Stand"], [r"\b5\b.{0,12}" + EURO], tags=["long_document"]),
    case("aq-fn-02", FALSE_NEGATIVE, "Innerhalb welcher Frist kann ich Einwendungen gegen die Niederschrift einer Gemeinderatssitzung erheben?", [GO],
         ["Einwendungen gegen die Niederschrift", "14 Tagen"], [r"\b14\b.{0,10}Tage"], tags=["long_document"]),
    case("aq-fn-03", FALSE_NEGATIVE, "Bis zu welchem Auftragswert ist nach AB08 eine Direktvergabe ohne Vergleichsangebote zulässig?", [VERGABE],
         ["AB-08", "1.000 Euro netto"], [r"1\.?000\s*" + EURO], variants=["Bis zu welchem Auftragswert ist nach AB-08 eine Direktvergabe ohne Vergleichsangebote zulässig?", "Bis zu welchem Auftragswert ist nach AB 08 eine Direktvergabe ohne Vergleichsangebote zulässig?", "Bis zu welchem Auftragswert ist nach ab-08 eine Direktvergabe ohne Vergleichsangebote zulässig?"], tags=["identifier_variants"]),
    case("aq-fn-04", FALSE_NEGATIVE, "Wofür wird das Formular FB-12 verwendet?", [FORM],
         ["FB12", "Direktvergabe"], [r"Direktvergabe"], variants=["Wofür wird das Formular FB12 verwendet?", "Wofür wird das Formular FB 12 verwendet?", "Wofür wird das Formular fb12 verwendet?"], tags=["identifier_variants"]),
    case("aq-fn-05", FALSE_NEGATIVE, "Welches Amt ist für den Vorgang ZV117 zuständig?", [ZUSTAENDIG],
         ["ZV-117", "Ordnungsamt"], [r"Ordnungsamt"], variants=["Welches Amt ist für den Vorgang ZV-117 zuständig?", "Welches Amt ist für den Vorgang ZV 117 zuständig?", "Welches Amt ist für den Vorgang zv-117 zuständig?"], tags=["identifier_variants", "table_late_row"]),
    case("aq-fn-06", FALSE_NEGATIVE, "Wie viele Tage beträgt die Bearbeitungsfrist für ZV-098?", [ZUSTAENDIG],
         ["ZV-098", "Fundsachenversteigerung", "9"], [r"\b9\b.{0,10}Tage"], tags=["table_late_row"]),
    case("aq-fn-07", FALSE_NEGATIVE, "Was kostet eine Warenauslage auf dem Gehweg?", [SONDER],
         ["Warenauslage", "3,00", "je qm und Monat"], [r"3(,00)?\s*" + EURO], tags=["table_title_row"]),
    case("aq-fn-08", FALSE_NEGATIVE, "Wie viel kostet der Saal im Bürgerhaus für Vereine pro Stunde?", [RAUMENTGELT],
         ["Saal", "Vereine", "25"], [r"\b25\b.{0,12}" + EURO], tags=["table_row_column"]),
    case("aq-fn-09", FALSE_NEGATIVE, "Wie lange darf ich eine DVD aus der Bibliothek ausleihen?", [BIB],
         ["DVDs", "7 Tage"], [r"\b7\b.{0,6}Tage"], tags=["pdf"]),
    case("aq-fn-10", FALSE_NEGATIVE, "Was kostet ein Urnenwahlgrab?", [FRIEDHOF],
         ["Urnenwahlgrab", "20", "780"], [r"780"], tags=["docx_table"]),
    # --- Unnötiger Hinweis: fully answerable, no gap or premise note belongs in the answer ------
    case("aq-nc-01", NEEDLESS_CAVEAT, "Wie viele Urlaubstage stehen Beschäftigten pro Jahr zu?", [URLAUB],
         ["30 Arbeitstage"], [r"\b30\b"]),
    case("aq-nc-02", NEEDLESS_CAVEAT, "Wie früh muss ich meinen Urlaub beantragen?", [URLAUB],
         ["mindestens 14 Tage"], [r"\b14\b.{0,10}Tage"]),
    case("aq-nc-03", NEEDLESS_CAVEAT, "Wie hoch ist die Wegstreckenentschädigung, wenn ich für eine Dienstreise mein privates Auto nutze?", [REISE],
         ["Wegstreckenentschädigung", "0,30 Euro je gefahrenem Kilometer"], [r"0,30"]),
    case("aq-nc-04", NEEDLESS_CAVEAT, "Wie viel Tagegeld bekomme ich für eine eintägige Dienstreise von neun Stunden?", [REISE],
         ["mehr als 8 Stunden", "14 Euro"], [r"\b14\b.{0,6}" + EURO]),
    case("aq-nc-05", NEEDLESS_CAVEAT, "Bis wann muss ich das Bürgerhaus am Samstagabend räumen?", [RAUM],
         ["Freitagen und Samstagen", "24 Uhr"], [r"24(:00)?\s*Uhr"]),
    case("aq-nc-06", NEEDLESS_CAVEAT, "Wie hoch ist die Geschwisterermäßigung in der Kita?", [KITA],
         ["Geschwisterermäßigung", "50 Prozent"], [r"50\s*(Prozent|%)"]),
    case("aq-nc-07", NEEDLESS_CAVEAT, "Wie lange darf ich ein Buch aus der Gemeindebibliothek behalten?", [BIB],
         ["Bücher", "28 Tage"], [r"\b28\b.{0,6}Tage"], tags=["pdf"]),
    case("aq-nc-08", NEEDLESS_CAVEAT, "An welchen Tagen findet der Wochenmarkt statt?", [MARKT],
         ["Mittwoch", "Samstag"], [r"Mittwoch", r"Samstag"]),
    case("aq-nc-09", NEEDLESS_CAVEAT, "Wie hoch ist die Monatspauschale für einen Dauerstandplatz auf dem Markt?", [MARKT],
         ["Dauerstandplatz", "36 Euro"], [r"\b36\b.{0,6}" + EURO], tags=["long_document"]),
    case("aq-nc-10", NEEDLESS_CAVEAT, "Ich fahre im Herbst mit der Bahn zu einer Dienstreise und übernachte zweimal. Bis zu welchem Betrag werden die Übernachtungskosten erstattet?", [REISE],
         ["Übernachtungskosten", "80 Euro je Nacht"], [r"\b80\b.{0,6}" + EURO]),
    # --- Falsche Bezugsgröße: the number is in the sources, unit/row/column must be right --------
    case("aq-wr-01", WRONG_REFERENCE, "Wie hoch ist die Standgebühr auf dem Wochenmarkt je Meter?", [MARKT],
         ["Standgebühr", "4,50 Euro je laufendem Meter Standfläche und Markttag"], [r"4,50\s*" + EURO, r"(Markttag|Tag)"], [r"36\s*" + EURO + r"\s*(je|pro)\s*(laufenden?\s*)?Meter"], tags=["long_document"]),
    case("aq-wr-02", WRONG_REFERENCE, "Was kostet das Mittagessen in der Kita?", [KITA],
         ["Mittagessen", "3,20 Euro je Essen"], [r"3,20\s*" + EURO, r"(je|pro)\s*(Essen|Mahlzeit|Mittagessen)"], [r"3,20\s*" + EURO + r"\s*(je|pro|im)\s*Monat"]),
    case("aq-wr-03", WRONG_REFERENCE, "Wie hoch ist der monatliche Kita-Beitrag für ein Kind unter drei Jahren bei 35 Wochenstunden Betreuung?", [KITA],
         ["Kinder unter drei Jahren", "35 Wochenstunden", "285 Euro"], [r"285\s*" + EURO], [r"\b160\s*" + EURO, r"\b210\s*" + EURO, r"\b360\s*" + EURO]),
    case("aq-wr-04", WRONG_REFERENCE, "Wie hoch ist die Säumnisgebühr in der Bibliothek?", [BIB],
         ["Säumnisgebühr", "0,50 Euro je Medium und angefangener Woche"], [r"0,50\s*" + EURO, r"(Woche)"], [r"0,50\s*" + EURO + r"\s*(je|pro)\s*Tag"], tags=["pdf"]),
    case("aq-wr-05", WRONG_REFERENCE, "Was kostet die Verlängerung des Nutzungsrechts an einem Wahlgrab?", [FRIEDHOF],
         ["Verlängerung", "15 Euro je Jahr"], [r"15\s*" + EURO, r"(je|pro)\s*Jahr"], [r"980"], tags=["docx_table"]),
    case("aq-wr-06", WRONG_REFERENCE, "Was kostet ein Gerüst auf dem Gehweg?", [SONDER],
         ["Gerüst", "0,25", "je qm und Woche"], [r"0,25\s*" + EURO, r"Woche"], [r"0,25\s*" + EURO + r"\s*(je|pro)\s*(qm|Quadratmeter)\s*und\s*(Tag|Monat)"], tags=["table_title_row"]),
    case("aq-wr-07", WRONG_REFERENCE, "Wie viel kostet die Außengastronomie je Quadratmeter?", [SONDER],
         ["Außengastronomie", "6,50", "je qm und Monat"], [r"6,50?\s*" + EURO, r"Monat"], [r"40\s*" + EURO + r"\s*(je|pro)\s*(qm|Quadratmeter)"], tags=["table_title_row"]),
    case("aq-wr-08", WRONG_REFERENCE, "Wie hoch ist die Kaution für das Foyer?", [RAUMENTGELT],
         ["Foyer", "Kaution", "100"], [r"\b100\s*" + EURO], [r"100\s*" + EURO + r"\s*(je|pro)\s*Stunde"], tags=["table_row_column"]),
    case("aq-wr-09", WRONG_REFERENCE, "Was kostet der Saal im Bürgerhaus für Auswärtige pro Stunde?", [RAUMENTGELT],
         ["Saal", "Auswärtige", "60"], [r"\b60\s*" + EURO], [r"\b(85|25|200)\s*" + EURO + r"\s*(je|pro)\s*Stunde"], tags=["table_row_column"]),
    case("aq-wr-10", WRONG_REFERENCE, "Welcher Betrag wird bei einer Stornierung weniger als 14 Tage vor dem Termin fällig?", [RAUM],
         ["Stornierung", "50 Prozent des vereinbarten Entgelts"], [r"50\s*(Prozent|%)", r"Entgelt"], [r"50\s*(Prozent|%)\s*(der|von)\s*(der\s*)?Kaution"]),
    case("aq-wr-11", WRONG_REFERENCE, "Wie hoch ist das Tagegeld bei einer Dienstreise von 24 Stunden?", [REISE],
         ["24 Stunden", "28 Euro je Kalendertag"], [r"28\s*" + EURO], [r"\b14\s*" + EURO + r"\s*(je|pro)\s*Kalendertag"]),
]

# Table checks of the format layer: the chunk holding the last data row must repeat the header, and
# the title row above the header row must not be taken for the header.
TABLE_CHECKS = [
    {"document": SONDER, "header_cells": ["Nutzungsart", "Entgelt in Euro", "Bezugseinheit"], "last_row": "Verkaufswagen", "title": "Entgelte für Sondernutzungen"},
    {"document": RAUMENTGELT, "header_cells": ["Raum", "Vereine", "Gewerbe", "Auswärtige", "Kaution"], "last_row": "Küche", "title": None},
    {"document": ZUSTAENDIG, "header_cells": ["Kennung", "Aufgabe", "Amt", "Bearbeitungsfrist"], "last_row": "ZV-130", "title": None},
]


def validate() -> None:
    ids = [c["id"] for c in CASES]
    if len(ids) != len(set(ids)):
        raise SystemExit("Duplicate case id")
    names = {name for name, _ in DOCUMENTS}
    for c in CASES:
        for document in c["documents"]:
            if document not in names:
                raise SystemExit(f"{c['id']}: unknown document {document}")


STATE_DATE = "2026-11-27"

# Format layer (Docker-free, AnswerQualityDatasetTest): every case not listed is solved. The gap is
# the first non-blank row of a sheet being read as the header although it is the title row.
FORMAT_GAPS = {
    case_id: (
        "Das Tabellenformat liest die erste nichtleere Zeile als Kopf; hier ist es die Titelzeile, "
        "die Spaltenköpfe stehen als Datenzeile im Chunk und tragen keinen Bezug zu den Werten."
    )
    for case_id in ("aq-fn-07", "aq-wr-06", "aq-wr-07")
}
FORMAT_SOLVED = "Ein Chunk des Dokuments trägt Wert und Bezug (Einheit, Zeile, Spalte) gemeinsam, der Tabellenkopf stimmt."

CONTEXT_UNMEASURED = (
    "Noch nicht gemessen: die Kontextebene braucht Embedding-Modell und Datenbank "
    "(./gradlew evaluateAnswerQuality); der Erstlauf trägt den Ist-Zustand ein."
)


def load_context_states() -> dict[str, dict]:
    """The context-layer state is measured by the Docker harness and kept across regenerations."""
    if not GOLDEN_FILE.exists():
        return {}
    data = json.loads(GOLDEN_FILE.read_text(encoding="utf-8"))
    return {
        c["id"]: c["expected_state"]["context"]
        for c in data["cases"]
        if "context" in c.get("expected_state", {})
    }


def states_for(case_id: str, context_states: dict[str, dict]) -> dict:
    if case_id in FORMAT_GAPS:
        fmt = {"state": "known_gap", "since": STATE_DATE, "reason": FORMAT_GAPS[case_id]}
    else:
        fmt = {"state": "solved", "since": STATE_DATE, "reason": FORMAT_SOLVED}
    context = context_states.get(
        case_id, {"state": "unmeasured", "since": STATE_DATE, "reason": CONTEXT_UNMEASURED}
    )
    return {"format": fmt, "context": context}


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    validate()
    context_states = load_context_states()
    CORPUS_DIR.mkdir(parents=True, exist_ok=True)
    for existing in CORPUS_DIR.iterdir():
        if existing.name.startswith("aq-"):
            existing.unlink()
    written: list[Path] = []
    for name, build in DOCUMENTS:
        content = build()
        path = CORPUS_DIR / name
        path.write_bytes(content.encode("utf-8") if isinstance(content, str) else content)
        written.append(path)
    manifest = [f"{sha256_of(p)} *{p.name}" for p in sorted(written, key=lambda p: p.name)]
    (CORPUS_DIR / "MANIFEST.sha256").write_text("\n".join(manifest) + "\n", encoding="utf-8", newline="\n")
    cases = []
    for c in CASES:
        entry = dict(c)
        entry["expected_state"] = states_for(c["id"], context_states)
        cases.append(entry)
    GOLDEN_FILE.write_text(
        json.dumps({"cases": cases, "table_checks": TABLE_CHECKS}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {len(written)} documents to {CORPUS_DIR} and {len(CASES)} cases to {GOLDEN_FILE}", file=sys.stderr)


if __name__ == "__main__":
    main()
