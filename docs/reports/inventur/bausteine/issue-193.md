# Issue #193 — fix(frontend): hamburger menu icon invisible in mobile header (white on white)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #669 (2026-08-20)

**Laut Issue:** Im mobilen Viewport (unterhalb `md`-Breakpoint) war das Hamburger-Icon im hellen Modus unsichtbar (weiß auf weiß), da `MobileHeader.tsx` nur `bgcolor: 'background.paper'` auf der `AppBar` setzte, während der geerbte Vordergrund (`primary.contrastText`, weiß) unverändert blieb und von `IconButton color="inherit"` übernommen wurde. Vorschlag: Vordergrund explizit setzen, z. B. `color: 'text.primary'` in der `sx`-Prop der `AppBar`. Ausdrücklich als Vorbestand markiert, nicht als Regression der MUI-9-Migration (#189/#191).

**Geliefert:** PR #669 setzt exakt den vorgeschlagenen Fix (`color: 'text.primary'` neben `bgcolor` in der `AppBar`-`sx`-Prop) und ergänzt `MobileHeader.test.tsx` mit einem Regressionstest. Reproduktionsnachweis im PR-Body dokumentiert: Test schlägt ohne Fix mit `expected 'var(--appbar-color)' to be 'rgb(1, 33, 66)'` fehl, besteht mit Fix. Deckt sich vollständig mit der Forderung, keine Abweichung.

**Verifikation:** `frontend/src/layouts/MobileHeader.tsx` enthält im heutigen Worktree `color: 'text.primary'` neben `bgcolor: 'background.paper'` in der `AppBar`-`sx`-Prop, der Fix ist unverändert vorhanden. `aria-label` wurde seither zusätzlich ins Deutsche übersetzt (`"Menü öffnen"`), was zur zwischenzeitlichen i18n-Konvention aus Issue #186 passt.

**Themen:** frontend, bug, mui, barrierefreiheit, ui
