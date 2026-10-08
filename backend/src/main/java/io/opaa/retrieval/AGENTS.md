# Modul retrieval

Pakete (`io.opaa.*`): retrieval. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Von der Frage zu den ausgewählten Chunks: die Retrieval-Pipeline. Im Wurzelpaket liegen der Rahmen
(Stufenvertrag, Kontext, Zustand, Erklärprotokoll), der Einstieg `KnowledgeRetrieval`, die
`RetrievalContextFactory` und die Parameter (`QueryProperties`, `RetrievalPipelineProperties`).
Die Stufen liegen in `scope` (Suchbereich, Metadatenfilter), `search` (Teilfragen-Zerlegung, Vektor-
und Volltextsuche) und `ranking` (MMR, Fusion, Reranking, Dokumentergänzung), ihre Reihenfolge in
`config`. retrieval hängt nur von foundation, format und knowledge ab. Antwort (`query`), Suche als
Dienst (`search`) und Diagnose (`searchadmin`) im Modul assistant setzen darauf auf.

## Invarianten und Stolpersteine

- **Eine Rangfolge:** Anfragen von außen laufen über `KnowledgeRetrieval` (Chat-Abfrage und
  `search`). Die Diagnose und der Eval-Harness fahren `RetrievalPipeline` selbst, holen den Kontext
  aber wie alle über `RetrievalContextFactory`. Eine zweite Rangfolge daneben gibt es nicht.
- **Rechte kommen von oben:** Der Suchbereich wird fertig aufgelöst übergeben; `SearchScopeStage`
  macht daraus den Bibliotheksfilter jeder Suchstufe und hält den Lauf bei leerem Bereich an.
  retrieval kennt weder Rechte noch Räume noch Chats, `ALLOWED_MODULE_EDGES` hält das fest.
- **Die Reihenfolge der Stufen steht nur in `config.RetrievalConfiguration#retrievalPipeline`.**
  Eine neue Stufe wird dort eingefügt und bekommt einen `RetrievalStageName`.
- **Das Wurzelpaket nennt keine Stufe.** Stufen hängen vom Rahmen ab, nur `config` nennt alle.
  So bleibt retrieval ohne Zyklen zwischen seinen Unterpaketen.
- **Vektorsuche nur über `VectorChunkSearch`:** iterativer HNSW-Scan mit transaktionslokalen
  Einstellungen, sonst liefert ein gefilterter Scan still weniger als fetch-k (#2345).
- **Jede Stufe schreibt ihr Protokoll** (`StageOutcome` gibt es nicht ohne `StageExplanation`). Eine
  abgeschaltete Stufe erscheint als `DISABLED`; die Diagnose liest das Protokoll, statt
  Entscheidungen nachzubauen.
- **Konfiguration:** `opaa.query.*` (`QueryProperties`), `opaa.query.pipeline.*` und
  `opaa.query.vector-index.*` (`VectorIndexScanProperties`). `QueryProperties`
  trägt auch die Breite des Gesprächsfensters, die die Antwort liest: Die Suche sieht nie mehr vom
  Gespräch als die Antwort.

## Verweise

- ADRs (`docs/decisions/`): 0010, 0011, 0012, 0013, 0031
- Handbuch: `docs/handbuch/suche.md`; Spezifikation: `docs/features/retrieval-algorithm.md`,
  `docs/features/hybrid-retrieval.md`; Evaluierung: `eval/README.md`
- Strukturtests: `ModularArchitectureTest`, `SearchDependencyStructureTest`,
  `SearchDiagnosisRerankParityTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.retrieval.*' --tests 'io.opaa.query.*' --tests 'io.opaa.search.*' \
  --tests 'io.opaa.searchadmin.*' --tests 'io.opaa.architecture.*'
```

retrieval besitzt keine Tabelle. Änderungen an Treffern oder Scores misst `./gradlew
checkRetrievalBaseline` gegen die Baseline (braucht Docker, nicht Teil von `build`), in CI der
Workflow „Retrieval-Regression" (Label `evaluation`). Einen verhaltensneutralen Umbau belegt der
Protokoll-Dump (`-Dopaa.eval.explanationDumpDir`, `eval/README.md`).
