# Issue #784 — Englische MUI-Standardtexte („No options", „Loading…", aria-Labels) statt deutscher Lokalisierung
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend, size:S
- PRs: #785 (2026-08-23)

**Laut Issue:** Die leere Vorschlagsliste der Bibliothekszuordnung im Space zeigte den englischen MUI-Standardtext „No options", da fünf `Autocomplete`-Verwendungen kein `noOptionsText` setzten; darüber hinaus liefert MUI ungefiltert weitere englische Defaults (`loadingText`, `clearText`/`openText`/`closeText`). Gefordert war ein globaler Ansatz über die MUI-Lokalisierung (`deDE`) statt punktueller Fixes, plus kontextspezifische Texte an den betroffenen Stellen wo sinnvoll.

**Geliefert:** Wie gefordert. `deDE` aus `@mui/material/locale` wird jetzt global in `createTheme` (`frontend/src/theme/theme.ts`) eingebunden, wodurch alle MUI-Standardtexte projektweit deutsch erscheinen. Die fünf betroffenen `Autocomplete`-Stellen erhielten zusätzlich kontextspezifische deutsche Texte. Nebeneffekt dokumentiert: die globale Lokalisierung änderte auch aria-Labels bereits bestehender Komponenten (Alert-Schließen-Button, Pagination), zwei bestehende Tests wurden entsprechend angepasst.

**Verifikation:** `frontend/src/theme/theme.ts` enthält weiterhin `deDE` (2 Fundstellen im Worktree).

**Themen:** frontend, doku, barrierefreiheit
