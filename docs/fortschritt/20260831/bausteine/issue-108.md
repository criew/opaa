# Issue #108 — feat(auth): Spring Security with OIDC authentication
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:L, auth
- PRs: #135 (2026-03-07)

**Laut Issue:** Spring Security mit OIDC als Grundlage der gesamten Zugriffskontrolle einrichten — Filter Chain, OIDC-Integration (Keycloak als Referenz), JWT-Session-Handling, geschützte Endpunkte, Login-/Logout-Flow, CORS-Update, Konfiguration über Umgebungsvariablen; Mock-Profil soll Auth für lokale Entwicklung umgehen.

**Geliefert:** PR #135 liefert deutlich mehr als drei geforderte Modi: `mock` (kein Auth), `oidc` (Resource Server gegen Keycloak/Auth0/Okta/Azure AD) und zusätzlich `basic` (statische Credentials mit backend-signierten JWTs für PoCs) — zustandslose Architektur ohne Server-Sessions, Nutzer-Auto-Provisionierung, Auth-Config-Discovery-Endpunkt, Frontend-OIDC-Flow mit PKCE, Login-Seite, geschützte Routen, Keycloak-Dev-Setup, ADR-0005. Der PR schließt außerdem #120 (Login-UI) mit. Umfang übertrifft die Anforderung des Issues (dritter Auth-Modus, ADR).

**Verifikation:** `backend/src/main/java/io/opaa/auth/OidcSecurityConfig.java`, `SecurityCorsConfig.java` und `UserProvisioningFilter.java` existieren im heutigen Worktree; ADR-0005 unter `docs/decisions/0005-authentication-strategy.md` ebenfalls.

**Themen:** auth, oidc, security, backend, frontend
