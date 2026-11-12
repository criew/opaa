# ADR-0074: Ein Datenspeicher für Metadaten und Abschnitte — keine Trennung, Lesereplik als erste Ausbaustufe

## Status

Akzeptiert · zuletzt geändert 2026-11-12 · ergänzt ADR-0002 Entscheidung 3 und ADR-0042 Entscheidung 3 ·
beantwortet den Baustein „Speicher-Backends trennen“ aus Epic #1439

## Kontext

Epic #1439 fragt, ob große Installationen Metadaten (Konten, Bibliotheken, Spaces, Rechte, Dokumente)
in einer PostgreSQL halten und die Abschnitte (`vector_store`, `chunk_full_text`) in einer anderen,
gegebenenfalls je Bibliothek in einem wählbaren „Datentopf“. Heute liegt alles in einer Datenbank und
einem Schema (ADR-0002 Entscheidung 3, ADR-0034, ADR-0065 Entscheidung 2); pgvector ist der einzige
Vektorspeicher (ADR-0014 Entscheidung 6). Mehrere Backend-Instanzen stimmen sich über genau diese
Datenbank ab (ADR-0053).

Einen gemessenen Bedarf gibt es nicht. Keine Installation hat eine Grenze der einen Datenbank
gemeldet, und Größenklassen samt Messung des Kernsystems sind erst Gegenstand von Epic #3492. Die
bekannte Grenze von pgvector bei Beständen von vielen Millionen Vektoren gilt für jede PostgreSQL,
auch für eine eigene nur für Abschnitte.

Das Inventar am Code (Entscheidung 2) zeigt zwei Seiten. Der **Suchpfad** ist schon heute frei von
Metadaten: Die Rechte kommen als fertige Liste von Bibliotheken in die Abfrage, die Metadatenfilter
laufen über die Schlüssel, die jeder Abschnitt selbst trägt, und Titel und Fundort holt eine eigene
Abfrage danach. **Schreiben, Löschen, Wartung und Wiederherstellung** dagegen stützen sich an
mehreren Stellen auf eine gemeinsame Transaktion oder einen Join über beide Seiten, am stärksten die
nachweisbare Löschung privater Bibliotheken (ADR-0049 Entscheidung 2).

## Entscheidung

### 1. Eine PostgreSQL je Installation für Metadaten, Abschnitte, Vektoren und Volltext

Metadaten, `vector_store`, `chunk_full_text` und die Tabellen des Vektorindex (`embedding_index`,
`embedding_model`) liegen in derselben Datenbank und demselben Schema; `config.DatabaseSchemaGuard`
bleibt dabei. OPAA bietet weder eine installationsweite zweite Datenbank für Abschnitte an noch einen
wählbaren Datentopf je Bibliothek. Eine Installation hat eine Sicherung, eine Wiederherstellung, einen
Migrationsschritt und je Instanz einen Verbindungspool.

Begründung: Die Trennung kostet die Zusagen, die heute an einer gemeinsamen Transaktion hängen
(Löschnachweis, Schreibsperre gegen gelöschte Bibliotheken, ein konsistenter Sicherungsstand), und
verschiebt die Grenze von pgvector nicht; sie entlastet nur die Metadaten, den kleinen Teil. Wofür
sie gedacht ist (Last der Suche von der übrigen Arbeit fernhalten), leistet eine Lesereplik mit weit
weniger Umbau (Entscheidung 4).

### 2. Inventar der Kopplung

Diese Stellen setzen eine gemeinsame Datenbank voraus. Wer die Frage neu stellt, beginnt hier.

