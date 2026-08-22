# Issue #11 — feat(query): implement vector similarity search
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, size:M
- PRs: keine

**Laut Issue:** Retrieval-Komponente der RAG-Pipeline implementieren: Nutzerfrage mit demselben Embedding-Modell wie beim Indexing einbetten, Vektor-Ähnlichkeitssuche gegen `document_chunks` (Kosinus-Ähnlichkeit) durchführen, Top-K konfigurierbar (Default K=5), `RetrievalResult`-DTO mit Datei, Chunk-Text, Relevanzscore, sortiert nach Relevanz.

**Geliefert:** Kein eigener PR verknüpft. `stateReason` ist „completed", nicht „not planned" — das Issue wurde also nicht verworfen, sondern vermutlich zusammen mit #12 in einem gemeinsamen PR erledigt und dabei fälschlich nicht separat verlinkt (kein „Closes #11" im PR-Body von #36). Prüfung im Code: `QueryService.java` wurde laut `git log --diff-filter=A` im selben Commit „feat(query): implement LLM answer generation with source references" (PR #36, schließt #12) neu angelegt. Das bedeutet: Die Retrieval-Funktionalität aus #11 wurde faktisch als Teil von PR #36 mitgeliefert, nicht als eigenständiger PR — eine Zuordnungslücke in den GitHub-Daten, kein tatsächlich fehlendes Feature.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryService.java` existiert im heutigen Worktree.

**Themen:** backend, retrieval, rag, pgvector, dokumentationslücke
