# ADR-0025: Mehrere OIDC-Anbieter — Identität als (Issuer, Subject), Anbieter in der Datenbank, Anmeldefluss bleibt in der SPA

## Status

Akzeptiert · zuletzt geändert 2026-10-09 · ergänzt ADR-0005 · Entscheidung 3 teilweise ersetzt durch ADR-0033 (Entscheidungen 4, 5), Entscheidung 4 ergänzt durch ADR-0036, Entscheidung 3 (Verzeichnisabgleich je Anbieter)

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

Die SPA führt den Autorisierungscode-Fluss mit PKCE gegen den gewählten Anbieter — ein
`UserManager` (`oidc-client-ts`) je Anbieter aus `issuerUri` und `clientId`; alle Anbieter sind
Public Clients ohne Client-Secret. Das Backend prüft jedes Bearer-Token über einen dynamischen
Multi-Issuer-Resolver (`OidcSecurityConfig`, `authenticationManagerResolver`): ein `JwtDecoder` je
aktiviertem Anbieter in der prozesslokalen `OidcProviderRegistry`, die nach jedem Commit einer
Anbieteränderung neu aufgebaut wird (ADR-0021). Ein Token mit unbekanntem `iss` wird mit `401` und
`WWW-Authenticate: … error_description="unknown_issuer"` abgewiesen (`SecurityCorsConfig` gibt den
Header frei); die SPA startet dann keine stille Erneuerung, sondern meldet lokal ab mit der
Meldung, dass der Anbieter nicht mehr verfügbar ist — sonst liefe sie in eine Schleife. Ein
Anbieter, dessen Decoder nicht gebaut werden kann (Discovery nicht erreichbar, Adressprüfung),
wird protokolliert übersprungen und nach einer Wartezeit erneut versucht (`RETRY_INTERVAL`).
OPAA speichert für die **Anmeldung** keine Tokens und prägt keine; Quellverbindungen und
verbundene Konten speichern Refresh-, Zugriffstoken und persönliche Geheimnisse verschlüsselt im
Token-Speicher (ADR-0041), nie das Anmeldetoken eines OIDC-Anbieters. Der OAuth-Ablauf für
Quellen meldet niemanden an OPAA an: `state` und PKCE-Verifier liegen kurzlebig in connections,
der Rücksprung landet in der SPA, das Backend tauscht den Code als vertraulicher Client und
bleibt zustandslos.

**`azp` wird geprüft, `aud` nicht** (`AuthorizedPartyValidator`): Trägt ein Token `azp`, muss es
der `client_id` der Anbieterzeile entsprechen; fehlt der Claim, gilt keine Prüfung. Einzige
Ausnahme: Nennt `aud` die `client_id`, passiert das Token. `aud` bleibt ungeprüft, weil Keycloak
es nicht auf den anfragenden Client setzt.

Preis: Jeder Anbieter braucht einen Public Client mit PKCE, Redirect-URI `<Origin>/auth/callback`,
Post-Logout-Redirect `<Origin>` und Web-Origin. „Ohne Neustart" gilt für das Backend, nicht für die
CSP: `frontend/nginx.conf` führt Anbieter-Origins in `connect-src` über `OPAA_CSP_CONNECT_SRC_EXTRA`,
ersetzt beim Containerstart; ein Anbieter auf neuem Origin ist erst nach Neustart des
Frontend-Containers anmeldefähig.

### 2. Identitätsmodell: `(issuer, subject)` bleibt der Schlüssel, der Anbieter ist über den Issuer eindeutig

Keine eigene Identitätstabelle: OPAA bindet ausschließlich OIDC an, jedes Token trägt einen `iss`.
`oidc_providers.issuer_uri` wird gespeichert, wie der Anbieter ihn prägt (Schrägstrich
eingeschlossen), und ist über den normalisierten Wert (Ausdrucksindex `rtrim(issuer_uri, '/')`)
eindeutig; die Bestandsübernahme braucht keine Migration an `users`. Das Ändern der `issuer_uri`
eines Anbieters mit Konten wird mit `409` verweigert — die einzige Aktion mit irreversibler
Datenwirkung. **Keine Zusammenführung über die E-Mail:** Ein unbekanntes `(issuer, subject)`
provisioniert ein neues Konto, auch bei gleicher Adresse; die einzige Ausnahme ist die von der
Person selbst eingelöste Übergabe eines lokalen Kontos (ADR-0033, Entscheidung 12). `email` und
`display_name` werden bei jeder Anfrage aus den Claims aktualisiert. Die Zeile trägt
`display_name`, `enabled`, `is_default`, `sort_order`, `issuer_uri`, `client_id`, optional
`jwk_set_uri`, die Claim-Zuordnung (Entscheidung 4), `provider_type` (`OIDC | LOCAL`, ADR-0033) und
die Verzeichnisabgleich-Einstellungen (ADR-0036); keine Secret-Spalte. `jwk_set_uri` ist ein
Vertrauensanker (es bestimmt, welche Schlüssel Token beglaubigen): nur für `SYSTEM_ADMIN`
änderbar, jede Änderung ein Audit-Ereignis.

