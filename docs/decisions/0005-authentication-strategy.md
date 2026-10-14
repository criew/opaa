# ADR-0005: Authentifizierungsstrategie

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt durch ADR-0025, ADR-0033, ADR-0035

## Kontext

OPAA braucht Benutzeridentität als Grundlage für Spaces und Zugangskontrolle. Anforderungen:
zustandslose Architektur ohne serverseitige Sessions, OIDC für Enterprise-SSO (Keycloak als
Referenz), ein Weg, die Anwendung lokal und in automatisierten Tests ohne Identity-Provider zu
betreiben, und automatische Benutzerbereitstellung beim ersten Login.

## Entscheidung

### 1. Zwei Auth-Modi über Spring-Profile

| Modus | Profil | Mechanismus |
|---|---|---|
| `oidc` | `oidc` | Resource Server: das Backend prüft JWTs der konfigurierten Anbieter (ADR-0025) und des lokalen Ausstellers (ADR-0033). Der einzige Betriebsmodus. |
| `dev` | `dev` | Kein Anmeldevorgang, kein Token. `DevAuthFilter` setzt je Anfrage ein synthetisches `Jwt` eines konfigurierten Nutzers. Nur Entwicklung und Tests. |

Es gibt keinen Standardwert: Ist weder `oidc` noch `dev` aktiv, bricht `AuthProfileGuard` den Start
mit erklärender Meldung ab. Lautes Scheitern ist einer scheinbar laufenden Instanz vorzuziehen.

### 2. Der Dev-Modus ist ein vollwertiger Modus

`DevSecurityConfig` liefert eine eigene `SecurityFilterChain`, die sich von `OidcSecurityConfig`
(`io.opaa.account`) in der Herkunft des Principals unterscheidet und die nur den lokalen Aussteller
betreffenden Teile (CSRF-Cookie, `PasswordChangeRequiredFilter`) weglässt; Autorisierungsregeln,
Method Security und `UserProvisioningFilter` sind identisch. Der Nutzer kommt aus dem Header
`X-OPAA-Dev-User`, ohne Header gilt `opaa.auth.dev.default-user`; ein unbekannter Wert ergibt `401`,
nie einen stillen Rückfall. Das Frontend wählt ihn über `?devUser=<subject>`
(`frontend/src/shared/session/devAuth.ts`, gemerkt in `sessionStorage`). Vorkonfiguriert sind
`dev-admin` (`admin@opaa.local`, Standardwert von `opaa.auth.initial-admin-email`, damit
`SYSTEM_ADMIN`) und `dev-user`. `DevSecurityConfig` warnt beim Start, dass keine Anmeldedaten geprüft
werden.

### 3. Der Auth-Modus bleibt in der Security-Konfiguration

Welche Fachfunktionen es gibt, hängt nicht vom Auth-Modus ab: Fach-Controller und Domänendienste
tragen keine `@Profile`-Annotation. Bewusste Ausnahme sind die drei Controller des lokalen
Ausstellers (`LocalAuthController`, `LocalSelfServiceController`, `LocalHandoverController`,
`@Profile("oidc")`): Sie sind Teil des Anmeldewegs selbst, und der Dev-Modus hat keinen Aussteller.
Ebenso an `oidc` gebunden sind die Startwächter dieses Modus (`LocalAuthSecretGuard`,
`TrustedProxyStartupGuard`) und die Bean `passwordChangeRequiredFilter` in
`LocalAuthIssuerConfiguration`.

### 4. Zustandslose JWT-Validierung

Das Backend erstellt keine HTTP-Sessions. Im `oidc`-Modus trägt jede Anfrage `Authorization:
Bearer <jwt>`; ein Multi-Issuer-Resolver prüft gegen das JWK-Set des jeweiligen Anbieters oder gegen
den Schlüssel des lokalen Ausstellers `urn:opaa:local`, dessen Refresh-Token als HttpOnly-Cookie
reist (ADR-0033). Die OIDC-Anbieter liegen in der Datenbank, die Umgebung sät nur den ersten einmalig; die SPA ist
Public Client ohne Secret. Für OIDC-Anbieter gibt es kein Passwortformular und kein Signaturgeheimnis
in der Betriebskonfiguration. Der Erstadministrator ist immer ein lokales Konto (ADR-0033).

### 5. Frontend-OIDC-Fluss

Das Frontend führt den Autorisierungscode-Fluss mit `oidc-client-ts` (`authStore.ts`): Modus über
`GET /api/v1/auth/config` erkennen, bei `oidc` zum gewählten Anbieter weiterleiten, Token im
Speicher halten (`WebStorageStateStore` auf `sessionStorage`), jede API-Anfrage über einen
Axios-Interceptor mit Bearer-Token versehen. Das Access-Token wird still über das Refresh-Token
erneuert (`automaticSilentRenew`, 60 s vor Ablauf, kein Iframe, weil `frame-ancestors 'none'` in
`frontend/nginx.conf`); `authStore` hält das Token über `UserLoaded`/`UserUnloaded`/`SilentRenewError` aktuell. Ein `401`
löst in `apiInterceptors.ts` genau einen `signinSilent()`-Versuch mit Wiederholung der Anfrage aus
(`_retry`-Guard); erst wenn das scheitert, setzt `expireSession` den lokalen Zustand zurück, ohne
`signoutRedirect()` und damit ohne die Sitzung beim Anbieter zu beenden. Ein bewusstes „Abmelden“
bleibt der volle `signoutRedirect()`. `keycloak/realm-export.json` setzt die Token-Lebensdauern
ausdrücklich. Scheitert `GET /api/v1/auth/config`, bleibt das Frontend unauthentifiziert und zeigt
einen Fehler.

