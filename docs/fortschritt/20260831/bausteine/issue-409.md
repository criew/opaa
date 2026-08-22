# Issue #409 — security(frontend): Sicherheits-Header im Webserver ergänzen
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:S, security
- PRs: #670 (2026-08-20)

**Laut Issue:** `frontend/nginx.conf` setzte keinen einzigen Sicherheits-Header (kein CSP, kein `X-Content-Type-Options`, kein `X-Frame-Options`, kein `Referrer-Policy`, `server_tokens` nicht aus). Gefordert waren mindestens diese Header plus Prüfung, dass die CSP zum gebauten Frontend passt (Chat, Dokumentenansicht, Verwaltung, Anmeldung funktionieren weiterhin), dokumentierte Aufteilung der Verantwortung für `Strict-Transport-Security` (vorgelagerter TLS-Terminator statt hier).

**Geliefert:** PR #670 ergänzt CSP, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`, `server_tokens off` in `frontend/nginx.conf`, mit `always` gesetzt im `server`-Block (nicht in `location`) wegen der nginx-Vererbungsfalle. HSTS bewusst ausgeklammert und in `docs/deployment.md` als Anforderung an den vorgelagerten TLS-Terminator dokumentiert. CSP wurde gegen den echten Produktions-Build verprobt (keine Inline-Skripte, `style-src 'unsafe-inline'` wegen MUI/Emotion notwendig, `img-src data: blob:` wegen Logo-Vorschau). Laut PR-Body wurde kein automatisierter Browser-Lauf gegen Verstöße durchgeführt (nur Build-Output-Analyse und Header-Check am laufenden Container) — insofern bleibt ein Abnahmekriterium („Konsole meldet keine Verstöße") nur indirekt belegt, nicht per Playwright-Lauf. 7 von 454 Frontend-Tests schlugen laut PR-Body zum Zeitpunkt fehl, laut Autor unabhängig von der Änderung (keine Datei unter `frontend/src` geändert).

**Verifikation:** `frontend/nginx.conf` enthält im heutigen Worktree CSP, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `server_tokens off` wie im PR beschrieben, allerdings inzwischen mit zusätzlicher Variable `${OPAA_CSP_CONNECT_SRC_EXTRA}` und `object-src 'none'` — offenbar seither weiterentwickelt (nicht Teil dieses PRs, spätere Änderung).

**Themen:** security, deployment, frontend, csp, adr-0004