### 3. Konfiguration in der Datenbank, Bootstrap aus der Umgebung, Adressprüfung mit eigener Allowlist

Anbieter werden über die Admin-API (`identity-providers.yaml`, nur `SYSTEM_ADMIN`) gepflegt und
wirken ohne Neustart; Anlegen, Ändern, Aktivieren, Löschen sind Audit-Ereignisse
`OIDC_PROVIDER_*` mit `AuditObjectType.SYSTEM_SETTING`. `OidcProviderSeeder` übernimmt beim
ersten Start im `oidc`-Profil `opaa.auth.oidc.{issuer-uri,client-id,jwk-set-uri}` (ohne
Vorgabewerte) als erste aktivierte Standard-Anbieterzeile, gegen Wiederholung durch
`OidcProviderSeedMarker` gesichert — nicht durch „ist die Tabelle leer?". Danach bestimmen die
Variablen den Betrieb nicht mehr, bleiben aber gesetzt als implizit erlaubte Adressen der
Adressprüfung. Der `dev`-Modus kennt keine Anbieterzeilen und schreibt keine Markierung.

Seit ADR-0033 ist der Erstadministrator das lokale Notanker-Konto; `InitialAdminPolicy` vergibt
`SYSTEM_ADMIN` nur noch für den Dev-Issuer, ein Zustand ohne OIDC-Anbieter ist zulässig, und der
letzte aktivierte OIDC-Anbieter darf deaktiviert oder gelöscht werden, wenn
`LocalAdminAvailabilityGuard` einen gesicherten lokalen Systemverwalter findet (sonst 409
`LAST_LOGIN_CAPABLE_ADMIN`) und der Aufruf `acknowledgeLastProvider` trägt. `is_default` steuert
nur noch Anmeldevorschlag und Löschschutz; `OPAA_OIDC_BOOTSTRAP=force` (Wiederanlauf des
Umgebungsanbieters) bleibt bis **31.03.2027** und ist durch `OPAA_LOCAL_ADMIN_RESET=force`
abgelöst. Ein Start ohne übernommenen Anbieter meldet „keine Anmeldung möglich" (`ERROR`) nur,
wenn kein anmeldefähiger lokaler Systemverwalter existiert; mit einem solchen Konto ist eine
fehlende Issuer-URI `INFO`, eine unvollständige Angabe bleibt `ERROR` (#2396).

**Adressprüfung (SSRF)** über `OidcAddressPolicy` mit eigenem Block
`opaa.auth.oidc.target-validation.{enabled,allowlist}` (`OPAA_OIDC_TARGET_VALIDATION_*`), nicht
über die Indexing-Konfiguration — wer die Konnektorprüfung abschaltet, schaltet die Anmeldung nicht
mit. Die beiden Adressen aus `OPAA_OIDC_ISSUER_URI` und `OPAA_OIDC_JWK_SET_URI` sind nach Schema,
Host **und** Port immer erlaubt. Geprüft wird beim Speichern, beim Aufbau der Registry, vor jedem
Abruf des Verbindungstests und für die aus der Discovery gelesene `jwks_uri`; Weiterleitungen
werden nicht gefolgt, das Discovery-Dokument muss die konfigurierte `issuer_uri` nennen, und jeder
Decoder trägt einen Issuer-Validator (`NimbusOidcJwtDecoderFactory`).

### 4. Claim-Zuordnung, Rollen, Gruppen und Organisation je Anbieter

`OidcClaimMapping` je Anbieterzeile: `email_claim` (`email`), `display_name_claim` (`name`,
Rückfall `preferred_username`, nie das Subject), `roles_claim`, `system_admin_role`,
`auditor_role`, `groups_claim` (alle leer = aus). Mit `roles_claim` ist der Anbieter für
`SYSTEM_ADMIN` und `AUDITOR` führend: `TokenRoleSynchronizer` leitet die Systemrolle bei jeder
Anfrage ab und schreibt nur bei Abweichung, als Rollenereignis unter einem Systemprozess-Akteur.
Drei Sicherungen: Der letzte anmeldefähige `SYSTEM_ADMIN` wird nie per Token entzogen — ein
bedingter `UPDATE` unter dem Advisory-Lock der Organisation
(`LocalAdminAvailabilityGuard#withdrawSystemAdminIfAnotherRemains`, ADR-0033, Entscheidung 4),
kein „lesen, dann schreiben"; die manuelle Rollenvergabe ist für Konten eines Anbieters mit
`roles_claim` gesperrt (`409`); das Setzen von `roles_claim` verlangt in der Oberfläche eine
Bestätigung. `AUDITOR` ist nicht geschützt. Gruppen aus dem Token (`groups_claim`) werden zu
Mitgliedschaften in Gruppen mit `GroupKind.IDENTITY_PROVIDER` und `external_id` im Namensraum
`oidc:<anbieter-id>:<gruppenname>` (`TokenGroupSynchronizer`) — hebt die Festlegung von ADR-0005
auf, dass Gruppen nur über `DirectoryClient` kommen. Der Verzeichnisabgleich hängt seit #1816 an
der Anbieterzeile (`directory_sync_enabled`, ADR-0036, Entscheidung 3), löst Mitglieder issuergebunden auf
(`UserRepository#findByOrganizationIdAndIssuerAndSubjectIn`) und verwaltet nur `ORG_UNIT`-Gruppen.
Alle Anbieter provisionieren in `Organization.DEFAULT_ID`.

### 5. Abmeldung, Callback und Token-Lebensdauer je Anbieter

Alle Anbieter teilen die Redirect-URI `<Origin>/auth/callback`; die SPA legt den Anbieter des
laufenden Flusses vor `signinRedirect()` im `sessionStorage` ab (je Tab) und den zuletzt benutzten
nur als Vorschlag im `localStorage` (`authStore.ts`). Beim Betreten der Anmeldeseite startet die SPA
einmal je Tab einen Versuch mit `prompt=none` beim vorgeschlagenen Anbieter (#1631); jede Antwort
mit OAuth-Fehlercode gilt als Absage und endet ohne Fehlermeldung auf der Anmeldeseite, der Code
steht nur in der Browser-Konsole. Eine geklickte Anmeldung sendet keinen `prompt`-Wert; „Mit anderem
Konto anmelden" entfällt (#1629, #1630), den Kontowechsel übernimmt der Anbieter nach dem
RP-initiierten Logout (`signoutRedirect()` mit `end_session_endpoint` des Anbieters der aktiven
Sitzung, `post_logout_redirect_uri = <Origin>`; ohne Endpunkt lokale Abmeldung mit Hinweis). Der
öffentliche `GET /api/v1/auth/config` nennt je aktiviertem Anbieter nur Anzeigename, Issuer-URI,
Client-ID, Vorauswahl und Reihenfolge — keine Claim-Zuordnung, nichts über Konten.

### 6. Grenzen

Kein SAML; keine Kontenzusammenführung außer der Übergabe aus ADR-0033; nur Public Clients mit
PKCE; keine `aud`-Prüfung; der letzte `AUDITOR` ist nicht geschützt; ein neuer Anbieter-Origin
braucht einen Frontend-Neustart; eine Organisation; der `dev`-Modus bleibt unverändert.

## Konsequenzen

- **Einfacher:** ADR-0005 bleibt in seinen Grundsätzen; Bestandsinstallationen migrieren ohne
  Datenänderung an `users`; ein deaktivierter Anbieter ist sofort unwirksam; die Identitätsregel
  ist am Schema durchgesetzt.
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

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-12 | #1529, #1534 | Erstadministrator ist das lokale Notanker-Konto; letzter OIDC-Anbieter mit Guard deaktivierbar (ADR-0033) | 3 |
| 2026-09-13 | #1631 | Automatischer Versuch mit `prompt=none`; jede OAuth-Absage endet still | 5 |
| 2026-09-13 | #1629, #1630 | „Mit anderem Konto anmelden" entfällt | 5 |
| 2026-09-20 | #1816 | Verzeichnisabgleich je Anbieter, `TrustedProvider` entfällt (ADR-0036) | 4 |
| 2026-10-03 | #2159 | Token für Quellen und verbundene Konten im Token-Speicher (ADR-0041) | 1 |
| 2026-10-09 | #2396 | Startmeldung ohne Anbieter nur `ERROR`, wenn kein lokaler Systemverwalter | 3 |
