# Issue #125 — test: end-to-end tests for workspace flow
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, frontend, size:L, workspace
- PRs: keine

**Laut Issue:** Umfassende E2E-Tests über den kompletten Workspace-Lebenszyklus (Auth, Verwaltung, Upload, rechtebasierte Suche, Rollen, Cross-Workspace-Isolation, Kontingente/Dubletten) mit Testcontainers, CI-Integration, Ausführungszeit < 5 Minuten.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR, weil die Testszenarien durchgehend workspace-formuliert waren und das inzwischen abgelöste Modell trafen. Laut Schließungskommentar existiert stattdessen eine eigene Playwright-Suite unter `e2e/` (#231), ergänzt um #232/#233 für Indizierung und Suche im Demo-Korpus. Neue E2E-Abdeckung für das Space-/Asset-Modell wird im Rahmen von Epic #198 nachgezogen, sobald die betroffenen Ausbaustufen stehen.

**Verifikation:** `e2e/`-Verzeichnis mit Playwright-Suite existiert im Worktree (siehe `AGENTS.md`-Verweis auf `e2e/README.md`), bestätigt die im Schließungskommentar genannte Alternative.

**Themen:** workspaces, e2e, testing, spaces, migration, verworfen
