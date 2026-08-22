# Issue #63 — 🚨 [CRITICAL] No Authentication/Authorization Implementation
- Geschlossen: 2026-08-15 (completed)
- Labels: enhancement, backend, size:L, security, auth
- PRs: keine direkt verknüpft

**Laut Issue:** Alle API-Endpunkte waren öffentlich ohne Authentifizierung/Autorisierung. Gefordert (mehrphasig): Auth-Mechanismus (JWT/OAuth2), geschützte Endpunkte, Nutzerkontext in Services, Frontend-Auth-Flow, Workspace-Isolation auf Datenebene, Basis-RBAC, Audit-Logging mit Nutzerbezug.

**Geliefert:** Kein PR ist direkt gegen dieses Issue verlinkt; erledigt wurde es über das große Folge-Epic #107 (Workspaces & Access Control) mit seinen Unter-Issues (#108 OIDC-Auth, #110 System-Admin-Rollen usw.) und später über das Space/Library-Rechtemodell. Laut Abschlusskommentar (15.08.2026, Backlog-Sichtung) sind alle Punkte bis auf einen erledigt: Authentifizierung (OIDC, `OidcSecurityConfig`, ADR-0005), geschützte Endpunkte, Nutzerkontext (`UserProvisioningFilter`), Frontend-Auth-Flow (`LoginPage`, `AuthCallbackPage`, `ProtectedRoute`, `authStore`) und ein Rollenmodell, das über die ursprüngliche Forderung hinausgeht (SystemRole, Space-Rollen, Asset-Rollen, Gruppen aus Verzeichnisabgleich). Die geforderte „Workspace-Isolation" wurde konzeptionell durch das Space-/Library-Modell abgelöst — die Filterung sitzt direkt in der Vektorsuche (`QueryService`, `LibraryAccessService`), nicht als nachgelagerter Filter. Offen blieb nur die nutzerbezogene Audit-Protokollierung, ausgelagert nach #355 und #391–#395.

**Verifikation:** `backend/src/main/java/io/opaa/auth/OidcSecurityConfig.java` und `UserProvisioningFilter.java` existieren im Worktree; ein eigenständiges `workspace`-Paket gibt es nicht mehr (umbenannt zu `space`, siehe Issue #107-Familie), Audit-Log-Package fehlt tatsächlich noch (bestätigt den offenen Punkt aus dem Abschlusskommentar).

**Themen:** security, auth, epic-abhängig, workspace, spaces
