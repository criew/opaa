# Issue #112 — feat(workspace): workspace CRUD API
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #132 (2026-03-07)

**Laut Issue:** REST-API zum Erstellen, Auflisten, Ansehen und Löschen von Workspaces — Erstellung nur durch System-Admin, Auflistung nach Mitgliedschaft, Details mit Mitgliederzahl/Rolle, Update durch Admin/Owner, Löschung durch Owner/System-Admin (persönliche Workspaces nicht löschbar), Fehlerbehandlung (404/403/409), Integrationstests mit Testcontainers.

**Geliefert:** PR #132 liefert Controller, Service-Logik und DTOs für alle fünf Endpunkte wie gefordert. Bemerkenswert: Die Autorisierung für „System-Admin only" beim Erstellen läuft laut PR-Beschreibung über ein Request-Header-Flag statt über die in #110 eingeführte `@PreAuthorize`-Rollenprüfung — möglicherweise ein Zwischenstand vor der vollständigen Zusammenführung mit #110/#111. Die PR-Checkliste vermerkt zudem ausdrücklich, dass die volle Backend-Testsuite zum Zeitpunkt des PRs noch durch den Status des Vorgänger-Tickets blockiert war (nur gezielte Workspace-Tests liefen lokal).

**Verifikation:** Wie bei #111 — das `workspace`-Paket existiert im heutigen Code nicht mehr, abgelöst durch `space`. Der `WorkspaceController` von damals ist nicht mehr auffindbar; die CRUD-Funktionalität lebt heute im Space-Controller-Äquivalent fort (nicht im Detail nachgeprüft, da außerhalb des Chunk-Umfangs).

**Themen:** workspace, spaces, backend, api
