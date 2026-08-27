# Issue #773 — fix(ai): Suchqualitäts-Regression durch Embedding über Ollamas /v1-Endpunkt (statt nativer API)
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:M, evaluation
- PRs: #779 (2026-08-23)

**Laut Issue:** Seit der Umstellung auf den OpenAI-kompatiblen Embedding-Pfad (#762/PR #766) brach die Suchqualität messbar ein — CI-Timeout im Retrieval-Regression-Workflow und lokal ein `checkRetrievalBaseline`-Fehlschlag mit Deltas bis -0,234 bei `hitRateAt5`. Das Issue benannte mehrere Verdachtsursachen (Truncation, Prompt-Präfix, Batching) und zwei mögliche Wege: Revert auf den nativen Ollama-Starter oder Ursachenbehebung im `/v1`-Pfad, mit dem Eval-Harness als Schiedsrichter.

**Geliefert:** Die tatsächliche Ursache war eine Metadaten-Kontamination: `OpenAiEmbeddingModel` embeddet (anders als der vorherige `OllamaEmbeddingModel`) den über `MetadataMode.EMBED` formatierten Dokumenttext inklusive der fünf Bookkeeping-Metadatenfelder (`document_id`, `chunk_index`, `file_name`, `library_id`, `organization_id`), während Suchanfragen weiterhin reinen Text embedden — ein Index-vs-Query-Vektorraum-Mismatch. Fix: `FileProcessingService.CHUNK_EMBED_CONTENT_FORMATTER` schließt diese Felder aus und setzt ein reines Textformat je Chunk. Der zunächst als Fallback offengehaltene Revert-PR #774 wurde nicht gemerged. Nach dem Fix lief `checkRetrievalBaseline` lokal und in CI wieder exakt auf Baseline. `docs/deployment.md` erhielt einen Neuindizierungshinweis für Bestandsinstallationen, deren zwischen #766 und #773 indizierte Vektoren weiterhin kontaminiert bleiben.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java` existiert im Worktree weiterhin.

**Themen:** retrieval, indexing, modellverwaltung, evaluation, doku
