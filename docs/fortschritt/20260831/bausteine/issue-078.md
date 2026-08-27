# Issue #78 — Silent Error Fallback for Invalid Document IDs
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S
- PRs: keine

**Laut Issue:** In `QueryService` wird ein ungültiges UUID-Format nur auf DEBUG-Level geloggt (`log.debug("Invalid document ID format: {}", docId)`), was Datenkorruption oder Bugs in Produktion verschleiert. Gefordert war Anhebung auf WARN, ein Metrik-Zähler und Prüfung, warum ungültige IDs überhaupt auftreten.

**Geliefert:** Kein Kommentar und kein verknüpfter PR vorhanden — aber der Code zeigt die geforderte Änderung: `QueryService.java` protokolliert ungültige Dokument-IDs inzwischen an zwei Stellen mit `log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", docId)`. Die weitergehenden Vorschläge (Metrik-Zähler, systematische Ursachenklärung, Validierung bei der Indizierung) sind nicht erkennbar umgesetzt.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryService.java` Zeilen 553 und 695 bestätigen `log.warn(...)` für ungültige Dokument-IDs — Kernforderung (DEBUG → WARN) ist erfüllt, vermutlich beiläufig in einem größeren Query-Umbau statt als eigener PR für dieses Issue.

**Themen:** retrieval, logging, backend
