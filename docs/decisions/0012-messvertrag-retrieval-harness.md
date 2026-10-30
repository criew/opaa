# ADR-0012: Messvertrag des Retrieval-Harness

## Status

Akzeptiert · zuletzt geändert 2026-10-29 · ergänzt durch ADR-0013 (Fehlerkriterium) und
ADR-0026 Entscheidung 5 (Konfidenzschwelle der Metadaten-Extraktion) · präzisiert ADR-0011

## Kontext

ADR-0011 legt fest, dass der Retrieval-Harness java-nativ ist, welches Einbettungsmodell er pinnt
und wo Korpus und Golden Dataset liegen. Es legt nicht fest, wie die Metriken definiert sind, an
welchem Fenster und unter welcher Konfiguration gemessen wird. Eine committete Baseline ist aber nur
so lange vergleichbar, wie genau das unverändert bleibt: Ändert sich die Gain-Funktion, die Basis
des idealen DCG, das Fenster, die Schwellenbehandlung oder eine Messbedingung wie das
Einbettungs-Laufzeitsystem, verschieben sich alle Zahlen ohne Änderung der Suchqualität, und der
Regressionsjob meldet eine Regression, wo die Messgrundlage gewechselt hat.

Dieses ADR ist der Katalog dieses Messvertrags: je Messpfad die Metrikdefinition, das Fenster, die
geprüften Festpunkte mit ihrem heutigen Wert, und die Versionszählung, die eine Baseline einem
Vertragsstand zuordnet. Das Fehlerkriterium, nach dem ein Lauf gegen seine Baseline besteht oder
fällt, steht in ADR-0013; die Baseline-Dateien und ihr Pflegeverfahren in `eval/baseline/README.md`;
die Fallklassen und Zustandsfelder der Datensätze in `docs/features/retrieval-benchmark.md`.

## Entscheidung

Gegliedert nach Messpfad; die `####`-Überschriften tragen die stabilen Entscheidungsnummern, die
deshalb nicht aufsteigend laufen. Das Nummernverzeichnis am Ende löst jede Nummer 1–73 auf.

### Regeln für alle Messpfade

#### 6. Jede Vertragsänderung erhöht die Versionsnummer ihres Pfads

Jeder Pfad führt seine Vertragsversion als Konstante im Report und als Festpunkt der Baseline:
`EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION` (Rohvektor, heute 16),
`PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION` (Pipeline, 21),
`ConversationEvaluationReport.CONVERSATION_MEASUREMENT_CONTRACT_VERSION` (Mehrrunden, 11). Die
Version steigt, wenn sich ändert, was „dieselbe Messung“ heißt: eine Metrikdefinition, ein Fenster,
die Schwellenbehandlung, die Mittelungsart, ein neuer, umbenannter oder enger definierter
Festpunkt — auch wenn kein gemessener Wert sich bewegt. Sie steigt nicht, wenn nur der **Wert**
eines bestehenden Festpunkts wechselt (Entscheidung 66) oder ein Datensatz-Zustand nachgezogen wird
(Entscheidung 53); dann werden die betroffenen Baselines neu gezogen oder nur im Festpunkt
nachgeführt. Eine Baseline mit abweichender Version ist unvergleichbar, nicht regressiert.

#### 16. Drei unabhängige Zählungen (ergänzt um 41)

Die drei Pfade messen Verschiedenes und sind nicht ineinander umrechenbar. Jeder zählt seine
Version eigenständig ab 1; ein Schritt auf einem Pfad erzwingt keinen auf einem anderen. Steigen
mehrere im selben Commit, dann weil derselbe Festpunkt alle betroffenen Pfade bewegt (etwa das
Ollama-Image), nicht aus Gleichlauf.

#### 29. Ein bewegter Sammelabdruck ist ein Versionsschritt auf jedem Pfad, der ihn führt

`ingestionPipelineFingerprint` (Entscheidung 28) erfasst alle registrierten Ingestion-Pipelines.
Bewegt eine Pipeline-Version ihn, steigen Rohvektor-, Pipeline- und Mehrrunden-Vertrag im selben
Schritt, auch wenn kein Korpus die geänderte Pipeline erreicht; die Baselines werden dann ohne
Messlauf nur im Festpunkt und in der Version nachgezogen („Fingerabdruck-Schritt“ in den
Versionstabellen). Erreicht die Änderung einen Korpus, werden dessen Baselines neu gemessen.

#### 66. Ein geänderter Festpunktwert bewegt keine Version, aber die Baseline

