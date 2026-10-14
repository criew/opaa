# ADR-0028: Übergang nach einer Anhebung von `content_tsv_version` — der lexikalische Suchpfad filtert nicht auf die Fassung

## Status

Akzeptiert · zuletzt geändert 2026-10-08

## Kontext

Jede Zeile in `chunk_full_text` trägt in `content_tsv_version` die Fassung der Lexembildung, unter
der ihr `tsvector` geschrieben wurde; `FullTextChunkStore.CURRENT_TSV_VERSION` wird angehoben, sobald
sich die gespeicherten Lexeme ändern — bisher stets **additiv** (Kennungslexeme, E-Mail-Adressen,
Kontextpräfix, technische Feld- und Elementnamen). Drei Verbraucher: der Füllstand
(`FullTextIndexFillStateService`), die Reindex-Auswahl (`PipelineReindexService`) und der Suchpfad
(`FullTextChunkSearch`), der bis #1346 nur Zeilen der aktuellen Fassung las. Damit war nach jedem
Deployment einer Anhebung der gesamte Volltextindex für den lexikalischen Pfad unsichtbar, bis der
Pipeline-Nachzug jede Bibliothek neu geschrieben hatte — im Widerspruch zur Zusage in
[metadata-schema.md](../features/metadata-schema.md), „Nachlauf im Betrieb", dass die Suche während
des Nachlaufs verfügbar bleibt. Tragend: Je Chunk gibt es genau eine Zeile (Upsert auf `chunk_id`),
der Versionsfilter dedupliziert also nichts; und der Rechtefilter (`library_id = ANY(?)`) ist
versionsunabhängig.

## Entscheidung

### 1. Kein Versionsfilter in der Suche

`FullTextChunkSearch` liest `chunk_full_text` ohne Bedingung an `content_tsv_version`.
`CURRENT_TSV_VERSION` steuert ausschließlich Füllstand und Reindex-Auswahl. Eine Zeile alter Fassung
bleibt lexikalisch findbar; ihr fehlen nur die Lexeme der neueren Fassung, bis der Nachzug sie neu
schreibt. Der Zähler `fullTextOutdatedChunks` der Zustandsübersicht (seit #1493, Zustand `OUTDATED`
„Nachzug ausstehend") bedeutet: Abschnitte mit einer Zeile alter Fassung — findbar, nicht
unsichtbar. Keine Migration, kein Backfill.

### 2. Bedingung für jede künftige Anhebung: additiv

Eine Änderung, die die Lexemform bestehender Zeilen bricht (Wechsel der Textsuchkonfiguration
`FullTextChunkStore.TEXT_SEARCH_CONFIGURATION`, heute `german`; anderes Stemming, andere Zerlegung), erzeugt Zeilen,
deren Lexeme mit einer nach neuer Regel gebauten `tsquery` nicht mehr übereinstimmen. Eine solche
Anhebung muss den Versionsfilter im Suchpfad wieder einführen (als Gleichheit oder Untergrenze),
bevor sie ausgeliefert wird. Die Bedingung steht im Javadoc der Konstante; der PR, der sie anhebt,
benennt, ob die Änderung additiv ist. Der Code kann das nicht prüfen.

## Konsequenzen

- **Einfacher:** Die Zusage „Suche bleibt während des Nachlaufs verfügbar" gilt für beide Hälften;
  ein Bump macht den Index schrittweise besser, nicht schlagartig unsichtbar. Keine zweite Konstante.
- **Restrisiko:** „Additiv" ist eine Vertragsbedingung, keine geprüfte Eigenschaft.

## Verworfene Alternativen

- Untergrenze `MIN_SEARCHABLE_TSV_VERSION` — eine zweite Konstante, die jede Anhebung mitpflegen müsste, ohne dass ein Test sie erzwingt; für den additiven Bestand dauerhaft `1`.
- Zusage in der Dokumentation einschränken — ein Volltextindex, der nach jedem Deployment für Stunden ausfällt, ist genau die Überraschung, die der Nachlauf verhindern soll.

## Offen

—

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-11 | #1493 | Zähler zählt nur noch den Fassungs-Rückstand (`fullTextOutdatedChunks`, `OUTDATED`) | 1 |
| 2026-10-08 | #2337 | Fassung 6 (technische Feld- und Elementnamen), additiv | 2 |
