# ADR-0024: Metadatenschema — Kernfelder am Dokument, Herkunftspflicht und deterministische Extraktion

## Status

Vorgeschlagen (04.09.2026, Issue #1066, Epic #1065). Die Architekturannahmen stammen aus dem
Baubeginn-Kommentar des Maintainers am Epic vom selben Tag; dieser ADR hält sie in der Form fest, in
der #1066 sie umgesetzt hat.

## Kontext

[docs/features/metadata-schema.md](../features/metadata-schema.md) legt fest, **welche** Metadaten es
gibt (drei Kernfelder Titel, Dokumentart, Datum/Stand; später Bibliotheksfelder und Schlagworte),
**woher** sie kommen (deterministisch, dann Modell, dann leer) und **was sie bewirken** (Filter,
Kontextpräfix, Beleg). Bis #1066 existierte davon nichts im Code: Chunk-Metadaten waren rein technisch,
und es gab keinen Ort, an dem ein Wert je Dokument mit seiner Herkunft gespeichert wurde.

Die Umsetzung des ersten Arbeitspakets musste sechs Fragen beantworten, die die Spezifikation offen
lässt oder nur fachlich beantwortet — und deren Antwort alle Folgepakete (#1067 Bestandslauf, #1068
manuelle Korrektur, #1069 Pflege-Anker, #1070 Filter, #1071 Bibliotheksfelder) tragen müssen.

## Entscheidung

### 1. Metadaten hängen am Dokument, nicht am Chunk

Tabelle `document_metadata_values` (Migration 018): eine Zeile je `(document_id, field_key)`, mit
`ON DELETE CASCADE` auf `documents`. Die Chunks in `vector_store` tragen keine eigene Fassung dieser
Werte, sondern erben sie beim Schreiben. Das ist die fachliche Aussage der Spezifikation („eine Fassung
gilt für das Dokument"), keine Speicheroptimierung — und die Voraussetzung dafür, dass eine
Korrektur an genau einer Stelle stattfindet.

Ein **leeres Feld ist die Abwesenheit der Zeile**, kein Nullwert. Der dritte Zustand „kein Wert
ermittelbar" (#1069) ist im Schema als `value_state = 'NOT_DETERMINABLE'` vorgesehen — eine Zeile ohne
Wert, die nur mit `origin = 'MANUAL'` gespeichert werden kann (CHECK-Constraint); #1066 schreibt ihn
nicht.

### 2. Jeder Wert trägt seine Herkunft — auf Datenbankebene erzwungen

Jede Zeile trägt `origin` (`DETERMINISTIC` / `DERIVED` / `MANUAL`), `extraction_version` (Pflicht für
alles Nicht-Manuelle), `confidence` (nur bei `DERIVED` speicherbar), `actor_user_id` (bei `MANUAL`),
`model_id` und `created_at`/`updated_at`. Die Regeln der Spezifikation sind CHECK-Constraints, nicht
Servicelogik: Ein deterministischer Wert mit Konfidenz oder ein automatischer Wert ohne
Extraktionsversion ist nicht speicherbar.

**Ein manueller Wert wird von keiner Extraktion überschrieben** — `DocumentMetadataService` lässt eine
Zeile mit `origin = MANUAL` bei jeder Reconciliation unangetastet, auch wenn die Extraktion für dasselbe
Feld einen anderen oder keinen Wert liefert (abgesichert im Integrationstest).

### 3. Dokumentart ist eine Seed-Tabelle mit stabilen Codes, kein Enum und keine Freitextspalte

`document_type_vocabulary` (Code, deutsches Label, Sortierung) plus `document_type_synonyms`. Der Wert am
Dokument ist ein **Fremdschlüssel** auf den Code: Ein Wert außerhalb des Vokabulars ist nicht
speicherbar — die Regel „nicht auf den nächstähnlichen abbilden, nicht auf einen Vorgabewert setzen"
wird dort erzwungen, wo sie nicht vergessen werden kann. Stabile Codes (`SATZUNG_ORDNUNG`,
`DIENSTANWEISUNG`, …) statt eines Java-Enums, weil die Spezifikation die Liste „je Installation
erweiterbar" verlangt: Eine Erweiterung ist ein `INSERT`, kein Release. Die Abbildung eines Tokens auf
einen Code ist eine **exakte**, groß-/kleinschreibungs- und umlautunempfindliche Übereinstimmung mit
Code, Label oder Synonym — keine Ähnlichkeitsabbildung.

### 4. Datum/Stand ist ein Datum mit Genauigkeit

`date_value` plus `date_precision` (`DAY` / `MONTH` / `YEAR`); der Wert ist auf den ersten Tag des
unbekannten Teils aufgefüllt. „Fassung 2024" ist damit `(2024-01-01, YEAR)` — filterbar wie jedes andere
Datum, im Beleg als „2024" darstellbar, nie als „01.01.2024". Ein Dateisystem-Änderungsdatum ist keine
Quelle: Es sagt, wann eine Datei kopiert wurde, nicht welcher Stand gilt.

### 5. Die filterbaren Werte stehen zusätzlich in den Chunk-Metadaten

`FileProcessingService#storeChunks` schreibt `doc_type`, `doc_date` und `doc_date_precision` auf jeden
Chunk — zentral, nicht über `passthroughMetadataKeys()` der Pipelines, weil die Werte am Dokument hängen
und keine Pipeline sie kennt. Der Titel wird nicht dupliziert (nicht filterbar; der Beleg liest ihn vom
Dokument). Damit können beide Suchpfade (#1070) dieselbe Bedingung tragen. Eine spätere Änderung
(manuelle Korrektur, Bestandslauf) schreibt die Schlüssel per JSON-Update nach
(`VectorChunkStore#updateDocumentMetadata`) — ohne Neu-Chunking und ohne Neu-Einbetten.

### 6. Die Extraktion ist ein Systemprozess des Ingest; die Pipelines raten nichts

`CoreMetadataExtractor` ist der einzige Ort, an dem aus Rohquellen ein Feldwert wird. Die Pipelines
liefern über `DocumentProperties` nur, was ihr Format selbst erklärt (Titel-Eigenschaft, Erstell-/
Änderungsdatum, erste Überschrift, Markdown-Frontmatter, Mail-Betreff und -Datum) und über
`DocumentPipeline#readProperties` dieselben Rohquellen **ohne Chunking** — der Baustein, den der
Bestandslauf (#1067) je Dokument aufruft.

Quellenreihenfolge (Version 1, `CoreMetadataExtractor.EXTRACTION_VERSION`):

- **Titel:** Format-Eigenschaft → Frontmatter `titel` → erste Überschrift → humanisierter Dateiname.
- **Dokumentart:** Frontmatter `dokumentart` (eine Deklaration außerhalb des Vokabulars lässt das Feld
  leer und fällt nicht durch) → Dateinamens-Token (genau ein eindeutiger Code; zwei verschiedene lassen
  das Feld leer).
- **Datum/Stand:** Frontmatter `stand_datum`/`fassung` und das formateigene Dokumentdatum (Mail-Header)
  → erste Überschrift (Kopfbereich) → Dateiname → Änderungs- → Erstell-Eigenschaft. Ein nacktes Jahr zählt
  nur als eigenständiges vierstelliges Token 1900–2099.

Die Extraktion läuft ohne Personenrechtekontext (Beschluss 1 des Maintainers, Epic #1065): Sie zeigt
niemandem Inhalte, sie liest, was der Ingest ohnehin liest. Die Rechte-Invariante der Spezifikation gilt
für Aggregate, Stichproben und die modellgestützte Extraktion.

**Regel (d) der Ingestion-Spezifikation bleibt unberührt:** Die `version()` keiner Pipeline steigt,
obwohl sie neue Rohquellen liefert — die erzeugten Chunks ändern sich nicht. Die Nachrüstung des
Bestands läuft über `documents.metadata_extraction_version` (NULL = nie extrahiert), nicht über die
Pipeline-Version; das ist die Selektionsspalte von #1067.

## Konsequenzen

- **Einfacher:** #1068 setzt eine `MANUAL`-Zeile und ruft `updateDocumentMetadata`; #1067 ruft je Dokument
  `DocumentMetadataService#reextractFromFile` und selektiert nach `metadata_extraction_version`; #1070
  filtert auf `doc_type`/`doc_date`; #1071 hängt Bibliotheksfelder über einen weiteren Feldbezug an
  dieselbe Werte-Tabelle.
- **Schwieriger:** Jede Regeländerung im Extraktor ist eine neue `EXTRACTION_VERSION` und damit ein
  Bestandslauf. Ein Vokabular-Code darf nie umbenannt werden, solange Dokumente ihn tragen (FK).
- **Bewusst offen:** Die Erweiterung des Vokabulars je Organisation oder je Bibliothek (Offener Punkt der
  Spezifikation) — die Tabelle ist heute installationsweit.
