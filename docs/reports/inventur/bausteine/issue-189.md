# Issue #189 — chore(frontend): bump all frontend dependencies to latest stable (MUI 9, Vite 8, TypeScript 6, ESLint 10)
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #191 (2026-08-02)

**Laut Issue:** Alle Frontend-Abhängigkeiten auf die jeweils aktuelle stabile Version heben, insbesondere die Majors MUI v7→v9, Vite 7→8, TypeScript 5.9→6.0, ESLint 9→10 sowie `react-router-dom`→`react-router` 8, plus ein langer Schwanz an Patch-/Minor-Bumps. ESLint-9-EOL (2026-08-06) machte den Schritt zeitkritisch. Akzeptanzkriterien: sauberer `npm ci`, grünes `format:check`/`lint`/`test`/`build`, funktionierender Dev-Server, aktualisierte Versionsreferenzen in AGENTS.md/README/ADR-0002.

**Geliefert:** PR #191 setzt die Zielversionen um (ESLint 10.8.0, TypeScript 6.0.3, Vite 8.2.0, MUI 9.2.0, react-router 8.3.0, plus Long-Tail-Bumps). Zwei Abweichungen vom Issue-Scope, beide dokumentiert als nötig für grüne CI: (1) Node-Baseline musste von „20+" auf `>=22.22.0` angehoben werden, da `jsdom@30` und `react-router@8` das verlangen; (2) ein npm-`override` für `openapi-typescript`s TypeScript-5-Peer-Dependency war nötig. Die tatsächlichen MUI-9-Breaking-Changes wichen von den im Issue vermuteten ab (nicht `Stack direction="column"`, sondern entfernte System-Props auf `Typography`/`Stack`, `inputProps`→`slotProps`, entfernte `*Outline`-Icons). Laut PR-Body steht der manuelle Klick-Test (Login/Workspace/Chat) explizit noch aus; drei UI-Dateien (WorkspacePage, MobileHeader, SettingsPage) mit den `sx`-Konvertierungen hatten keine vollständige Testabdeckung zum Merge-Zeitpunkt.

**Verifikation:** `frontend/package.json` im heutigen Worktree bestätigt die Zielversionen weiterhin aktiv (`@mui/material` ^9.2.0, `react` ^19.2.8, `react-router` ^8.3.0, `eslint` ^10.8.0, `typescript` ~6.0.3, `vite` ^8.2.0). Die im PR als unvollständig getestet genannte `MobileHeader.tsx` wurde durch Issue #193 (weißes Hamburger-Icon) unmittelbar nachträglich als Bug bestätigt — plausibler Zusammenhang mit der hier fehlenden manuellen Verifikation.

**Themen:** frontend, dependencies, mui, vite, typescript, eslint, react-router
