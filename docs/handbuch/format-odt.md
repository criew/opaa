# Format: OpenDocument Text (ODT)

> **Entwurf.** Pipeline `odt`, Version 2. Der gemeinsame Rahmen aller Format-Pipelines steht im
> Kapitel [Indexierung](indexierung.md), Abschnitt 5. Das Verhalten entspricht weitgehend dem
> von [Word](format-docx.md); hier stehen die Unterschiede.

## 1. Zulassung

| Endung | Prüfung |
|---|---|
| `.odt` | strikt: OpenDocument-Textdokument |

OpenDocument-Dateien tragen ihren Typ als ersten Eintrag im Container, sodass die Erkennung
bereits aus dem Dateianfang sicher ist. Flat-XML (`.fodt`) ist nicht zugelassen.

## 2. Was gelesen wird

Es gibt keine OpenDocument-Bibliothek im Backend. Die Pipeline liest den Container mit einem
eigenen, gehärteten XML-Leser: den Inhalt (`content.xml`) und die Formatvorlagen (`styles.xml`)
für Kopf- und Fußzeilen.

## 3. Struktur und Chunks

- **Überschriften** kommen direkt aus der Gliederungsebene des Überschriftenelements.
  Geschnitten wird an den Ebenen 1 bis 3, Zielgröße rund 4.000 Zeichen, harte Obergrenze 20.000,
  keine Überlappung, Überschriftenzeile am Anfang jedes Chunks.
- **Tabellen** werden zeilenweise mit ` | ` zwischen den Zellen zu einem Absatzblock.
- **Kopf- und Fußzeilen** aller Varianten (Standard, linke Seite, erste Seite) werden
  dedupliziert als ein führender Chunk „Kopf-/Fußzeile" abgelegt. Seitenzahl-, Seitenanzahl-,
  Datums- und Uhrzeitfelder werden dabei ausgelassen.
- **Änderungsverfolgung**: Aufgezeichnete Änderungen (gelöschter Text zur Prüfung) werden
  vollständig übersprungen.
- Leerzeichen-, Tabulator- und Zeilenumbruch-Elemente werden in Text übersetzt.

## 4. Metadaten am Chunk

| Feld | Inhalt | Beispiel |
|---|---|---|
| Ortsangabe (`location`) | Abschnittspfad | `Abschn. Allgemeines › Geltungsbereich` |
| Ortsangabe des Kopfzeilen-Chunks | fest | `Kopf-/Fußzeile` |

**Dokumenteigenschaften:** Titel, Erstellungs- und Änderungsdatum aus `meta.xml` sowie die erste
Überschrift der Ebene 1.

## 5. Scans und Fehler

| Befund | Ergebnis |
|---|---|
| Kein echter OpenDocument-Container (fehlende Inhaltsdatei) | fehlgeschlagen, „kein Inhalt" |
| Beschädigtes ZIP, abgelehnte externe XML-Entität, Grenzwert gerissen | fehlgeschlagen, „kein Inhalt" |
| Inhalt leer oder Zuschnitt ergibt nichts | abgewiesen, „kein extrahierbarer Text" |
| Formatvorlagen-Datei defekt | nur der Kopfzeilen-Chunk entfällt, das Dokument wird indiziert |

Wie bei Word rettet ein Kopfzeilentext ein sonst leeres Dokument nicht vor der Einstufung als
Scan.

## 6. Grenzwerte

Schlüssel unter `opaa.indexing.odf.*` (gelten auch für [ODP](format-odp.md)):

| Schlüssel | Standard | Wirkung |
|---|---|---|
| `max-content-xml-bytes` | 10 MiB | Grenze für den entpackten Inhalt je Containerdatei (Inhalt und Formatvorlagen getrennt) |
| `max-odt-paragraphs` | 50.000 | Elemente im Inhalt; gilt nicht für die Formatvorlagen |
| `max-space-repeat` | 1.000 | maximale Ausrollung eines wiederholten Leerzeichens |
| `max-text-characters` | 10.000.000 | Gesamttext je Datei |

Der Leser verarbeitet keine Dokumenttyp-Deklarationen und keine externen Entitäten.

## 7. Nicht verarbeitet

- **Verschachtelte Tabellen:** Eine Tabelle innerhalb einer Tabellenzelle geht verloren; die
  äußere Tabelle bleibt erhalten. Bewusst in Kauf genommen.
- Fußnoten, Kommentare und Anmerkungen
- Alternativtexte von Bildern, Linkziele, Text in Zeichnungsrahmen außerhalb normaler Absätze
- Änderungsverfolgung wird nicht ausgewertet, nur ausgeschlossen
