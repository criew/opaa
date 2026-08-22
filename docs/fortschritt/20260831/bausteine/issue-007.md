# Issue #7 — chore: scaffold React frontend with TypeScript and MUI 7
- Geschlossen: 2026-02-19 (completed)
- Labels: mvp, frontend, setup, size:S
- PRs: #22 (2026-02-19)

**Laut Issue:** React-Frontend mit TypeScript und Material UI 7.3.8 via Vite aufsetzen, Vitest + React Testing Library als Testframework, ESLint/Prettier, App-Shell mit ThemeProvider/CssBaseline, Platzhalter-Landingpage, API-Proxy auf `http://localhost:8080`.

**Geliefert:** PR #22 liefert genau das: Vite+React+TypeScript-Projekt in `frontend/`, MUI 7.3.8, Emotion, Axios, Vitest+RTL mit Beispieltest, ESLint+Prettier, OPAA-Landingpage mit ThemeProvider/CssBaseline, API-Proxy `/api` → Backend. Keine Abweichung vom Issue.

**Verifikation:** `frontend/package.json` und `frontend/src/App.tsx` existieren weiterhin im Worktree. Das Frontend wurde seither erheblich ausgebaut (Chat-UI, Routing, Stores etc. ab #14).

**Themen:** frontend, projektsetup, react, mui