| Bereich | Stelle | Art der Kopplung |
|---|---|---|
| Indexieren | `indexing.chunk.VectorStoreWriter#writeEmbeddedChunks` | Vektor und Volltexteintrag in einer Transaktion; vorher `FOR KEY SHARE` auf `knowledge_libraries` gegen eine vorgemerkte Löschung; Generation aus `embedding_index` unter dem geteilten Schemalock (`EmbeddingIndexStore#lockGenerations`) |
| Löschen eines Dokuments | `library.LibraryDocumentService#deleteDocument` | Zeile und Abschnitte samt Anhängen in einer Transaktion: kein Abschnitt überlebt sein Dokument |
| Löschpflicht privater Bibliotheken | `library.PrivateLibraryErasure` | Abschnitte, Dokumente, Verweise, Nachweis `PRIVATE_LIBRARY_ERASED` und Nachzählung in einer Transaktion; Rollback, wenn etwas übrig bleibt |
| Löschen einer Konnektor-Bibliothek | `library.KnowledgeLibraryService`, `indexing.maintenance.StaleDocumentCleanupService` | Abschnitte nach dem Commit bzw. vor der Zeile; Reste fängt der Aufräumlauf |
| Metadatenkorrektur | `metadata.DocumentMetadataService#reextractFromProperties`, `#rewriteChunkMetadata` | `document_metadata_values` und die Schlüssel der Abschnitte in einer Transaktion |
| Pipeline-Nachzug | `indexing.maintenance.PipelineReindexService` | SQL-Join `vector_store` ⋈ `documents`, `EXISTS` auf `assets.owner_only`, rekursives `UPDATE documents` über Abschnitte |
| Neueinbettung | `indexing.chunk.VectorIndexSchema#chunkCounts`, `#textLengths`; `EmbeddingIndexReconciler` | Join auf `assets`; DDL der Vektortabelle und Zeile `embedding_index` unter Advisory-Lock 213; Trigger `trg_embedding_index_invalidation` am Bus (ADR-0053 Entscheidung 5) |
| Aufräumlauf | `indexing.maintenance.OrphanedChunkCleanup` | Anti-Join `chunk_full_text` gegen `documents` |
| Füllstand des Volltexts | `indexing.maintenance.FullTextIndexFillStateService` | nur Abschnittstabellen (`vector_store` ⋈ `chunk_full_text`) |
| Suche | `retrieval.scope.SearchScopeStage`, `retrieval.scope.MetadataFilterExpressions`, `retrieval.search.FullTextChunkSearch`, `search.SearchHitAssembler` | keine: Bibliotheksliste als Parameter, Filter über `vector_store.metadata`, Anreicherung als eigene Abfrage |
| Schema und Migration | `knowledge/V1.4__knowledge_baseline.sql`, `knowledge.VectorTableCreator`, `config.DatabaseSchemaGuard` | `chunk_full_text` ohne Fremdschlüssel; `vector_store` legt der Abschluss der Installation an; ein Schema für beide Seiten erzwungen |
| Texterkennung | `document_text_recognition` (knowledge-Migration) | liegt bewusst neben Dokument und Abschnitten, damit eine Rücksicherung alle drei gleich zurückbringt |
| Fakten-Manifest | `compliance/manifest/libraries.yaml` | `vector_store` und `chunk_full_text` als Tabellen ohne Angabe eines Speicherorts |
| Sicherung | Handbuch [Sicherung und Wiederherstellung](../handbuch/betrieb/sicherung-und-wiederherstellung.md) | ein `pg_dump`, eine Zeilenzählung (P2) über alle Tabellen |

Rechteentzug berührt die Abschnitte nicht: Rechte, Freigaben und Space-Zuordnungen werden vor der
Suche zur Liste lesbarer Bibliotheken aufgelöst.

### 3. Der Suchpfad liest nur Abschnittstabellen

Vektor- und Volltextsuche, Nachbarabschnitte und Einbettungen der Rangfolge lesen nur die Tabellen
des Index: `vector_store`, `chunk_full_text` und die Generation in `embedding_index`. Rechte kommen als Liste von Bibliotheken in die Abfrage (ADR-0065
Entscheidung 4), Metadatenfilter laufen über die Schlüssel des Abschnitts (ADR-0024 Entscheidung 5),
Titel, Fundort und Bibliotheksname holt eine getrennte Abfrage nach der Rangfolge. Ein Join des
Suchpfads auf eine Metadatentabelle braucht eine eigene Entscheidung.

Begründung: Diese Eigenschaft hält Entscheidung 4 und eine spätere Trennung offen, ohne heute etwas
umzubauen; sie geht verloren, sobald eine einzige Suchabfrage `documents` oder `assets` joint.

### 4. Ausbaustufen mit Eintrittsbedingung

