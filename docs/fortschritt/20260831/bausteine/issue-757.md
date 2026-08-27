# Issue #757 — feat(models): Admin-API für Chat-Modelle (CRUD, Aktivierung, Verbindungstest)
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #764 (2026-08-22)

**Laut Issue:** Phase 2 des Epics #755, aufbauend auf dem Datenmodell aus #756. Gefordert: spec-first-Erweiterung der OpenAPI um Ressource `models` (Liste, Anlegen, Ändern, Löschen, `activate`, `test`), Zugriff ausschließlich für `SYSTEM_ADMIN`, API-Schlüssel als schreibend (nie zurückgelesen, nur "gesetzt"/"nicht gesetzt"), Aktivierung transaktional exakt ein aktives Modell, Verbindungstest mit unterscheidbaren deutschen Fehlermeldungen und Zeitlimit, Verweigerung des Löschens des aktiven Modells mit 409, Audit-Ereignisse für alle vier Änderungsarten.

**Geliefert:** Wie gefordert, plus drei Nachbesserungen aus dem Review von #756/#763: (1) Löschschutz für das aktive Modell mit 409 vor dem eigentlichen Löschaufruf; (2) `DataIntegrityViolationException` bei gleichzeitiger Aktivierung wird auf eine handlungsleitende deutsche Meldung statt der generischen 409-Meldung des `GlobalExceptionHandler` abgebildet; (3) neues Audit-Ereignis `LLM_MODEL_DEACTIVATED` für das bisher aktive Modell bei jeder Aktivierung (Migration 061), damit "wann hörte Modell X auf, aktiv zu sein" nicht nur indirekt aus fremden `LLM_MODEL_ACTIVATED`-Ereignissen ablesbar ist. `LlmModelConnectionTester` verzichtet bewusst auf den `TargetAddressValidator`-SSRF-Schutz, da ein lokal betriebener Ollama-Server im eigenen Netz der vorgesehene Regelfall ist, keine Bedrohung.

**Verifikation:** `backend/src/main/java/io/opaa/api/LlmModelController.java` und `backend/src/main/java/io/opaa/llm/LlmModelConnectionTester.java` existieren im Worktree. Die zugehörige Migration 061 wurde wie 058 in die Liquibase-Baseline (#906) konsolidiert und liegt nicht mehr als Einzeldatei vor.

**Themen:** modellverwaltung, backend, security, api, audit
