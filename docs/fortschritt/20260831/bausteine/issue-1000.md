# Issue #1000 — ci(renovate): Gleichzeitige Lockfile-Updates gegen semantische Merge-Brüche absichern

- Geschlossen: 2026-08-28 (completed)
- Labels: enhancement, size:S, ci
- PRs: #1008 (2026-08-28)

**Laut Issue:** Der Lockfile-Bruch #996 wiederholt sich potenziell an jedem Renovate-Tag mit
mindestens zwei gleichzeitig grünen npm-Updates — die Konfiguration muss gleichzeitige
Lockfile-Änderungen strukturell absichern.

**Geliefert:** PR #1008 gruppiert npm-non-major-Updates zu einem Sammel-PR (`groupName:
'npm (non-major)'`) und stellt für Lockfile-ändernde Updates auf Renovate-seitigen Merge um
(`platformAutomerge: false` für die Gruppe), sodass Renovate vor dem Merge gegen den aktuellen
Stand rebased.

**Verifikation:** `renovate.json5` führt die npm-Gruppierung und den abweichenden Merge-Weg.

**Themen:** Renovate, pnpm, Lockfile, CI-Härtung
