# Quellen der echten Scans

Neun Dokumente, 17 Seiten, für die Nachmessung des OCR-PoC mit echten Scans. Die PDFs liegen nicht im
Repository. `fetch_real.py` lädt sie von den hier genannten Adressen, nimmt die genannten Seiten und
rastert sie neu (300 dpi, Graustufen). Dabei fällt eine vorhandene alte OCR-Textebene weg. Die
Sollwerte je Dokument (`truth/<id>.json`) wurden von Hand aus dem Seitenbild abgelesen, nicht aus der
alten Textebene.

## Rechtsgrundlage

Alle Dokumente sind amtliche Werke im Sinne von § 5 UrhG:

- **Bundestagsdrucksachen** sind amtliche Werke nach § 5 Abs. 2 UrhG. Es gelten das Änderungsverbot (§ 62 UrhG) und
  das Gebot der Quellenangabe (§ 63 UrhG). Siehe die
  [Hinweise des Bundestages](https://www.bundestag.de/services/faq/dokumente-244896).
- **Bundesgesetzblatt:** Gesetze und Verordnungen sind amtliche Werke nach § 5 Abs. 1 UrhG. Die Scans
  stellt [Offene Gesetze](https://offenegesetze.de/) bereit.
- **Ortsrecht** (Hauptsatzung, Satzung einer Körperschaft des öffentlichen Rechts) und amtliche
  Bekanntmachungen sind amtliche Werke nach § 5 Abs. 1 UrhG. Veröffentlicht sind sie auf der Seite
  des Amtes Güstrow-Land.

Die Scans werden nur zur Messung gelesen und nicht weitergegeben. Deshalb stehen hier nur Adressen
und Seitenangaben, keine Dateien.

## Dokumente

| id | Dokument | Seiten | Quelle |
|---|---|---|---|
| `btd-1-1` | Drucksache 1/1 (1949), Alphabetisches Verzeichnis der Mitglieder des Bundestags: Tabelle, Bleisatz | 1 | https://dserver.bundestag.de/btd/01/000/0100001.pdf |
| `btd-2-200` | Drucksache 2/200 (1953), Entwurf Haushaltsgesetz 1954: Anschreiben mit Aktenzeichen, Gesetzestext mit §, Zahlentabelle | 1, 3, 9 | https://dserver.bundestag.de/btd/02/002/0200200.pdf |
| `btd-5-500` | Drucksache V/500 (1966), Kleine Anfrage: Beträge, § im Fließtext | 1 | https://dserver.bundestag.de/btd/05/005/0500500.pdf |
| `btd-11-1000` | Drucksache 11/1000 (1987), Gesetzentwurf ERP-Wirtschaftsplangesetz 1988: §-Gliederung, Haushaltstabelle | 1, 6, 12 | https://dserver.bundestag.de/btd/11/010/1101000.pdf |
| `bgbl1-1955-1` | Bundesgesetzblatt I 1955 Nr. 1: Inhaltstabelle, Verordnungstext mit ausgefransten Lettern | 1, 3 | https://media.offenegesetze.de/bgbl1/1955/bgbl1_1955_1.pdf |
| `bgbl1-1972-1` | Bundesgesetzblatt I 1972 Nr. 1: Inhaltstabelle, Betäubungsmittelgesetz mit §-Absätzen | 1, 3 | https://media.offenegesetze.de/bgbl1/1972/bgbl1_1972_1.pdf |
| `ort-glasewitz-hauptsatzung` | Hauptsatzung der Gemeinde Glasewitz (2024): Bürodruck-Scan, Unterschrift über dem Text | 1, 6 | https://www.amt-guestrow-land.de/images/pdf/Glasewitz/Hauptsatzung2024neu.pdf |
| `ort-jagd-siemitz` | Satzung der Jagdgenossenschaft Siemitz-Käselow (2023): grauer Scan, Handschrift, Stempel und Siegel | 1, 5 | https://www.amt-guestrow-land.de/images/pdf/Mistorf/JagdgenossenschaftSiemitz-KaeselowSatzung2023.pdf |
| `ort-fnv-vietgest` | Flurneuordnungsverfahren Vietgest, Feststellung der Ergebnisse der Wertermittlung (2022): Aktenzeichen, Wappen | 1 | https://www.amt-guestrow-land.de/images/pdf/Glasewitz/FNVVietgestFeststellungErgebnisseWertermittlung.pdf |

Abgerufen am 6. November 2026. Fehlt eine Quelle später, ist die Nachmessung mit einem Ersatzdokument
gleicher Art zu wiederholen. Die Sollwerte gelten nur für die hier genannten Fassungen.
