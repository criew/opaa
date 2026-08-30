# Issue #996 — fix(deps): pnpm-Lockfile auf main nach Renovate-Auto-Merge-Serie gebrochen — Frontend-CI komplett rot

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S, ci
- PRs: #1003 (2026-08-28, gemeinsam mit #1001)

**Laut Issue:** Mehrere Lockfile-ändernde npm-Update-PRs mergten per Auto-Merge nacheinander,
ohne dass die späteren gegen den neuen Stand rebased waren. Die textuell konfliktfreie
Vereinigung der `pnpm-lock.yaml` war semantisch inkonsistent
(`ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY`); der Frontend-CI-Job war für jeden PR und main-Push
rot.

**Geliefert:** PR #1003 stellt `main` wieder her (Lockfile neu erzeugt, zusammen mit dem
Temurin-Revert aus #1001). Die strukturelle Absicherung gegen Wiederholung lieferte #1000
(npm-Updates gruppieren, Renovate-seitiger Merge mit Rebase).

**Verifikation:** Commit `93ab40f3` auf `main`; Frontend-CI seither grün.

**Themen:** Renovate, pnpm, Lockfile, CI-Ausfall
