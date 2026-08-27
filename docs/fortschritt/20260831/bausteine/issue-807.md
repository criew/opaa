# Issue #807 — docs(marketing): Demo-Video auf der GitHub Page bereitstellen
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation
- PRs: #808 (2026-08-23)

**Laut Issue:** Das produzierte Demo-Video (`opaa-demo-stadt-rheinfurt.mp4`, ~21 MB) soll dauerhaft unter `https://criew.github.io/opaa/opaa-demo-stadt-rheinfurt.mp4` erreichbar sein. Da der Landing-Page-Workflow `page/` mit `rsync --delete` in die `gh-pages`-Wurzel synchronisiert, würde eine dort separat abgelegte Videodatei beim nächsten Workflow-Lauf wieder gelöscht — die rsync-Ausnahmen mussten ergänzt werden.

**Geliefert:** Ein rsync-Ausschluss für `*.mp4` in `.github/workflows/landing-page.yml`, sodass das Video (direkt auf `gh-pages` gepusht, nicht im Quell-Repository) beim Sync erhalten bleibt. Kleine, reine Workflow-Änderung ohne Effekt auf `report/`/`.nojekyll`. Deckt sich mit dem Issue-Umfang.

**Verifikation:** `.github/workflows/landing-page.yml` existiert im Worktree und enthält weiterhin einen `mp4`-bezogenen rsync-Ausschluss (per Grep bestätigt).

**Themen:** ci, doku, marketing, deployment
