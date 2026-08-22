# Issue #148 — feat: Dark/Light Mode Toggle in User Preferences
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement
- PRs: keine

**Laut Issue:** Ein Umschalter zwischen Dark- und Light-Mode in den Nutzereinstellungen, global angewendet über MUI `ThemeProvider`, persistiert (localStorage/Zustand), mit Systemvoreinstellung als Default und barrierefreier Bedienung.

**Geliefert:** Keine PR-Verknüpfung und kein Schließungskommentar vorhanden. Der heutige Code zeigt jedoch, dass die Funktion existiert: `frontend/src/stores/uiStore.ts` definiert `ThemeMode = 'dark' | 'light' | 'system'` mit `setThemeMode`, dazu ein `frontend/src/theme/theme.ts` mit zugehörigem Test. Die Funktionalität wurde also vermutlich im Rahmen eines anderen, breiteren PRs (z. B. #134, der laut Dateiliste `frontend/src/theme/theme.ts` und `uiStore.ts` bereits berührte) mitgeliefert, ohne dass dieses Issue dabei explizit referenziert wurde.

**Verifikation:** `frontend/src/stores/uiStore.ts` und `frontend/src/theme/theme.ts` (inkl. `theme.test.ts`) existieren im Worktree und enthalten Dark/Light/System-Logik — die Anforderung ist im heutigen Code erfüllt.

**Themen:** frontend, ui, theming, barrierefreiheit
