# Issue #113 — feat(workspace): personal workspace auto-creation
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:S, workspace
- PRs: #140 (2026-03-07)

**Laut Issue:** Bei Erstanmeldung automatisch einen persönlichen Workspace („My Documents", Typ `PERSONAL`, Owner-Mitgliedschaft) anlegen; persönliche Workspaces dürfen nicht löschbar sein und keine weiteren Mitglieder bekommen; idempotent bei wiederholter Anmeldung.

**Geliefert:** PR #140 liefert die automatische Anlage bei Erstanmeldung inkl. Idempotenz-Sicherung sowie die Validierungen (keine Mitglieder, keine Löschung) — gemeinsam mit Issue #114 in einem PR (siehe dortiger Baustein für die Details der Mitgliederverwaltung).

**Verifikation:** Wie bei #111/#112 — das `workspace`-Paket ist im heutigen Code durch `space` ersetzt; die Auto-Anlage eines persönlichen Bereichs ist als Konzept im Space-Modell plausibel weitergeführt (nicht im Detail nachgeprüft, außerhalb des Chunk-Umfangs).

**Themen:** workspace, spaces, backend, onboarding
