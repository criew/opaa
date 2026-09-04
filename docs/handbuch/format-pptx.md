# Format: PowerPoint (PPTX)

> **Entwurf.** Pipeline `pptx`, Version 1. Der gemeinsame Rahmen aller Format-Pipelines steht im
> Kapitel [Indexierung](indexierung.md), Abschnitt 5.

## 1. Zulassung

| Endung | Prüfung |
|---|---|
| `.pptx` | strikt: OOXML-Container mit PowerPoint-Inhaltstyp |

Das ältere Binärformat `.ppt` ist nicht zugelassen.

## 2. Was gelesen wird

Die Pipeline liest die Präsentation mit Apache POI, Folie für Folie: Titel, alle Textformen in
ihrer Reihenfolge (auch innerhalb von Gruppen), Tabellen zeilenweise sowie die Sprechernotizen.

## 3. Struktur und Chunks

**Eine Folie ist ein Chunk.** Auch eine leere Folie erzeugt einen Chunk, damit die
Foliennummern im Zitat lückenlos bleiben. Mehrere Folien werden nie zusammengelegt.

Aufbau eines Chunks:

1. Folientitel (Platzhalter „Titel" oder „zentrierter Titel") als erste Zeile
2. Text aller übrigen Formen; Tabellenzeilen mit ` | ` zwischen den Zellen
3. Sprechernotizen als abschließender Absatz „Notizen: …"

Ausgefiltert werden Foliennummer, Datum, Kopf- und Fußzeilenplatzhalter. Ein Chunk ist auf
20.000 Zeichen begrenzt; eine Zielgröße gibt es bei Folien nicht.

## 4. Metadaten am Chunk

| Feld | Inhalt | Beispiel |
|---|---|---|
| Ortsangabe (`location`) | Foliennummer und Titel | `Folie 3: Zuständigkeiten` |
| Ortsangabe ohne Titel | Foliennummer | `Folie 3` |

**Dokumenteigenschaften:** Titel, Erstellungs- und Änderungsdatum aus den Dokumenteigenschaften
sowie der Titel der ersten Folie als erste Überschrift.

## 5. Scans und Fehler

| Befund | Ergebnis |
|---|---|
| Datei nicht lesbar oder ohne Folien | fehlgeschlagen, „kein Inhalt" |
| Folien vorhanden, aber keine einzige mit Text (nur Bilder) | abgewiesen, „kein extrahierbarer Text" |

## 6. Grenzwerte

Keine eigenen Konfigurationsschlüssel. Apache POI schützt prozessweit gegen ZIP-Bomben.

## 7. Nicht verarbeitet

- Text auf Masterfolien und Layouts (anders als bei [OpenDocument Präsentation](format-odp.md))
- Kommentare, Animationen
- Alternativtexte von Bildern, SmartArt und Diagrammtexte außerhalb normaler Textformen
- Texterkennung für rein bildbasierte Folien
