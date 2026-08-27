# Issue #786 — feat(frontend): Globale Leiste (Rail) als immer sichtbare erste Navigationsebene
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #791 (2026-08-23)

**Laut Issue:** Nach Mockup-Abschnitt 2a sollte Globales und Space-Bezogenes räumlich getrennt werden: eine schmale, immer sichtbare globale Leiste (Rail) links mit Logo, Spaces/Katalog/Admin-Einträgen und Avatar-Menü unten, während die bisherige Navy-Seitenleiste zur reinen Space-Spalte (Space-Wechsler, Chat-Liste, space-bezogene Fußlinks) wird. Verlangt waren zudem responsives Verhalten, beide Farbschemata, Barrierefreiheit nach Landmarken-Konzept und aktualisierte Mockups im Repo.

**Geliefert:** Wie gefordert umgesetzt: neue `GlobalRail`-Komponente (64 px, eigene Landmark `nav aria-label="Globale Navigation"`, Logo, Spaces/Katalog/Admin mit `aria-current`, Avatar-Menü), Navy-Spalte auf 248 px verschlankt und auf Space-Wechsler/Chat-Liste/Fußlinks reduziert (Landmark-Struktur `aside "Space-Bereich"` mit `nav "Chats"`). Neue `railRoles`-Tokens/`createRailTheme` für beide Farbschemata; mobiles Verhalten über den Drawer gelöst. Mockups wurden bereits im vorgelagerten PR #790 eingecheckt. Vollständiger lokaler E2E-Lauf (34/34) inkl. axe-A11y-Suite gegen die neue Landmark-Struktur bestanden. Folge-Issues #787–#789 bauen explizit auf dieser Rail auf.

**Verifikation:** `frontend/src/layouts/GlobalRail.tsx` existiert im Worktree weiterhin.

**Themen:** frontend, barrierefreiheit, doku
