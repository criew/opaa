# ADR-0025: Mehrere OIDC-Anbieter — Identität als (Issuer, Subject), Anbieter in der Datenbank, Anmeldefluss bleibt in der SPA

## Status

Akzeptiert · zuletzt geändert 2026-10-25 · ergänzt ADR-0005 · Entscheidung 3 teilweise ersetzt durch ADR-0033 (Entscheidungen 4, 5) und ADR-0060 (Entscheidung 9) · Entscheidung 4 ergänzt durch ADR-0036, Entscheidungen 2 und 3 (Gruppenherkunft und Verzeichnisabgleich je Anbieter)

## Kontext

OPAA kannte genau einen OIDC-Issuer aus `OPAA_OIDC_*`, der beim Start Resource-Server und
Anmeldefluss der SPA konfigurierte. Die Identität eines Kontos war schon das Paar
`users(subject, issuer)` (`uq_users_subject_issuer`), aber der Verzeichnisabgleich löste
Mitglieder ohne Issuer auf, und keine Rolle kam aus dem Token. Epic #1294 verlangt mehrere
Identitätsanbieter gleichzeitig (Keycloak der Verwaltung neben einem Partner-IdP, Anbieterwechsel
ohne Stichtag), als Datenbankzeilen mit Admin-UI, wirksam ohne Neustart, mit SSRF-Schutz für
eingegebene Adressen und einer Anmeldeseite mit Anbieterwahl. Vier Fragen: Anmeldefluss
(SPA-Client oder `oauth2Login` im Backend), Identitätsmodell, Bestandsübernahme und Notzugang,
Abmeldung und Rollen je Anbieter.

## Entscheidung

### 1. Anmeldefluss: die SPA bleibt der OIDC-Client, das Backend zustandsloser Resource-Server

Die SPA führt den Autorisierungscode-Fluss mit PKCE gegen den gewählten Anbieter (`oidc-client-ts`,
ein `UserManager` je Anbieter, nur Public Clients ohne Secret). Das Backend prüft jedes Bearer-Token
über `OidcSecurityConfig` (`authenticationManagerResolver`): ein `JwtDecoder` je aktiviertem Anbieter
in der `OidcProviderRegistry` jeder Instanz, nach jedem Commit einer Anbieteränderung über den
Invalidierungsbus neu aufgebaut (ADR-0053 Entscheidung 5); der lokale Issuer aus ADR-0033 ist ein weiterer Decoder daneben
(`LocalIssuerAuthenticationManagerResolver`). Ein unbekannter `iss` ergibt `401` mit
`error_description="unknown_issuer"`, worauf die SPA lokal abmeldet statt zu erneuern; ein nicht
baubarer Decoder wird übersprungen und nach `RETRY_INTERVAL` erneut versucht. `AuthorizedPartyValidator`:
Ein vorhandenes `azp` muss der `client_id` entsprechen, oder `aud` nennt sie; `aud` allein bleibt
ungeprüft, weil Keycloak es nicht auf den anfragenden Client setzt.

