# Issue #164 — fix(auth): eliminate implicit algorithm coupling in basic-auth JWT signing/decoding
- Geschlossen: 2026-03-09 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #166 (2026-03-09)

**Laut Issue:** `JwtTokenService` wählte über `Keys.hmacShaKeyFor` je nach Secret-Länge implizit HS256/HS384/HS512, während `BasicSecurityConfig.jwtDecoder()` fest HS256 erwartete — bei Secrets > 32 Byte schlug die Token-Validierung fehl. Gefordert: gemeinsame `buildKey()`-Methode als einzige Quelle für die Schlüsselerzeugung.

**Geliefert:** PR #166 extrahiert `JwtTokenService.buildKey(String secret)`, `BasicSecurityConfig.jwtDecoder()` delegiert dorthin, der Test wurde entsprechend angepasst. Deckt den geforderten Umfang exakt ab.

**Verifikation:** `JwtTokenService.java` existiert im heutigen Worktree nicht mehr — Basic-Auth wurde komplett entfernt (Commit `fd042462`, „Auth-Modi auf oidc und dev reduzieren", siehe #120/#138/#139). Der Fix selbst ist damit gegenstandslos geworden, weil sein gesamter Kontext (Basic-Auth-JWT-Signierung) entfallen ist — kein Rückschritt, sondern Folge der späteren Auth-Vereinfachung.

**Themen:** auth, security, jwt, bugfix, verworfen-durch-migration
