# ADR-0033: Lokale Benutzerverwaltung — lokaler Systemverwalter, Backend als Token-Aussteller, lokaler Anbieter als Anbieterzeile, Mail-Infrastruktur

## Status

Vorgeschlagen (11.09.2026, Issue #1531, Epic #1529). Setzt den Beschluss aus #1368 vom 10.09.2026
um. Nachtrag zu [ADR-0005](0005-authentication-strategy.md) und
[ADR-0025](0025-mehrere-oidc-anbieter.md), die weiter gelten, soweit dieser ADR sie nicht an einer
benannten Stelle präzisiert oder aufhebt (Entscheidungen 4, 5 und 12). Architekturvorbild ist qnop
(`qnophq/qnop`, Vertikale „Identity & administration": dortige ADR-0022, 0023, 0025, 0026, 0027 und
Issues #12, #17, #19, #20).

## Kontext

OPAA kennt ausschließlich Konten, die über einen OIDC-Anbieter entstehen (ADR-0005; seit #1294
mehrere Anbieter, ADR-0025). Die Anbieter liegen nur noch in der Datenbank; eine frische Installation
hat keinen, und der Erstadministrator entsteht heute über `OPAA_INITIAL_ADMIN_EMAIL` beim ersten
Login über den Standardanbieter (`InitialAdminPolicy`, `TrustedProvider`). Der einzige Weg aus
„letzter Anbieter falsch konfiguriert" ist `OPAA_OIDC_BOOTSTRAP=force` plus Neustart. ADR-0005 nennt
als negative Konsequenz, dass Interessenten ohne eigenen Identitätsanbieter kein Angebot haben, und
`docs/features/access-control.md` führt lokale Konten seit jeher als Rückfallebene, ohne dass davon
etwas gebaut wäre. OPAA hat keinerlei Mailversand, keine Benutzerverwaltungsseite und in `users` weder
Passwort- noch Statusspalten.

#1368 hat die Frage als Diskussionsgrundlage aufgearbeitet; die Abstimmung vom 10.09.2026 hat
entschieden:

- **Zuschnitt B + C als eine Lösung:** vollständige, optionale lokale Benutzerverwaltung, per
  Konfiguration an- und abschaltbar, Standard aus; konzeptionell ein weiterer Anbieter vom Typ „lokal"
  (Datenmodell wie Modell III).
- **Systemverwalter nach Modell I:** Der Erstadministrator ist **immer ein lokales Konto**, wird beim
  allerersten Start angelegt, das Einmalpasswort einmalig ins Log geschrieben, der Wechsel bei der
  ersten Anmeldung erzwungen. Eigene Anmeldeseite für lokale Systemverwalter, solange die lokale
  Verwaltung deaktiviert ist. Mehrere Systemverwalter mit je eigenem Konto, keine Sammelkonten.
- **Einladung per E-Mail, Passwort vergessen per E-Mail, optionale Selbstregistrierung** — damit
  braucht OPAA eine SMTP-Konfiguration; der Ausschluss „kein Mailversand" ist aufgehoben, die
  Mail-Infrastruktur wird mit dem Kanal E-Mail aus #1297 nur einmal gebaut.
- **Sicherheitsmindestmaß aus #1368, Frage 6** gilt unverändert.

Die Messlatte ist die Historie in ADR-0005: Der Modus `basic` fiel, weil er `subject = username`
vergab (kein Migrationspfad), Klartext ohne konstante Laufzeit verglich, kein Rate-Limiting und kein
Refresh hatte, genau einen Nutzer kannte und ein HMAC-Secret in der Betriebskonfiguration führte. Jede
Entscheidung unten benennt, wo sie diese Lehre einlöst.

Was ADR-0025 für die Mehranbieter-Anmeldung ausdrücklich **nicht** wollte — Sitzungszustand,
Token-Prägung, Refresh-Ledger, CSRF —, ist für eine lokale Anmeldung mit Passwort unvermeidlich.
Dieser ADR führt es deshalb ein, aber nur für den lokalen Issuer: Der OIDC-Weg bleibt, wie ADR-0025 ihn
festgelegt hat (SPA als Client, Backend als zustandsloser Resource-Server, keine eigenen Tokens für
OIDC-Sitzungen).

## Entscheidung

### 1. Anmeldekennung ist die E-Mail-Adresse

Lokale Konten melden sich mit **E-Mail-Adresse und Passwort** an; es gibt keinen eigenen
Benutzernamen. Die Eindeutigkeit gilt groß-/kleinschreibungsunabhängig und **nur innerhalb des
lokalen Issuers** (partieller Unique-Index über `lower(email) WHERE issuer = 'urn:opaa:local'`) — ein
OIDC-Konto mit derselben Adresse bleibt zulässig, wie ADR-0025 es für Konten zweier Anbieter
festlegt.

qnop führt zusätzlich `username`. OPAA verzichtet darauf: Der Beschluss nennt „Name und
E-Mail-Adresse", die Einladung und jede Wiederherstellung laufen ohnehin über die Adresse, und ein
zweiter Bezeichner wäre ein zweiter Aufzählungskanal ohne Gegenwert. Die Adresse wird beim Anlegen
normalisiert gespeichert (getrimmt, Kleinschreibung nur für den Vergleich, Anzeige wie eingegeben).

### 2. Identität: fester lokaler Issuer, Subject ist die Konto-UUID

Lokale Konten sind gewöhnliche `users`-Zeilen mit `issuer = 'urn:opaa:local'` und
`subject = users.id` (die UUID als Zeichenkette). Die URN ist installationsunabhängig — eine
Basis-URL ändert sich beim Umzug einer Installation, die Identität ihrer Konten darf es nicht. Nie ist
die Anmeldekennung das Subject (Lehre aus `basic`); eine geänderte E-Mail-Adresse lässt Spaces,
Mitgliedschaften und Rollen unberührt. Die Identitätsregel `(issuer, subject)` aus ADR-0025 gilt damit
unverändert, `UserProvisioningFilter` findet lokale Konten über denselben Schlüssel wie jedes andere.

### 3. Datenmodell: lokale Zugangsdaten als 1:1-Tabelle, Kontozustand abgeleitet

Die lokalen Felder liegen in einer eigenen Tabelle `local_credentials` (`user_id` als Primär- und
Fremdschlüssel auf `users(id) ON DELETE CASCADE`), nicht als Spalten an `users`:

| Spalte | Bedeutung |
| --- | --- |
| `password_hash` | nullbar — ein eingeladenes Konto hat noch keines |
| `password_change_required` | erzwungener Wechsel (Seed, erzeugtes Anfangspasswort, administratives Zurücksetzen) |
| `password_invalidated_before` | jedes Token mit `iat` davor ist ungültig (Massenwiderruf) |
| `locked_at`, `locked_reason` (`ADMIN` \| `FAILED_LOGINS`) | Sperre durch Verwalter oder Fehlversuche |
| `failed_login_attempts`, `lockout_until` | Zähler und Ende der Fehlversuch-Sperre |
| `expires_at` | Ablaufdatum (Auflage aus `access-control.md`) |
| `email_verified_at` | gesetzt durch Einladungslink oder Bestätigungslink der Selbstregistrierung |
| `created_reason` | Anlagegrund, freier Text |
| `created_at`, `updated_at`, `version` | Zeitstempel, optimistische Sperre |

qnop hält dieselben Felder in `qnop_user` mit einem `CHECK` „intern ⇒ Passwort gesetzt". OPAA
wählt die Nebentabelle, weil `users` bereits anbieterbezogene Spalten trägt und in jeder Anfrage
gelesen wird: Acht nullbare Spalten, die für jedes OIDC-Konto leer bleiben, verwischen die Semantik der
Tabelle, und der qnop-`CHECK` passt nicht zu Konten, die eingeladen sind und noch kein Passwort haben.
Die Invariante „eine `local_credentials`-Zeile existiert genau dann, wenn `users.issuer` der lokale
Issuer ist" erzwingt der einzige Schreibpfad (`LocalUserService`); ein Integrationstest prüft sie.

Der **Kontozustand wird abgeleitet, nicht gespeichert**: `INVITED` (kein Passwort oder E-Mail nicht
bestätigt), `ACTIVE`, `LOCKED` (`locked_at` gesetzt oder `lockout_until` in der Zukunft), `EXPIRED`
(`expires_at` vergangen). Anmeldefähig ist nur `ACTIVE` — und bei abgeschalteter Verwaltung nur ein
`ACTIVE`-Konto mit `SYSTEM_ADMIN`. Das Seed-Konto und Konten, die ein Verwalter mit Anfangspasswort
anlegt, gelten als bestätigt (`email_verified_at` wird beim Anlegen gesetzt — der Verwalter bürgt für
die Adresse); nur Einladung und Selbstregistrierung verlangen den Klick auf den Link.

Dazu kommen drei Token-Tabellen (qnop `0004-token-schema.yaml`): `local_refresh_tokens`
(Familie, HMAC-Lookup-Hash, Ausstellung, Ablauf, Widerruf mit Grund, Nachfolger),
`local_revoked_tokens` (`jti`-Hash, Ablauf) und `local_action_tokens` (ein Zweck `SET_PASSWORD` \|
`RESET_PASSWORD` \| `VERIFY_EMAIL`, SHA-256-Hash, Ablauf, Verbrauch) — eine Tabelle mit Zweck statt
qnops zwei baugleicher. Roh-Tokens werden nirgends gespeichert.

### 4. Der lokale Anbieter ist eine Anbieterzeile — ihr Schalter ist der Schalter der Verwaltung

`oidc_providers` erhält `provider_type` (`OIDC` \| `LOCAL`). Es gibt **genau eine** `LOCAL`-Zeile
(partieller Unique-Index), angelegt vom Seed (Entscheidung 5), mit `issuer_uri = urn:opaa:local`,
ohne `client_id`, ohne Adressprüfung, Discovery und Verbindungstest. **Ihr `enabled` ist der Schalter
der lokalen Benutzerverwaltung**, Standard `false`; `enable`/`disable` laufen über die bestehende
Anbieter-API und sind Audit-Ereignisse (`LOCAL_ACCOUNTS_ENABLED`/`_DISABLED`). Die Zeile ist nicht
löschbar, ihr Issuer nicht änderbar. Der öffentliche `GET /api/v1/auth/config` führt sie nicht in
`providers`, sondern nur als `localAccounts { enabled, selfRegistrationEnabled, passwordResetEnabled,
passwordMinLength }`.

Das ändert drei Regeln aus ADR-0025, Entscheidung 3, und hebt eine auf:

- **`is_default` bedeutet nur noch „Verzeichnis-Anbieter".** Es gilt ausschließlich für `OIDC`-Zeilen
  und steuert, wie bisher, den Verzeichnisabgleich (`TrustedProvider`). Die `LOCAL`-Zeile ist nie
  Standard (`CHECK`). Die Erstadministrator-Wirkung des Standardanbieters entfällt (Entscheidung 5).
- **Ein Zustand ohne OIDC-Anbieter ist zulässig** — eine frische Installation *ist* dieser Zustand.
  Die Regel „genau ein Standard, solange Anbieter existieren" gilt weiter, aber nur über die
  `OIDC`-Zeilen.
- **Der letzte aktivierte OIDC-Anbieter darf deaktiviert oder gelöscht werden**, weil der lokale
  Systemverwalter immer anmeldefähig bleibt (Entscheidung 8). Die API verlangt dafür eine
  ausdrückliche Bestätigung (`acknowledgeLastProvider = true`, sonst 409 mit der Konsequenz), die
  Oberfläche zeigt sie als Dialog: Danach können sich nur noch lokale Konten anmelden; ein
  vertippter Anbieter lässt sich aus der lokalen Anmeldung heraus korrigieren — ohne
  Datenbankzugriff und ohne Umgebungsvariable. **Aufgehoben** ist damit die Aussage „es gibt keinen
  Zustand ohne anmeldefähigen Anbieter" in ihrer alten Form: Der anmeldefähige Weg ist jetzt
  immer der lokale Systemverwalter, nicht ein OIDC-Anbieter.

Der lokale Decoder (Entscheidung 8) wird **unabhängig von `enabled`** registriert. Was der Schalter
bewirkt, entscheidet sich im Token-Validator und im Login: Bei `enabled = false` werden Login und
Token regulärer lokaler Konten abgewiesen (`WWW-Authenticate`-Marker `local_accounts_disabled`),
lokale `SYSTEM_ADMIN`-Konten passieren. Das Abschalten wirkt damit sofort auf laufende Sitzungen, ohne
dass Sitzungen serverseitig aufgezählt werden müssten. Die Anbieter-Registry bleibt eine prozesslokale
Fundstelle nach ADR-0021; dieser ADR trägt die neuen Fundstellen dort ein.

### 5. Erstadministrator: lokaler Seed beim ersten Start, Einmalpasswort ins Log, Wiederanlauf per Variable

Beim ersten Start im `oidc`-Profil legt `LocalAdminSeeder` (vor dem Webserver, wie
`OidcProviderSeedRunner`; gegen Wiederholung durch eine Markierungszeile gesichert, nicht durch „ist
die Tabelle leer?" — Muster `OidcProviderSeedMarker`/`LlmModelSeedMarker`) die `LOCAL`-Anbieterzeile
und **ein lokales `SYSTEM_ADMIN`-Konto** an: E-Mail aus `OPAA_INITIAL_ADMIN_EMAIL` (die Variable
behält Namen und Bedeutung „Adresse des ersten Systemverwalters"), Anzeigename „Systemverwaltung",
Anlagegrund „Erstadministrator", `password_change_required = true`.

**Passwort.** Ist `OPAA_INITIAL_ADMIN_PASSWORD` gesetzt, wird es verwendet und der Wechsel **nicht**
erzwungen — der Weg für CI, E2E und automatisierte Bereitstellung (qnop `QNOP_ADMIN_PASSWORD`). Sonst
erzeugt der Seed ein Passwort (`PasswordGenerator`, lesbares Alphabet, 20 Zeichen) und schreibt es
**einmalig als deutlich markierten Block ins Anwendungslog** — so hat es der Maintainer entschieden,
und im Container ohne Shell (ADR-0029) ist das Log der Kanal, den der Betrieb ohnehin liest. Die
Kehrseite ist bekannt: Log-Weiterleitungen sehen den Wert. Drei Sicherungen halten den Schaden klein:
Der Wert ist nur bis zur ersten Anmeldung gültig (erzwungener Wechsel), das Konto ist ohne Anmeldung
wertlos, und wer das Log nicht belasten will, setzt die Variable. Das reguläre Log nennt danach nur,
**dass** gesät wurde. Audit `LOCAL_ADMIN_SEEDED` als Systemprozess-Ereignis. qnops Weg (`System.err`
am Logger vorbei plus Datei `0600`) wird nicht übernommen: Im Container ist `stderr` dasselbe Log,
und eine Datei im Volume wäre ein zweiter Ort, an dem ein Geheimnis liegen bleibt.

**Die Erstadministrator-Regel für OIDC-Konten entfällt.** `InitialAdminPolicy` vergibt `SYSTEM_ADMIN`
nicht mehr für Konten eines OIDC-Anbieters; die einzige Wirkung der Regel bleibt der **Dev-Issuer**,
damit `dev-admin` wie heute Systemverwalter ist und der `dev`-Modus unverändert bleibt (ADR-0005).
IdP-Konten werden Systemverwalter ausschließlich durch Rollenvergabe (manuell oder über `roles_claim`,
ADR-0025, Entscheidung 4). Damit ist die Kapermöglichkeit über einen zweiten Anbieter, die ADR-0025 nur
eindämmen konnte, ganz weg. **Bestandsinstallationen** verlieren nichts: Rollen liegen in `users`,
die Regel griff nur beim Anlegen; der Seed legt das lokale Konto **zusätzlich** an, der Betrieb
entscheidet, ob er es nutzt oder sperrt (das Handbuch sagt: nutzen, denn es ist der Notanker).

**Wiederanlauf.** `OPAA_LOCAL_ADMIN_RESET=force` setzt beim Start einmalig das Seed-Konto zurück:
entsperren, Ablauf löschen, neues Einmalpasswort (wieder ins Log oder aus
`OPAA_INITIAL_ADMIN_PASSWORD`), `password_change_required`, alle Sitzungen widerrufen — laut
protokolliert, auditiert (`LOCAL_ADMIN_RESET`), und der Betrieb entfernt die Variable danach. Das
ersetzt `OPAA_OIDC_BOOTSTRAP=force` als Notanker; die alte Variable bleibt **eine Version lang** mit
Deprecation-Hinweis im Log funktionsfähig und wird dann entfernt. Die `OPAA_OIDC_*`-Variablen behalten
ihre Bootstrap-Rolle für den ersten OIDC-Anbieter (ADR-0025) — wer sie setzt, bekommt wie heute den
Anbieter gesät; wer sie nicht setzt, legt Anbieter über die Oberfläche an, angemeldet als lokaler
Systemverwalter.

Der Seed läuft **nicht** im `dev`-Profil (dort gibt es keine Anbieterzeilen und keinen Aussteller) und
schreibt dort keine Markierung.

### 6. Token: HS256-Access-Token mit HKDF-abgeleitetem Schlüssel aus einem validierten Secret

Das Backend prägt Access-Tokens selbst (Variante a aus #1368) — als **HS256-JWT** (Nimbus
`JwtEncoder`) mit einem Schlüssel, der per HKDF-SHA256 (RFC 5869) aus `OPAA_AUTH_JWT_SECRET`
abgeleitet wird, mit Zweckbindung (`opaa:jwt:access-token`; ein zweiter Zweck
`opaa:jwt:refresh-token-lookup` für den HMAC der Refresh-Tokens). Claims: `jti` (UUID, widerrufbar),
`iss = urn:opaa:local`, `sub = users.id`, `iat`, `exp` (15 Minuten, `opaa.auth.local.access-token-ttl`),
`email`, `name` (damit `TokenClaims` wie bei jedem Anbieter liest) und `pcr` (Passwortwechsel
erforderlich). Keine Rolle im Token — Rollen kommen aus `users`, wie heute.

**Das Secret ist Betriebskonfiguration mit Zähnen.** `LocalAuthProperties` ist `@Validated`; ein
`@ValidSecret`-Constraint verlangt mindestens 32 Zeichen und lehnt bekannte Platzhalter ab
(`change_me`, `changeme`, `secret`, `password`, `opaa`, …). Fehlt das Secret oder ist es schwach,
bricht der Start im `oidc`-Profil mit einer Meldung ab, die die Variable nennt (Muster
`AuthProfileGuard`); im `dev`-Profil ist es ohne Wirkung. Das ist der Unterschied zum HMAC-Secret von
`basic`, das ADR-0005 als Last nennt: Dort war es ein weiteres unvalidiertes Geheimnis für einen Modus
ohne Gegenwert; hier ist es die eine Wurzel eines Aussteller-Subsystems mit fail-fast-Prüfung, neben
den bestehenden `OPAA_SETTINGS_ENCRYPTION_KEY` und `OPAA_CREDENTIALS_ENCRYPTION_KEY`. Eine Rotation
des Secrets ist eine bewusste Abmeldung aller lokalen Sitzungen (Access- **und** Refresh-Tokens, weil
der Lookup-Hash mitwechselt).

**Kein asymmetrischer Schlüssel, kein JWK-Set.** #1368 hatte das erwogen. Es gibt keinen zweiten
Prüfer: derselbe Prozess stellt aus und prüft, und ADR-0021 legt eine Instanz fest. Ein Schlüsselpaar
mit `kid`-Rotation und `/.well-known/jwks.json` wäre Schlüsselverwaltung für niemanden. Sollte je ein
externer Dienst lokale Tokens prüfen müssen, ist der Wechsel auf RS256 eine Änderung in
`LocalTokenService` und `NimbusOidcJwtDecoderFactory`, nicht im Datenmodell.

### 7. Sitzung: Refresh-Token als HttpOnly-Cookie, Rotation mit Wiederverwendungserkennung, zweistufiger Widerruf

- **Refresh-Token:** 256 Bit Zufall, base64url, nur der HMAC-SHA256-Lookup-Hash in
  `local_refresh_tokens`; Lebensdauer 7 Tage (`opaa.auth.local.refresh-token-ttl`). **Familien mit
  Rotation:** Jede Vorlage eines aktiven Tokens widerruft es (`ROTATED`) und stellt einen Nachfolger
  derselben Familie aus; die Vorlage eines bereits widerrufenen Tokens ist ein Replay und widerruft
  die **ganze Familie** (`REUSE_DETECTED`, Audit). Unbekannt, abgelaufen und wiederverwendet sind für
  den Client ununterscheidbar: 401 und Cookie löschen.
- **Cookie** `opaa_refresh`: `HttpOnly`, `SameSite=Strict`, `Secure` (abschaltbar über
  `opaa.auth.local.cookie-secure` für lokales HTTP), `Path=/api/v1/auth/local` — es reist nur zu den
  Refresh- und Logout-Endpunkten und ist für Skripte unsichtbar. Das Access-Token hält die SPA nur im
  Speicher; nichts im `localStorage`.
- **CSRF** nur dort, wo das Cookie trägt: `POST /api/v1/auth/local/refresh` und `/logout` verlangen ein
  Double-Submit-Token (`CookieCsrfTokenRepository.withHttpOnlyFalse()`, Header `X-XSRF-TOKEN`). Alle
  anderen Endpunkte bleiben Bearer-only und CSRF-frei, wie heute.
- **Widerruf** in zwei Schichten: eine `jti`-Denylist (`local_revoked_tokens`, SHA-256, Caffeine-Cache
  mit `expireAfterWrite = access-token-ttl`) für einzelne Tokens (Logout) und
  `password_invalidated_before` für alles auf einmal (Passwortwechsel, Sperre, Zurücksetzen,
  Abschalten). Logout und Passwortwechsel wirken damit **sofort**, nicht erst beim Ablauf — die
  Zusicherung aus `access-control.md`, „Sitzungsverwaltung". Abgelaufene Zeilen räumt der bestehende
  Scheduler täglich ab.
- **Voraussetzung `SameSite=Strict`:** Die API liegt in allen ausgelieferten Konfigurationen unter dem
  Origin der SPA (`frontend/nginx.conf` proxyt `/api/`, der Vite-Dev-Server ebenso). Ein getrennter
  API-Origin bräuchte `SameSite=None` und CORS mit Credentials — eine Grenze dieses ADR, nicht
  vorgesehen.

### 8. Integration in den Multi-Issuer-Resolver: ein weiterer Decoder, nie ein Provisionierer

`OidcProviderRegistry` (ADR-0025) registriert für den lokalen Issuer einen
`NimbusJwtDecoder.withSecretKey(...)` mit Issuer- und Zeitvalidator sowie einem
**Widerrufs-Validator**, der `jti`-Denylist, `password_invalidated_before`, Kontozustand
(`LOCKED`, `EXPIRED`) und den Schalter aus Entscheidung 4 prüft — bei jeder Anfrage, gegen den
Datenbankzustand, mit dem Caffeine-Cache nur für die Denylist. Keine `azp`-Prüfung (es gibt keine
`client_id`). Abgewiesene Tokens tragen wie `unknown_issuer` einen `BearerTokenError` mit
`error_description` ∈ {`local_accounts_disabled`, `account_locked`, `session_revoked`}, damit die SPA
den Fall vom abgelaufenen Token unterscheidet und **keinen** Erneuerungsversuch startet.

`UserService#provisionFromToken` behandelt den lokalen Issuer als **Finder, nie als Anleger**: Ein
Token mit unbekanntem `sub` führt zu 401, nicht zu einem neuen Konto; `email` und `display_name`
werden nicht aus dem Token zurückgeschrieben (die Datenbank ist hier die Quelle, nicht der Claim).
`InitialAdminPolicy` wird für den lokalen Issuer nicht konsultiert. Unterhalb davon ist alles wie bei
jedem Anbieter: `UserProvisioningFilter` legt `CurrentUser` ab, `UserProvisionedEvent` löst den
persönlichen Space aus, Method Security sieht `ROLE_SYSTEM_ADMIN` aus `users.system_role`.

`PasswordChangeRequiredFilter` läuft nach `AuthorizationFilter`: `pcr = true` und Pfad nicht unter
`/api/v1/auth/local/` ⇒ 403 mit Fehlercode `PASSWORD_CHANGE_REQUIRED`. Der Wechsel setzt `pcr` nur im
nächsten Token zurück — deshalb stellt `change-password` unmittelbar ein neues Access-Token aus.

Der Aussteller existiert nur im `oidc`-Profil (`@Profile("oidc")`); `DevSecurityConfig` kennt weder
die Endpunkte noch den Filter. `AuthProfileGuard` bleibt unverändert: Der Betriebsmodus heißt weiter
`oidc`, auch wenn er jetzt eine lokale Anmeldung enthält — eine Umbenennung bräche jede
`SPRING_PROFILES_ACTIVE` im Feld für einen Namen. Das Handbuch nennt `oidc` künftig „Betriebsmodus
(OIDC-Anbieter und lokale Konten)".

### 9. Sicherheitsmindestmaß

- **Hashing:** BCrypt mit Kostenfaktor 12, hinter Spring Securitys `DelegatingPasswordEncoder`
  (`{bcrypt}`-Präfix), damit ein späterer Wechsel auf Argon2id eine Konfigurationsänderung mit
  Neu-Hash bei der nächsten Anmeldung ist. Vergleich ausschließlich über `PasswordEncoder#matches`;
  bei unbekannter Kennung wird gegen einen festen Dummy-Hash verglichen, damit die Antwortzeit nicht
  verrät, ob die Kennung existiert. Argon2id wird nicht gewählt, weil es Bouncy Castle als
  zusätzliche Abhängigkeit zieht und BCrypt für einen Login mit Rate-Limiting und Kontosperre
  ausreicht; die Präfix-Kodierung hält die Tür offen.
- **Passwortrichtlinie:** mindestens 12 Zeichen (Vorgabe, verwaltbar über
  `local_auth_settings.password_min_length`, nie unter 8), höchstens 64 Zeichen und 72 Byte (BCrypt
  verarbeitet 72 Byte; mehr würde still abgeschnitten oder — je nach Version — abgelehnt), nicht gleich
  der E-Mail-Adresse. **Keine Komplexitätsregeln** (Sonderzeichenpflicht verschiebt Passwörter auf
  Zettel), kein erzwungener periodischer Wechsel (BSI ORP.4.A8 in der aktuellen Fassung verlangt ihn
  nicht mehr; erzwungen wird der Wechsel nur bei Verdacht und nach Erst- oder Rücksetzpasswort).
  Die Oberfläche zeigt die Regel an und bietet „sicheres Passwort erzeugen".
- **Rate-Limiting je IP und je Konto** (qnop ADR-0027): Login 10/60 s je IP, Refresh 30/60 s je IP,
  Passwortwechsel 5/300 s je Subject, Registrierung 5/3600 s je IP und 3/3600 s je Adresse, Passwort
  vergessen 5/3600 s je IP und 3/3600 s je Adresse, Passwort setzen 10/900 s je IP; Antwort 429 mit
  `Retry-After`. **Kontosperre** nach 5 Fehlversuchen für 15 Minuten (feste Dauer; eine progressive
  Verlängerung wäre eine spätere Verschärfung), Zähler atomar (`UPDATE … SET n = n + 1`), Sperre und
  Entsperren auditiert; ein gesperrtes Konto antwortet auf Login identisch zum falschen Passwort,
  der Zustand ist nur in der Verwaltung sichtbar.
- **Client-IP nur von vertrauten Proxys.** `X-Forwarded-For` wird ausschließlich ausgewertet, wenn
  `getRemoteAddr()` in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` liegt; die Vorgabe ist **leer** (Header
  ignoriert). Der bestehende `RateLimitFilter` liest den Header heute ungeprüft — hinter dem
  Compose-nginx kann damit jeder Client seinen Bucket frei wählen. Er wird auf dieselbe Auflösung
  umgestellt; der Compose-Stack bekommt ein festes Subnetz und eine passende Vorgabe in
  `.env.docker.example`, das Handbuch die Pflicht, die Variable hinter jedem Reverse-Proxy zu setzen.
- **Kein Geheimnis im Log** außer dem Einmalpasswort des Seeds (Entscheidung 5): keine Roh-Tokens,
  keine Passwörter, keine SMTP-Passwörter; ein Test nach dem Muster von qnops `LogPrivacyTest`
  belegt es.
- **MFA (TOTP)** ist **nicht** Teil dieses Epics. Für lokale Systemverwalter wäre sie die
  naheliegende nächste Härtung; sie braucht eine eigene Enrollment-Oberfläche, Wiederherstellungscodes
  und eine zweite Anmeldestufe im Aussteller — ein eigenes Issue nach dem Epic, im Handbuch als
  Empfehlung „lokale Systemverwalter-Konten befristen und die Verwaltung im Regelbetrieb aus lassen"
  vorweggenommen.

### 10. Mail-Infrastruktur: SMTP in den Systemeinstellungen, Vorlagen mit Code-Standards, Ergebnis statt Exception

`io.opaa.mail` ist ein eigenes Subsystem nach qnop #19:

- **SMTP-Einstellungen** in der Singleton-Tabelle `mail_settings` (Muster `branding_settings`):
  `enabled`, `host`, `port`, `username`, `password_ciphertext` (verschlüsselt über `SettingsEncryptor`,
  `OPAA_SETTINGS_ENCRYPTION_KEY`), `encryption` (`NONE` \| `STARTTLS` \| `SSL`), `from_address`,
  `from_name`. Gepflegt über `GET/PUT /api/v1/system/mail-settings` (nur `SYSTEM_ADMIN`); das Passwort
  erscheint in Antworten als `***`, und `***` im Request bedeutet „unverändert" — ein Secret läuft
  nie durch den Browser zurück. Snapshot mit Neuaufbau nach Commit; `MailSenderProvider` verwirft den
  gecachten `JavaMailSenderImpl` bei jeder Änderung — **ohne Neustart**. Keine Adressprüfung für den
  SMTP-Host: Ein interner Mailserver ist der Regelfall, und die Einstellung ist `SYSTEM_ADMIN`
  vorbehalten; dieselbe Abwägung wie bei den Modell-Endpunkten.
- **Öffentliche Basis-URL** für Links in Mails: `OPAA_PUBLIC_BASE_URL` (`opaa.public-base-url`) als
  **Umgebungsvariable**, nicht als Verwaltungseinstellung — sie ist eine Eigenschaft der
  Bereitstellung wie `OPAA_CORS_ALLOWED_ORIGINS` und `OPAA_CSP_CONNECT_SRC_EXTRA`, und wer sie im
  Browser änderbar macht, öffnet Phishing-Links über eine kompromittierte Verwaltersitzung. Fehlt sie,
  meldet der Testversand das, und Einladungen fallen auf die Link-Übergabe zurück (Entscheidung 11).
  Der `Host`-Header der Anfrage wird **nie** als Basis verwendet.
- **`MailService.send(key, locale, recipient, vars)`** liefert ein versiegeltes
  `SendResult { Sent \| Skipped(reason) \| Failed(reason) }` und wirft nie; `Skipped`, wenn SMTP nicht
  konfiguriert ist. Versand **synchron** mit Timeouts, ohne Outbox und ohne Wiederholung: Die
  Auth-Mails sind an eine Nutzeraktion gebunden, deren Ergebnis der Aufrufer sofort braucht (der
  Verwalter sieht „gesendet" oder bekommt den Link angezeigt). Ein späterer Digest aus #1297 kann eine
  Warteschlange ergänzen, ohne den Vertrag zu ändern.
- **Vorlagen:** `MailTemplateKey` ist die Registry mit deutschen Standards je Schlüssel (Betreff,
  Text, HTML-Fragment, Schaltflächentext, deklarierte Platzhalter): `LOCAL_ACCOUNT_INVITATION`,
  `PASSWORD_RESET`, `ADMIN_PASSWORD_RESET`, `REGISTRATION_VERIFICATION`, `TEST_MAIL`.
  Überschreibungen liegen in `mail_templates(template_key, locale, subject, body_plain, body_html)`;
  Auflösung Datenbank → Standard. Rendering mit JMustache, strikt (fehlender Platzhalter ist ein
  Fehler), zwei Compiler (Betreff/Text ohne, HTML mit Escaping); `MailPlaceholderValidator` lässt nur
  deklarierte Platzhalter zu; `EmailLayoutBuilder` liefert den gebrandeten HTML-Rahmen aus
  `BrandingSettings` (Produktname, Farbe). Vorlagen-API mit Vorschau, Zurücksetzen und Testversand.
- **Verhältnis zu #1297:** Der Kanal E-Mail des Benachrichtigungssystems nutzt `MailService` und die
  Registry unverändert und ergänzt Vorlagen und gegebenenfalls einen Digest. ADR-0019 bleibt gültig;
  er erhält den Hinweis, dass ein Mail-Sender existiert.

### 11. Kontolebenszyklus: Einladung, Zurücksetzen mit Link-Rückfall, Sperren als Regelweg, Löschen nach dem DSGVO-Pfad

- **Anlegen** durch `SYSTEM_ADMIN` mit E-Mail, Name, optional Rolle, Ablaufdatum und Anlagegrund —
  entweder **per Einladung** (Konto `INVITED`, Aktionstoken `SET_PASSWORD` mit 72 Stunden, Mail
  `LOCAL_ACCOUNT_INVITATION`) oder **mit erzeugtem Anfangspasswort** (einmalig in der Antwort,
  `pcr`). Schlägt der Versand fehl oder ist SMTP aus, enthält die Antwort die Einladungs-URL **genau
  einmal**, damit der Verwalter sie auf anderem Weg übergibt; die Oberfläche zeigt sie mit dem Hinweis,
  dass sie nicht erneut abrufbar ist (qnop `AdminPasswordResetResponse.resetUrl`).
- **Zurücksetzen** durch den Verwalter: Aktionstoken `RESET_PASSWORD` (30 Minuten,
  `local_auth_settings.reset_token_ttl_minutes`) per Mail, mit demselben Link-Rückfall; oder ein
  erzeugtes Passwort mit `pcr`. Beides widerruft alle Sitzungen des Kontos.
- **Sperren ist der Regelweg**, Löschen die Ausnahme — wie `access-control.md` es für das Ausscheiden
  vorsieht. Sperren widerruft alle Sitzungen sofort und hebt eine Fehlversuch-Sperre auf; Entsperren
  setzt Zähler zurück. Selbstsperre und Sperre, Ablauf, Rollenentzug oder Löschung des **letzten
  Systemverwalters** werden abgelehnt (409 `SELF_LOCKOUT`, `LAST_ADMIN`) — über denselben bedingten
  `UPDATE` und Advisory-Lock wie `TokenRoleSynchronizer` (#1331, #1349), keine zweite Zählung.
- **Ablaufdatum:** Ein abgelaufenes Konto ist `EXPIRED` — kein Login, laufende Tokens abgewiesen, in
  der Liste hervorgehoben, nicht gelöscht. Die Verwaltung zeigt dauerhaft, wie viele lokale Konten
  **ohne** Ablaufdatum existieren; das ist die sichtbare Form der Auflage „begründet und regelmäßig
  überprüft" aus `access-control.md`. Systemverwalter-Konten sind davon nicht ausgenommen.
- **Löschen** folgt dem Pfad in `docs/features/security-and-compliance.md`, „Löschung eines
  Benutzerkontos" (Zugang deaktivieren, Assets „Nachfolge offen", aus Spaces und Gruppen entfernen,
  Konto/Sitzungen/Tokens löschen, Pseudonymzuordnung entfernen). Solange dieser Pfad nicht als
  gemeinsamer Dienst für alle Konten gebaut ist, löscht `DELETE` ein lokales Konto nur, wenn es keine
  Assets und keinen Space außer dem persönlichen besitzt; sonst 409 mit dem Hinweis, zu sperren. Die
  Kaskade auf `local_credentials` und alle Token-Tabellen erledigt das Schema.
- **Selbstregistrierung** nur bei `selfRegistrationEnabled` (Standard aus): Rolle immer `USER`,
  Konto bis zur Bestätigung (`VERIFY_EMAIL`, 24 Stunden) nicht anmeldefähig; bei belegter Adresse
  entsteht kein zweites Konto, die Antwort ist identisch (202); keine Hinweis-Mail an die belegte
  Adresse (sie wäre selbst ein Aufzählungskanal für jeden, der die Adresse kennt).
- **Passwort vergessen** nur bei `passwordResetEnabled` (Standard an, sobald die Verwaltung an ist):
  immer 204 nach konstanter Zeitklasse, unabhängig davon, ob ein aktives Konto existiert; gesperrte,
  abgelaufene und eingeladene Konten erhalten keine Mail. **Abgeschaltete Flüsse antworten 404 wie
  eine unbekannte Route**, damit ihre Existenz nicht sondiert wird; die SPA kennt den Zustand aus
  `/auth/config`.
- **Passwort ändern** (mit aktuellem Passwort) steht jedem lokalen Konto in den
  Benutzereinstellungen offen und widerruft die übrigen Sitzungen.

### 12. Übergabe eines lokalen Kontos an eine Anbieteridentität — bewusst, eng, administrativ

Zuschnitt B (Anlaufbetrieb ohne IdP, später Umstellung) war der Grund, aus dem `basic` an der Migration
scheiterte: Spaces, Mitgliedschaften und Rollen blieben an der alten Identität zurück. ADR-0025
verbietet jede Zusammenführung, und dabei bleibt es für alles, was **automatisch** oder über die
**E-Mail** liefe. Dieser ADR fügt **eine benannte Ausnahme** hinzu:

Ein `SYSTEM_ADMIN` kann ein **gesperrtes** lokales Konto einer Anbieteridentität übergeben
(`POST /api/v1/admin/local-users/{id}/handover` mit `issuerUri` eines aktivierten OIDC-Anbieters und
dem **ausdrücklich eingegebenen** `subject`). Voraussetzungen: Unter `(issuer, subject)` existiert noch
kein Konto (sonst 409 — es wird nichts zusammengeführt), das Konto ist gesperrt (keine laufende
Sitzung), der Anbieter ist aktiviert. Wirkung: `users.issuer`/`users.subject` werden umgeschrieben,
`local_credentials` und alle Tokens gelöscht, `system_role` bleibt (bei einem Anbieter mit
`roles_claim` wird sie ohnehin beim nächsten Token geführt), Audit `LOCAL_USER_HANDED_OVER` mit
Vorher/Nachher. Die nächste Anmeldung dieser Person über den Anbieter findet das Konto über den
bestehenden Schlüssel — kein Sonderpfad im Provisionierer. Kein Abgleich über die E-Mail, kein
Vorschlag durch das System; wer das Subject nicht kennt, kann es beim Anbieter oder in einem
Testkonto ablesen. Das Risiko einer Fehlzuordnung liegt beim Verwalter und ist auditiert; es ist
dasselbe Vertrauen, das die Rollenvergabe an ihn stellt. Die Umsetzung ist ein eigenes Sub-Issue des
Epics nach der Admin-API.

### 13. Audit-Ereignisse

Alle Ereignisse nach ADR-0015 mit den bestehenden Objekttypen (`USER_ACCOUNT`, `SYSTEM_SETTING`),
Subjekte als Pseudonym, Akteur die handelnde Person oder der Systemprozess `local-auth`; neue Werte
in `io.opaa.api.types.AuditEventType` und der Spec (Parity-Test, keine Migration):

| Bereich | Ereignisse |
| --- | --- |
| Seed und Notanker | `LOCAL_ADMIN_SEEDED`, `LOCAL_ADMIN_RESET` |
| Schalter | `LOCAL_ACCOUNTS_ENABLED`, `LOCAL_ACCOUNTS_DISABLED`, `LOCAL_ACCOUNTS_SETTINGS_CHANGED` |
| Anmeldung und Sitzung | `LOCAL_LOGIN_FAILED`, `LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`, `LOCAL_SESSION_REVOKED`, `LOCAL_REFRESH_REUSE_DETECTED` |
| Passwort | `LOCAL_PASSWORD_CHANGED`, `LOCAL_PASSWORD_SET` (per Link), `LOCAL_USER_PASSWORD_RESET_REQUESTED`, `LOCAL_USER_PASSWORD_GENERATED` |
| Konto | `LOCAL_USER_CREATED`, `LOCAL_USER_INVITED`, `LOCAL_USER_REGISTERED`, `LOCAL_USER_CHANGED`, `LOCAL_USER_LOCKED`, `LOCAL_USER_UNLOCKED`, `LOCAL_USER_DELETED`, `LOCAL_USER_HANDED_OVER` |
| Mail | `MAIL_SETTINGS_CHANGED`, `MAIL_TEMPLATE_CHANGED`, `MAIL_TEMPLATE_RESET`, `MAIL_TEST_SENT` |

Erfolgreiche Anmeldungen werden **nicht** einzeln auditiert — `users.last_login_at` genügt der
Nachweisführung, und ein Anmeldeprotokoll je Person wäre ein Instrument der Verhaltenskontrolle
(Personalrat-Perspektive). Fehlversuche werden auditiert, weil sie Angriffe sichtbar machen; das
Ereignis trägt das Pseudonym des Kontos und die Zahl, nicht die eingegebenen Werte. Die vorhandenen,
ungenutzten Werte `ACCOUNT_DEACTIVATED` und `ACCOUNT_REAUTHENTICATION_FORCED` bleiben unangetastet —
sie sind für den Verzeichnis-Lebenszyklus reserviert.

### 14. Grenzen

- Kein SAML, keine anderen Protokolle; keine API-Tokens für programmatischen Zugang (eigenes Thema).
- Keine automatische oder E-Mail-basierte Zusammenführung; nur die administrative Übergabe aus
  Entscheidung 12.
- Keine MFA in diesem Epic (Entscheidung 9).
- Eine Organisation; lokale Konten entstehen in `Organization.DEFAULT_ID`.
- Der `dev`-Modus ist unverändert: kein Aussteller, kein Seed, keine Anmeldeseite; die E2E-Suite läuft
  weiter auf `dev`, der lokale Anmeldeweg bekommt ein eigenes E2E-Ziel (#1543).
- Der Profilname `oidc` bleibt (Entscheidung 8).
- Lokale Konten erhalten Gruppen nur manuell; der Verzeichnisabgleich bleibt an den
  Standard-OIDC-Anbieter gebunden (ADR-0025).
- Ein getrennter API-Origin wird nicht unterstützt (Entscheidung 7).
- Kein Mehrsprachigkeitsversprechen für Mails über `de` hinaus; die `locale`-Spalte ist vorbereitet.

## Konsequenzen

### Positiv

- Eine Installation ist **nie ohne anmeldefähigen Systemverwalter** — nicht durch eine Regel über
  OIDC-Anbieter, sondern durch ein Konto, das OPAA selbst führt. Der Weg zurück aus jeder
  Anbieter-Fehlkonfiguration ist eine Anmeldung, kein Datenbankeingriff.
- Interessenten ohne Identitätsanbieter können OPAA betreiben (ADR-0005, negative Konsequenz Nr. 1
  entfällt), und der spätere Umstieg auf einen Anbieter verliert keine Daten (Entscheidung 12).
- Jede Lehre aus `basic` ist an einer benannten Stelle eingelöst: Subject-UUID (2), Hashing und
  konstante Zeit (9), Rate-Limiting und Kontosperre (9), Refresh mit Rotation (7), mehrere Konten mit
  Rollen (11), validiertes Secret statt Betriebs-HMAC (6).
- OIDC bleibt, wie ADR-0025 es festgelegt hat; der Aussteller berührt den OIDC-Pfad nur an der
  Registry und im Provisionierer.
- Die Mail-Infrastruktur steht #1297 fertig zur Verfügung.

### Negativ

- Das Backend verwaltet erstmals Sitzungszustand (Refresh-Familien, Denylist) und ein
  Signaturgeheimnis. Beides ist auf den lokalen Issuer begrenzt, aber es ist Verantwortung, die vorher
  beim Anbieter lag.
- Das Einmalpasswort steht einmal im Log. Log-Weiterleitungen sehen es; der erzwungene Wechsel und
  `OPAA_INITIAL_ADMIN_PASSWORD` sind die Gegenmittel, kein Ausschluss.
- Lokale Konten laufen am Verzeichnis vorbei. Ablaufdatum, Anlagegrund und der dauerhafte Zähler
  „Konten ohne Ablauf" machen das sichtbar, verhindern es aber nicht; das Handbuch empfiehlt die
  Verwaltung im Regelbetrieb aus zu lassen und Systemverwalter-Konten zu befristen.
- Der letzte OIDC-Anbieter kann deaktiviert werden. Ein Verwalter, der das bestätigt, sperrt alle
  Nutzer außer den lokalen Konten aus — beabsichtigt, aber ein größerer Hebel als bisher.
- Drei neue Umgebungsvariablen sind Pflicht oder dringend empfohlen (`OPAA_AUTH_JWT_SECRET`,
  `OPAA_PUBLIC_BASE_URL`, `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`); `.env.docker.example` und das
  Handbuch tragen sie.
- BCrypt begrenzt Passwörter auf 72 Byte; die Richtlinie macht die Grenze sichtbar.

### Neutral

- `OPAA_INITIAL_ADMIN_EMAIL` wechselt die Bedeutung von „E-Mail, die beim ersten OIDC-Login Admin
  wird" zu „E-Mail des lokalen Seed-Kontos"; der Name bleibt.
- `OPAA_OIDC_BOOTSTRAP=force` wird durch `OPAA_LOCAL_ADMIN_RESET=force` abgelöst, eine Version
  Übergang.
- ADR-0021 erhält neue prozesslokale Fundstellen (lokaler Decoder in der Registry, `jti`-Denylist,
  Rate-Limit-Buckets der Auth-Endpunkte, `MailSenderProvider`-Cache, Snapshots von `mail_settings` und
  `local_auth_settings`).
- `docs/features/user-frontends.md` („Eine eigene Benutzer- und Passwortverwaltung gibt es nicht")
  und `access-control.md` („Anmeldung und Identität", „Lebenszyklus eines Kontos", „Offboarding")
  werden mit den Sub-Issues nachgezogen; dieser PR ersetzt den Rückfallebenen-Absatz durch den
  Beschluss.

## Zuschnitt der Sub-Issues (gegen diesen ADR geprüft)

| Issue | Folgt aus diesem ADR | Zu korrigieren |
| --- | --- | --- |
| #1532 Schema und Krypto | 1:1-Tabelle `local_credentials` (3), drei Token-Tabellen (3), `provider_type` mit den drei `CHECK`s (4), BCrypt 12 hinter `DelegatingPasswordEncoder` (9), HKDF mit den zwei Zwecken und `@ValidSecret` (6) | Passwortmaximum: 64 Zeichen **und** 72 Byte statt 200 (9) |
| #1533 Token-Aussteller | Claims, TTLs, Cookie, CSRF, Rotation, Widerruf (6, 7), Marker und Finder-Regel (8), `pcr`-Filter (8) | `change-password` stellt sofort ein neues Access-Token aus, damit `pcr` ohne Refresh zurückgeht (8); Erfolgs-Logins nicht auditieren (13) |
| #1534 Erstadministrator | Seed, Markierung, `OPAA_INITIAL_ADMIN_PASSWORD`, Log-Ausgabe, `InitialAdminPolicy` nur Dev, `OPAA_LOCAL_ADMIN_RESET=force`, `is_default`-Semantik, letzter OIDC-Anbieter deaktivierbar mit Bestätigung (4, 5) | Passwort ins Log, nicht in eine Datei (5); `acknowledgeLastProvider` als API-Parameter (4) |
| #1535 Rate-Limiting | Grenzen, Trusted-Proxy, Kontosperre 5/15 min fest (9) | keine progressive Verlängerung (9) |
| #1536 Mail | `mail_settings`, `OPAA_PUBLIC_BASE_URL` als Umgebung, `SendResult`, Registry, JMustache, keine SMTP-Adressprüfung (10) | — |
| #1537 Admin-API | Einladung mit Link-Rückfall, Zurücksetzen, Sperren als Regelweg, Ablauf, Schutz des letzten Verwalters, `local_auth_settings` (11) | `DELETE` nur ohne eigene Assets/Spaces, sonst 409 „sperren" (11); Zähler „ohne Ablauf" auch für Systemverwalter (11) |
| #1538 Selbstbedienung | `set-password` für beide Zwecke, `forgot-password` 204 konstant, Registrierung 202 identisch, 404 für abgeschaltete Flüsse (11) | keine Hinweis-Mail an belegte Adressen (11) |
| #1539 Anmeldeseite und Sitzung | `sessionKind`, Refresh nur mit CSRF-Cookie, Marker ohne Erneuerung, `/login/system` (7, 8) | — |
| #1540 Selbstbedienungsseiten | eine Seite für Einladung und Zurücksetzen, Richtlinie sichtbar, Erzeugen-Schaltfläche (9, 11) | Maximum 64 Zeichen anzeigen (9) |
| #1541 Benutzerverwaltung | Zustände, Schalter mit Konsequenz-Dialog, Zähler „ohne Ablauf" (4, 11) | Aktion „An Anbieteridentität übergeben" für gesperrte Konten ergänzen (12) |
| #1542 E-Mail-Einstellungen | Maskierung `***`, Testversand, Vorlagen mit Vorschau (10) | Hinweis, wenn `OPAA_PUBLIC_BASE_URL` fehlt (10) |
| #1543 E2E und Handbuch | E2E-Ziel ohne Keycloak, Handbuchkapitel, Variablen (5, 9, 10) | Empfehlung MFA-Folgeschritt und „Verwaltung im Regelbetrieb aus" (9); `oidc` als „Betriebsmodus" benennen (8) |
| **neu** | Übergabe eines lokalen Kontos an eine Anbieteridentität (12) — Sub-Issue nach #1537 | anzulegen |

## Verworfene Alternativen

- **Benutzername als Anmeldekennung** (qnop): zweiter Bezeichner ohne Gegenwert, zweiter
  Aufzählungskanal; der Beschluss nennt E-Mail (1).
- **Basis-URL der Installation als Issuer:** ändert sich beim Umzug; Identität muss es überleben (2).
- **Lokale Felder als Spalten an `users` mit `CHECK`** (qnop ADR-0023): verwischt die in jeder
  Anfrage gelesene Tabelle, passt nicht zu eingeladenen Konten ohne Passwort (3).
- **Gespeicherter Kontozustand** (`status`-Spalte): zwei Quellen der Wahrheit neben `locked_at`,
  `expires_at`, `password_hash`; abgeleitet ist er nie inkonsistent (3).
- **Eigene Tabelle statt Anbieterzeile für „lokal":** der Beschluss verlangt Modell III; die Zeile
  erbt Reihenfolge, Audit, `AFTER_COMMIT`-Registry und den öffentlichen Konfigurationsendpunkt (4).
- **Modell II (lokaler Admin nur als Notanker) und Modell III ohne Sonderstatus:** von der
  Abstimmung verworfen; Modell I mit Zeile nach III ist der Beschluss (5).
- **Einmalpasswort in eine Datei oder auf `stderr` am Logger vorbei** (qnop): im Container ist
  `stderr` das Log, und eine Datei ist ein zweiter Ort für ein Geheimnis; der Maintainer hat das Log
  entschieden (5).
- **Erstadministrator-Regel für OIDC beibehalten:** zwei Wege zum `SYSTEM_ADMIN`, davon einer über
  eine E-Mail beim Anbieter kaperbar; der Beschluss will ihn weg (5).
- **Asymmetrischer Schlüssel mit JWK-Set** (#1368, Variante a in der Ausprägung): kein zweiter
  Prüfer, Schlüsselverwaltung ohne Nutzer (6).
- **Refresh-Token im `localStorage` oder im Body:** per XSS abgreifbar; das HttpOnly-Cookie ist
  unsichtbar (7).
- **Serverseitige Sitzungen** (`HttpSession`): das Backend bleibt für alles außer dem Refresh-Cookie
  zustandslos; ein Session-Store wäre ein zweiter Mechanismus neben dem Token (7).
- **Mitgelieferter Keycloak als lokale Verwaltung** (#1368, Variante b): macht Keycloak zur
  Pflichtkomponente gerade für Installationen ohne IdP; Admin-Credentials von Keycloak in OPAA.
- **Eingebetteter Authorization Server** (#1368, Variante c): Sitzungen, Login-Seiten und Consent im
  Backend, die ADR-0025 gerade vermieden hat; erheblicher Konfigurationsumfang.
- **Argon2id:** zusätzliche Abhängigkeit; BCrypt hinter `DelegatingPasswordEncoder` hält den Wechsel
  offen (9).
- **Komplexitätsregeln und periodischer Wechsel:** verschieben Passwörter auf Zettel; BSI verlangt
  beides nicht mehr (9).
- **Progressive Sperrdauer:** mehr Zustand für wenig Gewinn, solange IP-Grenze und 15 Minuten je
  Konto gelten; nachrüstbar (9).
- **`X-Forwarded-For` unverändert ungeprüft lassen:** hinter dem Compose-nginx wäre jede IP-Grenze am
  Login wirkungslos (9).
- **SMTP-Einstellungen in der Umgebung:** kein Testversand aus der Oberfläche, Neustart je Änderung;
  die Datenbank mit Snapshot ist das Muster der Anbieter (10).
- **Basis-URL als Verwaltungseinstellung** (qnop `general.base_url`): über eine kompromittierte
  Verwaltersitzung umlenkbar; die Umgebung ist die passende Vertrauensstufe (10).
- **Outbox mit Wiederholung:** die Auth-Mails brauchen ein sofortiges Ergebnis; ein Digest kann
  später eine Warteschlange ergänzen (10).
- **Hinweis-Mail „Konto existiert bereits" bei der Registrierung:** selbst ein Aufzählungskanal (11).
- **Hartes Löschen mit Kaskade auf alles** (qnop): OPAA hat einen definierten DSGVO-Pfad; bis er als
  Dienst existiert, bleibt Löschen auf Konten ohne Besitz beschränkt (11).
- **Keine Übergabe an eine Anbieteridentität:** wiederholt das Migrationsproblem von `basic` für
  Zuschnitt B; **Zusammenführung über die E-Mail oder Vorschlag durch das System:** genau das
  Übernahmerisiko, das ADR-0025 ausschließt — deshalb eng, gesperrt, explizit, auditiert (12).
- **Erfolgreiche Anmeldungen auditieren:** Verhaltenskontrolle ohne Nachweisgewinn (13).
- **Profil umbenennen** (`oidc` → `prod`): bricht jede Bereitstellung für einen Namen (8).

## Referenzen

- #1368 (Diskussionsgrundlage und Beschluss vom 10.09.2026), #1529 (Epic), #1531 (dieses ADR)
- [ADR-0005](0005-authentication-strategy.md) — Authentifizierungsstrategie, Historie `basic`
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md) — Audit-Ereignisse
- [ADR-0019](0019-minimale-benachrichtigungsinfrastruktur.md) — Benachrichtigungen
- [ADR-0021](0021-single-instance-betrieb.md) — prozesslokale Fundstellen
- [ADR-0025](0025-mehrere-oidc-anbieter.md) — mehrere OIDC-Anbieter, Identität `(issuer, subject)`
- [ADR-0029](0029-schlankes-backend-laufzeitimage.md) — Container ohne Shell
- `docs/features/access-control.md` — Anmeldung und Identität, Lebenszyklus, Sitzungsverwaltung
- `docs/features/security-and-compliance.md` — Löschung eines Benutzerkontos
- qnop: `docs/adr/0022-security-crypto-foundation.md`, `0023-identity-and-access-model.md`,
  `0025-application-settings-architecture.md`, `0026-jwt-session-and-revocation.md`,
  `0027-auth-rate-limiting.md`; `io.qnop.security` (`Hkdf`, `JwtKeyService`, `ValidSecret`),
  `io.qnop.service.{JwtTokenService,RefreshTokenService,TokenRevocationService,AdminUserService}`,
  `io.qnop.service.auth`, `io.qnop.service.mail`, `io.qnop.web.security`
  (`DelegatingJwtDecoder`, `PasswordChangeRequiredFilter`, `ratelimit`),
  `io.qnop.bootstrap.AdminInitializationRunner`
- BSI IT-Grundschutz ORP.4 (Identitäts- und Berechtigungsmanagement), Anforderung A8 (Regelungen für
  Passwörter)
