# Issue #734 — Ollama-Embedding-Aufrufe in io.opaa.indexing parallelisieren (city-landmarks-Eval-CI zu langsam)
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, evaluation
- PRs: #735 (2026-08-22)

**Laut Issue:** Der CI-Job `evaluate-city-landmarks` brauchte auf dem GitHub-Actions-Runner überproportional lange (hochgerechnet ~115 Minuten für 200 Dokumente, Faktor >3 gegenüber lokal), vermutlich weil Embedding-Aufrufe pro Dokument sequenziell an Ollama gehen. Vorschlag: Chunk-Embedding-Aufrufe parallelisieren oder bündeln, betrifft die Indizierungs-Pipeline insgesamt.

**Geliefert:** Teilweise, mit im PR offen benannter Abweichung vom ursprünglichen Ziel: `FileProcessingService#storeChunks` embeddet Chunks eines Dokuments jetzt konfigurierbar nebenläufig (`opaa.indexing.embedding-concurrency`, Default 3), mit gemessenem, aber auf CPU-gebundenem lokalem Ollama nur geringem Gewinn (Faktor 1,05×) und deutlicherem Gewinn gegen ein simuliertes latenzgebundenes API-/GPU-Backend (bis 1,22×). **Löst die eigentliche CI-Laufzeit-Regression ausdrücklich nicht** — der Regressionsjob bleibt bei `embedding-concurrency=1` gepinnt, weil pgvectors HNSW-Indexaufbau einfügereihenfolge-sensitiv ist und die Baseline sonst nicht mehr reproduzierbar wäre. Der Nutzen liegt laut PR im Produktivbetrieb mit latenzgebundenen Backends, nicht im CI-Job, den das Issue ursprünglich adressierte; die CI-Laufzeit bleibt über das Zeitbudget in der Workflow-Datei aufgefangen.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java` existiert im Worktree.

**Themen:** indexing, performance, ci, evaluation, embeddings
