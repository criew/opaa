# ADR-0005: Authentifizierungsstrategie

## Status

Akzeptiert

## Kontext

OPAA hat keine Authentifizierung. Alle API-Endpunkte sind öffentlich zugänglich. Vor der Implementierung von Workspaces und Zugangskontrolle (Epic #107) müssen wir Benutzeridentität etablieren. Anforderungen:

- Zustandslose Architektur (keine serverseitigen Sessions)
- OIDC-Unterstützung für Enterprise-SSO (Keycloak als Referenzimplementierung)
- Einfache Auth-Option für PoCs und lokale Entwicklung
- Mock-Auth-Modus muss weiterhin ohne Auth funktionieren
- Automatische Benutzerbereitstellung beim ersten Login
- Keine Rollenverwaltung in dieser Phase

## Entscheidung

### Drei Auth-Modi über Spring-Profile

Auth-Modus wird durch Spring-Profile und die `opaa.auth.mode`-Eigenschaft gewählt:

| Auth-Modus | Mechanismus |
|------------|-------------|
| `mock` | Kein Auth — alle Anfragen erlaubt, kein Login erforderlich |
| `oidc` | OIDC-Resource-Server — Backend validiert JWTs vom externen OIDC-Anbieter (Keycloak, Auth0, usw.) mittels JWK-Set |
| `basic` | Statische Anmeldeinformationen — Backend validiert Benutzername/Passwort gegen Konfiguration, gibt HMAC-signierte JWTs aus |

### Zustandslose JWT-Validierung

Sowohl `oidc`- als auch `basic`-Profile verwenden **zustandslose JWT-Validierung** über Spring Securitys OAuth2-Resource-Server. Das Backend erstellt niemals HTTP-Sessions. Jede Anfrage muss einen gültigen `Authorization: Bearer <jwt>`-Header tragen.

- **OIDC-Profil**: JWTs werden mit dem JWK-Set des Anbieters validiert (asymmetrische Schlüssel, automatisch erkannt).
- **Basic-Profil**: JWTs werden mit einem HMAC-Secret signiert und validiert (symmetrischer Schlüssel, über `opaa.auth.basic.secret` konfiguriert).

### Frontend-OIDC-Fluss

Das Frontend handhabt den OIDC-Autorisierungscode-Fluss direkt mit `oidc-client-ts`:
1. Frontend erkennt Auth-Modus über `GET /api/v1/auth/config`
2. Bei OIDC: leitet zum Anbieter um, handhabt Callback, speichert Token im Speicher
3. Bei Basic: zeigt Login-Formular an, ruft `POST /api/v1/auth/login` auf, speichert zurückgegebenen JWT im Speicher
4. Alle nachfolgenden API-Aufrufe enthalten `Authorization: Bearer <jwt>` über Axios-Interceptor

### Automatische Benutzerbereitstellung

Bei authentifizierten Anfragen extrahiert ein `UserProvisioningFilter` Benutzerinformationen aus dem JWT (Subject, Issuer, E-Mail, Name) und upserts einen Datensatz in der `users`-Tabelle. Keine Rollen werden gespeichert.

### Auth-Konfigurationserkennung

Ein öffentlicher Endpunkt `GET /api/v1/auth/config` gibt den aktiven Auth-Modus und die OIDC-Konfiguration zurück. Das Frontend verwendet dies, um zu bestimmen, welchen Login-Fluss es präsentiert.

## Konsequenzen

### Positiv
- **Flexibles Deployment**: Dieselbe Codebasis unterstützt Enterprise-SSO, einfache PoCs und lokale Entwicklung
- **Zustandslos**: Kein Session-Store benötigt, horizontales Skalieren ist trivial
- **Frontend-agnostisch**: Jeder Client, der JWTs senden kann, funktioniert (Web, CLI, API-Token in Zukunft)
- **Anbieter-unabhängig**: Jeder OIDC-konforme Anbieter funktioniert (Keycloak, Auth0, Okta, Azure AD)

### Negativ
- **Basic-Profil hat keine Token-Erneuerung**: Benutzer müssen sich nach JWT-Ablauf erneut anmelden (für PoCs akzeptabel)
- **Token im Speicher**: Seitenaktualisierung verliert Auth-Status; OIDC-Fluss kann still erneuern, Basic-Fluss erfordert erneutes Anmelden
- **Keycloak-Issuer-Mismatch in Docker**: Backend und Frontend sehen möglicherweise verschiedene Hostnamen für Keycloak, was eine sorgfältige `issuer-uri`-Konfiguration erfordert

### Neutral
- Kerberos-Authentifizierung wird am besten durch Konfiguration von Keycloak mit Kerberos-Federation gehandhabt, sodass OPAA weiterhin OIDC spricht
- Noch keine Rollenverwaltung — wird bei der Implementierung von Workspaces hinzugefügt