| Stufe | Was | Eintrittsbedingung |
|---|---|---|
| 0 (gebaut) | eine PostgreSQL, vertikal skaliert | — |
| 1 | Lesereplik für den Suchpfad (Entscheidung 3); Schreiben, Rechte und Anreicherung bleiben auf der Primärdatenbank | Die Messung des Kernsystems (Epic #3492) zeigt für eine Größenklasse, dass die Suchlast auf einem nach Größenplanung ausgestatteten Server die übrigen Anfragen messbar verlangsamt |
| 2 | eigene PostgreSQL für alle Abschnitte, installationsweit | Stufe 1 gemessen unzureichend, weil Indexieren und Neueinbettung die Metadaten bremsen; oder ein Haus verlangt nachweislich getrennte Betriebsverantwortung oder getrennten Sicherungsrhythmus für den Index |

Stufe 1 muss zwei Folgen der Verzögerung der Replik behandeln: Treffer, deren Dokument auf der
Primärdatenbank schon fehlt, fallen weg (heute zeigt `SearchHitAssembler` sie mit dem Dateinamen aus
dem Abschnitt), und hinkt die Replik dem Umschalten eines Embedding-Modells nach, findet die
Vektorsuche bis zum Aufholen nichts, weil sie die Generation im selben Statement prüft
(`retrieval.search.VectorChunkSearch`); die Volltextsuche antwortet dann allein. Rechteentzug und Löschnachweis bleiben auf der Primärdatenbank und sind nicht betroffen.

### 5. Was eine Trennung nach Stufe 2 lösen muss

Ein ADR zu Stufe 2 beantwortet mindestens: die Löschpflicht in zwei Phasen mit Nachweis erst nach
der Nachzählung in beiden Datenbanken; einen Ersatz für die Schreibsperre `FOR KEY SHARE` (etwa eine
Liste gelöschter Bibliotheken in der Abschnittsdatenbank, die jeder Schreiber prüft); die
Metadatenkorrektur als wiederholbaren Nachlauf; die Joins aus Entscheidung 2 als Abfragen in Stapeln;
`embedding_index` und den Schemalock auf der Seite der Vektoren samt Hörverbindung des Busses dort;
einen Abgleich nach jeder Rücksicherung (Abschnitte ohne Dokument entfernen, Dokumente ohne Abschnitte
neu indexieren); zwei Migrationsziele im Migrationsschritt (ADR-0053 Entscheidung 14); die Poolformel
je Datenbank; den Speicherort je Tabelle im Fakten-Manifest; Helm-Werte, Compose und Handbuch für
eine zweite Datenbank.

### 6. Kein Datentopf je Bibliothek

Abschnitte einer Bibliothek liegen nie in einer anderen Datenbank als die übrigen. Wer Daten einer
Organisationseinheit getrennt halten muss, betreibt eine eigene Installation mit eigener PostgreSQL
(Handbuch [Datenbank](../handbuch/betrieb/datenbank.md#eigenes-datenbankschema)).

Begründung: Je Datentopf verdoppeln sich Sicherung, Migration, Modellwechsel und Löschnachweis, und
eine Frage über Bibliotheken mehrerer Töpfe müsste jede Suchstufe auffächern und die Ränge
zusammenführen. Originale, Dokumente und Metadaten blieben trotzdem zentral; die Trennung wäre für
eine Behörde nur halb nachweisbar.

## Konsequenzen

**Einfacher:** Löschnachweis, Wiederherstellung und Wiederherstellungsprobe bleiben ein Vorgang gegen
einen Stand. Kein zweiter Pool, kein zweites Migrationsziel, keine zweite Datenbank in Chart und
Compose. Die Zusagen aus ADR-0049 und ADR-0053 gelten unverändert.

**Schwieriger:** Eine Installation wächst nur so weit, wie ein PostgreSQL-Server trägt, bis Stufe 1
gebaut ist. Indexieren, Neueinbettung und Suche teilen sich Speicher und Ein-/Ausgabe mit den
Metadaten. Entscheidung 3 ist eine Regel ohne eigenen Strukturtest.

**Restrisiken:** Die Eintrittsbedingungen setzen die Messung aus Epic #3492 voraus; bis dahin kann
eine Grenze erst im Betrieb sichtbar werden. Die Grenze von pgvector bei sehr großen Beständen löst
keine Stufe dieses ADR; dafür bliebe nur ein anderer Vektorspeicher und damit eine neue Entscheidung
gegen ADR-0014 Entscheidung 6.

## Verworfene Alternativen

- Eigene PostgreSQL für Abschnitte, jetzt — kein gemessener Bedarf, verliert Löschnachweis und konsistente Sicherung (Entscheidungen 1, 5).
- Datentopf je Bibliothek — vervielfacht Betrieb und Nachweise, Mandantentrennung leistet eine eigene Installation (Entscheidung 6).
- Partitionierung von `vector_store` — Primärschlüssel und `ON CONFLICT (id)` des Schreibwegs tragen keinen Partitionsschlüssel, und je Partition entstünde ein eigener HNSW-Index.
- Lesereplik sofort — ohne Messung kein Nachweis, dass sie etwas löst (Entscheidung 4).
- Zwei Datenbanken mit verteilter Transaktion (`PREPARE TRANSACTION`) — hängende vorbereitete Transaktionen halten Sperren und sind ein eigener Betriebsfall.
- `postgres_fdw` zwischen den Datenbanken — rettet die Joins, nicht die gemeinsame Transaktion.
- Externe Vektordatenbank — ausgeschlossen durch ADR-0014 Entscheidung 6.

## Offen

- Größenklassen und die Messung, an der die Eintrittsbedingungen von Entscheidung 4 hängen (Epic #3492).
