# Retrieval-Algorithmus (Ist-Stand)

> **Status: Ist-Stand.** Dieses Dokument beschreibt nur Gebautes und wird mit jeder Änderung am
> Retrieval nachgezogen; Zielbild und Begründung stehen in
> [Wissensschicht und Retrieval](./data-indexing-rag.md) und [Hybride Suche](./hybrid-retrieval.md).

Dieses Dokument beschreibt, **wie das Retrieval heute tatsächlich arbeitet** — Klasse für Klasse, Parameter
für Parameter, Stand `main` nach dem Epic
[#1045](https://github.com/criew/opaa/issues/1045). Es ist damit die
Ergänzung zu [Wissensschicht und Retrieval](./data-indexing-rag.md), das überwiegend das **Zielbild**
beschreibt (siehe dessen „Lesehinweis zum Umsetzungsstand"): Abschnitte wie
[Hybride Suche](./data-indexing-rag.md#hybride-suche) und [Reranking](./data-indexing-rag.md#reranking)
dort sind inzwischen beide gebaut: Die **hybride Suche** — der mit
[#1048](https://github.com/criew/opaa/issues/1048) gebaute lexikalische Suchpfad (Schritt 3b) — ist seit
[#1049](https://github.com/criew/opaa/issues/1049) eine Eingangsliste der Fusion und bestimmt die
Endauswahl mit, und ein **separates Reranking-Modell** gibt es seit
[#1050](https://github.com/criew/opaa/issues/1050) als eigene Modellrolle (Schritt 5b) — mit einem
eigenen Schalter und **per Voreinstellung aus**. Wer wissen will, was gebaut ist, liest dieses
Dokument; wer wissen will, wohin es geht, liest `data-indexing-rag.md`.

Die Stellschrauben-Tabelle in `data-indexing-rag.md` bleibt die eine Quelle der Wahrheit für Parameter und
ihre Defaults (siehe [Stellschrauben und ihre Wirkung](./data-indexing-rag.md#stellschrauben-und-ihre-wirkung));
sie wird hier nicht dupliziert, nur je Schritt referenziert. Quelle des Codes ist
`backend/src/main/java/io/opaa/retrieval/`: der Rahmen (`RetrievalPipeline`, Kontext, Zustand, Protokoll),
der Einstieg `KnowledgeRetrieval` und `QueryProperties` im Wurzelpaket, die `*Stage`-Klassen in `scope/`,
`search/` und `ranking/`, die Verdrahtung in `config/`. Die Antwort baut die Fassade `QueryService` in
`backend/src/main/java/io/opaa/query/` darauf auf.

---

## Die Pipeline als benannte Stufen (#1046)

Seit Arbeitspaket 1 von [Hybride Suche mit Reranking](./hybrid-retrieval.md#arbeitspaket-1-die-pipeline-als-benannte-stufen)
sind die Schritte 1 bis 6 keine Verzweigungen in `QueryService` mehr, sondern **registrierte Stufen** mit
einer gemeinsamen Schnittstelle (`RetrievalStage`): Kandidatenlisten rein, Kandidatenlisten raus, plus
Erklärprotokoll als Pflicht-Rückgabewert. Drei Eigenschaften sind baulich zugesichert und nicht bloß
verabredet:

- **Keine Stufe verändert den Rechtekontext.** Suchbereich und Parameter stehen im unveränderlichen
  `RetrievalContext`; eine Stufe bekommt ihn nur lesend.
- **Keine Stufe sieht mehr Kandidaten, als ihr übergeben wurden.** Nur eine Suchstufe erweitert den
  Kandidatenpool (`RetrievalState#withSearchResults`); alle übrigen schöpfen daraus.
- **Keine Stufe kann stumm bleiben.** `StageOutcome` hat keinen Konstruktor ohne `StageExplanation`.

**Die Reihenfolge steht an genau einer Stelle**: `RetrievalConfiguration#retrievalPipeline`. Dort werden auch
der Volltextpfad (AP 2) und die Reranking-Stufe (AP 4) eingehängt.

**Jede Stufe ist einzeln abschaltbar** über `opaa.query.pipeline.disabled-stages` (Ebene-1-Wert, in keiner
Administrationsoberfläche); die abgeschaltete Stufe entfällt dann vollständig, statt neutralisiert
mitzulaufen — der Unterschied zweier Läufe ist so der Beitrag der Stufe und nicht der Unterschied zweier
Codepfade. Einzige Ausnahme ist die Stufe `SEARCH_SCOPE`: „ohne Rechtefilter" ist keine Messvariante,
sondern eine Rechteumgehung, und wird beim Start abgelehnt
([Durchsetzung zur Abfragezeit](./spaces-and-assets.md#durchsetzung-zur-abfragezeit)).

**Für den Benchmark ist der Schalter (noch) nicht zugelassen.** Der Harness weist eine nicht-leere Menge
ab (`PipelineHarnessSupport#requireMeasurableConfiguration`): Kein Feld eines Pipeline-Reports hält fest,
welche Stufen liefen — ein Lauf mit abgeschalteter Stufe trüge denselben `runConfiguration`-Abdruck wie ein
vollständiger, und seine Zahlen würden gegen die committete Baseline als Codeänderung verbucht. Die
Stufen-Auswahl zur Messgröße zu machen ist ein bewusster Vertragsnachtrag (neuer Fixpunkt, erhöhte
Vertragsversion, neu gezogene Baselines), keine Property-Entscheidung.

**Das Erklärprotokoll** (`RetrievalExplanation`) enthält je registrierter Stufe einen Eintrag — auch für
eine abgeschaltete oder nicht erreichte Stufe — mit hereingekommenen, hinausgegangenen und verworfenen
Kandidaten, Begründung aus einem festen Vokabular (`VerdictReason`) und dem stufeninternen Wert
(Ähnlichkeit, RRF-Score, Verdrängungsstufe der Dokument-Vervollständigung). Erzeugt wird es immer; ob es
festgehalten wird, entscheidet der Aufrufer: `QueryService#query` verwirft es, die Admin-Diagnose
([#1053](https://github.com/criew/opaa/issues/1053)) wertet es aus.

Die Nummerierung der folgenden Schritte ist zugleich die Reihenfolge der registrierten Stufen.

---

## Teil 1: Ablauf einer Anfrage

Eine Anfrage (`QueryService#query`) durchläuft die folgenden Schritte der Reihe nach; die Schritte 1
bis 6 einschließlich 3b sind die registrierten Stufen der `RetrievalPipeline`, Schritt 7 liegt außerhalb. Jeder Schritt
arbeitet nur mit dem, was der vorherige ihm übergibt — kein Schritt weitet die Berechtigungs- oder
Ähnlichkeitsschwellengrenze eines früheren wieder auf (die Kandidatenmenge selbst schöpft Schritt 6
weiterhin aus dem in Schritt 3 gebildeten Pool, siehe dort).

### 1. Scope-Bestimmung

Stufenname: `SEARCH_SCOPE`. `QueryService#query` unterscheidet zunächst zwei Fälle: eine **persistierte Chat-Anfrage** (`chatId` löst
über `ChatService#findOwnedChat` auf einen vom Aufrufenden selbst angelegten Chat auf) oder eine
**ephemere Anfrage** (kein `chatId`, oder er löst nicht auf). Bei einem persistierten Chat bestimmt
`ChatService#effectiveLibraryScope` den Suchbereich aus dessen eigenem `useKnowledge`/
`referencedLibraryIds` — die Request-Parameter `useKnowledge`/`requestedLibraryIds` werden dann ignoriert,
nicht nur überschrieben. Eine ephemere Anfrage hat keinen Space und durchsucht **kein Wissen**, gleich
was `useKnowledge` und `requestedLibraryIds` sagen (`SearchScopeResolver#resolveSearchScope`); ihre
Antwort trägt `noSpaceContext`. Jede Anfrage der Weboberfläche braucht einen Space-Bezug; ohne Space
suchen nur externe KI-Werkzeuge und `POST /api/v1/search` ([ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md)).
Die lesbaren Bibliotheken selbst liefert
`LibraryAccessService#readableLibraryIds`; der resultierende Suchbereich (`searchScope`, eine Menge von
Bibliotheks-Kennungen) wird als `library_id IN (...)`-Filter (`SearchScopeStage#libraryFilter`) **Teil der
Vektorabfrage selbst**, nicht ein Filter auf dessen Ergebnis (siehe
[Durchsetzung zur Abfragezeit](./spaces-and-assets.md#durchsetzung-zur-abfragezeit) sowie die
Javadoc-Begründung an `QueryService#query`). Ein leerer Suchbereich überspringt die Schritte 2–6
vollständig (`relevantChunks = List.of()`, keine der dortigen Aufrufe läuft) — Schritt 7 läuft trotzdem,
mit null Chunks im Kontext, und markiert das Ergebnis mit dem Grund für den leeren Suchbereich:
`QueryOutcome#noSpaceContext` (kein eigener Chat, also kein Space), `noKnowledgeAssignedToSpace` (dem
Space ist kein Wissen zugeordnet), `noKnowledgeAvailableInSpace` (zugeordnet, aber nichts lesbar) oder
`answeredWithoutKnowledge` (Chip-Leiste bewusst geleert); höchstens eines davon ist gesetzt.

**Die Zuordnung als Grenze ([ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md)):**
Für einen persistierten Chat ist der Suchbereich höchstens das, was dem Space des Chats zugeordnet ist,
geschnitten mit den lesbaren Bibliotheken — mit @Space-Wissen genau diese Menge, mit konkreten Chips ihr
Ausschnitt. Einen Rückfall auf alle lesbaren Bibliotheken gibt es nicht: Hat der Space keine Zuordnung,
ist der Suchbereich leer, und die Antwort trägt eines von zwei getrennten Signalen
(`noKnowledgeAssignedToSpace` für „kein Wissen zugeordnet", `noKnowledgeAvailableInSpace` für „nichts
davon lesbar", siehe [Suchbereich je Chatart](./spaces-and-assets.md#suchbereich-je-chatart)). Die
ephemere Anfrage ohne Chat durchsucht nichts und trägt `noSpaceContext` (siehe oben). Die
Filteroptionen eines noch nicht angelegten Chats (`GET /api/v1/search/metadata-filter-options` mit
`spaceId`) folgen derselben Regel über `ChatService#draftLibraryScope`; ohne Chat und Space sind sie
leer.

### 1b. Metadatenfilter (#1070)

Stufenname: `METADATA_FILTER`. Direkt hinter der Scope-Bestimmung trägt `MetadataFilterStage` den Kernfeld-Filter der Person oder
des Chats (`MetadataFilter`: Dokumentart-Codes, Datumsfenster) in den Lauf — bei einem persistierten
Chat dessen eigener Filter (`chats.metadata_filter`), sonst der aus der Anfrage. Die Stufe übersetzt
ihn einmal in die `Filter.Expression` des Vektorpfads und reicht beide Formen im Zustand weiter; die
Suchstufen 3 und 3b hängen ihn **innerhalb ihrer Abfrage** mit UND unter den Rechtefilter, der
Volltextpfad als SQL über dieselben Chunk-Schlüssel (`doc_type`, `doc_date`, `doc_date_precision`).
Ein Dokument ohne Wert im gefilterten Feld bleibt in beiden Pfaden enthalten und wird in Schritt 7 am
`SourceReference` als `NO_VALUE` („ohne Angabe") gekennzeichnet. Semantik und Tests: [Metadatenschema,
Umgesetzt (#1070, Teil 1)](./metadata-schema.md#umgesetzt-1070-teil-1).

### 2. LLM-Teilfragen-Zerlegung/Reformulierung

Stufenname: `SUB_QUERY_DECOMPOSITION`. `SubQueryDecompositionStage` schneidet zuerst aus dem
Gesprächsfenster des Laufs (`RetrievalContext#conversationHistory`) das **Suchfenster**: die letzten
`opaa.query.search-window-turns` (`OPAA_QUERY_SEARCH_WINDOW_TURNS`, Default `2`) Runden, eine Runde
sind zwei Nachrichten; `0` heißt „nur die Frage". Der Schnitt liegt bewusst **in der Stufe** und
nicht bei ihrem Aufrufer — sonst suchten Diagnosewerkzeug und Messpfad, die die Pipeline selbst
starten, mit einem Fenster, das die Produktion nie verwendet.

Ist `opaa.query.query-decomposition-enabled` (`OPAA_QUERY_DECOMPOSITION_ENABLED`, Default `true`)
aktiv, ruft die Stufe `QueryDecompositionService#decompose` auf: Frage und Suchfenster gehen als ein
`DecompositionContext` an das systemweit aktive Chat-Modell (`ActiveChatModelResolver`, dieselbe
Anbindung wie die Antwortgenerierung), das 1 bis `opaa.query.max-sub-queries`
(`OPAA_QUERY_MAX_SUB_QUERIES`, Default `3`) eigenständige, vollständige Suchanfragen zurückgibt und
dabei Folgefragen kontextuell auflöst sowie Tippfehler normalisiert. Frage und Suchfenster
erreichen das Modell seit #1684 als **ein beschrifteter Textblock** in einer Nutzernachricht, nicht
als Folge von Nutzer- und Assistentennachrichten; als Gespräch gelesen setzte ein Chat-Modell den
Verlauf fort und lieferte Antwortsätze statt Suchanfragen. Findet das Modell in der Nachricht nichts
zu suchen (Wunsch zur Antwortform, Angabe zur Person, Dank), antwortet es mit dem Signalwort
`KEINE_SUCHE`: `decompose` liefert dann eine **leere Liste**, die Stufe sucht trotzdem mit der
Rückfall-Suchanfrage aus `buildSearchQuery` und setzt `searchNeeded = false` im Zustand (Notiz
„decomposition found nothing to search for: single-query fallback, answer told so", Zähler
`opaa.query.decomposition.no-search`). `QueryService` reicht das Flag an die Antwortgenerierung
weiter, die vor den Kontextdokumenten die Anweisung für Nachrichten ohne Suchbedarf einfügt; Chunks,
Quellen und durchsuchte Bibliotheken bleiben wie bei jeder Suche. Führende Beschriftungen des
Textblocks („Aktuelle Nutzerfrage:") werden aus Teilfragen geschnitten, reine Beschriftungszeilen
verworfen. Steht das Signalwort neben einer Suchanfrage, wird mit der Suchanfrage gesucht. Scheitert der Aufruf
(Zeitüberschreitung, kein aktives Modell, unparsebare oder leere Antwort), liefert `decompose`
**kein** Ergebnis (`Optional.empty()`), und `SubQueryDecompositionStage#buildSearchQuery` übernimmt
als Rückfallebene: die reine
Frage, oder — wenn das Suchfenster eine Vorrunde enthält — die **letzte** Nutzernachricht dieses
Fensters, vorangestellt. Dasselbe gilt seit #1254 für eine **degenerierte** Ausgabe: Eine Teilfrage
ohne Wortbezug zum Zerlegungskontext hat die Nutzerfrage ersetzt statt sie umzuformulieren. Ist auch
nur eine Teilfrage ohne Bezug, greift dieselbe Rückfallebene für die ganze Zerlegung — mit WARN-Log
(Zählwerte, keine Inhalte) und dem Zähler `opaa.query.decomposition.fallback` (`reason` =
`degenerate`/`pruned`/`failed`), nie stillschweigend. Details, Grenzen des Wächters, Diagramm und die
Vorher/Nachher-Messung stehen in
[Teilfragen-Zerlegung und Query-Reformulierung](./data-indexing-rag.md#teilfragen-zerlegung-und-query-reformulierung-multi-query-retrieval-923).

**Ankerraum-Invariante (#1486).** Der Wächter ankert gegen `DecompositionContext#contextTexts()` —
genau das Material, das dieselbe Instanz dem Modell rendert (Suchfenster, Frage und gerenderte
Kontextbausteine), nie gegen eine getrennt gepflegte Aufzählung von Quellen. Ein künftiger
Kontextbaustein — die `RAHMEN`-Punkte der Gesprächsnotiz, [conversation-memory.md](./conversation-memory.md),
Bauteil 2 — erweitert damit Modellkontext und Ankerraum in einem Schritt.

**Noch offen (Epic #1482, ADR-0031):** die Gesprächsnotiz selbst. Bis dahin besteht der
Zerlegungskontext aus Frage und Suchfenster.

### 3. Vektorsuche je Teilfrage

Stufenname: `VECTOR_SEARCH`. `VectorSearchStage` ruft für **jede** Suchanfrage aus Schritt 2 eine eigene
Vektorabfrage (`VectorChunkSearch#similaritySearch`) gegen PostgreSQL/pgvector auf,
mit identischem Rechtefilter (`searchScope` aus Schritt 1) und identischer Ähnlichkeitsschwelle für jede
Teilfrage. Parameter: `opaa.query.fetch-k` (`OPAA_QUERY_FETCH_K`, Default `25`) Kandidaten je Aufruf,
`opaa.query.similarity-threshold` (`OPAA_QUERY_SIMILARITY_THRESHOLD`, Default `0,3`) als Mindest-Kosinus-
Ähnlichkeit — ein Chunk unterhalb der Schwelle wird gar nicht erst zum Kandidaten. Eine Einthemen-Frage
ohne Kontextbezug decodiert typischerweise zu genau einer Suchanfrage; dieser Fall läuft denselben Pfad
wie vor #923, nur ohne die vorangestellte Verlaufs-Heuristik. Details zu beiden Parametern in der
Stellschrauben-Tabelle. Mit HNSW-Index läuft die Abfrage als iterativer Indexscan, damit der Filter
nicht erst auf den ersten `hnsw.ef_search` Kandidaten greift; eine Liste unter `fetch-k` nennt das
Erklärprotokoll ([Hybride Suche, #2345](./hybrid-retrieval.md#rechtefilter-im-vektorpfad-und-der-hnsw-index-2345)).

### 3b. Volltextsuche je Teilfrage (#1048/#1049)

Stufenname: `FULL_TEXT_SEARCH`. `FullTextSearchStage` führt für **jede** Suchanfrage aus Schritt 2 eine PostgreSQL-Volltextabfrage gegen
`chunk_full_text` aus — mit **identischem Rechtefilter** wie Schritt 3 (`library_id = ANY(...)` als Teil
der `WHERE`-Klausel, nie ein Nachfilter,
[Durchsetzung zur Abfragezeit](./spaces-and-assets.md#durchsetzung-zur-abfragezeit)) und identischem
`opaa.query.fetch-k`. Sortiert wird nach `ts_rank`.

**Die Suchanfrage wird genauso gebaut wie der Index** (`FullTextChunkSearch`), aus zwei Hälften:

- die `german`-Analysekette über die Wörter der Frage, ODER-verknüpft (`to_tsquery('german', 'w1 | w2 | …')`).
  ODER, nicht UND: Der Pfad liefert Kandidaten für eine Fusion; eine Frage, deren sämtliche Wörter
  vorkommen müssten, träfe nichts.
- die **unzerlegten Kennungs-Tokens** (`FullTextIdentifiers`), ODER darübergelegt. Sie tragen im Index das
  Gewicht `A` gegen das `D` des Fließtexts — der Mechanismus, der „§ 34" und „§ 35" in einer Rangliste mit
  Konkurrenz auseinanderhält und nicht nur im Treffer. Erkannt werden Paragrafenverweise (auch
  Aufzählungen hinter `§§`), gerichtliche Aktenzeichen sowie Dienstanweisungs-, Formular-, Erlass- und
  Drucksachennummern — jedes schlüsselwortgeführte Muster mit einem schlüsselwortfreien Gegenstück,
  weil ein Dokument die Nummer hinter einem Schlüsselwort nennt und eine Frage sie nackt.

Beide Hälften entstehen aus bereinigten Tokens (Wörter auf Buchstaben und Ziffern reduziert,
Kennungs-Lexeme per Konstruktion ASCII-alphanumerisch); kein Zeichen der Nutzerfrage erreicht
`to_tsquery` als Operator.

**Ein Tor, verengend:** `opaa.query.full-text-search-enabled` (`OPAA_QUERY_FULL_TEXT_SEARCH_ENABLED`,
Ebene-1-Wert, Default `true`). Jede Bibliothek des Rechtebereichs wird sonst durchsucht; das frühere
Backfill-Tor ist mit #1270 entfallen (siehe
[Arbeitspaket 2a](./hybrid-retrieval.md#arbeitspaket-2a-volltextspalte-index-und-füllstand)). Auf dem
regulären Schreibweg entsteht der Volltexteintrag in derselben Transaktion wie der Vektor, ein halb
gefüllter Index also nicht. Zurückliegen kann allein die **Fassung** einer Zeile, nach einem Bump von
`content_tsv_version`: Solche Zeilen bleiben findbar, nur ohne die neuen Lexeme
([ADR-0028](../decisions/0028-tsv-version-uebergang.md)). Die Administrationsseite weist diesen
Rückstand je Bibliothek als „Nachzug ausstehend" aus; das Erklärprotokoll der einzelnen Suche nennt
ihn seit #1429 nicht mehr — er ist ein Bestandszustand, kein Anfragezustand.

**Ein Fehlschlag der Volltextabfrage lässt den Lauf scheitern** (seit #1429), wie ein Fehlschlag der
Vektorsuche: Das frühere Weiterlaufen sicherte gegen eine fehlende Volltextspalte ab, und eine halbe
hybride Suche gäbe eine schlechtere Antwort als normale aus.

**Eingangsliste der Fusion (#1049).** Die Listen dieser Stufe werden im Pipeline-Zustand
weitergereicht, genau wie die der Vektorsuche, und in Schritt 5 rangbasiert mit ihnen zusammengeführt.
Die Stufe ist damit — neben der Vektorsuche — die zweite, die dem Lauf Kandidaten hinzufügt; alles,
was sie hinzufügt, hat denselben Rechtefilter passiert, und der Kandidatenpool bleibt die Obergrenze
für Schritt 6. Abgeschaltet (`opaa.query.full-text-search-enabled=false`) ist die Stufe die Identität:
Die Auswahl ist dann bit-identisch zu der ohne sie — die Messvariante `vector-only`.

### 4. MMR-Auswahl je Teilfrage

Stufenname: `MMR_SELECTION`. `MmrSelectionStage` sortiert die `fetch-k` Kandidaten **jeder Liste einzeln** — je Teilfrage und je
Suchpfad, nie über die zusammengeführte Gesamtmenge — mittels `MmrSelector#select` nach Vielfalt um
und **kürzt keine Liste**: Alle `fetch-k` Kandidaten erreichen die Fusion, die allein auf das
Kandidatenbudget kürzt (Schritt 5). Bis [#2620](https://github.com/criew/opaa/issues/2620) kürzte die
Stufe jede Liste auf das Kandidatenbudget, auch bei `mmr-lambda` 1,0; `fetch-k` wirkte dadurch wie
`top-k`, und ein Einzeltreffer auf Listenrang 21 bis 25 erreichte die Fusion nie. Steuernder Parameter
ist `opaa.query.mmr-lambda` (`OPAA_QUERY_MMR_LAMBDA`, Default `1,0`). Bei `1,0` ist der Diversitätsterm
null, die Umsortierung wäre die Identität: Die Stufe läuft dann nicht und steht im Erklärprotokoll als
`DISABLED`. Bei `mmrLambda < 1,0` wird die paarweise Ähnlichkeit über die **echten
Chunk-Embeddings** berechnet (Kosinus-Ähnlichkeit), die `ChunkEmbeddingLookup` in einem einzigen,
über alle Teilfragen gepoolten SQL-Roundtrip direkt aus der pgvector-Tabelle nachliest (`id::text = ANY(?)`
gegen `spring.ai.vectorstore.pgvector.*`) — kein zusätzlicher Aufruf beim Einbettungsdienst. Details, Messwerte und die Begründung des Defaults in
[MMR](./data-indexing-rag.md#stellschrauben-und-ihre-wirkung).

### 5. Reciprocal Rank Fusion

Stufenname: `RANK_FUSION`. `RankFusionStage` führt über `CandidateFusion` die vollständigen `fetch-k`-Ranglisten je Teilfrage und
**je Suchpfad** zu einer einzigen zusammen — seit #1049 also zwei Listen je Teilfrage, die der
Vektor- und die der Volltextsuche. Im Auslieferungsstand erhält jeder Chunk je Liste, in der er vorkommt, den Beitrag
`1 / (60 + Rang)` (Rang 1-basiert, Dämpfungskonstante 60 nach Cormack et al.), die Beiträge einer
Teilfrage werden summiert, und die Endauswahl wird **reihum über die Teilfragen** gefüllt (Quote je
Teilfrage, unten). Die fusionierte Liste wird auf das Kandidatenbudget gedeckelt (`top-k` ohne
Reranking, `rerank-candidate-count` mit — die Deckelung auf `top-k` übernimmt dann Schritt 5b). Keine Gewichtung je Pfad: RRF ist
tuningfrei, und ein ungetuntes Gewicht kippt die Suche unbemerkt auf eine Modalität
(siehe [Hybride Suche mit Reranking](./hybrid-retrieval.md#arbeitspaket-3-fusion)).

**Quote je Teilfrage und Wertefusion (#2619).** Die frühere Fusion summierte die Beiträge über die
Listen **aller** Teilfragen. Das belohnt Überschneidung statt Aspektabdeckung: Bei fast wortgleichen
Teilfragen sammeln Übersichtsdokumente, die jede Teilfrage trifft, bis zu sechs Beiträge, ein
aspektgenauer Treffer nur einen. In der Verlustanalyse am Testkorpus Finanzverwaltung (s3-06) fielen so
die Treffer zweier Teilfragen von Rang 1 und 2 ihrer eigenen Liste auf Rang 21 bis 32 der Fusion, also
aus `top-k`. Zwei Stellschrauben, beide Ebene-1-Werte unter `opaa.query.fusion.*`:

- **`sub-query-quota`** (`OPAA_QUERY_FUSION_SUB_QUERY_QUOTA`, Default `true`): Jede Teilfrage wird für
  sich fusioniert (ihre Vektor- und ihre Volltextliste), danach füllt die Stufe die Auswahl reihum: Jede
  Runde nimmt von jeder Teilfrage den besten noch nicht gewählten Kandidaten, innerhalb der Runde nach
  fusioniertem Wert sortiert. Ein Chunk, den mehrere Teilfragen führen, wird von der ersten genommen,
  die ihn erreicht. Mit einer Suchanfrage ist das die Identität; die Messpfade ohne Zerlegung bewegt
  die Quote deshalb nicht. `false` ist die Fusion von vor #2619.
- **`method`** (`OPAA_QUERY_FUSION_METHOD`, Default `rrf`): `score` ersetzt den Rangbeitrag durch eine
  konvexe Kombination normierter Werte. Eine Kosinus-Ähnlichkeit wird von der Ähnlichkeitsschwelle
  (0) bis 1 (1) skaliert und mit `vector-weight` (`OPAA_QUERY_FUSION_VECTOR_WEIGHT`, Default `0,7`)
  gewichtet, ein `ts_rank` relativ zum besten Wert seiner Liste mit dem Rest. Ein Treffer mit Kosinus
  0,82 zählt dann mehr als einer mit 0,45 auf demselben Rang.

- **`guaranteed-per-list`** (`OPAA_QUERY_FUSION_GUARANTEED_PER_LIST`, Default `1`, 0 bis 10, #2634):
  die fest übernommenen Spitzentreffer, unten.

**Warum RRF mit Quote der Default ist.** Die Quote hatte als einzige Variante schon vor der Messung
einen Beleg: In der Simulation der Verlustanalyse brachte sie 144 statt 139 von 220 Kernfakten bei
praktisch gleich großem Kontext. Die Messung vom 14.10.2026 auf der FV-Arbeitsmenge (20 Fragen × 3
Läufe, Bericht im Korpus-Repo unter `testlaeufe/2026-10-14-retrieval-umbau/`, Zusammenfassung in
[#2386](https://github.com/criew/opaa/issues/2386)) widerspricht dem nicht: Die neuen Defaults (RRF
mit Quote, zusammen mit den übrigen Änderungen des Retrieval-Umbaus) hielten den Stand (36/60 Läufe mit
allen Kernfakten im Kontext, 84 von 132 Kernfakten). Isoliert gemessen ist die Quote damit nicht; sie
löste s3-06 und kostete ue-04. **Die Wertefusion**
(`method=score`, `vector-weight` 0,7) war gleich gut oder schlechter (30/60, 81 von 132). Sie verliert
Treffer, die nur ein Suchpfad findet: Ein Chunk auf Volltextrang 2 landete auf Fusionsrang 26
(s3-11), ähnlich ust-09 und lw-11. Ein ungemessenes Gewicht ist genau das, wovor
[Arbeitspaket 3](./hybrid-retrieval.md#arbeitspaket-3-fusion) warnt, und das gemessene hat nicht
geholfen. Die Wertefusion bleibt als Option gebaut und über den Variantenvergleich
`eval/variants/verwaltung-fusion.json` messbar, ist aber nicht voreingestellt.

**Fest übernommene Spitzentreffer (#2634).** Auch mit Quote verliert ein Treffer, den nur eine der
beiden Listen einer Teilfrage kennt, gegen jeden Chunk, den beide Listen um Rang 10 führen: ein
RRF-Beitrag von `1/61` gegen zwei von je etwa `1/70`. Die Messung zeigt das Muster an ue-06
(Antwort-Chunk auf Volltextrang 1, nur in einem von drei Läufen im Kontext), s3-13 (Volltextrang 22,
Fusionsrang 40) und ue-03; es ist der Großteil der verbliebenen Verluste an Fusion und `top-k`.
Deshalb nimmt die Stufe die besten `guaranteed-per-list` Kandidaten **jeder Liste** (je Teilfrage und
Suchpfad, nach einer etwaigen MMR-Umsortierung) fest in die Auswahl:

- `CandidateFusion` markiert jeden Kandidaten, der in mindestens einer Liste unter den ersten
  `guaranteed-per-list` steht (`FusedCandidate#guaranteed`); die fusionierte Reihenfolge ändert sich
  dadurch nicht. Ein Chunk, der mehrere Listen anführt, ist ein garantierter Kandidat.
- `RankFusionStage` wendet das Kandidatenbudget über `GuaranteedSelection` an: alle garantierten
  Kandidaten, die übrigen Plätze in fusionierter Reihenfolge, die Auswahl behält die fusionierte
  Reihenfolge. Übersteigen die garantierten allein das Budget, gewinnen die in der Fusion vorderen.
  Das gilt mit und ohne Quote, für `rrf` und `score`, und für das Budget `top-k` wie für das
  Reranking-Fenster.
- **Deterministisch** (ADR-0013): Die Auswahl hängt nur von der fusionierten Reihenfolge und den
  Listenrängen ab, nie von der Iterationsreihenfolge einer Menge; zwei Läufe auf denselben Listen
  liefern dieselben Verdikte.
- **Verhaltensneutral bei `0`:** Ohne garantierten Kandidaten ist die Auswahl der Kopf der
  fusionierten Liste, und die Stufe schreibt weder einen neuen Grund noch eine neue Notiz.
- Die Auswahl hält die garantierten Kandidaten als `RetrievalState#guaranteedChunkIds`. Der
  Relevanzschnitt (5c) entfernt sie nicht, die Dokument-Vervollständigung (6) verdrängt sie in keiner
  Stufe, und ein Reranking, dessen Endpunkt nichts bewertet, kürzt nach derselben Regel auf `top-k`
  (5b). Ein Reranking, das bewertet, ist nicht an sie gebunden.

**Warum der Default 1 ist** (Maintainer-Entscheidung). Mit höchstens drei Teilfragen und zwei
Suchpfaden gibt es bis zu sechs Listen; bei `1` belegen die Spitzentreffer also höchstens 6 der 20
Plätze und lassen der Fusion den größeren Teil. Bei `2` wären es bis zu 12 von 20, und Quote und
Fusion bestimmten nur noch den kleineren Teil der Auswahl. Die Wirkung misst der Koordinator lokal
auf der FV-Arbeitsmenge; der Wert ist danach anpassbar (Varianten `quota-rrf-no-guarantee` und
`quota-rrf-guarantee-2` im Variantenvergleich).

**Protokoll.** Jedes Verdikt der Stufe nennt die Listen, deren Beiträge seinen Wert ausmachen, mit
Rang und Beitrag (`CandidateVerdict#contributions`); die Beiträge ergeben zusammen den Wert. Gepoolt
sind das alle Listen, in denen der Kandidat vorkam, mit Quote nur die Listen der Teilfrage, die ihn in
die Auswahl gebracht hat (`fused · sub-query n`); Ränge in anderen Teilfragen zählen dort nicht und
stehen deshalb in den Verdikten der Suchstufen, nicht hier. Ein garantierter Kandidat jenseits des
Budgets trägt `GUARANTEED_LIST_TOP`, ein Kandidat innerhalb des Budgets, dessen Platz er belegt hat,
`DISPLACED_BY_GUARANTEED_LIST_TOP`; ein garantierter Kandidat, der ohnehin im Budget lag, bleibt
`WITHIN_BUDGET`. Der Rang eines behaltenen Kandidaten ist seine Position in der Auswahl, der eines
verworfenen sein Fusionsrang. Eine Notiz nennt, wie viele Kandidaten garantiert waren und wie viele
davon erst durch die Garantie in die Auswahl kamen. Die Fusion lehnt eine Liste ohne Herkunft
(Suchpfad und Teilfrage) mit einer Exception ab, statt sie still einer eigenen Gruppe zuzuordnen. Die
Notizen nennen Verfahren und Quote.
Dedupliziert wird per Chunk-Kennung
(`Document#getId()`), nie per Score — Ähnlichkeitswerte verschiedener Suchvektoren sind nicht vergleichbar,
genau die Wurzel des in [#912](https://github.com/criew/opaa/issues/912) dokumentierten Fehlerbilds; ein
Chunk, den beide Pfade finden, ist **ein** Kandidat mit zwei Beiträgen. Von zwei Instanzen desselben
Chunks überlebt die aus der **früheren** Liste, nicht die mit dem höheren Score: Seit dem zweiten
Suchpfad können das eine Kosinus-Ähnlichkeit und ein `ts_rank` sein, und die größere der beiden Zahlen
bedeutet nichts. Da die Pipeline ihre Listen in Stufenreihenfolge übergibt, ist die frühere die der
Vektorsuche — der angezeigte Relevanzwert bleibt damit eine Ähnlichkeit, außer bei einem Chunk, den nur
der Volltextpfad gefunden hat; was der Wert für diesen Fall bedeuten soll, ist offen
([#1102](https://github.com/criew/opaa/issues/1102)). Bei
genau einer Suchanfrage und abgeschaltetem Volltextpfad läuft die Stufe mit und ist dort nachweislich die Identität: innerhalb einer Liste
sind alle Ränge verschieden, die fusionierten Werte also streng fallend in der Listenreihenfolge, und die
Deckelung behält den Kopf der Liste. Diese Identität ruht seit #1458 auf dieser
Begründung und auf den Stufentests (`RankFusionStageTest`, `RetrievalPipelineTest`, `HybridFusionTest`),
nicht mehr auf einem Vergleich gegen die Vor-#1046-Orchestrierung: Der frühere `RetrievalPipelineParityTest`
ist entfallen, und der Protokoll-Diff des Eval-Harness (`eval/README.md`, Abschnitt „Protokoll-Dump")
prüft nur die Gleichheit zweier Stände desselben Verfahrens — mit abgeschalteter Zerlegung, also ohne
den Mehr-Teilfragen-Pfad.

### 5b. Reranking (#1050)

Stufenname: `RERANK`. `RerankStage` bewertet die fusionierte Kandidatenmenge mit der **Rerank-Modellrolle** neu und deckelt
sie wieder auf `top-k`. Die Stufe steht zwischen Fusion und Dokument-Vervollständigung: Die
Vervollständigung ergänzt Geschwister-Chunks bereits ausgewählter Dokumente und muss deshalb auf der
endgültigen Rangfolge arbeiten, nicht auf einer, die der Reranker gleich wieder umsortiert
(siehe [Hybride Suche mit Reranking](./hybrid-retrieval.md#arbeitspaket-4-reranking-als-modellrolle)).

**Die Rolle ist per Voreinstellung aus.** `OPAA_RERANK_ENABLED` ist ein eigener, expliziter Schalter,
getrennt von den Endpunktangaben `OPAA_RERANK_BASE_URL`, `OPAA_RERANK_MODEL` und
`OPAA_RERANK_API_KEY` — „Reranking aus" soll eine Aussage der Betreiberin sein und nicht das
ununterscheidbare Ergebnis einer fehlenden Konfigurationszeile. Ein Widerspruch (Schalter an, Rolle
unbelegt oder Endpunkt stumm) wird beim Start als Fehler geloggt und bleibt danach über
`RerankRoleStatusProvider#currentStatus()` abfragbar — dem schmalen Vertrag, den die
Administrationsseite ([#1053](https://github.com/criew/opaa/issues/1053)) liest. Die Suche
läuft weiter, aber nicht unbemerkt.

Die Kandidatenzahl steuert `opaa.query.rerank-candidate-count`
(`OPAA_QUERY_RERANK_CANDIDATE_COUNT`, Default `50`, Ebene-1-Wert). `0` schaltet die Stufe über ihren
eigenen Parameter ab, so wie `max-chunks-per-document = 1` die Dokument-Vervollständigung
abschaltet (dort seit #2502 der Default). Der Startwert 50 entspricht dem, was zwei Suchpfade bei
`fetch-k = 25` je Teilfrage überhaupt liefern können.

**Das Fenster vergrößert die Reichweite nicht.** Was keine Suchstufe zurückgegeben hat, kann weder
die Fusion noch der Reranker heben; die Reichweite ist `fetch-k` je Liste mal der Zahl der Listen im
Lauf (Teilfragen mal aktive Suchpfade). Wer die Fundstelle aus [#938](https://github.com/criew/opaa/issues/938)
— dort auf Rang 50 einer Vektorliste — allein über das Reranking erreichen will, muss `fetch-k`
mitheben; im Auslieferungsstand mit `fetch-k = 25` liegt Rang 50 einer einzelnen Liste außerhalb
dessen, was die Suche überhaupt liefert.

Angebunden wird über denselben Weg wie Chat und Einbettung: ein `POST {base-url}/rerank` mit
`{model, query, documents}`, das vLLM, Text Embeddings Inference, Infinity, Jina, Cohere und Voyage
gleichermaßen sprechen. **Ein Fehlschlag kostet die Reihenfolge, nie die Antwort**: Bleibt der
Endpunkt stumm, behält die Stufe die fusionierte Reihenfolge, stellt die `top-k`-Deckelung wieder her
und vermerkt im Erklärprotokoll den Status `UNAVAILABLE` statt so zu tun, als hätte sie entschieden.

**Diese Reihenfolge ist die des Laufs ohne Reranking.** Vor der Fusion hängt keine Stufe vom
Kandidatenbudget ab (seit [#2620](https://github.com/criew/opaa/issues/2620) kürzt die MMR-Stufe
nicht mehr), die fusionierte Liste ist also mit und ohne Reranking dieselbe. Die Stufe kürzt sie nach
der Regel der Fusion auf `top-k` (`GuaranteedSelection` mit `RetrievalState#guaranteedChunkIds`) und
erhält so die Auswahl der Konfiguration ohne Reranking, auch wenn das breitere Fenster einen
Spitzentreffer hinter Platz `top-k` gesetzt hatte. Ein Reranking, das bewertet, ordnet dagegen frei:
Der Reranker liest jeden Chunk mit der Frage und kennt die Schieflage der Rangfusion zwischen Ein- und
Zwei-Listen-Treffern nicht, gegen die die Garantie schützt. Ein Messlauf mit unterbrochenem Reranking
gilt trotzdem nicht als Messwert der Variante mit Reranking (siehe
[eval/variants/README.md](../../eval/variants/README.md)).

**Der Zustand der Rolle reist mit, nicht ein Ja/Nein.** `RetrievalContext#rerankAvailability`
unterscheidet „abgeschaltet", „an, aber nicht nutzbar" und „nutzbar", und das Erklärprotokoll führt
die ersten beiden getrennt: `DISABLED` für die Betreiberentscheidung, `UNAVAILABLE` für die Störung.
Ein leerer Kandidatensatz bekommt eine eigene Notiz statt der Behauptung, die Rolle sei nicht
nutzbar gewesen. **Das Diagnosewerkzeug liest denselben Rollenzustand wie der Chatpfad**, sonst
erklärte es eine Suche, die so nie gelaufen ist. Wird die Stufe über
`opaa.query.pipeline.disabled-stages` abgeschaltet, während die Rolle an ist, nimmt die Pipeline das
verbreiterte Kandidatenfenster mit zurück — sonst stellte niemand die `top-k`-Deckelung wieder her.

### 5c. Relevanzschnitt (#2622)

Stufenname: `RELEVANCE_CUTOFF`. `RelevanceCutoffStage` kürzt die Auswahl nach den Werten, nach denen sie
geordnet ist (`RetrievalState#selectionScores`: der fusionierte Wert aus Schritt 5, nach einem
erfolgreichen Reranking dessen Wert; ein Chunk ohne Wert zählt 0). Sie entfernt nur, ordnet nicht um,
unterschreitet nie `min-chunks` und kann `top-k` nicht überschreiten, weil Fusion und Reranking schon
darauf gedeckelt haben. Parameter unter `opaa.query.relevance-cutoff.*` (`RelevanceCutoffProperties`,
Ebene 1):

- `mode` (`OPAA_QUERY_RELEVANCE_CUTOFF_MODE`, Default `fixed`): `fixed` lässt die Stufe aus
  (`DISABLED`), `relative` behält, was mindestens `relative-threshold` (Default `0,6`) des besten Werts
  erreicht, `knee` schneidet vor dem ersten Chunk, dessen Wert um mindestens `knee-drop` (Default
  `0,25`) des besten Werts unter dem des Vorgängers liegt.
- `min-chunks` (Default `10`): die Untergrenze in beiden Verfahren.

Die Grenze wird auf den **nach Wert sortierten** Werten bestimmt (relativ: das Minimum aus Anteil des
besten Werts und dem Wert des `min-chunks`-ten Chunks; Knick: der Wert vor dem ersten großen Abfall)
und dann als Filter angewandt: Was darunter liegt, fällt weg, der Rest behält seine Reihenfolge. Das
ist nötig, weil die Auswahl nicht nach Wert geordnet sein muss, etwa wenn eine Quote je Teilfrage sie
reihum füllt. Gleichstände an der Grenze bleiben. Nach einem Reranking zählt ein Chunk, den das Modell
nicht bewertet hat (hinter dem Kandidatenfenster), mit 0 und wird deshalb geschnitten.

Ist der beste Wert nicht positiv (etwa rohe Logits eines Rerankers), schneidet die Stufe nicht und
sagt das. Die Notiz nennt in jedem Fall den Grund des Abbruchs; jedes entfernte Verdikt trägt
`BELOW_RELEVANCE_CUTOFF`.

**Fest übernommene Spitzentreffer bleiben** (`RetrievalState#guaranteedChunkIds`, Schritt 5): Ein
garantierter Chunk unter der Grenze wird behalten und trägt `GUARANTEED_LIST_TOP`, eine Notiz nennt
ihre Zahl. Begründung: Die Grenze wird am besten Wert über alle Teilfragen gemessen. Eine Teilfrage,
deren Werte insgesamt niedriger liegen, verlöre sonst als Erstes ihren Spitzentreffer, und der Schnitt
höbe die Quote je Teilfrage teilweise auf; genau das zeigte die Messung. Die Ausnahme gilt auch nach
einem Reranking, weil dessen Werte ebenfalls gegen die ganze Frage gemessen sind. Die Zahl nach dem
Schnitt (`RelevanceCut#budget`) schließt die behaltenen Spitzentreffer ein; da der Schnitt nur
entfernt, bleibt sie höchstens `top-k`.

**Der Schnitt bestimmt die Kontextgröße.** Läuft die Stufe (jeder Modus außer `fixed`), hält sie ihre
Entscheidung als `RetrievalState#relevanceCut` fest. Die Dokument-Vervollständigung (Schritt 6) nimmt
dann die Zahl nach dem Schnitt als Gesamtbudget statt `top-k` und zieht keinen geschnittenen Chunk aus
dem Kandidatenpool nach.

**Warum `fixed` der Default bleibt.** Die Verlustanalyse am Testkorpus Finanzverwaltung zeigte den Bedarf
(top-k 30 brächte 150 statt 139 von 220 Kernfakten bei 32 % mehr Kontext). Unter RRF sind die Werte
aber flach und rangabgeleitet: Ein Chunk, den beide Pfade fanden, erreicht etwa den doppelten Wert
eines Einzeltreffers, ein relativer Schnitt bei 0,6 trennt also vor allem diese beiden Gruppen und kaum
nach Passung. Die Messung vom 14.10.2026 (Schritt 5) bestätigt die Zurückhaltung. Gemessen wurde auf
Score-Fusion (`vector-weight` 0,7) gegen dieselbe Variante ohne Schnitt (K3 gegen K2): `relative` 0,6
mit `min-chunks` 10 sparte etwa ein Drittel der Tokens (Median 38.646 statt 58.370), verlor aber
deutlich Kernfakten (68 statt 81 von 132 im Kontext). Er misst am besten Wert über alle
Teilfragen, kürzte fast immer auf die Mindestzahl und hob dabei die Quote teilweise auf, indem er
Spitzentreffer einzelner Teilfragen entfernte; gemessen wurde ohne die Ausnahme für garantierte
Kandidaten oben. `knee` 0,25 schnitt fast nie. **Empfehlung: Wer Tokens sparen will, senkt eher
`top-k`.** Für einen Bereich wie
10–30 Chunks wird `top-k` auf 30 gesetzt; der Pipeline-Messpfad des Eval-Harness misst nur am
Produktionsfenster `top-k` 20 und kennt die Parameter des Schnitts nicht als Festpunkt.

### 6. Dokument-Vervollständigung

Stufenname: `DOCUMENT_COMPLETION`. `DocumentCompletionStage` (`DocumentCompletion#complete`, #932/#934/#935) läuft **zuletzt** und zieht ausschließlich
aus den bereits berechtigungs- und schwellenwertgefilterten Kandidaten von Schritt 3 nach — nie darüber
hinaus. Ziel: Ein Dokument, das mit einem Chunk bereits in der Auswahl vertreten ist, wird bis zu
`opaa.query.max-chunks-per-document` (`OPAA_QUERY_MAX_CHUNKS_PER_DOCUMENT`, Default `1`, bis #2502 `2`) Chunks aufgefüllt,
bevor ein Chunk eines anderen Dokuments den verbleibenden Platz bekommt. Der Wert ist ein
**Auffüllziel, keine Obergrenze**: Ein Dokument, das aus Fusion, MMR oder Reranking schon mit so
vielen oder mehr Chunks kommt, bekommt nichts dazu und wird nie auf den Wert gekürzt. Chunks
verlieren kann es nur, wenn ein anderes Dokument aufgefüllt wird, und dann unabhängig vom Wert:
Stufe 1 nimmt den schwächsten Chunk eines Dokuments mit mindestens zwei Chunks, das in diesem
Aufruf selbst nicht aufgefüllt wurde. Ein Dokument, das schon voll war, ist davor also nicht
geschützt (bei Wert `2` und `top-k = 4` wird `[A1, A2, A3, B1]` mit B2 im Pool zu `[A1, A2, B1, B2]`).
Ist es auf einen Chunk geschrumpft, kann Stufe 2 auch diesen verdrängen. Bei `1` läuft nichts davon,
die Auswahl bleibt unverändert. In der Endauswahl der
FV-Arbeitsmenge standen beim Default `1` bis zu sechs Chunks eines Dokuments
([#2623](https://github.com/criew/opaa/issues/2623)). Die Stufe ist die Abhilfe für den Fall, dass
der Gebühren-Chunk eines Dokuments in RRF/MMR gegen Chunks *anderer* Dokumente verlor, weil der
Einleitungs-Chunk desselben Dokuments dort besser rankte und dessen Platz belegte
(siehe [#932](https://github.com/criew/opaa/issues/932)). Zwei Verdrängungsstufen: **Stufe 1** entfernt den
auswahlrang-schwächsten Chunk eines anderen, bereits mit ≥2 Chunks vertretenen Dokuments — die
Dokumentvielfalt sinkt dabei nie unter das, was Fusion/MMR bereits hergestellt hatten. **Stufe 2** greift
nur, wenn Stufe 1 keine Quelle findet: Sie darf den auswahlrang-letzten Chunk der Gesamtauswahl verdrängen,
aber nur wenn das vervollständigende Dokument mit seinem besten Chunk strikt besser rankt als das Opfer,
gedeckelt auf `max(1, top-k / 4)` Verdrängungen je Aufruf — ohne diesen Deckel könnte eine
Acht-Themen-Auswahl in einem einzigen Aufruf auf eine Handvoll Dokumente schrumpfen. Zwei Schutzregeln
gelten stufenübergreifend: Ein in diesem Aufruf bereits vervollständigtes Dokument scheidet für ein
weiteres Dokument als Stufe-1-Quelle aus (`completedDocumentKeys`), und ein Chunk, den eine Vervollständigung
in diesem Aufruf hinzugefügt hat, kann in keiner der beiden Stufen selbst zum Opfer werden — eine
Vervollständigung kann eine frühere im selben Aufruf also nie rückgängig machen. Ebenso wenig wird
ein fest übernommener Spitzentreffer der Fusion (`RetrievalState#guaranteedChunkIds`, Schritt 5) zum
Opfer; Stufe 2 nähme sonst gerade ihn, weil ein garantierter Kandidat jenseits des Budgets am Ende
der Auswahl steht. `max-chunks-per-document
= 1` schaltet die Vervollständigung vollständig ab (Verhalten von vor #932). Seit
[#2502](https://github.com/criew/opaa/issues/2502) ist das der Default: Den Zusammenhang um einen
Treffer liefern die Nachbar-Chunks (6b), ohne einen Platz der Endauswahl zu belegen, und die
Vervollständigung hatte gut platzierte Fundstellen anderer Dokumente verdrängt.

### 6b. Nachbar-Chunks im Antwortkontext (#2374)

Keine Stufe der Pipeline, sondern der Schritt zwischen Endauswahl und Antwort:
`io.opaa.retrieval.context.NeighborChunkExpansion`, aufgerufen in `QueryService#query`. Zu jedem Chunk
der Endauswahl lädt `NeighborChunkLookup` die Chunks `chunk_index ± radius` desselben Dokuments
(`opaa.query.neighbor-chunks.radius`, `OPAA_QUERY_NEIGHBOR_CHUNKS_RADIUS`, Default `1`), in einer
SQL-Abfrage mit `library_id = ANY(Suchbereich)` in der Bedingung. Das Zeichenbudget
`max-added-characters` (Default `40000`) wird seit [#2621](https://github.com/criew/opaa/issues/2621)
in zwei Durchgängen verteilt. **Durchgang 1:** Jeder Treffer bekommt in Rangfolge einen
Abstand-1-Nachbarn, bevorzugt den folgenden (`chunk_index + 1`), sonst den vorangehenden — wenn der
folgende nicht existiert, außerhalb des Suchbereichs liegt oder nicht mehr ins Budget passt. Ein
Treffer, an den bereits ein Chunk des Kontexts grenzt (ein anderer Treffer oder ein schon geladener
Nachbar), bekommt in diesem Durchgang nichts. **Durchgang 2:** Das Restbudget geht nach Abstand und
Rang, erst alle übrigen Abstand-1-Nachbarn in der Rangfolge ihrer Treffer, dann die ferneren, jeder
nur neben einem schon vorhandenen Chunk. Ein Nachbar, der nicht mehr passt, wird übersprungen; ein
kürzerer dahinter kann noch passen. Vorher lief nur Durchgang 2, und bei erschöpftem Budget bekamen
die hinteren Treffer (Rang 18–20) keine Nachbarn mehr; in der Verlustanalyse am FV-Testkorpus
kostete das Kernfakten.
Das Ergebnis ist nach zusammenhängenden Ausschnitten geordnet: Aneinandergrenzende Chunks eines
Dokuments stehen in Indexreihenfolge, die Ausschnitte in der Rangfolge ihres besten Treffers.

Jeder Nachbar bleibt ein eigenes `Document` mit eigenem Chunk-Kopf, die Zitierfrage löst sich damit
ohne Sonderfall: Zitatvalidierung und Quellen-Mapping (Schritt 7) prüfen gegen genau die übergebene
Liste, ein Zitat auf einen Nachbarn ist gültig, und seine `chunkLocations` erscheinen an der
Quellenangabe seines Dokuments. Suchtreffer (`SearchService`), Erklärprotokoll, Diagnose und
Retrieval-Harness sehen die Nachbarn nicht, weil sie die Pipeline ohne diesen Schritt ausführen.
Messung auf dem Testkorpus Finanzverwaltung (Arbeitsmenge v1, 20 Fragen × 3 Läufe): Nachbarn ±1
zusammen mit `max-chunks-per-document = 1` brachten 36 statt 18 richtige von 60 Antworten, bei rund
+63 % Tokens je Antwort ([#2374](https://github.com/criew/opaa/issues/2374)).

### 7. Antwortgenerierung, Zitatvalidierung, Quellen-Mapping

`AnswerGenerationService#generateAnswer` formatiert die final ausgewählten Chunks als Kontextblock (mit
`document_id`, `chunk_index` und Dateiname je Chunk-Kopf) und ruft das systemweit aktive Chat-Modell auf,
mit der Zitierpflicht `【source: <document_id>#<chunk_index> | <file_name>】` im Systemprompt. Danach
extrahiert `CitationParser` die im Antworttext vorkommenden Zitate, und `CitationValidator#validate` prüft
deterministisch (kein zweiter Modellaufruf) für jedes Zitat, ob Dokument-Kennung, Abschnittsnummer und
Dateiname zu ein und demselben tatsächlich abgerufenen Chunk passen. Ein so gültiges Zitat wird
zusätzlich inhaltlich geprüft (Stufe 1, #937, geschärft im #939-Review): `CitationFactChecker`
extrahiert aus dem Satz vor dem Zitatmarker den nächstgelegenen harten Fakt (Geldbetrag, Datum,
Paragraphen-Referenz, sonstige Zahl mit Tausendertrennzeichen oder Dezimalkomma) und vergleicht ihn
nach Gattung normalisiert gegen den Text aller abgerufenen Chunks des zitierten Dokuments (nicht nur
des einen vom Marker genannten Chunks, wegen der #932-Dokumentvervollständigung) — fehlt ein Fakt
derselben Gattung dort völlig, bleibt das Zitat unangetastet; nur ein abweichender Wert derselben
Gattung stuft zurück. Ein Satz mit einer Näherung oder Summe ("rund", "etwa", "zusammen" u. Ä.) sowie
ein Satz ohne extrahierbaren Fakt bleiben ebenfalls unangetastet (bewusst konservativ: ein fälschlich
geflaggtes korrektes Zitat wiegt schwerer als ein übersehener Fehler). Eine LLM-gestützte
Stützungsprüfung (Stufe 2) ist als möglicher Folgeausbau denkbar, aber nicht Teil dieser Prüfung.
Ungültige Zitate werden im Antworttext belassen, aber in der zugehörigen `ChatSource` als
`citationValid = false` markiert
(`QueryService#mapSources`). Details zur Belegvalidierung stehen unter
[Belegvalidierung](./data-indexing-rag.md#belegvalidierung).

**Was `relevanceScore` bedeutet (#1102).** Der Relevanzwert einer Quellenangabe ist der **Kehrwert
ihrer Position in der Quellenliste der Antwort** — 1,0 für die erste Quelle, 0,5 für die zweite,
0,33 für die dritte —, **keine Ähnlichkeit**. Gezählt werden Quellen, nicht Chunks: Ein Dokument
kann mit mehreren Chunks in der Auswahl stehen, belegt in der Quellenliste
aber genau eine Position — die Rangvergabe erfolgt deshalb erst, nachdem die Chunks je Dokument zu
einer Quellenangabe zusammengefasst sind, und die Rangfolge ist lückenlos. Der Rohwert eines Chunks
(`Document#getScore()`) taugt dafür seit #1049 nicht mehr: Ein Chunk, den nur der lexikalische Pfad
gefunden hat, trägt einen `ts_rank` (grob 0,03–0,1), einer aus der Vektorsuche eine
Kosinus-Ähnlichkeit (grob 0,3–0,9); die beiden Skalen sind nicht vergleichbar, und eine nach ihnen
sortierte Belegliste hätte eine rein lexikalisch gefundene Fundstelle selbst dann ans Ende gestellt,
wenn die Fusion sie auf Rang 1 gesetzt hat. Der Rang dagegen bedeutet für jede Fundstelle dasselbe,
unabhängig vom Pfad. Eine synthetische Quellenangabe zu einem erfundenen Beleg (#386) hat gar keinen
Rang und trägt weiterhin 0. Das Belegfenster (`SourceEvidenceDrawer`) sortiert entsprechend nach der
Reihenfolge des `sources`-Arrays — also nach der Auswahlreihenfolge der Pipeline, nicht nach einem
Zahlenwert — und beschriftet die Zeile mit „Rang n" statt mit einem Prozentgewicht. Auch die
Beschriftung leitet sich allein aus der Position in `sources` ab, nicht aus dem Zahlenwert und nicht
aus der Zeilenposition im Belegfenster: Die Liste stellt zitierte Quellen vor unzitierte, während
„Rang n" dieselbe Position meint, deren Kehrwert `relevanceScore` trägt. So bleibt die Beschriftung
auch für vor #1102 gespeicherte Nachrichten richtig, deren `sources`-JSON noch Rohwerte trägt.

### Zusammenfassung als Ablauf

```
Frage + Gesprächsfenster
        ↓
1. Scope-Bestimmung (persistierter Chat / ephemer, Rechtefilter)
        ↓
2. Suchfenster schneiden (search-window-turns Runden), LLM-Teilfragen-Zerlegung
   (1..max-sub-queries Suchanfragen, Fallback: letzte Nutzerfrage des Suchfensters + Frage)
        ↓
3. Je Suchanfrage: Vektorabfrage (bis zu fetch-k Kandidaten, Rechtefilter + Schwelle)
        ↓
3b. Je Suchanfrage: Volltextsuche (fetch-k, identischer Rechtefilter)
        ↓
4. Je Liste (Teilfrage × Suchpfad): MMR-Umsortierung ohne Kürzung (mmr-lambda; bei 1,0 übersprungen)
        ↓
5. Fusion je Teilfrage (RRF oder Werte), Auswahl reihum über die Teilfragen, die besten
   guaranteed-per-list jeder Liste fest, gedeckelt auf das Kandidatenbudget
        ↓
5b. Reranking der Kandidatenmenge durch die Rerank-Modellrolle, zurück auf top-k
    (per Voreinstellung aus: OPAA_RERANK_ENABLED)
        ↓
5c. Relevanzschnitt nach Wert (per Voreinstellung aus: relevance-cutoff.mode fixed)
        ↓
6. Dokument-Vervollständigung (max-chunks-per-document, zweistufige Verdrängung)
        ↓
6b. Nachbar-Chunks nachladen (neighbor-chunks.radius, Zeichenbudget; nur für die Antwort)
        ↓
7. Antwortgenerierung, Zitatvalidierung, Quellen-Mapping
```

Die Schritte 1 bis 6 sind auch ohne Schritt 7 aufrufbar: `RetrievalContextFactory#contextFor`
baut den Kontext eines Laufs mit Produktionsparametern und Rerank-Zustand, `RetrievalPipeline#run`
führt ihn aus. Über genau diesen Einstieg misst der Pipeline-Messpfad des Retrieval-Harness
(`PipelineHarnessSupport`) diese Kette, und über denselben läuft das Diagnosewerkzeug
(`SearchDiagnosisService`) — siehe
[Retrieval-Benchmark](./retrieval-benchmark.md#1-messpfad-durch-die-produktive-pipeline). Der
Suchbereich wird dort übergeben, nicht aufgelöst: Berechtigungen bestimmt weiterhin ausschließlich
Schritt 1 in `QueryService#query`.

---

## Teil 2: Mögliche Verbesserungen

Ideen und bekannte Schwächen, keine Zusagen. Konsolidiert aus den verstreuten Verbesserungs-Hinweisen in
`data-indexing-rag.md` und den #912-Folge-Issues.

### Zurückgestellt, aber ausgearbeitet

- **MMR-Default-Aktivierung.** Gebaut, aber Opt-in (`mmr-lambda: 1,0`, siehe Schritt 4 oben). Gegen die 20
  `multi_topic`-Golden-Fälle aus #915 erreichte die reine `top-k`-Anhebung auf 8 ohne Vielfaltsauswahl
  20 von 20 Fällen, `mmr-lambda: 0,7` mit echten Chunk-Embeddings dagegen 19 von 20 — ein Fall schlechter.
  Details und Messmethodik in
  [MMR ist gebaut, aber per Voreinstellung deaktiviert](./data-indexing-rag.md#stellschrauben-und-ihre-wirkung).
  Eine künftige, größere oder heterogenere Mehrthemen-Stichprobe kann diese Einschätzung revidieren.

### Umgesetzt

- **Contextual Chunking** ([#933](https://github.com/criew/opaa/issues/933), umgesetzt). Wurzelanalyse
  (#932/#938): Ein Detail-Chunk (Gebührentabelle, Fristen, Kontaktdaten, ein einzelner Paragraph einer
  Satzung) trägt im Embedding kaum Signal, *wovon* er handelt, weil ihm der Dokumentkontext beim
  Zerteilen verloren geht — ein Gebühren-Chunk für „Personalausweis" rankte deshalb für eine
  Kostenfrage schwächer als der Einleitungs-Chunk desselben Dokuments, und ein einzelner
  Satzungs-Chunk mit sechs Paragraphen rankte in #938s Live-Diagnose so schwach, dass er selbst im
  echten Leserechte-Scope weit außerhalb jedes plausiblen Top-k-Fensters lag (Rang 50/97 bzw.
  96/147). Umsetzung: `io.opaa.indexing.document.DocumentIngestService#storeChunks` stellt jedem Chunk eines
  Dokuments, das beim Chunking in **2 oder mehr Chunks zerfiel**, vor dem Einbetten einen aus dem
  Dateinamen abgeleiteten, bereinigten Titel voran (`ChunkContextTitle#deriveTitle`, z. B. `"[prag]"`
  statt des rohen `"city-0022_prag.md"`) - ausschließlich für die `EMBED`-Formatierung der
  Einbettung, nicht für den gespeicherten Chunk-Text (heute `ChunkContextPrefix#applyTo`, über den
  beide Schreibwege laufen). Ein Dokument, das **ein einziger Chunk** blieb, bekommt bewusst
  **keinen** Präfix (Einbettungseingabe bit-identisch zum Chunk-Text, wie vor #933) -
  ein Detail-Chunk verliert Kontext durch das Zerteilen, ein ungesplittetes Dokument trägt seinen
  vollen Kontext bereits selbst. Diese Eingrenzung kam erst in einer zweiten Korrekturrunde: Ein
  Präfix aus dem rohen Dateinamen auf *jeden* Chunk (auch ungesplitteter Dokumente) verbesserte die
  einchunkige comic-characters-Domäne, regressierte aber die mehrchunkige city-landmarks-Domäne
  (`multi_topic`/`allExpectedDocumentsHitAt10` 1,000→0,850); ein humanisierter Titel auf jeden Chunk
  behob city-landmarks, regressierte aber stattdessen comic-characters (`hitRateAt5` 0,521→0,496) -
  erst die Beschränkung auf mehrchunkige Dokumente hielt beide Domänen zugleich unauffällig
  (comic-characters bit-identisch, city-landmarks vollständig erholt). Details und alle drei
  Messreihen in der Beschreibung von PR [#940](https://github.com/criew/opaa/pull/940). Erfordert
  einen vollständigen Reindex jeder Bibliothek (siehe
  [Handbuch: Was ein Update mit dem Index macht](../handbuch/betrieb/aktualisierung.md#was-ein-update-mit-dem-index-macht)) - Alt-Chunks
  ohne Präfix und Neu-Chunks mit Präfix ranken sonst inkonsistent gegeneinander.

### Bekannte offene Schwächen (aus den #912-Verifikationen)

- **Chunk-Granularität allgemein.** Contextual Chunking (oben) mildert das Signalverlust-Problem für
  den häufigsten Fall (Dateiname als Minimalsignal), löst es aber nicht vollständig - ein Chunk trägt
  weiterhin keinen Abschnittstitel oder Bibliothekskontext. Dokument-Vervollständigung (Schritt 6)
  behebt zusätzlich das Symptom bei Geschwister-Chunks desselben Dokuments (der richtige Chunk fällt
  nicht mehr aus der Auswahl, sofern er unter den `fetch-k` Kandidaten war), nicht die Ursache.
- **Einchunkige Dokumente konkurrieren ohne Kontextsignal gegen präfixierte mehrchunkige.** Contextual
  Chunking gibt nur mehrchunkigen Dokumenten ein Kontext-Präfix (siehe oben) - ein einchunkiges
  Dokument in derselben Bibliothek rankt dadurch relativ zu einem thematisch verwandten, jetzt
  präfixierten mehrchunkigen Dokument tendenziell schlechter als vor #933, selbst wenn es inhaltlich
  ebenso einschlägig ist. Live im #938-Kontext beobachtet: `02_gebuehrenbefreiung-beduerftigkeit.docx`
  (einchunkig) fällt für `maria.weber`s Leserechte-Scope von Rang 26/147 auf Rang 119/120 (siehe PR
  [#940](https://github.com/criew/opaa/pull/940), Abschnitt „Offener Punkt gegen #938"). Kein
  behobener Fall, sondern ein bekannter, noch offener Zielkonflikt dieses Ansatzes.
- **Nicht-rangfaire Geschwister-Sortierung über Teilfragen.** Bei mehreren Suchanfragen bestimmt
  `DocumentCompletion#complete`, welcher noch nicht ausgewählte Chunk eines zu vervollständigenden
  Dokuments als nächstes nachrückt, über die Reihenfolge im Kandidatenpool — der schlichten
  Aneinanderreihung der `fetch-k`-Kandidatenlisten aller Teilfragen (`RetrievalState#candidatePool`,
  `DocumentCompletion#unusedCandidatesByDocument`). Das ist die Ankunftsreihenfolge der Suchanfragen, keine
  über Teilfragen hinweg vergleichbare Rangfolge: Ein Geschwister-Chunk aus der zweiten oder dritten
  Teilfrage rankt in dieser Poolreihenfolge strukturell schlechter als ein gleich relevanter aus der
  ersten, unabhängig vom tatsächlichen Relevanzunterschied. Betrifft nur die Auswahl **zwischen mehreren
  Kandidaten desselben zu vervollständigenden Dokuments** — der äußere `topK`-Wettbewerb zwischen
  verschiedenen Dokumenten läuft weiterhin über die rangfaire Reciprocal Rank Fusion (Schritt 5).
- **Reine Vektorsuche verfehlt Dokumente, deren Embedding-Signal trotz wörtlicher Begriffstreffer zu
  schwach ist — akzeptierte Grenze (Maintainer-Entscheidung in #938).** Der belegte Fall: Für „Was
  gilt bei Gebührenbefreiung wegen Bedürftigkeit?" rankt `01_verwaltungsgebuehrensatzung.pdf` im
  Leserechte-Scope eines Kfz-Kontos auf Rang 50 (Score 0,553 gegen 0,59–0,63 thematisch fremder
  Konkurrenz), obwohl § 3 VGS die Anfragebegriffe („Befreiung", „Bedürftigkeit") wörtlich enthält.
  Keine Auswahlmechanik dieses Dokuments (topK, MMR, Dokument-Vervollständigung,
  Teilfragen-Zerlegung) kann einen solchen Rückstand überbrücken — sie ordnen nur, was die
  Vektorsuche liefert. Der passende Mechanismus wäre eine lexikalische Suchkomponente
  (Hybrid-Suche, siehe unten); sie ist als Arbeitspaket 2 von
  [Hybride Suche mit Reranking](./hybrid-retrieval.md) beauftragt und mit #1048/#1049 zur Hälfte
  gebaut: Der lexikalische Pfad findet ein Dokument jetzt auch dann, wenn sein Embedding die Anfrage
  nicht erreicht, sofern der Anfragebegriff wörtlich darin steht (auf der Verwaltungsdomäne löst der
  Pipeline-Pfad seither zwei der neun `literal_term_weak_embedding`-Fälle, die Hit Rate@5 dieser Klasse
  stieg von 0,556 auf 0,889). Die zweite Hälfte — ein Reranker für die Fälle, in denen die richtige
  Fundstelle im Fenster liegt, aber nicht weit genug oben — ist seit #1050 gebaut (Schritt 5b), aber
  in der Auslieferungskonfiguration abgeschaltet; erst mit `OPAA_RERANK_ENABLED=true` wirkt sie auf
  diesen Fall (Diagnose vollständig in #938).

### Etablierte Verfahren, die OPAA noch nicht nutzt

Als Ideen zu verstehen, ohne Zusage einer Umsetzung.

- ~~**Hybrid-Suche (BM25 + Vektor).**~~ Gebaut mit #1048/#1049 — Schritt 3b und die erweiterte Fusion
  in Schritt 5. Was hier als Trade-off stand (zweiter Suchindex, score-unabhängige Zusammenführung),
  ist eingelöst: Der Index liegt in derselben PostgreSQL-Datenbank (`tsvector`/GIN statt einer eigenen
  Engine), zusammengeführt wird rangbasiert. Es ist `ts_rank` statt BM25 — die bekannte Grenze steht in
  [Hybride Suche mit Reranking](./hybrid-retrieval.md#die-bekannte-grenze-ts_rank-ist-kein-bm25).
- ~~**Cross-Encoder-Reranking.**~~ Gebaut mit #1050 — Schritt 5b (`RERANK`). Ein Modell, das Frage und
  Passage gemeinsam statt getrennt bewertet, erkennt Passung genauer als eine reine Vektorähnlichkeit —
  kostet einen zusätzlichen Modellaufruf je Kandidat (oder Batch) und damit Latenz und Betrieb eines
  weiteren Modells; deshalb per Voreinstellung aus (siehe Schritt 5b oben).
- **HyDE (Hypothetical Document Embeddings).** Das Sprachmodell erzeugt vor der Suche eine hypothetische
  Antwort und embedded diese statt der Frage — eine Antwort ähnelt einem Fundstellentext oft mehr als eine
  Frage. Trade-off: ein zusätzlicher LLM-Aufruf vor jeder Suche, und eine plausible, aber falsche
  hypothetische Antwort kann die Suche in eine falsche Richtung lenken.
- **Query-Expansion.** Die Suchanfrage wird um Synonyme oder verwandte Begriffe ergänzt (klassisch
  lexikalisch, oder LLM-gestützt) statt in eigenständige Teilfragen zerlegt zu werden — anders als
  Teilfragen-Zerlegung (Schritt 2), die Themen trennt, verbreitert Query-Expansion ein einzelnes Thema.
  Trade-off: mehr Trefferabdeckung bei mehrdeutigen Begriffen, aber mehr Rauschen, wenn die Expansion am
  eigentlichen Sinn vorbeigeht.
