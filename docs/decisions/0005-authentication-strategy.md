# ADR-0005: Authentication Strategy

## Status

Accepted

## Context

OPAA has no authentication. All API endpoints are publicly accessible. Before implementing workspaces and access control (Epic #107), we need to establish user identity. Requirements:

- Stateless architecture (no server-side sessions)
- OIDC support for enterprise SSO (Keycloak as reference implementation)
- Simple auth option for PoCs and local development
- Mock auth mode must continue working without auth
- User auto-provisioning on first login
- No role management at this stage

## Decision

### Three Auth Modes via Spring Profiles

Authentication mode is selected through Spring profiles and the `opaa.auth.mode` property:

| Auth Mode | Mechanism |
|-----------|-----------|
| `mock` | No auth — all requests permitted, no login required |
| `oidc` | OIDC Resource Server — backend validates JWTs from external OIDC provider (Keycloak, Auth0, etc.) using JWK Set |
| `basic` | Static credentials — backend validates username/password against config, issues HMAC-signed JWTs |

### Stateless JWT Validation

Both `oidc` and `basic` profiles use **stateless JWT validation** via Spring Security's OAuth2 Resource Server. The backend never creates HTTP sessions. Every request must carry a valid `Authorization: Bearer <jwt>` header.

- **OIDC profile**: JWTs are validated using the provider's JWK Set (asymmetric keys, auto-discovered).
- **Basic profile**: JWTs are signed and validated with an HMAC secret (symmetric key, configured via `opaa.auth.basic.secret`).

### Frontend OIDC Flow

The frontend handles the OIDC authorization code flow directly using `oidc-client-ts`:
1. Frontend discovers auth mode via `GET /api/v1/auth/config`
2. For OIDC: redirects to provider, handles callback, stores token in memory
3. For Basic: shows login form, calls `POST /api/v1/auth/login`, stores returned JWT in memory
4. All subsequent API calls include `Authorization: Bearer <jwt>` via Axios interceptor

### User Auto-Provisioning

On authenticated requests, a `UserProvisioningFilter` extracts user info from the JWT (subject, issuer, email, name) and upserts a record in the `users` table. No roles are stored.

### Auth Config Discovery

A public endpoint `GET /api/v1/auth/config` returns the active auth mode and OIDC configuration. The frontend uses this to determine which login flow to present.

## Consequences

### Positive
- **Flexible deployment**: Same codebase supports enterprise SSO, simple PoCs, and local development
- **Stateless**: No session store needed, horizontal scaling is trivial
- **Frontend-agnostic**: Any client that can send JWTs works (web, CLI, API tokens in future)
- **Provider-independent**: Any OIDC-compliant provider works (Keycloak, Auth0, Okta, Azure AD)

### Negative
- **Basic profile has no token refresh**: Users must re-login when JWT expires (acceptable for PoCs)
- **Token in memory**: Page refresh loses auth state; OIDC flow can silently renew, basic flow requires re-login
- **Keycloak issuer mismatch in Docker**: Backend and frontend may see different hostnames for Keycloak, requiring careful `issuer-uri` configuration

### Neutral
- Kerberos authentication is best handled by configuring Keycloak with Kerberos federation, so OPAA still speaks OIDC
- No role management yet — will be added when workspaces are implemented
