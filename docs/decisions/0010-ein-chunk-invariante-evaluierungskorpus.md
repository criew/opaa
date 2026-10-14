# ADR-0010: Chunk-Zahl-Invariante des Evaluierungskorpus

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt durch ADR-0012

## Kontext

Der Evaluierungskorpus `comic-characters` (ADR-0011, `docs/features/search-quality-evaluation.md`)
beruht auf einer Invariante: Ein generiertes Dokument ergibt beim Indizieren genau einen Chunk. Nur so
ist ein Treffer eindeutig einer Entität zuordenbar, die Voraussetzung für die Ground Truth des Golden
Dataset und für Hit Rate, MRR und nDCG. Die Invariante koppelt den Python-Generator unter
`eval/generator/` (außerhalb von Gradle und CI, ADR-0011 Entscheidung 2) mit dem Schnitt der
Indizierungspipeline. Der erste Generator prüfte eine Byte-Obergrenze; die Pipeline zählt aber Tokens
beziehungsweise Zeichen, und die Tokendichte eines Texts ist nicht konstant. Ein Golden-Eintrag, dessen
Entität still in zwei Chunks zerfällt, ist nicht offensichtlich falsch, er verzerrt nur leise die
Metriken.

## Entscheidung

### 1. Konservative Byte-Vorabprüfung im Generator

`generate_corpus.py` bricht ab, wenn ein Dokument `MAX_DOCUMENT_BYTES` (3.000) überschreitet. Die
Grenze liegt unter dem für die höchste beobachtete Tokendichte des Korpus gemessenen Kipppunkt und
fängt Regressionen sofort beim Generator-Lauf, ohne Backend. Sie ist eine Annäherung, kein Beweis,
und eine manuell gepflegte Konstante: Ändert sich die Chunkgröße der Pipeline, wird sie bewusst neu
bewertet (`eval/generator/README.md`). Der Generator bleibt bei der Python-Standardbibliothek.

### 2. Beweiskräftige Prüfung im Java-Harness

Der Retrieval-Harness (`backend/src/evalTest/java/io/opaa/eval/`) indiziert den eingefrorenen Korpus
über die produktive Pipeline mit der laufenden Anwendungskonfiguration, zählt die Chunks je
Quelldokument und bricht bei Verletzung der Erwartung ab. Das ist die einzige Prüfung, die die
Invariante beweist, weil derselbe Schnitt wie in Produktion läuft. Für die Markdown-Korpora ist das
heute `MarkdownDocumentFormat` mit `HeadingSectionSplitter` (`opaa.indexing.chunk-target-characters`);
`opaa.indexing.chunk-size` und `opaa.indexing.chunk-overlap` steuern den Tokenschnitt
(`format.chunk.OverlappingTokenTextSplitter`, `cl100k_base`) für Tika-Rückfall und Klartext. Der
Harness assertiert den Anwendungsdefault von `chunk-size` (1000), damit eine Änderung nicht still mit
einer womöglich ungültigen Invariante weitermisst. Der Overlap ändert die Chunk-Zahl nicht: Er hängt
jedem Chunk ab dem zweiten die letzten `chunk-overlap` Tokens seines Vorgängers an.

### 3. Die Invariante ist eine Domänen-Eigenschaft

Seit #721 (in Code und Docs als „ADR-0010 Nachtrag“ zitiert) deklariert jede Domäne in
`EvalDomainConfig` eine `ChunkCountExpectation`: `ONE_CHUNK`
(`exactlyOneChunk()`, `comic-characters`) oder `MIN_CHUNKS_PER_DOCUMENT` (`atLeast(n)`, mehrchunkige
Domänen wie `city-landmarks` und `verwaltung` mit mindestens 3). Beide Formen sind derselbe
Mechanismus mit umgekehrter Richtung der Grenze; `ChunkCountExpectation.violations` ist eine reine
Funktion (`ChunkCountExpectationTest`, `evalUnitTest`). Eine mehrchunkige Domäne bekommt ihre eigene
Vorabprüfung im Generator (`CityLandmarksChunkSizeDryRunTest`, `VerwaltungChunkSizeDryRunTest` als
Docker-freie Trockenläufe) und dieselbe beweiskräftige Harness-Prüfung.

### 4. Kopplung an die Chunkgröße

Eine Änderung an `chunk-size`, `chunk-overlap` oder `chunk-target-characters` ändert den eingefrorenen
Korpus nicht, nur ob seine Erwartung noch hält. Schlägt der Harness danach fehl, ist das der erwartete
Signalweg: Baseline und Chunkgröße sind gekoppelt, eine Änderung erfordert einen bewussten neuen
Baseline-Lauf (ADR-0011 Entscheidung 6, ADR-0012).

## Konsequenzen

Einfacher: Die meisten Regressionen fallen beim Generator-Lauf auf; die eigentliche Garantie hängt an
einer Stelle, die mit der Produktionskonfiguration mitzieht.

Schwieriger: Zwei Prüfstellen je Domäne; die Byte-Grenze veraltet lautlos, wenn sie nach einer
Änderung der Chunkgröße nicht neu bewertet wird.

Restrisiken: Ein grüner Generator-Lauf beweist die Invariante nicht, erst ein grüner Harness-Lauf.
Die Dokumentation beider Werkzeuge muss das sichtbar halten. Der Harness assertiert nur `chunk-size`;
`chunk-target-characters`, der tatsächliche Schnitt der Markdown-Korpora, ist weder assertiert noch
im Chunking-Fingerprint von `MarkdownDocumentFormat` erfasst. Eine Änderung fällt erst über die
Chunk-Zahl-Prüfung oder die Baseline auf.

## Verworfene Alternativen

- Tokens im Generator zählen (`tiktoken`) — zweite, von der Backend-Konfiguration entkoppelte
  Tokenisierung, die lautlos veraltet und die Standardbibliothek-Eigenschaft kostet.
- Nur die Harness-Prüfung — jede Korpus-Regenerierung zeigt Fehler erst nach einem vollständigen
  Testcontainers-Lauf.
- Globale Ein-Chunk-Regel für alle Domänen — eine bewusst mehrchunkige Domäne bräche an der ersten
  Prüfung.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-21 | #721 | Invariante wird Domänen-Eigenschaft (`ChunkCountExpectation`) | 3 |
| 2026-10-14 | #2697 | Neufassung; Overlap und zeichenbasierter Markdown-Schnitt eingearbeitet | 2, 4 |
