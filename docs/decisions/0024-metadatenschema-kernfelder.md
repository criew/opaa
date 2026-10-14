# ADR-0024: Metadatenschema — Kernfelder am Dokument, Herkunftspflicht und deterministische Extraktion

## Status

Akzeptiert · zuletzt geändert 2026-09-05 · ergänzt durch ADR-0026 (Kontextpräfix, modellgestützte Extraktion)

## Kontext

[metadata-schema.md](../features/metadata-schema.md) legt fest, **welche** Metadaten es gibt
(Kernfelder Titel, Dokumentart, Datum/Stand; Bibliotheksfelder; Schlagworte), **woher** sie kommen
(deterministisch, dann Modell, dann leer) und **was sie bewirken** (Filter, Kontextpräfix, Beleg).
Vor #1066 existierte davon nichts im Code: Chunk-Metadaten waren rein technisch, und es gab keinen
Ort, an dem ein Wert je Dokument mit seiner Herkunft gespeichert wurde. Dieser ADR beantwortet die
Fragen, die die Spezifikation offen lässt und die alle Arbeitspakete des Epics #1065 tragen müssen.

## Entscheidung

### 1. Metadaten hängen am Dokument, nicht am Chunk

Tabelle `document_metadata_values`: eine Zeile je `(document_id, field_key)`, `ON DELETE CASCADE`
auf `documents`. Chunks in `vector_store` tragen keine eigene Fassung, sondern erben beim
Schreiben — eine Fassung gilt für das Dokument, und eine Korrektur findet an genau einer Stelle
statt. Ein leeres Feld ist die Abwesenheit der Zeile; der dritte Zustand „kein Wert ermittelbar"
ist `value_state = 'NOT_DETERMINABLE'` (`MetadataValueState`), eine Zeile ohne Wert, die nur mit
`origin = 'MANUAL'` speicherbar ist (CHECK-Constraint).

### 2. Jeder Wert trägt seine Herkunft — auf Datenbankebene erzwungen

Jede Zeile trägt `origin` (`DETERMINISTIC` / `DERIVED` / `MANUAL`), `extraction_version` (Pflicht
für alles Nicht-Manuelle), `confidence` (nur bei `DERIVED`), `actor_user_id` (bei `MANUAL`),
`model_id`, `created_at`/`updated_at`; die Regeln sind CHECK-Constraints, nicht Servicelogik.
Ein manueller Wert wird von keiner Extraktion überschrieben. Ein abgeleiteter Wert weicht nur einem
echten deterministischen Ergebnis, nicht einem leeren — sonst müsste das Modell nach jeder
Neuaufnahme derselben Datei erneut bezahlt werden. Werte und Chunk-Nachzug sind eine Transaktion:
`DocumentMetadataService` schreibt über ein `TransactionTemplate`, damit das Parsen der Datei
außerhalb jeder Transaktion läuft und ein fehlgeschlagener Chunk-Nachzug das Dokument unverändert
lässt.

### 3. Dokumentart ist eine Seed-Tabelle mit stabilen Codes, kein Enum und keine Freitextspalte

`document_type_vocabulary` (Code, deutsches Label, Sortierung) plus `document_type_synonyms`; der
Wert am Dokument ist ein Fremdschlüssel auf den Code, ein Wert außerhalb des Vokabulars ist nicht
speicherbar. Stabile Codes statt eines Java-Enums, weil die Liste je Installation erweiterbar sein
muss: Eine Erweiterung ist ein `INSERT`, kein Release. Die Abbildung eines Tokens ist eine exakte,
groß-/kleinschreibungs- und umlautunempfindliche Übereinstimmung mit Code, Label oder Synonym —
keine Ähnlichkeitsabbildung; kein Synonym ist kürzer als vier Buchstaben.

### 4. Datum/Stand ist ein Datum mit Genauigkeit

`date_value` plus `date_precision` (`DAY` / `MONTH` / `YEAR`), aufgefüllt auf den ersten Tag des
unbekannten Teils: „Fassung 2024" ist `(2024-01-01, YEAR)`, filterbar wie jedes Datum, im Beleg als
„2024" dargestellt. Ein Dateisystem-Änderungsdatum ist keine Quelle.

### 5. Die filterbaren Werte stehen zusätzlich in den Chunk-Metadaten

`DocumentMetadataService` schreibt `doc_type`, `doc_date` und `doc_date_precision`
(`CoreMetadataChunkKeys`) zentral auf jeden Chunk, nicht über `passthroughMetadataKeys()` der
Formate — die Registry weist ein Format zurück, das einen dieser Schlüssel deklariert. Der Titel
wird nicht dupliziert. Eine spätere Änderung schreibt die Schlüssel per JSON-Update nach
(`ChunkMetadataStore#updateDocumentMetadata`, Ausdrucksindex `idx_vector_store_document_id`), ohne
Neu-Chunking und Neu-Einbetten; beide Suchpfade tragen dieselbe Bedingung.

### 6. Die Extraktion ist ein Systemprozess des Ingest; die Formate raten nichts

