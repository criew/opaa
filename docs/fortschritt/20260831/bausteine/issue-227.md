# Issue #227 — test(eval): Retrieval-Metrik-Harness (Hit Rate, MRR, nDCG, Recall)
- Geschlossen: 2026-08-03 (completed)
- Labels: enhancement, backend, size:L, evaluation
- PRs: #292 (2026-08-03)

**Laut Issue:** JUnit-Integrationstest, der den eingefrorenen Korpus über die produktive Indizierungs-Pipeline indiziert, alle Golden-Queries gegen den Vektor-Store ausführt und Hit Rate@5, MRR, nDCG@10, Recall@10 berechnet — gesamt sowie je Kategorie/Schwierigkeit/Sprache. Eigener Gradle-Task außerhalb von `build`, Manifest-Prüfung vor dem Lauf, maschinenlesbarer Report mit Lauf-Konfiguration, Stabilitätsnachweis (<0,01 Abweichung zwischen zwei Läufen), Ausweis der zehn schlechtesten Anfragen.

**Geliefert:** Eigenes Gradle-Source-Set `backend/src/evalTest/` mit Task `evaluateRetrieval` (bewusst nicht an `build`/`check`/`test` gehängt), Testcontainers pgvector + Ollama/`nomic-embed-text`. Prüft zusätzlich zur Manifest-Summe die Ein-Chunk-Invariante aus ADR-0010 (jedes Dokument muss genau einen Chunk ergeben) und benennt Verletzungen. JSON-Report unter `backend/build/eval-reports/retrieval-metrics.json`. Gemessene Baseline-Werte: Gesamt-nDCG@10 0,463 (später bei #228 auf 0,445 mit gepinntem Modell korrigiert). ADR-0012 zum Messvertrag neu angelegt. Kein Abweichen vom Issue-Umfang erkennbar.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/` enthält alle im PR genannten Klassen (`RetrievalEvaluationHarnessTest.java`, `CorpusManifest.java`, `MetricsAggregate.java`, `ReportWriter.java` u. a.) plus seither ergänzte Domäne `city-landmarks` (Issue #234-Folgearbeit). Task `evaluateRetrieval` im `backend/build.gradle.kts` vorhanden. ADR-0012 liegt unter `docs/decisions/`.

**Themen:** eval, retrieval, backend, testinfrastruktur, gradle, doku
