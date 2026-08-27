# Issue #653 — Frontend auf pnpm umstellen (Worktree-Größe und Installationszeit)
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:M, ci
- PRs: #752 (2026-08-23)

**Laut Issue:** Folge-Issue zu #644 (dort als Maßnahme 5 zurückgestellt). Jeder Agent-Worktree trug ein eigenes, vollständig kopiertes `frontend/node_modules` (Hunderte MB), `npm ci` entpackte jedes Mal neu. Gefordert war die Migration von npm auf pnpm (Frontend und ggf. E2E), CI-Anpassung, Docker-Anpassung und Dokumentation, damit ein frischer Worktree ohne vollständige `node_modules`-Kopie auskommt.

**Geliefert:** Vollständig. `frontend/` und `e2e/` migriert (`package-lock.json` → `pnpm-lock.yaml` via `pnpm import`), pnpm-Version über `packageManager`-Feld gepinnt (`pnpm@11.21.0`), `frontend/pnpm-workspace.yaml` neu (msw-Postinstall bewusst nicht erlaubt, `peerDependencyRules` für TypeScript 6). Nebenbefund: `@mui/utils` musste als direkte Abhängigkeit deklariert werden — pnpms strikte `node_modules` deckte fehlendes Hoisting auf (16 Testdateien scheiterten zunächst). CI (`ci.yml`, `e2e.yml`, `demo-smoke.yml`) auf `pnpm/action-setup@v4` umgestellt, `frontend/Dockerfile` auf corepack+pnpm, neue Root-`.dockerignore` ersetzt die wirkungslose `frontend/.dockerignore`. Dokumentation (AGENTS.md, e2e/README.md, demo/README.md, docs/deployment.md) nachgezogen. Frische Installation: ~7s statt Minuten.

**Verifikation:** `frontend/pnpm-lock.yaml` existiert im Worktree, `frontend/package-lock.json` existiert nicht mehr — konsistent mit vollständiger Migration.

**Themen:** frontend, ci, projektsetup, worktrees, pnpm
