# Issue #120 — feat(ui): login flow and session management
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, frontend, size:M, auth
- PRs: #135 (2026-03-07)

**Laut Issue:** OIDC-Login-Redirect, Token-Handling, Auth-Store (Zustand), geschützte Routen, Nutzeranzeige im Header, Logout, 401-Behandlung und Axios-Interceptor im Frontend.

**Geliefert:** PR #135 lieferte den vollständigen Auth-Stack für Backend **und** Frontend in einem Rutsch (auch #108 wurde damit geschlossen): drei Auth-Modi (mock/oidc/basic), JWT-Service, Auto-Provisioning, `AuthConfigController`, sowie frontseitig OIDC Authorization-Code-Flow mit PKCE (`oidc-client-ts`), Login-Seite, geschützte Routen, Axios-Interceptor und Nutzeranzeige in der Sidebar. Deckt den geforderten Umfang ab, ging aber deutlich über das Frontend-Issue hinaus (kompletter Backend-Auth-Unterbau inklusive Keycloak-Dev-Setup und ADR-0005).

**Verifikation:** Der ursprüngliche Drei-Modi-Ansatz (mock/oidc/basic) existiert im heutigen Code nicht mehr — `BasicSecurityConfig.java` und `JwtTokenService.java` sind entfernt (`git log`: Commit `fd042462` „Auth-Modi auf oidc und dev reduzieren"). `OidcSecurityConfig.java` ist vorhanden. Die Login-/Session-Grundmechanik lebt fort, nur auf zwei statt drei Modi reduziert.

**Themen:** auth, oidc, frontend, session
