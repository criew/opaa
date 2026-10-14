# ADR-0020: Ordner in Bibliotheken als Navigation, keine Rechtegrenze

## Status

Akzeptiert · zuletzt geändert 2026-09-04

## Kontext

Epic #520 sieht Ordner für Wissensbibliotheken vor: In einer `UPLOAD`-Bibliothek soll sich der
Bestand wie in einer Dateiablage strukturieren lassen (Ordner anlegen, auch leer, umbenennen, löschen,
Dateien hineinladen); für lauf-basierte Bibliotheken soll die Verzeichnisstruktur der Quelle
schreibgeschützt sichtbar werden, statt gleichnamige Dateien aus verschiedenen Unterverzeichnissen
ununterscheidbar in einer flachen Liste zu zeigen. Zwei Fragen waren vorher zu klären: Widerspricht
ein Ordner in einer Bibliothek der Konzeptaussage „die Wissensbibliothek ist kein Ordner in einem
Raum“ (`docs/CONCEPTS.md`, `docs/VISION.md`)? Und wird die Struktur als eigene Entität oder als
Pfad-Präfix am Dokumentnamen abgebildet?

## Entscheidung

### 1. „Kein Ordner in einem Raum“ betrifft Bibliothek ↔ Space

Die Konzeptaussage bezieht sich auf das Verhältnis von Bibliothek und Space: Die Bibliothek trägt ihre
Rechte selbst und erbt sie nicht von einem Raum. Ordner innerhalb einer Bibliothek strukturieren deren
Bestand; der Rechteanker bleibt die Bibliothek (Entscheidung 3). Es gibt keinen Konzeptkonflikt.

### 2. Echte Ordner-Entität

Die Tabelle `library_folders` (`id`, `library_id`, `parent_folder_id`, `NULL` für die Wurzel, `name`)
bildet die Struktur ab; zwei partielle Unique-Indexe verhindern gleichnamige Ordner auf derselben
Ebene (`uk_library_folders_root_name`, `uk_library_folders_child_name`). `documents.folder_id` ist
nullable, `NULL` heißt Wurzelebene. Grund: Leere Ordner müssen existieren können, und Umbenennen oder
Verschieben ist ein Einzeiler-Update statt einer String-Kaskade über alle Dokumentnamen.

### 3. Grants nur auf Bibliotheksebene

`asset_grants` zeigt auf das Asset, nie auf einen Ordner. Wer eine Bibliothek lesen darf, sieht alle
Ordner; wer sie bearbeiten darf, darf in jedem Ordner anlegen, umbenennen und löschen. Das ist
dauerhaft: Eine feinere Ordner-Berechtigung verdoppelte die Filterachse der rechtebewussten Suche
(jeder Chunk trägt genau die Bibliotheks-Kennung). Wer getrennte Leserkreise braucht, legt getrennte
Bibliotheken an.

### 4. Retrieval ohne Ordner-Filter

Die Suche filtert nur nach Bibliothek. Ein Ordner-Filter bräuchte einen Chunk-Metadaten-Schlüssel und
eine Re-Indexierung; das ist eine Ausbaustufe bei konkretem Bedarf. Die Trefferanzeige zeigt den
Ordnerpfad zur Einordnung ([knowledge-sources.md](../features/knowledge-sources.md)).

### 5. Ordner mit Inhalt löschen heißt Dokumente löschen, durch den Service

Das Löschen eines Ordners löscht seine Dokumente und Unterordner, nach Anzeige der Anzahl und
ausdrücklicher Bestätigung; nichts wird in die Wurzel oder einen Papierkorb verschoben. Der Vorgang
läuft rekursiv durch `LibraryFolderService#deleteFolder`, nicht durch eine Datenbank-Kaskade, weil
das Entfernen eines Dokuments Chunks im Vektorspeicher und das Original mit aufräumt;
`fk_documents_folder` steht deshalb auf `RESTRICT`. Kein Widerspruch zur Löschsperre einer
`UPLOAD`-Bibliothek (ADR-0018 Entscheidung 5): Ein Ordner ist ein klar umrissener Teilbestand, den
die Person beim Navigieren gesehen hat.

### 6. Dedup bleibt bibliotheksweit für `UPLOAD`, pfadbasiert für Läufe

`uk_documents_library_checksum` gilt partiell für `source_type = 'UPLOAD'` über die ganze Bibliothek:
Zwei identische Dateien in verschiedenen Ordnern bleiben ein Duplikat. Lauf-basierte Typen
deduplizieren über `file_path`; dieselbe Datei in zwei Unterverzeichnissen ist dort zwei legitime
Dokumente und wird von der Spiegelung nicht zurückgewiesen.

### 7. Spiegelung der Quellstruktur für lauf-basierte Bibliotheken

Jeder Konnektor, der einen Verzeichnisbaum liefert (Dateisystem, HTTP-Verzeichnisliste und die
Dateisynchronisation `FileSync` der Cloud-Konnektoren), spiegelt ihn über `SourceFolderMirror` in
`library_folders`, mit `LibraryFolderService#materializeFolderPath` und `#pruneOrphanedFolders` als
gemeinsamem Lauf-Helfer statt je Konnektor einer Kopie. `SourceFolderPath` beurteilt die Segmente:
Ein Segment, das leer, `.` oder `..` ist, einen Pfadtrenner oder ein NUL-Byte enthält oder länger als
255 Zeichen ist, verwirft den ganzen Pfad; die Datei landet in der Wurzel mit Warnung im
Anwendungsprotokoll, nie unter erfundenem Namen; eine zu tiefe Kette wird bei `MAX_DEPTH` gekappt.
Für die Verzeichnisliste ist der Ordnerpfad der URL-Pfad relativ zur normalisierten Start-URL,
segmentweise prozentdekodiert, ohne Query und Fragment. Leere Ordner werden nur nach einem
vollständigen Lauf entfernt, nach der Bereinigung verschwundener Dokumente. Die Zuordnung geschieht
nach der Verarbeitung eines Eintrags auch dann, wenn er unverändert übersprungen wurde, also ohne
Neuindizierung. Anhänge erben die `folder_id` ihres Elterndokuments (ADR-0022); RSS-Feeds haben keine
Struktur und bleiben ohne Ordner.

## Konsequenzen

**Einfacher:** Leere Ordner sind darstellbar; Umbenennen und Verschieben sind atomare Updates; der
Rechteanker bleibt eindeutig; lauf-basierte Bibliotheken bilden ihre echte Struktur ab.

**Schwieriger:** Das Löschen eines Ordners ist ein rekursiver Service-Vorgang; „nur in diesem Ordner
suchen“ gibt es nicht.

## Verworfene Alternativen

- Virtuelle Pfad-Präfixe statt Tabelle — keine leeren Ordner, Umbenennen als Massenänderung.
- Ordner-Berechtigungen — verdoppelt die Filterachse der Suche; getrennte Bibliotheken leisten dasselbe.
- `ON DELETE CASCADE` von Ordner auf Dokumente — hinterließe verwaiste Chunks und Originale.

## Offen

- Ordner-Filter im Retrieval, sobald ein konkreter Bedarf besteht (Entscheidung 4).

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-24 | #825 | Erstfassung | 1–6 |
| 2026-09-04 | #1277 | Spiegelung auch für `HTTP_DIRECTORY`, gemeinsamer Lauf-Helfer, Segmentregeln | 7 |
