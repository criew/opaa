# Issue #914 — Query: MMR-Diversität im Retrieval (fetchK, mmrLambda) und topK-Anhebung
- Geschlossen: 2026-08-26 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: #922 (2026-08-26)

**Laut Issue:** Maßnahmen A+D aus #912. Gefordert: eine MMR-Nachauswahl (`fetchK`-Kandidaten, dann Diversitäts-Reduktion auf `topK`), neue `QueryProperties`-Parameter `fetchK` (Default ~25) und `mmrLambda` (Default ~0,7), Anhebung des `topK`-Defaults von 5 auf 8, sowie der Nachweis, dass Berechtigungsfilter und Ähnlichkeitsschwelle unverändert vor der MMR-Auswahl gelten und keine zusätzlichen API-Aufrufe entstehen.

**Geliefert:** `MmrSelector` mit echten Chunk-Embeddings (per SQL-Lookup über `ChunkEmbeddingLookup`, kein API-Aufruf) statt der zunächst erwogenen Jaccard-Textnäherung — Kurskorrektur nach dem ersten Review. `topK`-Default wie gefordert auf 8 angehoben. **Wesentliche Abweichung vom Issue:** `mmrLambda` startet mit Default **1,0** (MMR de facto abgeschaltet), nicht 0,7 wie im Issue vorgeschlagen — Messungen auf den 20 `multi_topic`-Fällen zeigten `mmrLambda=0,7` bei 19/20 gegenüber 20/20 für reines topK ohne MMR; die im PR festgelegte Entscheidungsregel verlangte mindestens Gleichstand mit dem `topK`-only-Ergebnis. MMR ist vollständig implementiert und per Konfiguration aktivierbar, aber kein Produktions-Default.

**Verifikation:** `backend/src/main/java/io/opaa/query/MmrSelector.java` existiert im Worktree; `top-k: ${OPAA_QUERY_TOP_K:8}` in `application.yml` bestätigt.

**Themen:** retrieval, query, mmr, evaluation, epic-912
