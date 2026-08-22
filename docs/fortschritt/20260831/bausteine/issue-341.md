# Issue #341 — docs: Einstiegsdokumente und Umsetzungsstand an die neue Ausrichtung angleichen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #372 (2026-08-14)

**Laut Issue:** `README.md` neu im Verwaltungsframe (drei Säulen, tatsächlicher Stack statt „wird während der Implementierung entschieden"), `docs/CONCEPTS.md` um neue Begriffe erweitert, `docs/STATUS.md` (neu) statt `MVP.md`/`MVP-STATUS.md` mit ehrlichem Stand je Themenbereich A–K, `docs/INDEX.md`/`docs/GETTING-STARTED.md` an neue Rollen-/Lesepfade angepasst.

**Geliefert:** PR #372 liefert alle geforderten Dateien und ersetzt zusätzlich `docs/MVP-VERIFICATION.md`. `docs/STATUS.md` benennt laut PR-Beschreibung unangenehme Befunde offen (Themenbereich D/Agenten ohne Code und ohne offene Vorgänge, Themenbereich K leer, kein revisionssicheres Audit-Log, keine Konnektoren/Chat-Kanäle) statt sie zu beschönigen. Verweiskorrekturen zusätzlich in `docs/AGENT-ORGANIZATION.md`, `agents/roles/product-manager.md`, `agents/roles/qa-engineer.md`, zwei ADRs und einer Diskussionsnotiz — historische ADRs wurden dabei bewusst nicht rückwirkend umgeschrieben, nur ergänzt. Der PR musste laut eigenem Hinweis als letzter der Merge-Kette gemergt werden, da er auf #359/#365/#366/#368/#371 verweist.

**Verifikation:** `README.md`, `docs/STATUS.md`, `docs/CONCEPTS.md`, `docs/INDEX.md`, `docs/GETTING-STARTED.md` existieren; `docs/MVP.md`, `docs/MVP-STATUS.md`, `docs/MVP-VERIFICATION.md` existieren nicht mehr im Worktree — Ablösung bestätigt.

**Themen:** doku, produktvision, status, einstieg