`CoreMetadataExtractor` ist der einzige Ort, an dem aus Rohquellen ein Feldwert wird. Die Formate
liefern über `DocumentProperties` nur, was ihr Format selbst erklärt, und über
`DocumentFormat#readProperties` dieselben Rohquellen ohne Chunking — der Baustein des Bestandslaufs.
Die Quellenreihenfolge je Feld und ihre Fassung (`CoreMetadataExtractor.EXTRACTION_VERSION`) steht
in [metadata-schema.md](../features/metadata-schema.md), Abschnitte „Umgesetzt"; die Regeln dieses
ADR — exakte Abbildung gegen das Vokabular, kein Wert außerhalb davon, ein unmögliches Datum wird
übersprungen, ein nacktes Jahr zählt nur in Dateiname und Frontmatter — gelten für jede Fassung.
Die Extraktion läuft ohne Personenrechtekontext; sie liest, was der Ingest ohnehin liest. Die
`version()` keines Formats steigt wegen neuer Rohquellen; der Bestand wird über
`documents.metadata_extraction_version` (NULL = nie extrahiert) nachgezogen, nicht über die
Format-Version.

### 7. Der Beleg trägt Metadaten als generische Feld-Wert-Liste

`SourceReference.metadata` ist eine Liste selbstbeschreibender Einträge (`SourceMetadataEntry`:
`fieldKey`, deutsches `label`, `value`, `displayValue`, `origin`, optional `datePrecision`),
schemagetrieben gerendert — das Frontend kennt kein Feld. Kernfelder sind die ersten Einträge,
Bibliotheksfelder (Entscheidung 8) und Formatfelder (`fmt:<key>`, #1242, Absender/An/Betreff einer
Mail) hängen an derselben Liste. Die Anzeigeform entsteht im Backend, damit jeder Client dieselbe
Darstellung zeigt; es gibt keine formatspezifischen Felder mehr am `SourceReference`.

### 8. Bibliotheksfelder hängen an derselben Werte-Tabelle, mit Fremdschlüssel auf ihre Werteliste

- Der Feldschlüssel ist namensraumgetrennt: `lib:<key>` plus `library_field_id` (`ON DELETE
  CASCADE`), ein CHECK bindet beide aneinander (`LibraryMetadataFieldKeys`). Damit gelten
  Herkunftspflicht, dritter Zustand, Audit, Pflege-Anker und Sammelzuweisung unverändert, statt
  für eine zweite Tabelle noch einmal geschrieben zu werden.
- Die Feldidentität ist `(Bibliothek, Feldschlüssel)`: Zwei Bibliotheken dürfen beide `fassung`
  führen. Ein Filter trägt beides, und seine Bedingung enthält einen Bibliotheksvorbehalt, damit
  ein Dokument nie gegen die Werteliste einer fremden Bibliothek geprüft wird.
- Der Wert eines Auswahlfeldes ist ein Fremdschlüssel auf seinen Listeneintrag (`ON DELETE
  RESTRICT`): Keine Operation entfernt einen benutzten Listenwert ohne bestätigte Abbildung;
  Umschlüsselung und Feldlöschung laufen zweiphasig und wiederaufnehmbar
  (`LibraryMetadataSchemaChangeService`, #1361).
- Am Chunk trägt ein Bibliotheksfeld drei Schlüssel: `lf_<key>` (Wert), `lfp_<key>`
  (Datumsgenauigkeit), `lfs_<key>` (Präsenzmarke) — die dritte, weil beide Suchpfade „kein Wert"
  als `NOT IN` über eine geschlossene Menge ausdrücken. Alle drei gehören zu jedem Nachzug, auch
  wenn das Feld gerade nicht filtert.
- Das Muster eines Kennungsfeldes ist Nutzereingabe und wird unter einem Schrittbudget
  ausgewertet, weil jede angemeldete Person eine Bibliothek besitzen kann.

## Konsequenzen

- **Einfacher:** Manuelle Korrektur, Bestandslauf (`DocumentMetadataService#reextractFromFile`),
  Filter und Bibliotheksfelder benutzen dieselbe Tabelle und denselben Chunk-Nachzug.
- **Schwieriger:** Jede Regeländerung im Extraktor ist eine neue `EXTRACTION_VERSION` und damit ein
  Bestandslauf. Ein Vokabular-Code darf nie umbenannt werden, solange Dokumente ihn tragen.

## Verworfene Alternativen

- Metadaten je Chunk — eine Korrektur müsste jeden Chunk anfassen; die Fachaussage gilt je Dokument.
- Dokumentart als Java-Enum oder Freitext — nicht je Installation erweiterbar bzw. nicht erzwingbar.
- Chunk-Schlüssel über `passthroughMetadataKeys()` der Formate — kein Format kennt die Werte am Dokument.
- Global eindeutige Schlüssel für Bibliotheksfelder — machte das Anlegen eines Feldes von fremden Bibliotheken abhängig.
- Zweite Werte-Tabelle für Bibliotheksfelder — jede Mechanik ein zweites Mal.

## Offen

- Erweiterung des Vokabulars je Organisation oder je Bibliothek — die Tabelle ist installationsweit.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-04 | #1263, #1289 | Dokumentart auch aus Titelzeile, Kompositum-Endung und Dateiformat (Version 2, 3) | 6 |
| 2026-09-05 | #1071 | Bibliotheksfelder an derselben Werte-Tabelle | 8 |
| 2026-09-05 | #1242 | Formatfelder (`fmt:`) lösen die Mail-Sonderfelder am Beleg ab | 7 |
