# Issue #1002 — fix(ci): Auto-gemergtes temurin-v25-Major bricht Backend-Image-Build — Majors vom Auto-Merge ausnehmen

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, backend, size:S, ci
- PRs: #1004 (2026-08-28)

**Laut Issue:** Das Major-Update #988 gelangte ungeprüft per Auto-Merge in `main` und brach den
Image-Build deterministisch. Major-Updates brauchen eine bewusste Freigabe statt Auto-Merge.

**Geliefert:** PR #1004 nimmt Major-Updates in `renovate.json5` vom Auto-Merge aus
(`automerge: false` für Majors); Majors bleiben als PR liegen, bis ein Maintainer sie prüft.

**Verifikation:** `renovate.json5` führt die Major-Ausnahme mit Begründungskommentar.

**Themen:** Renovate, Auto-Merge, Major-Updates, CI-Härtung
