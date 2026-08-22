# Issue #721 — feat(eval): Retrieval-Harness für mehrchunkige Dokumente ertüchtigen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:L, ci, evaluation
- PRs: #723 (2026-08-21)

**Laut Issue:** Der Retrieval-Harness konnte nur einchunkige Korpora messen (Ein-Chunk-Invariante, ADR-0010, hart verdrahtet). Vor der neuen mehrchunkigen Eval-Domäne (#234) sollte der Harness mehrchunkfähig werden: Chunk-Zahl-Eigenschaft als Domänen-Property, dokumentbezogenes k-Fenster (`documentTopK`) statt chunkbezogenem, neue Chunkebenen-Metrik über `answer_span`, Chunk-Map als Nebenprodukt, Domänen-Parametrisierung, erweiterte Baseline-Gültigkeitsfelder, `measurementContractVersion` erhöht, ADR-0010/ADR-0012 fortgeschrieben, Comichelden-Baseline neu gezogen (erwartet bitgleich).

**Geliefert:** Deckungsgleich, inklusive des geforderten Belegs der eigentlichen Fehlerwirkung (alter chunkbezogener vs. neuer dokumentbezogener topK, deutlich unterschiedliche nDCG@10/Recall@10 auf einem synthetischen Korpus). Comichelden-Baseline blieb bis auf eine erklärte Rundungs-Tie (0,912→0,913 bei `difficulty:easy`/`mrr`, kein Rechenunterschied) bitgleich. Bewusst nicht umgesetzt: Gradle-Task-Parametrisierung über mehrere Domänen (als spekulativ verworfen, solange nur eine Domäne existiert) und ein zweiter echter Testcontainers-Lauf für die Mehr-Chunk-Invariante (stattdessen vollständig Docker-frei unit-getestet) — beides im PR offen begründet, keine verschwiegene Lücke.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/ChunkAnswerSpanMetrics.java` und `ChunkCountExpectation.java` existieren im Worktree.

**Themen:** evaluation, retrieval, ci, chunking, adr
