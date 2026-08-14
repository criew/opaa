# ADR-0005: Authentifizierungsstrategie

## Status

Akzeptiert. Überarbeitet mit Issue #323: Die ursprüngliche Festlegung auf drei Auth-Modi
(`mock`, `basic`, `oidc`) ist zugunsten von zwei Modi (`oidc`, `dev`) aufgehoben — siehe
[Historie](#historie-warum-mock-und-basic-entfielen).

## Kontext

OPAA braucht Benutzeridentität als Grundlage für Spaces und Zugangskontrolle (Epic #107).
Anforderungen:

- Zustandslose Architektur (keine serverseitigen Sessions)
- OIDC-Unterstützung für Enterprise-SSO (Keycloak als Referenzimplementierung)
- Ein Weg, die Anwendung lokal und in automatisierten Tests zu betreiben, ohne einen
  Identity-Provider vorauszusetzen
- Automatische Benutzerbereitstellung beim ersten Login

## Entscheidung

### Zwei Auth-Modi über Spring-Profile

Der Auth-Modus wird durch das aktive Spring-Profil gewählt und in `opaa.auth.mode` gespiegelt:

| Auth-Modus | Profil | Mechanismus |
|------------|--------|-------------|
| `oidc` | `oidc` | OIDC-Resource-Server — das Backend validiert JWTs des externen Anbieters (Keycloak, Auth0, …) gegen dessen JWK-Set. **Der einzige für den Betrieb zulässige Modus.** |
| `dev` | `dev` | Kein Anmeldevorgang und kein Token. `DevAuthFilter` setzt für jede Anfrage ein synthetisches `Jwt` eines konfigurierten Nutzers in den `SecurityContext`. Ausschließlich für lokale Entwicklung und automatisierte Tests. |

Es gibt keinen Standardwert. Ist weder `oidc` noch `dev` aktiv, bricht `AuthProfileGuard` den Start
mit einer erklärenden Meldung ab. Ein lautes Scheitern ist einer scheinbar laufenden, tatsächlich
unbenutzbaren Instanz vorzuziehen.

### Der Dev-Modus ist ein vollwertiger Modus, keine Umgehung

`DevSecurityConfig` liefert eine eigene `SecurityFilterChain`, die sich von `OidcSecurityConfig`
allein darin unterscheidet, woher der Principal stammt. Autorisierungsregeln, Method Security und
`UserProvisioningFilter` sind identisch — Entwicklung und Betrieb üben denselben Pfad aus.

Der Nutzer wird je Anfrage über den Header `X-OPAA-Dev-User` gewählt; ohne Header gilt
`opaa.auth.dev.default-user`. Ein unbekannter Wert führt zu `401` statt zu einem stillen Rückfall
auf den Standardnutzer — ein Tippfehler im Test liefe sonst unbemerkt als der falsche Nutzer. Das
Frontend wählt den Nutzer über `?devUser=<subject>`, gemerkt für die Dauer der Browser-Session.

Vorkonfiguriert sind zwei Nutzer: `dev-admin` (E-Mail `admin@opaa.local`, entspricht dem
Standardwert von `opaa.auth.initial-admin-email` und wird damit als `SYSTEM_ADMIN` angelegt) und
`dev-user` (regulärer Nutzer). Damit sind Berechtigungsszenarien testbar.

`DevSecurityConfig` schreibt beim Start eine Warnung ins Log, die benennt, dass keine Prüfung von
Anmeldedaten stattfindet und welche Nutzer konfiguriert sind.

### Der Auth-Modus bleibt in der Security-Konfiguration

Controller und Domänendienste tragen **keine** `@Profile`-Annotationen. Welche Fachfunktionen es
gibt, darf nicht davon abhängen, wie authentifiziert wird.

### Zustandslose JWT-Validierung

Das Backend erstellt niemals HTTP-Sessions. Im `oidc`-Modus muss jede Anfrage einen gültigen
`Authorization: Bearer <jwt>`-Header tragen, der gegen das JWK-Set des Anbieters validiert wird
(asymmetrische Schlüssel, automatisch erkannt).

### Frontend-OIDC-Fluss

Das Frontend handhabt den Autorisierungscode-Fluss direkt mit `oidc-client-ts`:

1. Frontend erkennt den Auth-Modus über `GET /api/v1/auth/config`
2. Bei `oidc`: Weiterleitung zum Anbieter, Callback-Behandlung, Token im Speicher
3. Bei `dev`: kein Anmeldeschritt; das Frontend lädt direkt den aktuellen Nutzer
4. Alle API-Aufrufe tragen `Authorization: Bearer <jwt>` über einen Axios-Interceptor

Es gibt **keinen** passwortbasierten Anmeldeweg und kein Anmeldeformular.

Schlägt `GET /api/v1/auth/config` fehl, bleibt das Frontend unauthentifiziert und zeigt einen
Fehler an. Ein Rückfall auf einen angemeldet wirkenden Zustand ist ausgeschlossen: Das Backend
würde weiterhin jede Anfrage abweisen, der Nutzer säße vor einer funktionslosen Oberfläche.

### Automatische Benutzerbereitstellung

Bei authentifizierten Anfragen extrahiert `UserProvisioningFilter` Benutzerinformationen aus dem
JWT (`sub`, `iss`, `email`, `name`) und legt bzw. aktualisiert einen Datensatz in der
`users`-Tabelle. Gruppenzugehörigkeiten kommen **nicht** aus dem Token, sondern über
`DirectoryClient` (`io.opaa.group.sync`).

### Auth-Konfigurationserkennung

Der öffentliche Endpunkt `GET /api/v1/auth/config` gibt den aktiven Auth-Modus und die
OIDC-Konfiguration zurück. Das Frontend bestimmt daraus, welchen Anmeldeweg es präsentiert.

## Konsequenzen

### Positiv

- **Zustandslos**: kein Session-Store, horizontales Skalieren ist trivial
- **Anbieter-unabhängig**: jeder OIDC-konforme Anbieter funktioniert
- **Frontend-agnostisch**: jeder Client, der JWTs senden kann, funktioniert
- **Kein unsicherer oder unbenutzbarer Standardzustand**: ohne Auth-Profil startet die Anwendung
  nicht
- **Geringe Angriffsfläche im Betrieb**: kein Signing-Secret in der Betriebskonfiguration, kein
  passwortbasierter Anmeldeendpunkt, kein Brute-Force-Ziel
- **Entwicklung und Betrieb üben denselben Pfad aus** — der Auth-Modus wirkt sich nicht darauf aus,
  welche Fachfunktionen existieren

### Negativ

- **Kein Angebot für Interessenten ohne eigenen Identity-Provider.** Wer OPAA erproben will, braucht
  einen OIDC-Anbieter; das mitgelieferte Compose-Profil `oidc` mit Keycloak
  (`keycloak/realm-export.json`) deckt das ab, setzt aber Docker voraus.
- **Der OIDC-Anmeldeablauf ist nicht automatisiert abgedeckt.** Die E2E-Suite läuft auf `dev` und
  prüft die Anwendung hinter der Authentifizierung, nicht den Anmeldevorgang (siehe
  [ADR-0009](0009-e2e-teststrategie.md)).
- **Es existiert ein Modus mit vollständig deaktivierter Authentifizierung.** Er ist an ein
  ausdrücklich zu setzendes Spring-Profil gebunden, wird beim Start laut protokolliert und über
  `GET /api/v1/auth/config` nach außen als `dev` gemeldet. Wer ihn produktiv setzt, tut es sehenden
  Auges.
- **Token im Speicher**: Ein Seitenwechsel verliert den Auth-Status; der OIDC-Fluss kann still
  erneuern.

### Neutral

- Kerberos wird über eine Keycloak-Föderation abgebildet, sodass OPAA nur OIDC spricht.
- `DirectorySyncService` setzt einen einzelnen OIDC-Issuer je Organisation voraus.

## Historie: warum `mock` und `basic` entfielen

Die ursprüngliche Fassung dieses ADR sah drei Modi vor. Die Prüfung in Issue #323 hat ergeben, dass
zwei davon ihren Zweck nicht erfüllten:

**`mock` war kein Auth-Modus, sondern eine Lücke in der Konfiguration.** Es gab genau zwei
`SecurityFilterChain`-Beans (`@Profile("oidc")`, `@Profile("basic")`). Ohne eines dieser Profile
übernahm Spring Boots generische Security-Autokonfiguration und sperrte die Anwendung hinter ein
zufällig erzeugtes Passwort; zusätzlich existierten `UserService` und sämtliche Fach-Controller
wegen `@Profile({"oidc", "basic"})` gar nicht als Beans. Der ausgelieferte Standardwert
`OPAA_AUTH_MODE=mock` führte damit nicht zu einer offenen, sondern zu einer unbenutzbaren Anwendung
(#255). Die E2E-Suite musste deshalb auf `basic` ausweichen.

Die `@Profile`-Annotationen an Controllern und `UserService` waren Folgeschaden dieser Lücke: Der
aktive Auth-Modus entschied mit darüber, welche Fachfunktionen es überhaupt gab.

**`basic` erfüllte seinen dokumentierten Zweck als PoC-Option nicht.** Identitäten werden über
(`subject`, `issuer`) geführt; `basic` vergab `subject = username`, `issuer = "opaa-basic"` und eine
erfundene E-Mail `<username>@opaa.local`. Ein Umstieg vom PoC auf den Produktivbetrieb erzeugte
deshalb neue Nutzerdatensätze und ließ persönliche Spaces, Mitgliedschaften und Rollen an der alten
Identität zurück — ein Datenmigrationsproblem, kein Konfigurationswechsel. Dazu kam laufender
Aufwand ohne Gegenwert: HMAC-Secret in der Betriebskonfiguration, Klartext-Passwortvergleich ohne
konstante Laufzeit, kein Rate-Limiting am Login (#138), kein Refresh, genau ein konfigurierbarer
Nutzer (#260) — womit ausgerechnet Berechtigungsszenarien nicht abbildbar waren.

**Der Ersatz war billig, weil die Naht zwischen Auth und Anwendung schmal ist.** Unterhalb der
Filterkette kennt der gesamte Rest der Anwendung nur ein `Jwt`-Principal im `SecurityContext`. Ein
Modus, der ein synthetisches `Jwt` einsetzt, ist damit funktional äquivalent zu OIDC — für alles
außer dem Anmeldevorgang selbst.

**Für die E2E-Suite fiel die Wahl auf `dev` statt auf den mitgelieferten Keycloak**, weil ein
Identity-Provider im Prüfpfad einen weiteren Container, den Realm-Import und den
Weiterleitungsablauf des Autorisierungscode-Flusses hinzugefügt hätte — zusätzliche Fehlerquellen
ohne Aussagewert für die Fachszenarien.