### 6. Automatische Benutzerbereitstellung

`UserProvisioningFilter` liest `sub`, `iss`, `email`, `name` aus dem JWT und legt oder aktualisiert
den Datensatz in `users`; die Identität ist `(issuer, subject)`. Gruppen kommen über
`DirectoryClient` (`io.opaa.directory.sync`), für Anbieter mit `groups_claim` beziehungsweise
`roles_claim` aus dem Token (ADR-0025, Entscheidung 4). Derselbe Filter legt einmalig einen
`CurrentUser`-Schnappschuss als Request-Attribut ab; Controller erhalten ihn über
`@Caller`-Parameter (`CurrentUserArgumentResolver`). `CurrentUser` hat keinen öffentlichen
Konstruktor, damit ein fehlender Resolver die Anfrage scheitern lässt statt sie aus Parametern zu
binden.

### 7. Auth-Konfigurationserkennung

Der öffentliche Endpunkt `GET /api/v1/auth/config` meldet Modus und Anbieter; das Frontend leitet
daraus den Anmeldeweg ab.

## Konsequenzen

Einfacher: kein Session-Store; jeder OIDC-konforme Anbieter und jeder Client, der JWTs sendet,
funktioniert; ohne Auth-Profil startet die Anwendung nicht; Entwicklung und Betrieb üben dieselbe
Filterkette; Interessenten ohne eigenen Identity-Provider betreiben OPAA mit lokalen Konten
(ADR-0033; die frühere negative Konsequenz Nr. 1 dieses ADRs ist damit entfallen).

Schwieriger: Der OIDC-Anmeldeablauf gegen einen Anbieter ist nur im Demo-Rauchtest abgedeckt, die
reguläre E2E-Suite läuft auf `dev` (ADR-0009). Ein Browser-Neustart verliert die OIDC-Sitzung, weil
der Token-Store auf `sessionStorage` zeigt.

Restrisiken: Es existiert ein Modus ohne Authentifizierung. Er ist an ein ausdrücklich zu setzendes
Profil gebunden, wird beim Start protokolliert und über `/api/v1/auth/config` als `dev` gemeldet.
Mit dem lokalen Aussteller gibt es im `oidc`-Modus einen passwortbasierten Endpunkt; ADR-0033 sichert
ihn mit Hashing, Rate-Limiting je Adresse und Konto, Refresh-Rotation und validiertem Secret.

Neutral: Kerberos wird über eine Keycloak-Föderation abgebildet, OPAA spricht nur OIDC.
`DirectorySyncService` setzt einen einzelnen OIDC-Issuer je Organisation voraus, den des
Standardanbieters (ADR-0025).

## Verworfene Alternativen

- Modus `mock` — war kein Modus, sondern eine Konfigurationslücke: ohne `oidc`/`basic` griff Springs
  generische Security mit Zufallspasswort, die Anwendung war unbenutzbar (#255, #323).
- Modus `basic` (ein HMAC-signierter Nutzer; die „Historie `basic`“, an der ADR-0033 sich misst) —
  `subject = username` erzeugte beim Umstieg auf OIDC
  neue Identitäten, dazu unvalidiertes Secret, Klartextvergleich, kein Rate-Limiting, kein Refresh,
  nur ein Nutzer (#138, #260). ADR-0033 löst jede dieser Lehren an benannter Stelle ein.
- `@Profile` an Fach-Controllern — der Auth-Modus entschied mit, welche Fachfunktionen existieren.
- Keycloak im E2E-Prüfpfad — zweiter Container, Realm-Import und Weiterleitungen ohne Aussagewert für
  Fachszenarien; `dev` ist unterhalb der Filterkette äquivalent.
- Eigene app-Sitzung mit Token-Prägung statt zustandsloser Prüfung — ADR-0025, Verworfene Alternativen.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-14 | #323 | Drei Modi (`mock`, `basic`, `oidc`) auf `oidc` und `dev` reduziert | 1, 2, 3 |
| 2026-08-22 | #737 | Stille Token-Erneuerung und 401-Wiederholung statt Sofort-Logout | 5 |
| 2026-08-25 | #884 | `CurrentUser` als Request-Attribut, `@Caller`-Resolver | 6 |
| 2026-09-06 | #1294 | Mehrere OIDC-Anbieter, Gruppen und Rollen aus dem Token (ADR-0025) | 4, 6 |
| 2026-09-11 | #1529 | Lokale Benutzerverwaltung, Backend als Aussteller (ADR-0033) | 4 |
| 2026-10-14 | #2697 | Neufassung; `@Profile("oidc")` der lokalen Controller als Ausnahme | 3 |