Für die OIDC-Anmeldung speichert und prägt OPAA keine Tokens. Quellverbindungen und verbundene
Konten speichern Refresh-, Zugriffstoken und persönliche Geheimnisse verschlüsselt im
Token-Speicher (ADR-0041), nie das Anmeldetoken eines OIDC-Anbieters; der Dienstkonto-Schlüssel
(ADR-0040) speichert keines. Im
OAuth-Ablauf für Quellen sind `state` und PKCE-Verifier an die angemeldete Person gebunden
(`ConnectionAuthorization.user_id`; ein fremder `state` wird abgewiesen). Preis: Ein neuer
Anbieter-Origin wirkt in der CSP (`OPAA_CSP_CONNECT_SRC_EXTRA`) erst nach Frontend-Neustart, den
weder der Verbindungstest (Backend-Sicht) noch das E2E-Szenario prüft; Client-Einrichtung:
[Handbuch](../handbuch/betrieb/authentifizierung.md#oidc-keycloak).

### 2. Identitätsmodell: `(issuer, subject)` bleibt der Schlüssel, der Anbieter ist über den Issuer eindeutig

Keine eigene Identitätstabelle: OPAA bindet ausschließlich OIDC an, jedes Token trägt einen `iss`.
`oidc_providers.issuer_uri` wird gespeichert, wie der Anbieter ihn prägt (Schrägstrich
eingeschlossen), und ist über den normalisierten Wert (Ausdrucksindex `rtrim(issuer_uri, '/')`)
eindeutig; ein Anbieter kann umbenannt, deaktiviert, gelöscht und unter demselben Issuer neu
angelegt werden, ohne dass Konten ihre Identität verlieren, und die Bestandsübernahme braucht
keine Migration an `users`. Das Ändern der `issuer_uri`
eines Anbieters mit Konten wird mit `409` verweigert — die einzige Aktion mit irreversibler
Datenwirkung. **Keine Zusammenführung über die E-Mail:** Ein unbekanntes `(issuer, subject)`
provisioniert ein neues Konto, auch bei gleicher Adresse; die einzige Ausnahme ist die von der
Person selbst eingelöste Übergabe eines lokalen Kontos (ADR-0033, Entscheidung 12). `email` und
`display_name` werden bei jeder Anfrage aus den Claims gelesen und nur bei Abweichung geschrieben.
Die Zeile trägt
`display_name`, `enabled`, `is_default`, `sort_order`, `issuer_uri`, `client_id`, optional
`jwk_set_uri`, die Claim-Zuordnung (Entscheidung 4), `provider_type` (`OIDC | LOCAL`, ADR-0033) und
die Verzeichnisabgleich-Einstellungen (ADR-0036); keine Secret-Spalte. `jwk_set_uri` ist ein
Vertrauensanker (es bestimmt, welche Schlüssel Token beglaubigen): nur für `SYSTEM_ADMIN`
änderbar, jede Änderung ein Audit-Ereignis.

### 3. Konfiguration in der Datenbank, Bootstrap aus der Umgebung, Adressprüfung mit eigener Allowlist

Die Übernahme des ersten Anbieters aus der Umgebung und `OPAA_OIDC_BOOTSTRAP` werden durch den
Einrichtungs-Assistenten abgelöst (ADR-0060, Entscheidung 9); bis zu dessen Umsetzung gilt der
folgende Absatz.

Anbieter werden über die Admin-API (`identity-providers.yaml`, nur `SYSTEM_ADMIN`) gepflegt, wirken
ohne Neustart und sind Audit-Ereignisse `OIDC_PROVIDER_*` (`AuditObjectType.SYSTEM_SETTING`).
`OidcProviderSeeder` übernimmt beim ersten Start im `oidc`-Profil
`opaa.auth.oidc.{issuer-uri,client-id,jwk-set-uri}` als aktivierte Standardzeile, gesichert durch
`OidcProviderSeedMarker` statt „Tabelle leer?“; ein Start ohne Übernahme schreibt keine Markierung,
eine abweichende `OPAA_OIDC_AUTHORITY` wird protokolliert verworfen, die Variablen bleiben erlaubte
Adressen der Adressprüfung. `OPAA_OIDC_BOOTSTRAP=force` (bis 31.03.2027, abgelöst durch
`OPAA_LOCAL_ADMIN_RESET=force`) überschreibt oder legt die Zeile einmalig an, aktiviert sie und macht
sie zum Standard (`OidcProviderSeeder#forceBootstrap`; unvollständige Umgebung: `ERROR`).
Genau ein OIDC-Anbieter ist Standard (`ux_oidc_providers_single_default`), der erste automatisch;
`is_default` steuert Anmeldevorschlag und Löschschutz: Der Standard ist nicht deaktivier- oder löschbar, solange ein anderer aktivierter
Anbieter an seine Stelle treten kann (`409`). Der letzte aktivierte OIDC-Anbieter darf gehen, wenn
`LocalAdminAvailabilityGuard` einen anmeldefähigen lokalen Systemverwalter findet (sonst `409
LAST_LOGIN_CAPABLE_ADMIN`) und `acknowledgeLastProvider` gesetzt ist (ADR-0033); `InitialAdminPolicy`
vergibt `SYSTEM_ADMIN` nur dem Dev-Issuer.

Die Adressprüfung (`OidcAddressPolicy`) hat einen eigenen Block `opaa.auth.oidc.target-validation.*`,
damit das Abschalten der Konnektorprüfung die Anmeldung nicht mitnimmt; sie gilt beim Speichern, beim
Registry-Aufbau, im Verbindungstest und für die `jwks_uri` der Discovery, ohne Weiterleitungen, und
jeder Decoder trägt einen Issuer-Validator. Startmeldungen (#2396) und Betrieb:
[Handbuch](../handbuch/betrieb/authentifizierung.md#bestandsübernahme-aus-opaa_oidc_).

### 4. Claim-Zuordnung, Rollen, Gruppen und Organisation je Anbieter

`OidcClaimMapping` je Anbieterzeile: `email_claim` (`email`), `display_name_claim` (`name`,
Rückfall `preferred_username`, nie das Subject), `roles_claim`, `system_admin_role`,
`auditor_role`, `groups_claim` (alle leer = aus). Mit `roles_claim` ist der Anbieter für
`SYSTEM_ADMIN` und `AUDITOR` führend: `TokenRoleSynchronizer` leitet die Systemrolle bei jeder
Anfrage ab (`SYSTEM_ADMIN` vor `AUDITOR`, wenn ein Token beide trägt) und schreibt nur bei
Abweichung, als Rollenereignis unter einem Systemprozess-Akteur.
Drei Sicherungen: Der letzte anmeldefähige `SYSTEM_ADMIN` wird nie per Token entzogen — ein
bedingter `UPDATE` unter dem Advisory-Lock der Organisation
(`LocalAdminAvailabilityGuard#withdrawSystemAdminIfAnotherRemains`, ADR-0033, Entscheidung 4),
kein „lesen, dann schreiben"; die manuelle Rollenvergabe ist für Konten eines Anbieters mit
`roles_claim` gesperrt (`409`); das Setzen von `roles_claim` verlangt in der Oberfläche eine
Bestätigung. `AUDITOR` ist nicht geschützt. Der Weg aus einer falschen Rollenzuordnung ist das
Leeren von `roles_claim` — eine Anbieteränderung, keine Rollenvergabe. Gruppen aus dem Token
(`groups_claim`) werden zu Mitgliedschaften in Gruppen mit `GroupKind.IDENTITY_PROVIDER`
(`TokenGroupSynchronizer`), getrennt je Anbieter über `groups.provider_id` (ADR-0036,
Entscheidung 2; `external_id` trägt den Gruppennamen, ein Name über 255 Zeichen wird übersprungen
und protokolliert) — hebt die Festlegung von ADR-0005 auf, dass Gruppen nur über `DirectoryClient`
kommen. Der Verzeichnisabgleich hängt an der Anbieterzeile (`directory_sync_enabled`,
ADR-0036, Entscheidungen 2 und 3), löst Mitglieder issuergebunden auf
(`UserRepository#findByOrganizationIdAndIssuerAndSubjectIn`) und verwaltet nur `ORG_UNIT`-Gruppen.
Alle Anbieter provisionieren in `Organization.DEFAULT_ID`.

### 5. Abmeldung, Callback und Token-Lebensdauer je Anbieter

Alle Anbieter teilen die Redirect-URI `<Origin>/auth/callback`; die SPA legt den Anbieter des
laufenden Flusses vor `signinRedirect()` im `sessionStorage` ab (je Tab) und den zuletzt benutzten
nur als Vorschlag im `localStorage` (`authStore.ts`). Beim Betreten der Anmeldeseite startet die SPA
einmal je Tab einen Versuch mit `prompt=none` beim vorgeschlagenen Anbieter (#1631); für diesen
automatischen Versuch gilt jede Antwort mit OAuth-Fehlercode als Absage und endet ohne
Fehlermeldung auf der Anmeldeseite (der Code steht nur in der Browser-Konsole, nie das
Fehlerobjekt, dessen `form` die Token-Anfrage trägt; die Rücksprungadresse wird auch aus der Absage
zurückgelesen), ein Fehler eines geklickten Flusses behält seine Meldung. Eine geklickte Anmeldung
sendet keinen `prompt`-Wert; „Mit anderem
Konto anmelden" entfällt (#1629, #1630), den Kontowechsel übernimmt der Anbieter nach dem
RP-initiierten Logout (`signoutRedirect()` mit `end_session_endpoint` des Anbieters der aktiven
Sitzung, `post_logout_redirect_uri = <Origin>`; ohne Endpunkt lokale Abmeldung mit Hinweis).
Access-Token- und SSO-Lebensdauern sind Sache des Anbieters; stille Erneuerung und 401-Behandlung
aus ADR-0005 arbeiten auf dem `UserManager` der aktiven Sitzung, ergänzt um `unknown_issuer`. Der
öffentliche `GET /api/v1/auth/config` nennt je aktiviertem Anbieter nur Anzeigename, Issuer-URI,
Client-ID, Vorauswahl und Reihenfolge — keine Claim-Zuordnung, nichts über Konten.

### 6. Grenzen

Kein SAML; keine Kontenzusammenführung außer der Übergabe aus ADR-0033; nur Public Clients mit
PKCE; keine `aud`-Prüfung; der letzte `AUDITOR` ist nicht geschützt; ein neuer Anbieter-Origin
braucht einen Frontend-Neustart; eine Organisation; der `dev`-Modus bleibt unverändert.

## Konsequenzen

- **Einfacher:** ADR-0005 bleibt in seinen Grundsätzen; Bestandsinstallationen migrieren ohne
  Datenänderung an `users`; ein deaktivierter Anbieter ist sofort unwirksam; die Identitätsregel
  ist am Schema durchgesetzt. `spring.security.oauth2.resourceserver.jwt.*` gibt es im
  `oidc`-Profil nicht mehr, der Seeder liest allein `opaa.auth.oidc.*`.
- **Schwieriger:** Jeder Anbieter braucht Betriebsschritte außerhalb von OPAA (Client, CSP,
  Allowlist). Dieselbe Person mit zwei Anbietern lebt in zwei Konten. Ein Fehler in der
  Rollenzuordnung eines führenden Anbieters wirkt bei der nächsten Anfrage; der geschützte letzte
  Verwalter behält umgekehrt eine Rolle, die der Anbieter ihm entzogen hat.

## Verworfene Alternativen

- `oauth2Login` im Backend mit app-eigener Sitzung (qnop/Plugwerk) — Sitzungszustand, Token-Prägung, Refresh-Ledger und CSRF, die ADR-0005 vermeidet; Spring Security deckt den Mehr-Issuer-Fall im Resource-Server ab.
- Eigene Identitätstabelle mit Anbieter-UUID — nötig nur für Anbieter ohne Issuer.
- Zusammenführung über die E-Mail — Übernahmerisiko.
- Anbieter in Umgebungsvariablen oder Umgebung als dauerhafter Rückfall — Neustart je Änderung bzw. zwei Quellen der Wahrheit.
- Rollen aus dem Token nur additiv — ein entzogenes Recht bliebe in OPAA bestehen.
- Redirect-URI je Anbieter — jede Anleitung anbieterabhängig, ohne Gewinn.
- Home-Realm-Discovery über die E-Mail-Domäne — zweiter Eingabeschritt vor jeder Anmeldung; nachrüstbar.
- Indexing-Allowlist wiederverwenden oder Property „private Adressen erlauben" — koppelt Betriebsentscheidungen bzw. ist gröber als eine Allowlist.
- Token-Austausch über den Identitätsanbieter für Quellen (Spike #2174) — trägt nur in der laufenden Sitzung; Entscheidung offen in [connector-connections.md](../features/connector-connections.md).

## Offen

- Anbieter-zu-Organisation-Bindung für Mehrmandantenbetrieb.
