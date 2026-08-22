# Issue #138 — feat(auth): rate limit /api/v1/auth/login to mitigate brute-force attempts
- Geschlossen: 2026-08-14 (not planned)
- Labels: enhancement, backend, size:M, security, auth
- PRs: keine

**Laut Issue:** Rate-Limiting für `POST /api/v1/auth/login` (Brute-Force-Schutz), per IP und/oder global, mit 429-Antwort — Befund aus der Sicherheitsdurchsicht zu PR #135.

**Geliefert:** Nicht umgesetzt — als „not planned" geschlossen, weil der Endpunkt selbst inzwischen nicht mehr existiert. Laut Schließungskommentar ist `POST /api/v1/auth/login` mit dem Wegfall des `basic`-Auth-Modus (#328, Entscheidung #323) entfallen; die Anmeldung läuft im Betrieb ausschließlich über den OIDC-Anbieter, dessen Brute-Force-Schutz dort liegt. Der `dev`-Modus kennt keine Anmeldedaten, gegen die sich raten ließe. Das allgemeine Rate-Limiting für `/api/v1/query` und die Indizierung ist davon unberührt.

**Verifikation:** `JwtTokenService.java`/`BasicSecurityConfig.java` existieren im Worktree nicht mehr (siehe #120/#164), konsistent mit dem Wegfall des `basic`-Modus und damit des Login-Endpunkts.

**Themen:** auth, security, brute-force, verworfen
