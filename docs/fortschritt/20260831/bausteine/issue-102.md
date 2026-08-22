# Issue #102 — Add branch protection rules for main
- Geschlossen: 2026-03-06 (completed)
- Labels: setup, size:S, ci
- PRs: #103 (2026-03-06)

**Laut Issue:** Branch-Protection für `main` einrichten: Pflicht-Statuschecks (backend, backend-integration, frontend), 1 Pflicht-Approval, veraltete Reviews verwerfen, Konversationsauflösung erzwingen, direkte Pushes verhindern.

**Geliefert:** PR #103 fügt `.github/settings.yml` (probot/settings-App-Konfiguration) mit genau diesen Regeln hinzu. Erfordert laut PR-Beschreibung zusätzlich die Installation der probot/settings-GitHub-App, damit die Regeln tatsächlich angewendet werden — das ist ein externer, nicht im PR selbst nachprüfbarer Schritt.

**Verifikation:** `.github/settings.yml` existiert im heutigen Worktree weiterhin.

**Themen:** ci, github, projektsetup, branch-protection
