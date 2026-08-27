# Issue #812 — fix(frontend): index.html ohne Cache-Control — Browser zeigen nach Deployments den alten Stand
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend, size:S
- PRs: #813 (2026-08-23)

**Laut Issue:** `frontend/nginx.conf` lieferte `index.html` ohne `Cache-Control`-Header aus (nur `ETag`/`Last-Modified`), sodass Browser sie heuristisch cachen und nach einem Deployment die alten, gehashten Bundles referenzieren — real zweimal beim Maintainer aufgetreten. Erwartet: `Cache-Control: no-cache` für `index.html` (direkt und über den SPA-Fallback), `Cache-Control: public, max-age=31536000, immutable` für `/assets/*`, unter Beibehaltung der Security-Header in den neuen Location-Blöcken.

**Geliefert:** Beide `location`-Blöcke wie gefordert ergänzt, inkl. Wiederholung der Security-Header (nginx `add_header`-Vererbungsregel dokumentiert). Reproduktionsnachweis über einen neuen E2E-Test `e2e/tests/http-caching.spec.ts` gegen den echten nginx-Container — laut PR-Beschreibung rot vor dem Fix (`Received: undefined` bei `cache-control`), grün danach; voller E2E-Lauf 37/37. Deckt sich mit dem Issue-Umfang.

**Verifikation:** `frontend/nginx.conf` enthält im Worktree `Cache-Control "no-cache"` sowie `Cache-Control "public, max-age=31536000, immutable"` mit Kommentarverweis auf #812 — Fix vorhanden.

**Themen:** frontend, deployment, ci
