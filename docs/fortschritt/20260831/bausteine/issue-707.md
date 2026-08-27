# Issue #707 — fix(frontend): CSP blockiert als data:-URI gebündelte Font-Subsets im Docker-Deployment
- Geschlossen: 2026-08-25 (completed)
- Labels: bug, frontend
- PRs: #910 (2026-08-25)

**Laut Issue:** Beim OIDC-Testlauf des Docker-Deployments meldete die Browser-Konsole sechs CSP-Verstöße pro Seite: Vite bündelt Assets unter 4 KB als `data:`-URIs, kleine Quicksand-Subsets (kyrillisch/vietnamesisch) unterschritten diese Grenze, die nginx-CSP erlaubt aber nur `font-src 'self'`. Zwei Varianten standen zur Wahl: CSP lockern (`font-src 'self' data:`) oder Font-Inlining unterbinden.

**Geliefert:** Variante 2 (striktere CSP bleibt erhalten): `build.assetsInlineLimit` in `vite.config.ts` emittiert Fonts (woff2/woff/ttf/otf/eot) jetzt immer als Datei. Begründung im PR: Die CSP steht in vier Kopien in `frontend/nginx.conf`, eine Lockerung müsste dort mehrfach erfolgen und bliebe dauerhaft breiter als nötig. Neuer `e2e/tests/csp.spec.ts` sammelt alle CSP-Konsolenmeldungen beim Laden gegen den echten Docker-Compose-Stack — Regressionsschutz auch gegen künftige Ursachen. Reproduktionsnachweis: vor dem Fix 1 Test failed mit den sechs Font-CSP-Verstößen, danach 39/39 grün, `grep -c 'data:font' dist/assets/*.css` → 0.

**Verifikation:** `e2e/tests/csp.spec.ts` existiert im Worktree; `frontend/vite.config.ts` als geänderte Datei laut PR-Dateiliste plausibel für die beschriebene `assetsInlineLimit`-Funktion (nicht einzeln nachgelesen).

**Themen:** frontend, security, deployment, barrierefreiheit
