# Struktur-Review des Pakets `io.opaa.query`

**Thema:** Aufrufpfade von der Frage bis zur belegten Antwort, Paketschnitt, Zyklen, Doppelungen,
Kommentarbestand und Lücken zwischen Implementierung, Spezifikation und Handbuch.

**Status (10.09.2026):** Befundliste zu [#1444](https://github.com/criew/opaa/issues/1444). Analog zum
Struktur-Review der Indexing-Pipeline (#1316). Quellen: die 52 Klassen des Pakets (6 470 Zeilen), ihre
Nutzer in `api`, `searchadmin`, `chat` und `eval`, das Handbuchkapitel [Suche](../handbuch/suche.md),
die Spezifikationen [Retrieval-Algorithmus](../features/retrieval-algorithm.md) und
[Hybride Suche](../features/hybrid-retrieval.md) sowie eine Bytecode-Abhängigkeitsanalyse
(`jdeps -verbose:class` auf `build/classes/java/main`, danach Tarjan-SCC; Muster aus dem Kommentar an #1295).

**Kurzfassung.** Die Architektur trägt: Die Pipeline ist als benannte Stufen mit Pflicht-Erklärprotokoll
gebaut, die Reihenfolge steht an einer Stelle, der Rechtefilter ist baulich in jeder Suche, es gibt seit
#1424 keinen Klassenzyklus mehr. Was fehlt, ist die Ordnung darüber: 52 Klassen liegen flach in einem
Paket, obwohl sie sieben klar trennbare Themen bilden; `QueryService` vereint drei Rollen auf 992 Zeilen;
die Kommentarregel aus AGENTS.md ist mit 152 Issue-Nummern in 30 Dateien und 42 Stellen Review-Protokoll
flächig verletzt; und ein Dutzend Methoden hat keinen produktiven Aufrufer mehr.

---

## 1. Leseleitfaden: Handbuchstufe → Klassen

Die Tabelle ordnet jede Stufe des Handbuchs den Klassen zu, die sie heute umsetzen. Sie ist zugleich
die Begründung des Paketschnitts in Abschnitt 4: Was hier in einer Zeile steht, gehört in ein Paket.

| Handbuch [Suche](../handbuch/suche.md) | Spec-Schritt | `RetrievalStageName` | Klassen |
|---|---|---|---|
| Abschnitt 2, Suchbereich auflösen (Chat, Rechte, Leiste) | 1 | vor der Pipeline | `QueryService#resolveSearchScope`, `#intersectWithReadable`, `#validatedMetadataFilter`, `#seedConversationMemoryFromPersistedHistory`, `#maybeCheckAgainstPermissionHistory` |
| Stufe 1 Suchbereich → Rechtefilter | 1 | `SEARCH_SCOPE` | `SearchScopeStage` |
| Stufe 2 Metadatenfilter | 1b | `METADATA_FILTER` | `MetadataFilterStage`, `MetadataFilterExpressions` |
| Stufe 3 Teilfragen | 2 | `SUB_QUERY_DECOMPOSITION` | `SubQueryDecompositionStage`, `QueryDecompositionService` |
| Stufe 4 Vektorsuche | 3 | `VECTOR_SEARCH` | `VectorSearchStage` |
| Stufe 5 Volltextsuche | 3b | `FULL_TEXT_SEARCH` | `FullTextSearchStage`, `FullTextChunkSearch`, `FullTextIndexCompleteness` |
| Stufe 6 Auswahl je Liste (MMR) | 4 | `MMR_SELECTION` | `MmrSelectionStage`, `MmrSelector`, `ChunkEmbeddingLookup` |
| Stufe 7 Fusion | 5 | `RANK_FUSION` | `RankFusionStage`, `ReciprocalRankFusion` |
| Stufe 8 Reranking | 5b | `RERANK` | `RerankStage`, `RerankAvailability` (Rolle selbst in `io.opaa.llm`) |
| Stufe 9 Dokument-Vervollständigung | 6 | `DOCUMENT_COMPLETION` | `DocumentCompletionStage`, `DocumentCompletion` |
| Pipeline-Rahmen und Erklärprotokoll (Abschnitt 1, 8.2) | – | – | `RetrievalPipeline`, `RetrievalStage`, `RetrievalContext`, `RetrievalState`, `StageOutcome`, `StageExplanation`, `StageStatus`, `CandidateList`, `CandidateVerdict`, `CandidateOutcome`, `VerdictReason`, `RetrievalExplanation`, `RetrievalPipelineResult`, `RetrievalNote`, `RetrievalListLabel`, `RetrievalPipelineProperties`, `RetrievalStageName`, `ChunkGroupingKey` |
| Abschnitt 5, Filterangebot (Füllstand, Optionen, Cache) | – | außerhalb der Pipeline | `MetadataFilterOptionsService`, `MetadataFilterOptions`, `MetadataFilterOptionsCache`, `MetadataFilterProperties` |
| Abschnitt 6, Antwort erzeugen und Gesprächsgedächtnis | 7 | – | `AnswerGenerationService`, `CaffeineChatMemoryRepository`, `QueryConfiguration#chatMemory` |
| Abschnitt 7, Belege prüfen | 7 | – | `CitationParser`, `CitationValidator`, `CitationFactChecker` |
| Abschnitt 7, Fundstellen bilden | 7 | – | `QueryService#mapSources` und neun private Helfer (Zeilen 560–973) |
| Ergebnis und Konfiguration | – | – | `QueryService#query`, `QueryResult`, `QueryOutcome`, `SearchedLibraryRef`, `QueryProperties`, `QueryConfiguration` |

**Aufrufpfad einer Chat-Frage:** `QueryController#query` → `QueryService#query` (Chat auflösen,
Gedächtnis seeden, Leserechte, Rechteprobe, Suchbereich, Filter validieren) →
`#retrieveRelevantChunksInGivenScopeWithDecomposition` baut den `RetrievalContext` und ruft
`RetrievalPipeline#run` → neun Stufen in der Reihenfolge aus `QueryConfiguration#retrievalPipeline` →
`AnswerGenerationService#generateAnswer` → `CitationParser` / `CitationValidator` (mit `CitationFactChecker`) →
drei Lookups (Dokument, Kernfelder, Belegfelder) → `#mapSources` → `ChatService#appendTurn` → `QueryResult`.

**Zweiter Aufrufpfad (Diagnose):** `SearchDiagnosisService#run` baut denselben `RetrievalContext` selbst
und ruft `RetrievalPipeline#run` direkt, liest das Protokoll. **Dritter Aufrufpfad (Messung):**
`PipelineHarnessSupport` (`src/evalTest`) ruft `QueryService#retrieveRelevantChunksInGivenScopeWithDecomposition`.

---

## 2. Abhängigkeiten und Zyklen

**Klassenzyklen: keine.** Die SCC-Analyse findet im gesamten Paket keine stark zusammenhängende Komponente
mit mehr als einer Klasse. Der Achter-Zyklus aus #1424 ist aufgelöst; die Records des Protokolls
referenzieren sich nur noch in eine Richtung.

**Die Schichtung innerhalb des Pakets ist bereits sauber**, was einen Paketschnitt ohne Zyklenrisiko
erlaubt. Der Fan-in/Fan-out zeigt zwei Gruppen:

| Gruppe | Fan-out (Paket) | Fan-in (Paket) | Beispiele |
|---|---|---|---|
| Rahmen-Records | 0–3 | 8–13 | `RetrievalStageName` (0/13), `RetrievalContext` (2/12), `RetrievalState` (1/12), `StageExplanation` (3/12), `StageOutcome` (2/11), `RetrievalStage` (4/10), `RetrievalNote` (0/9) |
| Stufen | 13–17 | 1 | `FullTextSearchStage` (17/1), `VectorSearchStage` (15/1), `RerankStage` (15/1), `MmrSelectionStage` (14/1), `DocumentCompletionStage` (14/1) |

Eine Stufe hängt nur am Rahmen und an ihrem Algorithmus-Helfer. Es gibt **genau drei Querkanten zwischen
Stufen**, alle vermeidbar:

- `RerankStage` und `DocumentCompletionStage` → `RankFusionStage.FUSED_LIST_LABEL` (gehört nach `RetrievalListLabel`).
- `VectorSearchStage` und `FullTextSearchStage` → `SearchScopeStage.requiredLibraryFilter(state)` (gehört
  als `RetrievalState#requiredLibraryFilter()` in den Zustand, dessen Invariante es prüft).

**Paketkanten nach außen** (Klassenkanten, aus jdeps):

| Kante | Kanten | Träger |
|---|---|---|
| `query` → `indexing.metadata` | 36 | `MetadataFilterOptionsService` (11), `MetadataFilterExpressions` (6), `QueryService` (6), `MetadataFilterStage` (5) |
| `searchadmin` → `query` | 19 | Protokoll-Records, `RetrievalPipeline`, `RetrievalContext`, `QueryProperties` |
| `api` → `query` | 16 | `QueryService`, `MetadataFilterOptionsService`, Ergebnis- und Protokolltypen |
| `query` → `library` | 10 | `QueryService` (4), `MetadataFilterOptionsService` (3), `MetadataFilterOptionsCache` (3) |
| `query` → `api.types` | 9 | Filter-Klassen |
| `query` → `llm` | 8 | `AnswerGenerationService`, `QueryDecompositionService`, `RerankStage`, `QueryService`, `RerankAvailability` |
| `query` → `chat` | 7 | `QueryService` (5), `QueryResult`, `MetadataFilterOptionsService` |

Zwei Beobachtungen daraus:

1. **Die Metadatenfilter-Domäne ist die stärkste Außenkante und ein eigenes Thema.** Sieben Klassen
   (`MetadataFilterStage`, `MetadataFilterExpressions`, `MetadataFilterOptions`, `-Service`, `-Cache`,
   `-Properties` und die Filterhälfte von `FullTextChunkSearch`) tragen 36 der Kanten nach
   `indexing.metadata`. Vier davon (`MetadataFilterOptions*`) sind nicht Teil der Pipeline, sondern das
   Filterangebot der Oberfläche (Handbuch Abschnitt 5). Sie hängen an `QueryService` nur wegen
   `resolveSearchScope`.
2. **`QueryService` ist der einzige Ort mit Kanten in fünf Fachpakete** (`chat`, `library`, `llm`,
   `indexing.metadata`, `indexing.document`). Das ist für einen Orchestrator erwartbar, aber die Hälfte
   dieser Kanten gehört zur Fundstellenbildung, nicht zur Orchestrierung (Abschnitt 3.1).

---

## 3. Befunde

### 3.1 `QueryService` vereint drei Rollen (992 Zeilen)

| Rolle | Zeilen (ca.) | Methoden |
|---|---|---|
| Orchestrierung einer Frage | 113–331 | `query` (2 Überladungen) |
| Suchbereich und Kontext | 333–558 | `resolveSearchScope`, `intersectWithReadable`, `validatedMetadataFilter`, `seedConversationMemoryFromPersistedHistory`, `maybeCheckAgainstPermissionHistory`, `checkAgainstPermissionHistory`, vier `retrieveRelevantChunks*`-Überladungen, `RetrievalWithDecomposition` |
| Fundstellenbildung | 560–973 | `countMatchesPerDocument`, `lookupSourceDocuments`, `lookupCoreMetadata`, `lookupCitationFields`, `logInvalidCitations`, `mapSources`, `metadataFilterMatch`, `relevanceScoreForRank`, `parseDocumentId`, `buildOrphanSourceReferences`, `mergeSourceReferences`, `mergeChunkLocations`, `chunkLocationOf`, `searchedLibraries`, `isCitationValid` |

Die dritte Rolle ist mit rund 410 Zeilen die größte und hat mit der Orchestrierung nichts zu tun: Sie
nimmt Chunks, validierte Zitate und drei Lookup-Maps und baut `ChatSource`-Zeilen. Sie ist zugleich der
Grund für die Kanten `query → chat` (`ChatSource`, `ChatSourceLocation`, `ChatSourceMetadataEntry`) und
für vier der sechs Kanten nach `indexing.metadata`. `QueryServiceTest` (2 424 Zeilen, 77 Tests) mischt
entsprechend Orchestrierung, Zerlegungs-Fallback, Vervollständigung und die verschachtelte Klasse
`MergeSourceReferences`.

**Konkreter Defekt daran:** Über `lookupCitationFields` (Zeile 641–669) stehen zwei Javadoc-Blöcke
hintereinander; der erste (26 Zeilen über synthetische Einträge und `document_id`-Dedupe) beschreibt
`mapSources`, hängt aber an der falschen Methode. Der Javadoc von `mapSources` selbst fehlt.

**Vier Retrieval-Einstiege, einer ohne Aufrufer.** `retrieveRelevantChunksInGivenScope` (3-arg) hat
keinen Aufrufer außerhalb der Klasse; die 4-arg-Form ruft nur `query`. Das Paar
`retrieveRelevantChunksInGivenScopeWithDecomposition` mitsamt dem Record `RetrievalWithDecomposition`
existiert nur für den Eval-Harness, der damit eine Teilmenge des `RetrievalPipelineResult` bekommt, das
`SearchDiagnosisService` sich direkt von der Pipeline holt. Zwei Aufrufer, zwei Einstiege, ein
Rückgabetyp weniger als nötig.

**Der `RetrievalContext` wird zweimal gebaut.** `QueryService` (Zeile 538–546) und `SearchDiagnosisService`
(Zeile 236–243) erzeugen ihn mit demselben sechszeiligen Ausdruck samt
`RerankAvailability.of(rerankModelRole.currentStatus().state())`. Der Javadoc des Records warnt
ausdrücklich davor, dass ein Aufrufer die Verfügbarkeit vergisst; die Antwort darauf ist eine Fabrik,
nicht ein Warnhinweis. `SearchDiagnosisRerankParityTest` prüft heute die Gleichheit der beiden Stellen.

**`MetadataFilterOptionsService` hängt an `QueryService`** allein wegen `resolveSearchScope`. Die
Suchbereichsauflösung (Chat-Einstellungen oder Anfrageparameter, geschnitten mit Leserechten) ist eine
eigene Klasse wert; dann hat der Optionsdienst keine Kante mehr zum Orchestrator, und `QueryService`
verliert die Begründung, warum eine private Hilfsmethode `public` ist.

### 3.2 Kommentarbestand: Historie statt Vertrag

Zählung über `src/main/java/io/opaa/query`:

| Maß | Wert |
|---|---|
| Dateien mit Issue-Nummer | 30 von 52 |
| Issue-Referenzen gesamt | 152 (davon `QueryService` 44, `QueryProperties` 18, `CitationValidator` 13) |
| Stellen mit Review-Protokoll oder Vorher/Nachher-Erzählung | 42 |
| Kommentaranteil | `CitationValidator` 41 %, `QueryService` 34 %, `RetrievalState` 31 %, `RetrievalContext` 54 %, `RetrievalStage` 69 % |

Typische Muster, jeweils mit Fundstelle:

- **Review-Protokoll im Code.** `CitationValidator` Zeile 431 „#697 review, finding 3", Zeile 491
  „#939 review, finding 4", Zeile 533 „#939 review, finding 1"; `QueryService#buildOrphanSourceReferences`
  „#697 review, finding 4: cited = true is deliberate"; `#checkAgainstPermissionHistory` „code review of
  #427, nit 2"; `CitationValidator#validate(List, List)` „#939 review, finding 7".
- **Verdrahtungsgeschichte.** Fünfmal wortgleich „`@Service` (#889, O2): previously wired manually in
  `QueryConfiguration`" (`QueryService`, `AnswerGenerationService`, `CitationParser`, `CitationValidator`,
  `CaffeineChatMemoryRepository`); `QueryConfiguration` erklärt auf 10 Zeilen, was nach #889 übrig blieb.
- **Ablösungserzählung.** `RetrievalPipeline` „Replaces the seven-step orchestrator `QueryService` grew
  over #912 to #940"; `ReciprocalRankFusion` „its rule until #1049"; `DocumentCompletion` „#932 scope v2 -
  v1's tier-1-only rule was a no-op ..."; `QueryProperties#topK` „previously 5, raised as part of #914's
  Maßnahme D"; `SubQueryDecompositionStage#buildSearchQuery` „the pre-#923 fallback".
- **Parameter-Javadoc als Entscheidungsprotokoll.** `QueryProperties` trägt 100 Zeilen Javadoc für zehn
  Felder, darunter die Messbegründung des MMR-Defaults, die Ebenen-Einordnung nach hybrid-retrieval.md
  und den Verweis, wo die Zahlen „live" – alles Inhalt von Handbuch und Spec, die es bereits enthalten.
- **`QueryService#query` Javadoc: 50 Zeilen** mit fünf fett gesetzten Abschnitten (#202, #525, #238, #526,
  #299), gefolgt von 60 Zeilen Inline-Kommentaren im Methodenkörper.

**Veraltete Aussagen**, die nach Umbauten nicht mitgezogen wurden (das eigentliche Risiko der Historie:
sie stimmt irgendwann nicht mehr):

| Stelle | Aussage | Stand |
|---|---|---|
| `RetrievalStageName.DOCUMENT_COMPLETION`, `DocumentCompletion` Klassen-Javadoc | Pool „`VECTOR_SEARCH` produced" bzw. „the same pool `similaritySearch` produced" | seit #1049 füllt auch der Volltextpfad den Pool |
| `CandidateList` | „a second label per sub-query once the lexical path is added" | ist gebaut |
| `QueryProperties#topK` | „number of chunks `MmrSelector` finally selects" | Fusion/Reranking entscheiden; MMR kürzt je Liste |
| `QueryProperties#mmrLambda` | „`QueryService#query` also then skips the `ChunkEmbeddingLookup`" | tut `MmrSelectionStage` |
| `QueryProperties#queryDecompositionEnabled`, `#maxSubQueries` | „whether `QueryService#query` asks ..." | tut `SubQueryDecompositionStage` |
| `ReciprocalRankFusion`, `MmrSelector`, `QueryDecompositionService` | „`QueryService` retrieves / treats as / see `QueryService#query`" | die Stufen tun das |
| `SearchScopeStage`, `SubQueryDecompositionStage`, `VectorSearchStage`, `MmrSelectionStage`, `RankFusionStage`, `DocumentCompletionStage` | „Step 1/2/3/4/5/6 of retrieval-algorithm.md" | Spec zählt 1, 1b, 2, 3, 3b, 4, 5, 5b, 6; Handbuch zählt 1–9 |
| `MetadataFilterProperties` | „Koordinator-Festlegung 04.09.2026 at issue , ADR-0012" | Issue-Nummer fehlt |
| `QueryDecompositionService` | „replacing `QueryService`'s previous 'always prepend the first chat message' heuristic" | Historie |

### 3.3 Tote und nur von Tests genutzte API

| Element | Aufrufer in `main` | Aufrufer sonst |
|---|---|---|
| `QueryService#retrieveRelevantChunksInGivenScope(String, List, Set)` | keiner | keiner |
| `QueryService#retrieveRelevantChunksInGivenScopeWithDecomposition` (beide) + `RetrievalWithDecomposition` | keiner | `evalTest` |
| `CitationValidator#validate(List, List)` | keiner | Tests |
| `ReciprocalRankFusion#fuse` | keiner | `ReciprocalRankFusionTest`, `RetrievalPipelineParityTest` |
| `DocumentCompletion#complete` (4-arg) | keiner | `DocumentCompletionTest` |
| `FullTextChunkSearch#search` (3-arg) | keiner | `FullTextChunkSearchIntegrationTest` |
| `MetadataFilterOptionsCache#contains` (public) | keiner | Tests |
| `MetadataFilterOptions` 6-arg-Konstruktor | keiner | Tests |
| `RetrievalExplanation#forChunk`, `#stagesThatDropped` | keiner (`SearchDiagnosisService` iteriert selbst) | `RetrievalPipelineTest` |
| `CitationFactChecker#isSupportedByChunk`, `#extractFacts` (package-private) | nur intern | Tests |

Test-Überladungen sind nicht per se falsch, aber jede ist ein zweiter Vertrag, der gepflegt werden muss.
`CitationValidator#validate(List, List)` ist der problematische Fall: Der Javadoc erklärt auf sieben
Zeilen, dass Produktion ihn nie ruft.

### 3.4 Doppelungen

- **Verdikt-Schleifen der Suchstufen.** `VectorSearchStage` Zeile 444–455 und `FullTextSearchStage`
  Zeile 603–614 sind identisch (je Kandidat ein `ADDED`/`RETRIEVED_BY_SEARCH`-Verdikt mit Rang und Score).
  Ebenso identisch: der Rückfall „`state.searchQueries()` leer → Frage selbst" samt `withSearchQueries`
  in beiden Stufen (Zeile 428–429 / 460–461 und 577–578 / 634–635).
- **Kandidatenzählung.** `state.candidateLists().stream().mapToInt(list -> list.documents().size()).sum()`
  steht in `RetrievalPipeline` (Zeile 1098), `FullTextSearchStage` (560), `RerankStage` (968, 981),
  `MmrSelectionStage` (730), `RankFusionStage` (820). Gehört als `RetrievalState#candidateCount()` an den Zustand.
- **`RetrievalContext`-Bau** in `QueryService` und `SearchDiagnosisService` (Abschnitt 3.1).
- **Null-sichere `ChatResponse`-Extraktion** (`extractAnswer`, `extractModel`, `extractTokenCount`) in
  `QueryService`, während `AnswerGenerationService` und `QueryDecompositionService` dieselbe Prüfung inline
  wiederholen.
- **Drei mutable „Domain counterparts"** (`QueryResult`, `QueryOutcome`, `SearchedLibraryRef`) mit
  Fluent-Settern und Bean-Gettern, deren Javadoc als Existenzgrund nennt, dass Aufrufer des alten DTO-Typs
  unverändert bleiben sollten (#860). Der Grund ist erledigt; Records genügen.

### 3.5 Lücken zwischen Implementierung, Spezifikation und Handbuch

**Konfiguration**

- `opaa.query.metadata-filter.library-field-offer-threshold` ist in `MetadataFilterProperties` deklariert
  (Default 0.75) und im Handbuch (Abschnitt 10.3: „0,90 / 0,75 / 0,75 / 5m") und in metadata-schema.md
  genannt, hat aber **keinen Eintrag in `application.yml` und keine Umgebungsvariable in
  `docs/handbuch/deployment.md`**. Die beiden Nachbarwerte haben beides. Ein Betreiber kann den Wert nur
  über die relaxed-Binding-Form der Property setzen, die nirgends dokumentiert ist.

**Spezifikation `retrieval-algorithm.md`**

- Abschnitt „Etablierte Verfahren, die OPAA noch nicht nutzt" führt **Cross-Encoder-Reranking als „heute
  nicht gebaut"**; Schritt 5b derselben Datei beschreibt die gebaute Stufe. Analog zum durchgestrichenen
  Hybrid-Eintrag zu aktualisieren.
- „Die Schritte 1 bis 6 sind über `QueryService#retrieveRelevantChunksInGivenScope(question, history,
  searchScope)` auch einzeln aufrufbar – der Einstieg, über den der Pipeline-Messpfad ... misst": Der
  Harness nutzt tatsächlich `...WithDecomposition`; die genannte Methode hat keinen Aufrufer.
- Die Spec nummeriert 1, 1b, 2, 3, 3b, 4, 5, 5b, 6; das Handbuch 1–9; die Stage-Javadocs „Step 1–6" in
  einer dritten, älteren Zählung. Die einzige stabile Referenz ist `RetrievalStageName`. Vorschlag: Spec und
  Javadoc nennen die Stufe beim Namen, das Handbuch behält seine Zählung und führt die Namen in der
  Tabelle von Abschnitt 8.2 mit (dort erscheinen sie ohnehin in der Diagnose).

**Handbuch `suche.md`, Verhalten der Implementierung, das dort fehlt**

- Stufe 5: Schlägt die Volltextabfrage **für eine einzelne Teilfrage** fehl, entfällt nur deren Liste, der
  Lauf geht mit den übrigen weiter und das Protokoll notiert `lexical search failed for ...`
  (`FullTextSearchStage` Zeile 593–601). Das Handbuch kennt nur „abschaltbar" und „Rückstand".
- Stufe 8: Ist das Reranking-Fenster kleiner als die fusionierte Liste, werden die Kandidaten hinter dem
  Fenster in fusionierter Reihenfolge angehängt, nicht verworfen (`RerankStage` Zeile 923–928). Im
  Auslieferungsstand (Fenster 50 ≥ Budget) tritt der Fall nicht ein; bei `rerank-candidate-count < top-k`
  schon, und die Konfigurationstabelle lässt das zu (0 bis 200).
- Abschnitt 5: Formatfelder bieten höchstens 20 Werte an und melden `valuesCapped`
  (`MetadataFilterOptions.FormatFieldOption.MAX_OFFERED_VALUES`); das Handbuch beschreibt das Angebot ohne
  Deckel. (Gehört fachlich zu metadaten.md; hier nur der Verweis.)
- Abschnitt 6: Der Systemprompt der Antwort ist englisch (`AnswerGenerationService.SYSTEM_PROMPT`), der
  der Zerlegung deutsch (`QueryDecompositionService.SYSTEM_PROMPT_TEMPLATE`). Kein Widerspruch zum
  Handbuch, das zur Sprache nichts sagt, aber eine Inkonsistenz, die beim Lesen auffällt und die #1446
  (Gesprächsgedächtnis) berühren könnte.

**Geprüft und stimmig** (keine Lücke): leerer Suchbereich ohne Modellaufruf; Rechteprobe mit
Stichprobenrate; Zerlegungs-Rückfall „ganz oder gar nicht" mit erster Nutzerfrage und Metrik nach
Ursache; Kennungsmuster des Volltextpfads (Paragraf, Aktenzeichen, Drucksache, E-Mail) identisch zur
Indexierung; MMR liest bei λ = 1,0 keine Vektoren; Fusion nach Rang mit K = 60; drei Rerank-Zustände und
Ausfall während der Frage; zweistufige Verdrängung mit Deckel `top-k / 4`; Herkunfts- und Faktenprüfung
konservativ; verwaiste Fundstellenzeile ohne Rang; „Durchsucht wurden"-Zeile; Ratenbegrenzung
`opaa.rate-limit.query.*`; die vier Metriken aus Abschnitt 10.2.

### 3.6 Tests

- `QueryServiceTest` (2 424 Zeilen, 77 Tests) ist die zweitgrößte Testklasse des Backends und folgt der
  Dreifachrolle des Services. Nach der Extraktion (Abschnitt 4) zerfällt sie natürlich in
  `QueryServiceTest` (Orchestrierung, ~30 Tests), `ChatSourceAssemblerTest` (Fundstellen, darunter die
  heutige `MergeSourceReferences`) und `SearchScopeResolverTest`.
- Sechs Stufen (`SearchScopeStage`, `SubQueryDecompositionStage`, `VectorSearchStage`, `MmrSelectionStage`,
  `RankFusionStage`, `DocumentCompletionStage`) haben keinen eigenen Unit-Test; ihre Verdikt-Logik (Rang
  bei behalten/verworfen, Listen-Label) ist nur über `RetrievalPipelineTest` abgedeckt. Für den Umbau
  reicht das; als Verhaltensneutralitäts-Nachweis dient das Protokoll selbst (Abschnitt 5).
- `RetrievalPipelineParityTest` vergleicht die Pipeline gegen eine handverdrahtete Kette aus
  `ReciprocalRankFusion#fuse` und Co. – der Grund, warum die test-only-Überladungen aus 3.3 existieren.
  Nach dem Umbau ist der normalisierte Protokollvergleich (Abschnitt 5) die stärkere Zusicherung und der
  Paritätstest kann entfallen.

---

## 4. Zielbild: Paketschnitt

Der Schnitt folgt der Tabelle in Abschnitt 1. Jedes Handbuchkapitel findet sich in genau einem Paket.

```
io.opaa.query                       Fassade und Ergebnis (Handbuch 2, 6 Ende, 10)
  QueryService                        schlank: Chat, Rechte, Kontext bauen, Pipeline, Antwort, Belege, Turn schreiben
  SearchScopeResolver                 neu: aus QueryService#resolveSearchScope/#intersectWithReadable
  RetrievalContextFactory             neu: baut RetrievalContext samt RerankAvailability; genutzt von QueryService,
                                      SearchDiagnosisService und eval
  QueryResult, QueryOutcome, SearchedLibraryRef   als Records
  QueryProperties, QueryConfiguration

io.opaa.query.retrieval             Pipeline-Rahmen und Erklärprotokoll (Handbuch 1, 4 Rahmen, 8.2)
  RetrievalPipeline, RetrievalStage, RetrievalContext, RetrievalState, StageOutcome,
  StageExplanation, StageStatus, CandidateList, CandidateVerdict, CandidateOutcome, VerdictReason,
  RetrievalExplanation, RetrievalPipelineResult, RetrievalNote, RetrievalListLabel,
  RetrievalPipelineProperties, RetrievalStageName, RerankAvailability, ChunkGroupingKey

io.opaa.query.retrieval.scope       Stufen 1–2
  SearchScopeStage, MetadataFilterStage, MetadataFilterExpressions

io.opaa.query.retrieval.search      Stufen 3–5
  SubQueryDecompositionStage, QueryDecompositionService,
  VectorSearchStage, FullTextSearchStage, FullTextChunkSearch, FullTextIndexCompleteness

io.opaa.query.retrieval.ranking     Stufen 6–9
  MmrSelectionStage, MmrSelector, ChunkEmbeddingLookup,
  RankFusionStage, ReciprocalRankFusion, RerankStage,
  DocumentCompletionStage, DocumentCompletion

io.opaa.query.answer                Handbuch 6
  AnswerGenerationService, CaffeineChatMemoryRepository, ConversationMemoryConfiguration (chatMemory-Bean)

io.opaa.query.citation              Handbuch 7
  CitationParser, CitationValidator, CitationFactChecker,
  ChatSourceAssembler                 neu: aus QueryService#mapSources und Helfern

io.opaa.query.filter                Handbuch 5 (Filterangebot, nicht Pipeline)
  MetadataFilterOptionsService, MetadataFilterOptions, MetadataFilterOptionsCache, MetadataFilterProperties
```

Ergebnis: 8 bis 10 Klassen je Paket statt 52; das Wurzelpaket behält 7. `searchadmin` und `api` importieren
danach aus `query.retrieval` (Protokoll) und `query` (Fassade), sonst nichts.

**Folgen für Sichtbarkeit.** Heute sind `StageExplanation.executed/notRun`, `CandidateVerdict.of`,
`RetrievalNote`, `RetrievalListLabel` und `RetrievalContext#withoutReranking` package-private, weil Stufen
und Rahmen ein Paket teilen. Nach dem Schnitt müssen sie `public` werden. Das ist kein Verlust: Die Records
sind ohnehin öffentlich, und dass eine Stufe ein Protokoll erzeugen kann, ist der Vertrag der
Schnittstelle. Alternativ bleiben Rahmen und Stufen in einem Paket `query.retrieval` und nur `answer`,
`citation`, `filter` werden abgetrennt; das halbiert den Effekt (26 Klassen in `retrieval`), spart aber
jede Sichtbarkeitsänderung. Die Empfehlung ist der volle Schnitt: Die Stufengruppen sind das, was jemand
sucht, der das Handbuch neben dem Code liest.

**Alternative geprüft und verworfen:** ein Paket je Stufe (neun Pakete mit je 1–3 Klassen). Zu feinkörnig;
die Handbuchstufen 6–9 werden ohnehin als eine Phase „Zusammenführen und Ordnen" gelesen.

---

## 5. Folge-Issues

Reihenfolge wie bei #1316: Kommentare zuerst und allein, damit die Umbau-Diffs lesbar bleiben; dann
Entkernung, dann Schnitt.

**Verhaltensneutralitäts-Nachweis für 2, 3 und 4** (Muster aus #1316): Vor dem Umbau, auf dem
`main`-Stand, läuft die Pipeline für jede Frage des Golden-Sets der Verwaltungs-Evaldomäne einmal, und das
vollständige `RetrievalExplanation` wird als normalisiertes JSON in ein lokales Verzeichnis geschrieben
(Stufenname, Status, Zählungen, je Verdikt Chunk-ID, Outcome, Reason, Listen-Label, Rang; Notizen ohne
Laufzeitwerte). Nach dem Umbau derselbe Lauf gegen denselben Index, `diff -r` der beiden Verzeichnisse muss
leer sein. In den PR kommt nur das Ergebnis (Befehl, Zahl der Fragen, „Diff leer" oder der erklärte Diff);
die JSON-Dateien selbst sind ein Zwischenartefakt und werden nicht committet, anders als die Kennzahlen-
Baseline unter `eval/`, die unverändert bleibt oder begründet neu gezogen wird. Das **Werkzeug** dafür,
ein Dump-Modus des Eval-Harness (Systemproperty mit Zielverzeichnis, schreibt je Frage eine Datei), wird in
Issue 2 gebaut und bleibt im Repository, weil jeder weitere mechanische Umbau der Pipeline (etwa #1445) ihn
wieder braucht. Für Zwischenläufe darf das Host-Ollama (`-Dopaa.eval.ollamaBaseUrl`) genutzt werden, da
nur Gleichheit vor/nach verglichen wird, keine Baseline. Zusätzlich für Issue 2 ein Diff der
`ChatSource`-Ausgabe der `QueryServiceTest`-Fixtures vor und nach der Extraktion des `ChatSourceAssembler`.

| # | Titel | Inhalt | Größe |
|---|---|---|---|
| 1 | `docs(query): Javadoc-Kur im Paket query` | Review-Protokoll, Verdrahtungs- und Ablösungsgeschichte entfernen; veraltete Aussagen aus 3.2 korrigieren; verwaisten Javadoc über `lookupCitationFields` an `mapSources` hängen; Stage-Javadocs auf Stufennamen statt „Step N"; Klassen-Javadoc ≤ 10 Zeilen (Memory „Javadoc knapp halten"). Rein mechanisch, `spotlessApply`, keine Verhaltensänderung. | M |
| 2 | `refactor(query): QueryService entkernen` | Zuerst den Protokoll-Dump-Modus im Eval-Harness bauen und den Vorher-Stand aufnehmen. Dann `SearchScopeResolver` (mit `MetadataFilterOptionsService` umgestellt), `ChatSourceAssembler` (Fundstellenbildung samt Lookups), `RetrievalContextFactory` (genutzt von `QueryService`, `SearchDiagnosisService`, `PipelineHarnessSupport`; `SearchDiagnosisRerankParityTest` prüft dann die Fabrik); Retrieval-Einstiege auf einen reduzieren (`retrieve(...)` → `RetrievalPipelineResult`, eval liest `searchQueries()` daraus), `RetrievalWithDecomposition` und die 3-arg-Form entfallen; `QueryServiceTest` aufteilen. Nachweis: Protokoll-Diff + `ChatSource`-Diff. Abhängig von 1. | L |
| 3 | `refactor(query): Unterpakete retrieval, answer, citation, filter` | Schnitt aus Abschnitt 4; Sichtbarkeiten anpassen; `FUSED_LIST_LABEL` nach `RetrievalListLabel`, `requiredLibraryFilter` nach `RetrievalState`; `package-info.java` je Paket mit dem Handbuchverweis; `RetrievalPipelineTest` (410 Zeilen) entlang der neuen Pakete aufteilen: Rahmen (Registrierung, Protokollvollständigkeit, Abschaltung, Halt) bleibt, Stufenverhalten wandert zu den Stufenpaketen. Nachweis: Protokoll-Diff (muss leer sein, da nur Verschiebung) plus normalisierter Dateivergleich ohne `package`/`import`-Zeilen. Abhängig von 2. | M |
| 4 | `refactor(query): Doppelungen und test-only API` | `RetrievalState#candidateCount()`; gemeinsame Verdikt-Hilfe für die zwei Suchstufen; `QueryResult`/`QueryOutcome`/`SearchedLibraryRef` als Records (Mapper in `api` anpassen); test-only-Überladungen aus 3.3 entfernen, Tests auf die Produktionsform umstellen; `RetrievalPipelineParityTest` durch den Protokollvergleich ablösen. Abhängig von 3. | S |
| 5 | `docs(query): Doku-Lücken aus dem Struktur-Review` | `library-field-offer-threshold` in `application.yml` und deployment.md; retrieval-algorithm.md: Reranking-Eintrag streichen wie Hybrid, Harness-Einstieg korrigieren, Stufennamen statt Nummern; suche.md: Volltextausfall je Teilfrage (Stufe 5), Fenster kleiner als Liste (Stufe 8), Verweis auf Wertedeckel der Formatfelder. Unabhängig, kann parallel zu 1 laufen. | S |

Nicht Teil dieser Serie, aber im Review aufgefallen und bereits als Issues vorhanden: #1445 (Ablation je
Stufe; braucht den Schalter `disabled-stages` als Messgröße), #1446 (Gesprächsgedächtnis), #1448
(Filterangebot verständlich machen).
