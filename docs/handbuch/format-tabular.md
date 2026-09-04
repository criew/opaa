# Format: Tabellen (XLSX, CSV, ODS)

> **Entwurf.** Pipeline `tabular`, Version 1. Der gemeinsame Rahmen aller Format-Pipelines steht
> im Kapitel [Indexierung](indexierung.md), Abschnitt 5.

## 1. Zulassung

| Endung | Prüfung |
|---|---|
| `.xlsx` | strikt: OOXML-Container mit Excel-Inhaltstyp |
| `.ods` | strikt: OpenDocument-Tabellenkalkulation |
| `.csv` | text-tolerant: Inhalt muss Text sein **und** die Datei muss `.csv` heißen |

Welcher der drei Leser läuft, entscheidet das **erkannte** Format, nicht die Endung. Eine als
`.csv` benannte Excel-Datei wird als Excel-Datei gelesen. Das ältere `.xls` ist nicht zugelassen.

## 2. Was gelesen wird

| Format | Leser | Besonderheiten |
|---|---|---|
| XLSX | Apache POI | Zellen werden mit deutschem Zahlenformat gerendert („1.240,00"). Formelzellen liefern den zuletzt gespeicherten **Wert**, nicht die Formel; Formeln werden nicht neu berechnet. |
| CSV | Apache Commons CSV | Kodierung UTF-8 (mit oder ohne BOM), bei ungültigem UTF-8 einmaliger Rückfall auf Windows-1252 (deutscher Excel-Export). Trennzeichen wird aus `,` `;` und Tabulator erkannt, bewertet über die ersten 20 nicht-leeren Zeilen: Es gewinnt der Kandidat mit den meisten konsistent geteilten Zeilen. |
| ODS | eigener, gehärteter XML-Leser | Apache POI liest kein OpenDocument. Wiederholte leere Zeilen werden nicht ausgerollt, die Zeilennummer stimmt trotzdem. Wiederholte Spalten werden ausgerollt, gedeckelt. |

## 3. Struktur und Chunks

Je Tabellenblatt (bei CSV: die eine Tabelle):

1. Die **erste nicht-leere Zeile ist die Kopfzeile**.
2. Die Datenzeilen werden zu **Gruppen von höchstens 50 Zeilen** zusammengefasst. Eine Gruppe
   endet früher, wenn die nächste Zeile den Chunk über 6.000 Zeichen brächte. Eine einzelne
   Zeile, die für sich schon länger ist, wird nie in der Mitte geschnitten, sondern ein eigener
   Chunk.
3. **Jeder Chunk beginnt mit einer Kontextzeile und der Kopfzeile.** Beides steht im
   Chunk-Text selbst, nicht nur in den Metadaten, damit die Volltextsuche „Gebühr" in einer
   Spaltenüberschrift auch in der 200. Zeile trifft.

```
Blatt: Gebühren · Tabelle: Gebühren
Leistung | Gebühr | Rechtsgrundlage
Personalausweis | 37,00 EUR | § 1 PAuswGebV
Reisepass | 60,00 EUR | § 15 PassV
…
```

Bei CSV heißt die Kontextzeile „Tabelle: …" mit einem aus dem Dateinamen abgeleiteten Namen.

Sonderfall: Enthält ein Blatt genau eine nicht-leere Zeile, gilt sie als Inhalt, nicht als
leere Kopfzeile. Lieber wird gelegentlich eine Feldnamenzeile indiziert, als dass echte Daten
verloren gehen.

Keine Überlappung zwischen Chunks.

## 4. Metadaten am Chunk

| Feld | Inhalt | Beispiel |
|---|---|---|
| Ortsangabe XLSX/ODS | Blatt und Zeilenbereich | `Blatt Gebühren · Zeilen 12–61` |
| Ortsangabe XLSX/ODS, eine Zeile | Blatt und Zeile | `Blatt Gebühren · Zeile 12` |
| Ortsangabe CSV | Zeilenbereich | `Zeilen 12–61` |

Zeilennummern sind 1-basiert wie in der Tabellenkalkulation.

**Dokumenteigenschaften:** keine. Für Tabellen stehen dem Metadatenschema nur Dateiname und
Struktur zur Verfügung.

## 5. Fehler

| Befund | Ergebnis |
|---|---|
| Datei beschädigt, ungültiges ZIP, Grenzwert gerissen | fehlgeschlagen, „kein Inhalt" |
| ODS ohne Inhaltsdatei im Container | fehlgeschlagen, „kein Inhalt" |
| Kein Blatt mit Daten, leere Datei | abgewiesen, „kein extrahierbarer Text" |
| Zeile breiter als die Spaltengrenze | Zeile wird abgeschnitten, Warnung im Log, Verarbeitung läuft weiter |

## 6. Grenzwerte

Schlüssel unter `opaa.indexing.tabular.*`:

| Schlüssel | Standard | Wirkung |
|---|---|---|
| `max-row-columns` | 200 | Spalten je Zeile (XLSX und ODS); darüber wird abgeschnitten |
| `max-ods-cell-repeat` | 50 | maximale Ausrollung einer wiederholten Zelle in ODS |
| `max-ods-content-xml-bytes` | 10 MiB | Grenze für den entpackten Inhalt einer ODS-Datei (Schutz gegen ZIP-Bomben) |
| `max-ods-rows` | 100.000 | Zeilen je ODS-Datei |

Fest und nicht konfigurierbar, weil sie den Zuschnitt beschreiben und kein Sicherheitslimit
sind: 50 Zeilen und 6.000 Zeichen je Chunk. XLSX braucht keinen eigenen Deckel, Apache POI
schützt prozessweit gegen ZIP-Bomben. Der ODS-Leser verarbeitet keine externen XML-Entitäten.

## 7. Nicht verarbeitet

- Formeln als solche; es zählt der gespeicherte Wert
- Zellkommentare, Diagramme, Pivot-Tabellen, benannte Bereiche
- Verbundzellen werden nicht aufgelöst, ausgeblendete Zeilen und Blätter nicht unterschieden
- Zellformatierung als Signal (Fettdruck, Farbe)
- Excel-„Tabellen" als Objekte; Blattname und Tabellenname sind dasselbe
