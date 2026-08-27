# Issue #853 — fix(a11y): fg-3 in Sidebar- und Rail-Theme unterschreitet 4,5:1 auf Hover- und Aktiv-Flächen
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, frontend, size:S
- PRs: #878 (2026-08-24)

**Laut Issue:** Als vorbestehender Befund beim Review von PR #852 (#725) identifiziert. `navyRoles.fg3`/`railRoles.fg3` erreichen rechnerisch gegen bg-2/bg-3 (Hover-Füllung, aktive Kachel) nicht die 4,5:1-Kontrastschwelle. Noch nicht belegt, ob fg-3-Text tatsächlich dort landet — erster Schritt ist die Verifikation.

**Geliefert:** Verifikation ergab: fg-3 wird in `Sidebar.tsx`/`GlobalRail.tsx` nirgends als Text auf bg-2/bg-3 gerendert — Hover und aktive Kachel wechseln explizit auf fg-1 (Weiß). `MuiTableCell`-Kopf und `OutlinedInput`-Hover-Rahmen (Konsumenten von `roles.fg3`) kommen im Sidebar-/Rail-Theme gar nicht zum Einsatz. Damit lag kein tatsächlicher Kontrastfehler im UI vor — `tokens.ts` blieb unverändert. Stattdessen: ein Vitest-Wächter gegen die reale Textgrundfläche (bg-1) und eine explizite Dokumentation der Einschränkung in `guidelines.md`, damit künftige Komponenten fg-3 nicht versehentlich auf bg-2/bg-3 einsetzen.

**Verifikation:** `frontend/src/theme/theme.test.ts` und `docs/design/guidelines.md` im Worktree vorhanden.

**Themen:** frontend, barrierefreiheit, theme, ui
