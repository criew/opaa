# Issue #951 — feat(ci): Renovate-PRs mit aktiviertem GitHub-Auto-Merge eröffnen

- Geschlossen: 2026-08-28 (completed)
- Labels: enhancement, ci
- PRs: #952 (2026-08-28)

**Laut Issue:** Maintainer-Anweisung: Renovate soll auf seinen Update-PRs GitHubs natives
„Enable auto-merge" (Squash) aktivieren, damit Update-PRs automatisch mergen, sobald die
Required Checks grün sind; die Renovate-Bot-Ausnahme in AGENTS.md ist entsprechend zu ergänzen.

**Geliefert:** PR #952 setzt `automerge: true` mit `platformAutomerge` und Squash-Strategie in
`renovate.json5` und ergänzt die AGENTS.md-Ausnahme. Die Betriebserfahrung des ersten
Auto-Merge-Tages führte zu Nachschärfungen: Majors vom Auto-Merge ausgenommen (#1002),
npm-Updates gruppiert und Renovate-seitig gemergt (#1000).

**Verifikation:** `renovate.json5` führt `automerge: true`, `platformAutomerge: true`,
`automergeStrategy: 'squash'`; AGENTS.md dokumentiert die Ausnahme.

**Themen:** Renovate, CI, Abhängigkeitsverwaltung, Auto-Merge