Ändert sich der Wert eines geprüften Festpunkts ohne seine Bedeutung — Produktionsvorgaben wie
`max-chunks-per-document` (#2502) oder das Ollama-Image (#2343) —, bleiben die Vertragsversionen;
jede Baseline, die den Festpunkt führt, wird neu gezogen. Ausnahme ist `top-k`: Es ist das Fenster
des Pipeline- und des Mehrrunden-Pfads und ändert damit die Bedeutung jeder Zahl (Entscheidung 63).

#### 36. Schwellen werden vor der Messung festgelegt

Jede Schwelle und jeder Anker dieses Vertrags und des Fehlerkriteriums wird vor der ersten Messung
festgelegt und committet, nie am Ergebnis entlang gewählt. Die Konfidenzschwelle der
modellgestützten Metadaten-Extraktion folgt derselben Regel; ihr Wert und ihre Pflege stehen in
ADR-0026, Entscheidung 5.

### Rohvektor-Pfad

Misst `VectorStore#similaritySearch` direkt: die Qualität der Vektorsuche, unvermischt mit allem,
was danach umsortiert. Der aussagekräftigere Pfad für Vergleiche von Einbettungsmodellen und
Chunking-Varianten (Entscheidung 11). Report `retrieval-metrics[-<domäne>].json`, Baseline
`eval/baseline/<domäne>.json`, Vergleicher `BaselineComparator`.

#### 1. Metrikdefinitionen (mit 2, 4, 5, 7)

| Festlegung | Definition |
|---|---|
| Relevanz | binär; ein Treffer ist ein Dokument aus `expected_documents` |
| nDCG | Gain 1, Diskont `1/log2(rang+1)`, Rang 1-basiert; ideales DCG über `min(|erwartete Dokumente|, k)` |
| MRR | Kehrwert des Rangs des ersten relevanten Treffers im Fenster, sonst 0 (faktisch MRR@10) |
| Recall@k | `Treffer in top-k / |erwartete Dokumente|`, nicht auf `min(k, |erwartet|)` normiert; die erreichbare Obergrenze steht je Gruppe als `recallAt10Ceiling` im Report |
| Fenster | Hit Rate@5 (was ein Nutzer bei kleinem `top-k` sieht), MRR@10, nDCG@10, Recall@10 (`RetrievalMetrics.HIT_RATE_K`, `NDCG_K`, `RECALL_K`); bewusst ungleich, eine Tabelle mit vier Spalten suggeriert sonst Vergleichbarkeit |
| Mittelung | Mikro-Mittel über Anfragen, je Gruppe `overall`, `category:*`, `difficulty:*`, `language:*` (`MetricsAggregate`); `language:de` wird übersprungen, solange die Baseline die Gruppe nicht führt (`comic-characters` nicht mehr, weil sie dort dieselbe Fallmenge wie `category:crosslingual` ist, ADR-0013); `city-landmarks` führt und vergleicht sie |
| Fallzähler | `hitCountAt5`, `hitCountAt10`, `distinctExpectedDocumentSets` (= `n_eff` von ADR-0013), `allExpectedDocumentsHitAt10` je Gruppe |
| Einzelfälle | `allQueryResults` führt jede Anfrage mit ihren Metriken, damit Kreuzauswertungen ohne neuen Lauf möglich sind |

#### 3. Ohne Ähnlichkeitsschwelle, mit festem Suchfenster

Gemessen wird ohne Ähnlichkeitsschwelle; `productionSimilarityThreshold` wird nur ausgewiesen und
ist als bewusst nicht angewandter Wert Festpunkt. Der Harness misst die Rangfolge des Retrievals,
nicht die Trefferliste, die ein Nutzer erhält. Das Fenster ist `documentTopK = 10` (Entscheidung 8);
`searchTopK` ist die tatsächlich an `similaritySearch` übergebene Trefferzahl und ebenfalls
Festpunkt.

#### 8. Das k-Fenster ist dokumentbezogen

„top-k“ heißt zehn unterschiedliche **Dokumente**, nicht zehn Chunks. Der Harness sucht mit
`chunkTopK = documentTopK · maxChunksPerDocument` (`EvalDomainConfig`, je Domäne deklarierte
Obergrenze: `comic-characters` 1, `city-landmarks` 20, `verwaltung` 19) und dedupliziert nach
Dokument (`DocumentRanking`). Erreicht ein Lauf `documentTopK` nicht, meldet der Report das
(`documentWindowCoverage`) statt still ein kleineres Fenster zu messen. Je Domäne ist eine
Chunk-Zahl-Erwartung Messvoraussetzung (`ChunkCountExpectation`: genau ein Chunk für
`comic-characters`, mindestens drei für die beiden anderen); eine Verletzung ist ein harter Abbruch,
kein Toleranzfall.

#### 9. Zweite Metrikfamilie: Chunkebene über `answer_span` (mit 21)

Neben der für Baseline und CI maßgeblichen Dokumentebene misst `ChunkAnswerSpanMetrics`
`answerSpanHitRate@5` und den Rang des ersten Chunks, der den wörtlichen Ausschnitt `answer_span`
eines Falls enthält — ein Textausschnitt statt eines Chunk-Index, weil er unter geänderten
Chunking-Parametern stabil bleibt. Höchstens ein `answer_span` je Fall, nur bei genau einem
erwarteten Dokument (Entscheidung 21, `GoldenCaseCuration`). Jeder Span muss in einem Chunk eines
erwarteten Dokuments auflösbar sein (`answerSpanResolution`), sonst bricht der Lauf ab: Eine
zerschneidende Chunk-Grenze ist von einem Retrieval-Fehlschlag numerisch nicht unterscheidbar;
`SpanMatcher` kollabiert nur Whitespace, für `boundary_span`-Fälle ist die Grenze Messgegenstand.
Eine Domäne ohne Spans liefert `NOT_APPLICABLE` (`comic-characters`).

#### Festpunkte des Rohvektor-Pfads (Entscheidungen 3, 10, 28, 32, 45, 55, 57)

`Baseline.FixedPoints`, verglichen in `BaselineComparator`; weicht einer ab, ist die Baseline
ungültig und keine Metrik wird verglichen.

| Festpunkt | Wert heute | seit Version | Warum |
|---|---|---|---|
| `measurementContractVersion` | 16 | 1 | ordnet die Baseline einem Vertragsstand zu |
| `embeddingModel`, `embeddingModelDigest` | `nomic-embed-text:v1.5`, `0a109f42…` | 1 | Modell und Gewichte (ADR-0011) |
| `embeddingDimensions` | 768 | 1 | andere Dimension, anderer Vektorraum |
| `chunkSize`, `chunkSizeMatchesApplicationDefault` | 1000, `true` | 1 | die Zerlegung bestimmt, was eingebettet wird |
| `chunkOverlap` | 100 | 2 | wirkt bei mehrchunkigen Dokumenten auf die Chunks |
| `documentTopK` | 10 | 2 | das Fenster der Metriken |
| `chunkTopK`, `searchTopK` | 10 / 200 / 190 je Domäne | 2 (`searchTopK` seit 1) | die Suche, aus der das Dokumentfenster entsteht |
| `productionSimilarityThreshold` | 0,3, nicht angewandt | 1 | dokumentiert, dass ohne Schwelle gemessen wird |
| `pgvectorIndexType` | `hnsw` | 1 | ein Wechsel auf exakte Suche ist eine Messänderung (ADR-0011) |
| `corpusManifestSha256`, `corpusDocumentCount` | je Domäne | 1 | der Korpus |
| `goldenDatasetFile`, `goldenDatasetSha256`, `goldenCaseCount` | je Domäne | 1 | der Datensatz samt Zuständen |
| `ingestionPipelineFingerprint` | `confluence:5,docx:6,email:6,html:6,markdown:4,odp:5,odt:5,pdf:6,pptx:4,tabular:5,tika-fallback:2` | 3 | welche Pipelines in welcher Version gechunkt haben (Entscheidung 28) |
| `metadataFilterEnabled` | `true` | 6 | ob die Golden-Filter in der Suche wirkten (Entscheidung 32) |
| `ollamaImage` | `ollama/ollama:0.40.3` | 10 | das Laufzeitsystem, das die Vektoren berechnet (Entscheidung 45) |
| `ollamaCpuBackend` | `haswell` | 11 | die ggml-Kernelvariante bewegt Rangfolgen (Entscheidung 55) |
| `contextPrefixFingerprint` | `ff4022a9…` | 12 | die Form des Kontextpräfixes vor jedem Chunk (Entscheidung 57) |

#### Versionen des Rohvektor-Vertrags

| Version | Datum | Issue | Grund |
|---|---|---|---|
| 1 | 2026-08-03 | #227 | Erste Fassung: Metriken, Fenster, Festpunkte |
| 2 | 2026-08-21 | #721 | dokumentbezogenes k-Fenster, `chunkOverlap`/`documentTopK`/`chunkTopK` als Festpunkte, Chunkebene |
| 3 | 2026-09-03 | #1144 | `ingestionPipelineFingerprint` |
| 4 | 2026-09-03 | #1164 | Fingerabdruck-Schritt: `email` 2→3 |
| 5 | 2026-09-03 | #1183 | Fingerabdruck-Schritt: `email` 3→4 (ADR-0022) |
| 6 | 2026-09-05 | #1070 | `metadataFilterEnabled`; `verwaltung` neu gemessen |
| 7 | 2026-09-05 | #1242 | Fingerabdruck-Schritt: `email` 4→5 |
| 8 | 2026-09-05 | #1315 | Fingerabdruck-Schritt: `html` 1→2 |
| 9 | 2026-09-07 | #1357 | Fingerabdruck-Schritt: `html` 2→3, `confluence` 1→2 |
| 10 | 2026-09-11 | #1522 | `ollamaImage` |
| 11 | 2026-09-15 | #1652 | `ollamaCpuBackend`; alle Baselines neu gemessen |
| 12 | 2026-09-15 | #1650 | `contextPrefixFingerprint` |
| 13 | 2026-09-27 | #2033 | Fingerabdruck-Schritt: `pdf` 1→2 |
| 14 | 2026-10-08 | #2330 | Fingerabdruck-Schritt: `pdf` 2→3 |
| 15 | 2026-10-08 | #2327, #2328 | Fingerabdruck-Schritt: übergroße Abschnitte, neun Formate |
| 16 | 2026-10-08 | #2331 | Fingerabdruck-Schritt: Kleinstchunks, `markdown` 2→3 und acht weitere |

### Pipeline-Pfad

#### 11. Zweiter Messpfad durch die produktive Pipeline (mit 12, 14, 37)

Der Pipeline-Pfad misst auf demselben einmal indizierten Korpus die Endauswahl der produktiven
Pipeline über denselben Einstieg wie eine Chat-Anfrage: `RetrievalContextFactory#contextFor` und
`RetrievalPipeline#run` (Entscheidung 37). Der Suchbereich wird übergeben, nicht
aufgelöst (Entscheidung 14): genau die Eval-Bibliothek mit dem ganzen Korpus; Rechtedurchsetzung
ist nicht Messgegenstand. Die Ähnlichkeitsschwelle ist **angewandt**, das Fenster ist das
produktive `top-k`. Der Pipeline-Pfad ersetzt den Rohvektor-Pfad nicht; er ist der einzige, der
Aussagen über die Nutzererfahrung erlaubt, und beide stehen getrennt im Bericht.

| | Rohvektor-Pfad | Pipeline-Pfad |
|---|---|---|
| Gemessen | `similaritySearch` | Endauswahl der Pipeline auf Dokumentebene, ohne Nachbar-Chunks (Entscheidung 67) |
| Schwelle | ausgewiesen, nicht angewandt | angewandt |
| Fenster | 10 Dokumente | `top-k` = 20 Chunk-Plätze (Entscheidung 63) |
| Metriken | Hit Rate@5, MRR@10, nDCG@10, Recall@10 | Hit Rate@5, MRR@20, nDCG@20, Recall@20 |
| Report / Baseline | `retrieval-metrics[-<domäne>].json` / `<domäne>.json` | `pipeline-metrics-<domäne>.json` / `pipeline-<domäne>.json` |

#### 12. Die Metriken tragen ihr Fenster an jeder Zahl

Die beiden Pfade sind nicht ineinander umrechenbar. Die Fensterangabe ist deshalb Schema, nicht
Konvention: `mrrAt20`, `ndcgAt20`, `recallAt20`, `recallAt20Ceiling`, `hitCountAt20`,
`allExpectedDocumentsHitAt20` (`PipelineMetricsAggregate`, `RANKING_K = 20`), jede Textausgabe
beschriftet gleich, jeder Report führt einen `metricWindowNote`. `PipelineMetricsAggregate.of`
weist Ergebnisse an einem anderen Fenster zurück. Metrikmathematik und Mittelung sind die des
Rohvektor-Pfads (`RetrievalMetrics`), angewandt an diesem Fenster.

#### 13. Festpunkte des Pipeline-Pfads (mit 18, 20, 22, 32, 45, 55, 57, 71)

`PipelineBaseline.FixedPoints`, verglichen in `PipelineBaselineComparator`, Feld für Feld, auch
innerhalb der Blöcke `fusion` und `relevanceCutoff`. Alle Werte werden aus der laufenden
Anwendungskonfiguration gelesen, nicht im Harness gesetzt; `relevanceCutoff` aus
`RelevanceCutoffProperties`. Einzige Ausnahme ist `query-decomposition-enabled`: Das Produkt liefert
mit Zerlegung aus, der Harness setzt sie für den Einzelfragen-Pfad selbst ab
(`EvalQueryDecomposition`, Entscheidung 15). Ein geboxtes Feld (`metadataFilterEnabled`, die
Blöcke `fusion`/`relevanceCutoff`), das eine ältere Datei nicht kennt, lädt als `null` und ist damit
unvergleichbar, nie stillschweigend „ohne“. Kein Festpunkt ist das Nachbar-Budget aus #2625: Der
Harness misst die Endauswahl ohne Nachbar-Chunks (Entscheidung 67).

| Festpunkt | Wert heute | seit Version | Warum |
|---|---|---|---|
| gemeinsamer Block: Modell und Digest, `ollamaImage`, `ollamaCpuBackend`, Dimensionen, `chunkSize`, `chunkOverlap`, `pgvectorIndexType`, Korpus, Datensatz, `ingestionPipelineFingerprint`, `contextPrefixFingerprint`, `metadataFilterEnabled` | wie Rohvektor | 1; `ingestionPipelineFingerprint` 4, `metadataFilterEnabled` 8, `ollamaImage` 13, `ollamaCpuBackend` 14, `contextPrefixFingerprint` 15 | dieselben Chunks, dieselben Vektoren |
| `fetchK` | 25 | 2 (ausgewiesen seit 1) | Kandidatenzahl je Suchliste |
| `topK` | 20 | 1 | das Fenster; der Harness verweigert einen Lauf, dessen `top-k` von `RANKING_K` abweicht |
| `similarityThreshold` | 0,3 | 2 | hier angewandt, entfernt Dokumente aus der Liste |
| `maxChunksPerDocument` | 1 | 2 | Dokument-Vervollständigung |
| `mmrLambda` | 1,0 | 2 | Diversifizierung |
| `fusion.method`, `.subQueryQuota`, `.vectorWeight`, `.guaranteedPerList` | `RRF`, `true`, 0,7, 1 | 21 | wie die Listen zur Auswahl werden; auch wirkungslose Werte (`vectorWeight` nur unter `SCORE`) gehören dazu, weil ein Moduswechsel sie wirksam macht |
| `relevanceCutoff.mode`, `.minChunks`, `.relativeThreshold`, `.kneeDrop` | `FIXED`, 10, 0,6, 0,25 | 21 | der Relevanzschnitt der Auswahl |
| `fullTextSearchEnabled` | `true` | 3 | ob der lexikalische Pfad in die Fusion einging (Entscheidung 22) |
| `fullTextIndexUpToDate` | `true` | 3 (umbenannt 7 und 12) | ob jede Zeile des Volltextindex der Bibliothek auf der aktuellen `content_tsv_version` stand; seit 12 nur noch der Fassungs-Rückstand, weil die Zeile selbst in derselben Transaktion wie der Vektor entsteht |
| `queryDecompositionEnabled`, `maxSubQueries`, `chatModel` | `false`, 3, `null` | 1 (`maxSubQueries` 2) | Entscheidung 15: ohne Zerlegung gemessen; ein Modellname auf einer Seite allein macht die Baseline ungültig (Entscheidung 20) |
| `hitRateK`, `rankingK` | 5, 20 | 2 | die Fenster, die die Feldnamen wörtlich führen |

#### 15. Teilfragen-Zerlegung: abgeschaltet, nie stillschweigend degradiert (mit 20)

Der Einzelfragen-Pipeline-Pfad misst die Variante `decomposition-off`
(`query-decomposition-enabled = false`, `chatModel = null`).
`PipelineHarnessSupport#requireMeasurableConfiguration` bricht ab, wenn ein Lauf etwas anderes
misst, als seine Feldnamen behaupten: Zerlegung ohne Chat-Modell (stiller Rückfall auf
Einzelanfragen), `top-k` ungleich `RANKING_K`, abgeschaltete Pipeline-Stufen (kein Report-Feld hält
fest, welche liefen), lexikalischer Pfad aus, aktives Reranking (nutzbare Rerank-Rolle mit
`rerank-candidate-count > 0`). Die Baseline beschreibt die ausgelieferte Konfiguration: hybrid, ohne
Reranking; vector-only und Reranking sind benannte Variantenvergleiche (`eval/variants/*-lexical-path.json`,
`verwaltung-reranking.json`), und ein Variantenbericht ist keine Baseline.

#### 17. Eine Baseline-Datei je Pfad und Domäne

Flach unter `eval/baseline/`: `<domäne>.json`, `pipeline-<domäne>.json`,
`pipeline-<domäne>-conversations.json`; die Namen leitet `EvalDomainConfig` mit festem Präfix und
Suffix aus dem Rohvektor-Namen ab, eine Kollision ist per Tippfehler nicht erreichbar. Eigene Typen
(`Baseline`, `PipelineBaseline`, `ConversationBaseline`) mit eigenen Festpunkten und Gruppenwerten,
damit keine @10-Zahl unter einem @20-Feldnamen landen kann. Flach, damit der Absenkungsvergleich
gegen `main` (ADR-0013, Entscheidung 6) alle Dateien ohne Fallunterscheidung erfasst.

#### 19. Getrenntes Urteil je Pfad, eigene Anker der harten Untergrenze

Jeder Pfad wird gegen seine eigene Baseline mit dem **unveränderten** Fehlerkriterium aus ADR-0013
geprüft: alle Vergleicher über `BaselineComparator.metricCheck`, am Pipeline-Fenster über
`PipelineGroupChecks` (vier Metriken und `allExpectedDocumentsHitAt20`, Fallzähler `hitCountAt5`
bzw. `hitCountAt20`). Je Pfad ein eigenes Urteil mit eigener Delta-Tabelle (zwei Testklassen im
selben Gradle-Task, beide im Alarm-Issue); ein Fehler des Pipeline-Messlaufs lässt den Messlauf grün
(`PipelineHarnessSupport.runAndWriteGuarded`) und nur den Pipeline-Baseline-Test rot. Eine Domäne
ohne Pipeline-Baseline wird sichtbar ausgelassen (`pipelineBaselineTestClass = null` in
`backend/build.gradle.kts`), nie still als bestanden gewertet.

Die Anker der harten Untergrenze sind pfadspezifisch, die Formel `max(0,8 · Baselinewert, Anker)`
nur auf `overall` nicht: Pipeline- und Mehrrunden-Pfad Hit Rate@5 ≥ 0,15, MRR@20/nDCG@20/Recall@20 ≥
0,125 (`PipelineBaselineComparator`) — die Hälfte der Rohvektor-Anker, weil die Schwelle hier greift
und Dokumente ganz aus der Liste entfernen kann; vor der ersten Messung festgelegt (Entscheidung 36),
ein Netz gegen einen leeren Vektor-Store, kein Qualitätsziel, mit dem Fenster @20 unverändert.

#### 22. Der lexikalische Pfad ist Teil der Pipeline-Messung (mit 23)

Die Volltextsuche geht als Eingangsliste in die Fusion ein und bewegt die Endauswahl.
`fullTextSearchEnabled` und `fullTextIndexUpToDate` sind deshalb Festpunkte; erst beide zusammen
beantworten, ob der lexikalische Pfad in einem Lauf beigetragen hat. Ein Variantenlauf, der ihn über
einem unvollständigen Index anfordert, wird als „nicht ausgeführt“ gemeldet (`VariantPrerequisites`),
nicht stillschweigend degradiert. Der Rohvektor-Pfad kennt den lexikalischen Pfad und die Fusion
konstruktionsbedingt nicht; seine Version bleibt von jeder Änderung daran unberührt (Entscheidung 23).

#### 63. Das Fenster folgt der Produktion: @20

`top-k` ist das Fenster, weil der Pipeline-Pfad die Nutzererfahrung misst und die die ganze
Endauswahl bestimmt. Es ist 20; alle fenstertragenden Namen heißen entsprechend, im
Pipeline-, Mehrrunden- und Variantenbericht. Das Fenster ist nominell breiter als die zehn Dokumente
des Rohvektor-Pfads, zählt aber Chunk-Plätze.

#### Versionen des Pipeline-Vertrags

| Version | Datum | Issue | Grund |
|---|---|---|---|
| 1 | 2026-08-31 | #1039 | Messpfad, eigene Zählung; Query-Parameter ausgewiesen |
| 2 | 2026-08-31 | #1040 | `fetch-k`, `similarity-threshold`, `max-chunks-per-document`, `mmr-lambda`, `max-sub-queries`, `hitRateK`/`rankingK` durchgesetzt; erste Baselines |
| 3 | 2026-09-01 | #1049 | `fullTextSearchEnabled`, `fullTextBackfillComplete`; neu gemessen |
| 4 | 2026-09-03 | #1144 | `ingestionPipelineFingerprint` |
| 5 | 2026-09-03 | #1164 | Fingerabdruck-Schritt: `email` 2→3 |
| 6 | 2026-09-03 | #1183 | Fingerabdruck-Schritt: `email` 3→4 |
| 7 | 2026-09-04 | #1270 | Umbenennung `fullTextBackfillComplete` → `fullTextIndexComplete` |
| 8 | 2026-09-05 | #1070 | `metadataFilterEnabled`; `verwaltung` neu gemessen |
| 9 | 2026-09-05 | #1242 | Fingerabdruck-Schritt: `email` 4→5 |
| 10 | 2026-09-05 | #1315 | Fingerabdruck-Schritt: `html` 1→2 |
| 11 | 2026-09-07 | #1357 | Fingerabdruck-Schritt: `html` 2→3, `confluence` 1→2 |
| 12 | 2026-09-11 | #1429 | Umbenennung und Verengung `fullTextIndexComplete` → `fullTextIndexUpToDate` |
| 13 | 2026-09-11 | #1522 | `ollamaImage` |
| 14 | 2026-09-15 | #1652 | `ollamaCpuBackend`; alle Baselines neu gemessen |
| 15 | 2026-09-15 | #1650 | `contextPrefixFingerprint` |
| 16 | 2026-09-27 | #2033 | Fingerabdruck-Schritt: `pdf` 1→2 |
| 17 | 2026-10-08 | #2330 | Fingerabdruck-Schritt: `pdf` 2→3 |
| 18 | 2026-10-08 | #2327, #2328 | Fingerabdruck-Schritt: übergroße Abschnitte |
| 19 | 2026-10-08 | #2331 | Fingerabdruck-Schritt: Kleinstchunks |
| 20 | 2026-10-09 | #2373 | Fenster @8 → @20; neu gemessen |
| 21 | 2026-10-14 | #2386 | `fusion` und `relevanceCutoff`; neu gemessen |

### Mehrrunden-Pfad

#### 38. Ein dritter Messpfad, getrennt ausgewiesen

Gespräche aus `eval/golden/<domäne>-conversations.json` laufen Runde für Runde durch dieselbe
Kette wie eine echte Anfrage, mit dem Gesprächsfenster aus den vorangegangenen Runden. Eigener
Berichtstyp (`ConversationEvaluationReport`, `pipeline-conversations-<domäne>.json`), eigene
Baseline, eigener Vergleicher (`ConversationBaselineComparator`), eigene Vertragsversion
(Entscheidung 41). Metrikmathematik und Fenster sind die des Pipeline-Pfads: Eine Runde ist eine
Frage mit erwarteten Dokumenten und wird als `GoldenCase` gemessen, keine zweite Metrikfamilie.
Der Bericht aggregiert je Runde (`turn:<n>`), je Fallklasse (`category:*`) und `overall`; die
Rundengruppe zeigt, was ein Gesamtwert verdeckt: eine Änderung, die ersten Runden hilft und
Folgerunden schadet.

#### 39. „Gelöst“ je Runde und je Fall

Eine Runde ist gelöst nach `ExpectedStateAudit#isSolved` (alle erwarteten Dokumente im Fenster
**und** ein erwartetes auf Rang 1); ein Fall ist gelöst, wenn **jede** seiner Runden gelöst ist,
auch eine Runde ohne Suchbedarf (Entscheidung 61). Kein gemittelter Fallwert.

#### 40. Festpunkte des Mehrrunden-Pfads

`ConversationBaseline.FixedPoints`: der vollständige Pipeline-Block (Entscheidung 13, dasselbe
Schema, über `PipelineBaselineComparator.addPipelineFixedPointMismatches` verglichen) plus:

| Festpunkt | Wert heute | seit Version | Warum |
|---|---|---|---|
| `pipeline.metadataFilterEnabled` | `false` | 1 | Mehrrunden-Fälle tragen keinen Kernfeld-Filter; ein Filter entschiede vor der Bezugsauflösung (`ConversationHarnessSupport`) |
| `pipeline.queryDecompositionEnabled`, `pipeline.chatModel` | `true`, `qwen2.5:1.5b-instruct` | 1 | hier **geprüft**, nicht nur gemeldet: ohne Zerlegung wird kein Bezug aufgelöst; `ConversationBaseline.load` weist eine Datei ohne Modell zurück |
| `conversationWindowMessages` | 20 | 1 | Breite des Gesprächsfensters, **gemessen** an der produktiven `ChatMemory`-Bean (`ConversationMemoryProfile#measuredFrom`), nicht aus einer Konstante kopiert |
| `searchWindowTurns` | 2 | 1 | wie viele Runden die Zerlegung sieht (`opaa.query.search-window-turns`), aus den produktiven `QueryProperties` |
| `conversationNoteCap` | 10 | 1 | Deckel der Gesprächsnotiz; 0 hieße „keine Notiz“ |
| `turnCount` | 127 | 1 | zweite Hälfte der Datensatzgröße: eine Kuratierung, die nur Fälle verlängert, ließe `goldenCaseCount` unberührt |

#### 42. Ausführung: nur zerlegend, dreimal, Median (mit 48, 49, 51, 60)

Eigenes Task-Paar `evaluate<Domäne>Conversations`/`check<Domäne>ConversationBaseline` am Ende
desselben Harness-Laufs (Entscheidung 48): Die Task setzt Zerlegung und `opaa.eval.runConversations`
selbst, weil die Einzelfragen-Baselines derselben Domäne ohne Zerlegung gezogen sind; der
Einzelfragen-Pipeline-Pfad wird in diesem Lauf nicht gemessen (Entscheidung 60). Mehrfachlauf-Regel
(`MehrfachlaufRule`, `docs/features/retrieval-benchmark.md` §3): drei Läufe, der Median nach nDCG@20
vertritt die Messung; der Bericht führt Streubereich, Runden mit abweichender Zerlegung und je Runde
Notizpunkte und Teilfragen (Entscheidung 51). Fehlt Zerlegung, Chat-Modell oder Datensatz: „nicht
ausgeführt“. Eigener CI-Job `conversations` (`.github/workflows/retrieval-regression.yml`, Budget
300 Minuten; wer es verengt, zieht vorher weitere nächtliche Läufe heran) nächtlich, per
`workflow_dispatch` und beim Label `evaluation` (Entscheidung 49).

#### 43. Die Einpfadigkeit steht am Datensatz, nicht am Fall (Einpfad-Regel)

Ein Mehrrunden-Fall kann nur auf dem Pipeline-Pfad laufen; der Rohvektor-Pfad kennt weder Verlauf
noch Zerlegung. Eine Fallklasse, die konstruktionsbedingt nur auf einem Pfad laufen kann, gilt als
gelöst, wenn sie dort gelöst ist. Der Bericht vermerkt das einmal (`singlePathNote`), die
Zustandsfelder des Datensatzes bleiben flach und beschreiben diesen einen Pfad.

#### 44. Die Wechselrunde eines `topic_switch`-Falls wird benannt, nicht abgeleitet

Der Themen-Bleed wird über genau eine Runde je Fall gebildet, die der Datensatz als
`topic_switch_turn` (1-basiert) benennt. Eine Ableitung aus den Erwartungsmengen träfe auch
gewöhnliche Rückfragen in ein anderes Dokument und zählte deren richtiges Vorthemen-Dokument als
Bleed.

#### 50. Drei Ausgänge je Pfad

`ConversationBaselineVerdict` kennt wie der Pipeline-Pfad keine Regression, Regression und
unvergleichbare Baseline; die beiden letzten lassen den Job fehlschlagen, es gibt keinen Ausgang
„nicht beurteilt“. `ConversationPathIsolationTest` sperrt `searchWindowTurns`/`conversationNoteCap`
der committeten Baseline auf die Produktionswerte.

#### 61. Eine Runde ohne Suchbedarf steht außerhalb jedes Aggregats

Eine Runde mit `search_expected: false` hat keine erwarteten Dokumente und ist gelöst, wenn der Lauf
sie als Nachricht ohne Suchbedarf eingestuft hat (`searchNeeded = false`). Sie geht in kein
Aggregat ein (`n` und jeder Mittelwert bedeuten „Runden mit Suche“), trägt `null` statt `0` und
erscheint im Abschnitt `noSearch`, der beide Fehlrichtungen zählt. Kein eigener Metrikwert: Bei
neun solchen Runden je Datensatz gäbe es keinen Toleranzbegriff im Sinne von ADR-0013.

#### 62. Temperatur nur für einen externen Befundlauf

`-Dopaa.eval.chatTemperature` gilt nur mit `opaa.eval.chatBaseUrl`; das gepinnte Modell misst bei
Temperatur 0. Die Temperatur ist kein Festpunkt, ein externer Lauf ist über `chatModel` ohnehin
unvergleichbar; sie steht als Beobachtungsfeld im Bericht.

#### Versionen des Mehrrunden-Vertrags

| Version | Datum | Issue | Grund |
|---|---|---|---|
| 1 | 2026-09-11 | #1484 | Messpfad, eigene Zählung |
| 2 | 2026-09-11 | #1522 | `ollamaImage` (geteilter Block) |
| 3 | 2026-09-15 | #1652 | `ollamaCpuBackend`; Baseline neu gemessen |
| 4 | 2026-09-15 | #1650 | `contextPrefixFingerprint` |
| 5 | 2026-09-17 | #1684 | Runden ohne Suchbedarf; `n` bedeutet „Runden mit Suche“ |
| 6 | 2026-09-27 | #2033 | Fingerabdruck-Schritt: `pdf` 1→2 |
| 7 | 2026-10-08 | #2330 | Fingerabdruck-Schritt: `pdf` 2→3 |
| 8 | 2026-10-08 | #2327, #2328 | Fingerabdruck-Schritt: übergroße Abschnitte |
| 9 | 2026-10-08 | #2331 | Fingerabdruck-Schritt: Kleinstchunks |
| 10 | 2026-10-09 | #2373 | Fenster @8 → @20; neu gemessen |
| 11 | 2026-10-14 | #2386 | `fusion` und `relevanceCutoff`; neu gemessen |

### Zustandsfelder der Datensätze

#### 52. Der erwartete Zustand wird je Messpfad geführt, das Audit prüft je Pfad (mit 53)

`expected_state` eines Einzelfragen-Falls hat die Pflichteinträge `raw_vector` und `pipeline`, je
mit `state` (`solved`/`known_gap`), `since`, `reason` (`GoldenCaseCuration`). Der gemessene
Ist-Zustand ist der festgeschriebene Stand; `ExpectedStateAudit` meldet nur Abweichungen, je Pfad,
in beiden Richtungen. „Gelöst“: alle erwarteten Dokumente im Fenster **und** ein erwartetes auf
Rang 1, am Fenster des Pfads. Ein ohne den Mechanismus seiner Klasse gelöster Fall ist `solved` mit
dem Präfix `Ohne geprüften Mechanismus: ` (nur auf `solved`, nur als exakter Anfang; Zuordnung in
`docs/features/retrieval-benchmark.md` §5). Der Mehrrunden-Datensatz bleibt flach (Entscheidung 43).
Das Audit berechnet keine Metrik und lässt keinen Lauf fehlschlagen; Zustandspflege bewegt keine
Vertragsversion, nur `goldenDatasetSha256` der betroffenen Baselines (Entscheidung 53).

### Berichtsgrößen ohne Vertragscharakter (Rangreserve, Audits)

Diese Größen stehen im Report und in den Delta-Tabellen, aber in keiner Baseline und keinem
Vergleicher; sie bewegen keine Version:

- **Rangreserve** (#1151, `RetrievalMetrics#marginAtK`, `MarginAggregate`; im Mehrrunden-Bericht
  `hitRateMargin`/`rankingMargin` je Runde): Abstand des ersten relevanten Treffers zur Fenstergrenze.
  Maintainer-Entscheidung (#1210): bleibt report-only auf allen drei Pfaden, weil sie nur den ersten
  Treffer betrachtet, kein Lauf bisher eine Regression zeigte, die sie früher angezeigt hätte, und
  ein weiteres Kriterium Toleranz und Neuziehung kostete. Wiederaufnahme, wenn eine in den geprüften
  Metriken unsichtbare Regression in der Rangreserve sichtbar war — dann mit einer Marge über das
  schwächste erwartete Dokument. Deshalb gehört zu jeder Untersuchung einer gemeldeten
  Verschlechterung die Auswertung von `MarginAggregate`.
- **Metadatenfilter-Audit** (Entscheidung 34, `MetadataFilterAudit`): zählt je Pfad getrennt
  „Filter greift nicht“ (`confusable_document` trotz Filter im Fenster) und „Filter greift zu stark“
  (Fall mit `no_value_field` verliert sein Dokument); nie gemittelt, weil beide Richtungen
  verschiedene Korrekturen verlangen.
- **Zustandsfeld-Audit** (Entscheidung 52), `documentWindowCoverage`, `answerSpanResolution`
  (Entscheidungen 8, 9), Mehrfachlauf-Block und `noSearch` (Entscheidungen 51, 61).

### Eval-Umgebung

#### 68. Ollama-Image `ollama/ollama:0.40.3` (mit 45)

`EvalOllamaEndpoint.PINNED_IMAGE`, einmal definiert, Festpunkt `ollamaImage` aller drei Pfade
(Entscheidung 45): Derselbe Modell-Digest hinter einer anderen Fassung ist nicht nachweislich
dieselbe Messung — 0.6.5 bettete Eingaben über 512 Token in Teilstücken ein und lieferte falsche
Vektoren. 0.40.3 ist die Fassung des Compose-Stapels und des Kubernetes-Erprobungsaufbaus. Modell-
und Digest-Pin des Embedding-Modells (`nomic-embed-text:v1.5`) und des Chat-Modells
(`EvalChatModel`, `qwen2.5:1.5b-instruct`, Temperatur 0) stehen in ADR-0011, Entscheidung 4.

#### 54. Feste ggml-CPU-Variante `haswell` (mit 55)

Alle drei Harnesse starten den Container über `EvalOllamaCpuBackend`: Vor `ollama serve` werden alle
CPU-Bibliotheken außer `libggml-cpu-haswell.so` entfernt; nach der Indizierung prüft der Harness im
Container-Log, dass der Embedding-Runner und, bei Zerlegung, der Chat-Runner diese Variante geladen
haben, sonst bricht er vor der ersten Messung ab. `haswell` ist die höchste Stufe, die jeder
x86-64-Runner ausführt; AVX-512-Kernel rechnen ab der fünften Nachkommastelle anders, was
Einbettungs-Rangfolgen und die Zerlegung bei Temperatur 0 kippt. `ollamaCpuBackend` ist Festpunkt
aller drei Pfade (Entscheidung 55). Grenzen: kein ARM-Host; gegen einen externen Endpunkt greift der
Pin nicht (`ollamaCpuBackend: null`); mit `-Dopaa.eval.allowGpu=true` trägt der Lauf
`unpinned: gpu allowed`; mit externem Chat-Modell (`-Dopaa.eval.chatBaseUrl`/`chatModel`, #1674)
verlangt die Prüfung nur den Embedding-Runner. Alle drei Läufe sind gegen keine Baseline vergleichbar.

#### 69. Stetigkeitsprüfung vor jeder Messung

`EvalEmbeddingContinuity.require` bettet nach dem Modell-Pull 21 Präfixe eines Korpustexts zwischen
1 100 und 1 500 Zeichen ein (Schritt 20 Zeichen), verlangt, dass sie die 512-Token-Grenze tatsächlich
überspannen, und dass kein benachbartes Paar unter Kosinus 0,98 fällt. Läuft in jedem Lauf, auch
gegen einen externen Endpunkt; prüft nur das Embedding-Modell des Laufs an einem Text.

#### Externer Endpunkt

`-Dopaa.eval.ollamaBaseUrl` (#1076) ist ein Iterationswerkzeug: Der Report trägt
`externalOllamaEndpoint: true` und `ollamaImage: "extern: …"`, `requireBaselineComparable` bricht
jeden Vergleich ab, und `BaselineOllamaOrigin` weist beim Laden jede Baseline-Datei mit dem Marker
`extern: ` zurück — ein Ziehfehler, dessen einziger Ausgang ein neuer Messlauf ist. Ein fehlender
Festpunkt wird dagegen nicht abgewiesen, sondern als `null` unvergleichbar gemeldet.

#### Docker-freie Wächter

`PipelinePathIsolationTest` (sechs Einzelfragen-Baselines) und `ConversationPathIsolationTest`
(Mehrrunden-Baseline) halten die committeten Dateien gegen `EvalOllamaEndpoint.PINNED_IMAGE`,
`EvalOllamaCpuBackend.PINNED`, `ContextPrefixFingerprint.current()`, die reale
`DocumentFormatRegistry`, `QueryProperties.Fusion.DEFAULT` und `RelevanceCutoffProperties.DEFAULT`.
Ein Pin- oder Vorgabewechsel ohne Baseline-Nachzug fällt damit im `check` des verursachenden PRs auf,
nicht erst im nächtlichen Docker-Lauf.

#### 28. `ingestionPipelineFingerprint` (mit 24, 25)

Sortierter Sammelabdruck `id:version` über alle von `DocumentFormatRegistry` gemeldeten
Pipelines (`IngestionPipelineFingerprint`), ein lesbarer String statt eines Hashes, damit ein Diff
benennt, welche Pipeline sich bewegt hat. Alle registrierten, nicht nur die vom Korpus genutzten,
damit ein Routing-Fehler sichtbar wird. Kein Harness-Guard: Der Wert wird aus der verdrahteten
Registry gelesen und ist für jeden Lauf korrekt. Die drei Korpora sind Markdown und laufen seit
#1103 über `MarkdownDocumentFormat` (Schnitt an Überschriften, Frontmatter am Dateianfang
verworfen; Entscheidungen 24, 25, Regeln in `docs/features/ingestion-pipelines.md`).

#### 57. `contextPrefixFingerprint`

SHA-256 über die Einbettungseingaben, die der Produktionscode für zehn feste Beispieldokumente
berechnet (`ContextPrefixFingerprint`), mit denselben Methoden wie Aufnahme und Nachlauf
(`DocumentIngest#declaredOver`, `CoreMetadataExtractor.extract`, `ChunkContextPrefix#ingestTitle`,
`#eligible`, `#documentWasSplit`, `#titleAtRest`, `#applyTo`, `CoreContextPrefixSettings#coreValues`).
Ein Abdruck statt einer Präfix-Version, weil eine Version an der Sorgfalt hängt, die #1341 fehlte.
Nicht abgedeckt: eigene Bibliotheksfelder und Schlagworte, der Rückweg gespeicherter Kernfeld-Werte,
modellgestützte Extraktion und Vokabular, Fundort und Schnittgrenzen (`ChunkLocationResolver`,
`HeadingSectionSplitter`), Bestandsläufe, Zweige ohne Beispiel — eine Änderung dort verlangt eine
bewusste Neumessung; wer einen neuen Zweig einführt, ergänzt ein Beispiel. `ContextPrefixFingerprintTest` prüft, dass jedes Beispiel in den Wert eingeht.

### Nummernverzeichnis der Entscheidungen 1–73

| Nummer | Steht heute in |
|---|---|
| 1, 2, 4, 5, 7 | Rohvektor-Pfad, Entscheidung 1 (Metrikdefinitionen, Fenster, Recall, Mittelung, `allQueryResults`) |
| 3 | Rohvektor-Pfad, Entscheidung 3 |
| 6 | Regeln, Entscheidung 6 |
| 8 | Rohvektor-Pfad, Entscheidung 8 |
| 9, 21 | Rohvektor-Pfad, Entscheidung 9 |
| 10 | Versionen des Rohvektor-Vertrags, Version 2 |
| 11, 14, 37 | Pipeline-Pfad, Entscheidung 11 |
| 12 | Pipeline-Pfad, Entscheidung 12 |
| 13, 18, 22, 71 | Pipeline-Pfad, Festpunkte (Entscheidung 13) und Entscheidung 22 |
| 32 | Festpunkte des Rohvektor- und des Pipeline-Pfads (`metadataFilterEnabled`) |
| 15, 20 | Pipeline-Pfad, Entscheidung 15 |
| 16, 41 | Regeln, Entscheidung 16 |
| 17 | Pipeline-Pfad, Entscheidung 17 |
| 19 | Pipeline-Pfad, Entscheidung 19 |
| 23 | Pipeline-Pfad, Entscheidung 22; Versionen des Pipeline-Vertrags, Version 3 |
| 24, 25 | Eval-Umgebung, Entscheidung 28 |
| 26, 27, 30, 31, 33, 35, 46, 47, 56, 58, 59, 64, 65, 70, 72, 73 | Versionstabellen und Änderungsverlauf (Messläufe, Neuziehungen, Fingerabdruck-Schritte); Messwerte in den `notes` der Baselines |
| 28, 29 | Eval-Umgebung, Entscheidung 28; Regeln, Entscheidung 29 |
| 34 | Berichtsgrößen ohne Vertragscharakter |
| 36 | Regeln, Entscheidung 36; Wert in ADR-0026, Entscheidung 5 |
| 38–40, 42–44, 48, 49, 51, 60–62 | Mehrrunden-Pfad |
| 45 | Eval-Umgebung, Entscheidung 68 und „Externer Endpunkt“ |
| 50 | Mehrrunden-Pfad, Entscheidung 50 |
| 52, 53 | Zustandsfelder, Entscheidung 52 |
| 54, 55 | Eval-Umgebung, Entscheidung 54 |
| 57 | Eval-Umgebung, Entscheidung 57 |
| 63 | Pipeline-Pfad, Entscheidung 63 |
| 66 | Regeln, Entscheidung 66 |
| 67 | Pipeline-Pfad, Entscheidung 11 (Tabelle: ohne Nachbar-Chunks) |
| 68, 69 | Eval-Umgebung |

## Konsequenzen

**Einfacher:**

- Jede Baseline sagt über Version und Festpunkte, unter welchem Vertrag sie entstand; „Baseline
  ungültig“ und „Regression“ sind zwei verschiedene Ausgänge.
- Die Metrikdefinitionen und Festpunkte sind reviewbar, ohne den Harness-Code zu lesen.
- Ein Pfad kann seinen Vertrag ändern, ohne die Baselines der anderen zu entwerten.

**Schwieriger:**

- Drei Vertragsversionen, sieben Baselines und ein Sammelabdruck über alle Ingestion-Pipelines: Jede
  Pipeline-Versionsänderung zieht einen Versionsschritt auf drei Pfaden und sieben Dateien nach, auch
  wenn kein Korpus sie erreicht.
- Die ungleichen Fenster (5, 10 Dokumente, 20 Chunk-Plätze) müssen bei jeder Darstellung mitgedacht
  werden.
- Der Mehrrunden-Pfad ist der instabilste der drei: ein Chat-Aufruf je Runde, dreimal; die Zahl der
  Runden mit abweichender Zerlegung ist die Aussage darüber, wie belastbar der Rest des Berichts ist.

**Restrisiken:**

- `contextPrefixFingerprint` deckt nicht jede Stelle ab, die die Einbettungseingabe bewegt
  (Entscheidung 57); die Stetigkeitsprüfung prüft ein Modell an einem Text (Entscheidung 69).
- Die Harnesse pinnen `nomic-embed-text:v1.5`, während das Produkt `bge-m3` ab Werk
  nutzt; die Baselines beschreiben nicht das ausgelieferte Embedding-Modell.
- Die Anker der harten Untergrenze des Pipeline-Pfads sind vor der ersten Messung gesetzt und seither
  nicht neu bewertet.

## Verworfene Alternativen

- Pipeline-Pfad unter der Rohvektor-Versionszählung — hätte jede Rohvektor-Baseline für eine
  unveränderte Messung entwertet.
- Eine gemeinsame Baseline-Datei oder ein gemeinsames Schema für beide Pfade — eine @10-Zahl könnte
  unter einem @20-Feldnamen landen, eine Neuziehung die Zahlen des anderen Pfads überschreiben.
- Chunk-Index statt wörtlichem `answer_span` — wird bei jeder Chunking-Änderung lautlos falsch.
- `answer_span`-Liste je Dokument bei mehrdokumentigen Fällen — eine neue Metrik mit eigener
  Trefferdefinition, die kein heutiger Messgegenstand braucht.
- Hash statt lesbarem String als Ingestion-Fingerabdruck — ein Diff müsste benennen, welche Pipeline
  sich bewegt hat.
- Präfix-Version statt Abdruck — hängt an der Sorgfalt, die bei #1341 fehlte.
- Fenster des Pipeline-Pfads bei 8 lassen und nur die ersten acht einer 20er-Auswahl messen — blendete
  aus, welche Dokumente das Modell zusätzlich sieht.
- Rangreserve als Fehlerkriterium — misst nur den ersten Treffer, ohne gezeigten Bedarf.
- Eigenes Ollama-Image mit einer CPU-Variante bauen — das Löschen der übrigen Bibliotheken legt die
  Auswahl ohne eigenes Image fest.
- Mehrrunden-Pfad an den Job der Einzelfragen anhängen — sprengte dessen Zeitbudget.
- Wechselrunde eines `topic_switch`-Falls aus den Erwartungsmengen ableiten — zählte richtige
  Vorthemen-Dokumente als Bleed.

## Offen

- Welches Chat-Modell der Einzelfragen-Pipeline-Pfad mit aktiver Zerlegung messen soll; die
  Entscheidung erhöht die Pipeline-Version und zieht die Pipeline-Baselines neu
  (`docs/features/retrieval-benchmark.md`, „Offene Punkte“).
- Wechsel der Harnesse auf `bge-m3`, das Vorgabemodell des Produkts.
- Wiederaufnahme der Rangreserve als Kriterium nur unter der in „Berichtsgrößen ohne
  Vertragscharakter“ genannten Bedingung.
