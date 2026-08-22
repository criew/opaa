# Issue #553 — PATCH-Anfragen scheitern mit 403: Methode fehlt in der CORS-Konfiguration, Frontend-Proxy überschreibt X-Forwarded-Proto
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #555 (2026-08-20)

**Laut Issue:** Auf der Testinstallation scheiterten „Chat umbenennen" und „Wissen nutzen"-Umschalten (beides `PATCH /api/v1/chats/{chatId}`) mit HTTP 403. Zwei verifizierte Ursachen: (1) `SecurityCorsConfig#corsConfigurationSource` erlaubte nur `GET, POST, PUT, DELETE, OPTIONS` — `PATCH` fehlte, sodass Springs CORS-Prüfung mit 403 ablehnte, bevor Authentifizierung greift; (2) `frontend/nginx.conf` überschrieb das vom äußeren Reverse-Proxy gesetzte `X-Forwarded-Proto: https` mit dem eigenen `$scheme` (http) auf dem inneren Hop, wodurch das Backend same-origin-Anfragen fälschlich als cross-origin behandelte und die CORS-Prüfung überhaupt erst anwendete. Die E2E-Suite fängt das nicht, da dort alles ohne TLS auf einem Origin läuft.

**Geliefert:** Genau wie im Issue beschrieben behoben: `PATCH` in die erlaubten CORS-Methoden aufgenommen; `nginx.conf` reicht ein eingehendes `X-Forwarded-Proto` jetzt durch (Fallback `$scheme` ohne äußeren Proxy). Empirische Verifikation vor dem Fix im PR-Body dokumentiert (PATCH mit Origin → 403, ohne Origin → 401, DELETE mit Origin → 401). Neuer Test `SecurityCorsConfigTest` lässt die echte Konfiguration durch Springs `DefaultCorsProcessor` laufen, inkl. Negativtest für fremde Origins.

**Verifikation:** `backend/src/main/java/io/opaa/auth/SecurityCorsConfig.java` enthält `PATCH` in der Methodenliste (Zeile 21 im Worktree).

**Themen:** auth, cors, deployment, chats, security
