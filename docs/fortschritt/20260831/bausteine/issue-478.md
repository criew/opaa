# Issue #478 — feat(indexing): Indizierungsanstoß je Bibliothek aus gespeicherter Konfiguration
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #500 (2026-08-19)

**Laut Issue:** Der Indizierungsanstoß sollte von einem Request mit voller Konfiguration auf einen reinen Bibliotheksverweis umgestellt werden: neuer Endpunkt je Bibliothek, `UPLOAD` → 409, Nebenläufigkeit je Bibliothek statt global, alter globaler Endpunkt samt `url`-Fallback entfällt.

**Geliefert:** Wie gefordert, mit einer bewussten Zusatzänderung: `POST /api/v1/libraries/{libraryId}/indexing` ersetzt `POST /api/v1/indexing/trigger`, `IndexingTriggerRequest` entfällt vollständig. Nebenläufigkeit ist jetzt je Bibliothek (`IndexingJobService#isJobRunning(UUID)`). Zusätzlich zum Issue-Umfang: Die frühere `SYSTEM_ADMIN`-Schranke des Trigger-Endpunkts wurde bewusst fallengelassen (ADR-0018, Entscheidung 2) — es genügt jetzt `EDITOR` auf der Bibliothek. `opaa.indexing.document-path` bleibt als totes, ungenutztes Konfigurationsfeld bestehen (im PR selbst als Aufräumkandidat vermerkt).

**Verifikation:** `backend/src/main/java/io/opaa/api/IndexingController.java` und `LibraryController.java` existieren; der bibliotheksbezogene Indizierungsendpunkt ist heute Standard (auch von späteren Issues wie #485, #501, #513 weiter ausgebaut). Kein Hinweis, dass der globale Endpunkt zurückgekehrt wäre.

**Themen:** backend, indexing, retrieval, adr, sicherheit
