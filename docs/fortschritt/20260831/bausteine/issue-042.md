# Issue #42 — feat: Distinct-Darstellung der Quellen ohne Duplikate
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, frontend
- PRs: #46 (2026-02-26)

**Laut Issue:** Quellenangaben in Antworten konnten Duplikate enthalten — dasselbe Dokument mehrfach gelistet. Gefordert war Deduplizierung, bevorzugt auf Backend-/API-Ebene, ggf. mit Gruppierung nach Score/Relevanz, sodass die relevanteste Referenz pro Dokument angezeigt wird.

**Geliefert:** PR #46 dedupliziert in `QueryService.mapSources()` per `Collectors.toMap()` gruppiert nach `fileName`, behält jeweils die Referenz mit dem höchsten `relevanceScore`, stabile Reihenfolge über `LinkedHashMap`. Genau die im Issue vorgeschlagene Backend-Lösung, keine Frontend-Deduplizierung nötig. Zusätzlich wurde die RAG-Feature-Spec (`data-indexing-rag.md`) entsprechend dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryService.java` enthält weiterhin `mapSources(...)` mit Dedup-Logik nach `fileName`; ein Codekommentar referenziert sogar eine spätere Review-Klarstellung ("#639 review: the dedupe key is fileName, not document_id"), was zeigt, dass die Logik im Kern bis heute Bestand hat und weiterentwickelt wurde.

**Themen:** backend, retrieval, quellenanzeige, deduplizierung
