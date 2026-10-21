# ADR-0033: Lokale Benutzerverwaltung — lokaler Systemverwalter, Backend als Token-Aussteller, lokaler Anbieter als Anbieterzeile, Mail-Infrastruktur

## Status

Akzeptiert · zuletzt geändert 2026-10-21 · ergänzt ADR-0005 und ADR-0025 (ersetzt dort Entscheidung 3 teilweise) · ergänzt durch ADR-0035 (Zugangstokens) und ADR-0041 (Konto-Lebenszyklus)

## Kontext

OPAA kannte nur Konten über OIDC-Anbieter; eine frische Installation hatte keinen, der
Erstadministrator entstand über `OPAA_INITIAL_ADMIN_EMAIL` beim ersten Login, und der einzige Weg
aus „letzter Anbieter falsch konfiguriert" war `OPAA_OIDC_BOOTSTRAP=force`. Kein Mailversand, keine
Benutzerverwaltungsseite. Beschluss aus #1368 (10.09.2026): vollständige, optionale lokale
Benutzerverwaltung (Standard aus) als weiterer Anbieter vom Typ „lokal"; der Erstadministrator ist
immer ein lokales Konto, beim ersten Start angelegt, Einmalpasswort einmalig ins Log; Einladung,
Passwort vergessen und optionale Selbstregistrierung per E-Mail; Sicherheitsmindestmaß. Die lokale
Anmeldung nach diesem ADR ist der Ersatz für den früheren Modus `basic`, den ADR-0005 entfernt hat;
Messlatte sind dessen Schwächen (`subject = username`, Klartextvergleich, kein Rate-Limiting, kein
Refresh, HMAC-Secret in der Betriebskonfiguration). Was ADR-0025 für OIDC nicht
wollte — Sitzungszustand, Token-Prägung, Refresh-Ledger, CSRF — ist für eine Passwortanmeldung
unvermeidlich und wird nur für den lokalen Issuer eingeführt. Bindend: **kein stiller Eingriff in
ein Konto** und **kein personenbezogener Auswertungspfad** (`access-control.md`,
`security-and-compliance.md`). Vorbild qnop.

## Entscheidung

### 1. Anmeldekennung ist die E-Mail-Adresse

Kein eigener Benutzername (zweiter Aufzählungskanal ohne Gegenwert). Eindeutigkeit
groß-/kleinschreibungsunabhängig und nur innerhalb des lokalen Issuers (partieller Unique-Index über
`lower(email)`); ein OIDC-Konto mit derselben Adresse bleibt zulässig.

### 2. Identität: fester lokaler Issuer, Subject ist die Konto-UUID

`users.issuer = 'urn:opaa:local'` (`LocalIssuer`, installationsunabhängig), `subject = users.id`.
Nie ist die Anmeldekennung das Subject; eine geänderte Adresse lässt Spaces, Mitgliedschaften und
Rollen unberührt. Die Regel `(issuer, subject)` aus ADR-0025 gilt unverändert.

### 3. Datenmodell: lokale Zugangsdaten als 1:1-Tabelle, Kontozustand abgeleitet, Einstellungen als Singleton

`local_credentials` (`user_id` als Primär- und Fremdschlüssel, `ON DELETE CASCADE`): `password_hash`
(nullbar), `password_change_required` mit `password_change_reason` (`INITIAL | ADMIN_RESET |
SECURITY`), `password_invalidated_before` (Massenwiderruf), `locked_at`/`locked_reason` (`ADMIN |
INACTIVITY`), `failed_login_attempts`/`lockout_until` (Zähler wird nie ausgegeben; geht bei jeder erfolgreichen
Anmeldung, jedem Zurücksetzen und jeder Entsperrung auf null), `expires_at`, `email_verified_at`
(gesetzt beim Anlegen durch einen Verwalter — er bürgt für die Adresse —, sonst durch Einladungs-
oder Bestätigungslink), `created_reason` (Pflicht, 200 Zeichen, zweckgebunden), `is_bootstrap`
(höchstens eine Zeile). Die Invariante „`local_credentials`-Zeile genau dann, wenn lokaler Issuer"
erzwingt der einzige Schreibpfad `LocalUserService`. Der **Kontozustand wird abgeleitet**: `INVITED`,
`ACTIVE`, `LOCKED`, `EXPIRED`; anmeldefähig ist ein `ACTIVE`-Konto, bei abgeschalteter Verwaltung
nur mit `SYSTEM_ADMIN`. Ein **gesicherter lokaler Systemverwalter** (Begriff des Guards aus
Entscheidung 4) ist ein lokales `SYSTEM_ADMIN`-Konto mit Passwort und bestätigter Adresse, ohne
`locked_at` und ohne abgelaufenes `expires_at` — eine laufende Fehlversuch-Sperre nimmt ihm die
Eigenschaft nicht. Da OPAA von einem OIDC-Konto nur weiß, dass sein Anbieter aktiviert ist, hängt die
Invariante am **Notanker-Konto, das gegenüber Verwaltungsakten unverletzlich ist**: keine Sperre
durch Verwalter oder Inaktivität, kein Ablaufdatum, kein Rollenentzug, keine Löschung, keine
Übergabe, kein von der Verwaltung erzeugtes Passwort und kein Rücksetzlink (409
`BOOTSTRAP_ACCOUNT`); es verändern nur die Fehlversuch-Sperre, `OPAA_LOCAL_ADMIN_RESET=force` und
der eigene Passwortwechsel. Drei Token-Tabellen: `local_refresh_tokens` (Familie, absoluter Ablauf,
HMAC-Lookup-Hash, Widerrufsgrund als Enum), `local_revoked_tokens` (`jti`-Hash), `local_action_tokens`
(Zweck `SET_PASSWORD | RESET_PASSWORD | VERIFY_EMAIL | HANDOVER`); Roh-Tokens werden nirgends
gespeichert, alle Hashes unter einem aus dem Secret abgeleiteten Schlüssel. Einstellungen in
`local_auth_settings`: `self_registration_enabled` (aus) mit `self_registration_allowed_domains`
(leer = keine Registrierung), `password_reset_enabled` (an), `password_min_length` (12, nie unter 8),
`invitation_token_ttl_hours` (72), `reset_token_ttl_minutes` (30), `default_expiry_days` (90),
`inactive_days` (90, mindestens 30); jede Änderung `LOCAL_ACCOUNTS_SETTINGS_CHANGED` mit
Vorher/Nachher der geänderten Schlüssel.

### 4. Der lokale Anbieter ist eine Anbieterzeile — ihr Schalter ist der Schalter der Verwaltung, und „nie ohne Systemverwalter" ist eine geprüfte Invariante

`oidc_providers.provider_type` (`OIDC | LOCAL`; `LOCAL ⇒ is_default = false`, höchstens eine
`LOCAL`-Zeile, nicht löschbar, Issuer nicht änderbar, ohne Adressprüfung). Ihr `enabled` ist der
Schalter der lokalen Verwaltung (Standard `false`, Audit `LOCAL_ACCOUNTS_ENABLED/_DISABLED`);
`GET /api/v1/auth/config` führt sie als `localAccounts { enabled, selfRegistrationEnabled,
passwordResetEnabled, passwordMinLength }`. Gegenüber ADR-0025, Entscheidung 3: `is_default` steuert
nur noch Anmeldevorschlag und Löschschutz (der Verzeichnisabgleich hängt an der
Anbieterzeile, ADR-0036); ein Zustand ohne OIDC-Anbieter ist zulässig; der letzte aktivierte
OIDC-Anbieter darf deaktiviert oder gelöscht werden, wenn ein gesicherter lokaler Systemverwalter
existiert (409 `LAST_LOGIN_CAPABLE_ADMIN` sonst) und der Aufruf `acknowledgeLastProvider` trägt;
seine Zeile behält `is_default`.

Die Invariante prüft genau eine Stelle, `LocalAdminAvailabilityGuard`
(`requireAnotherLoginCapableAdmin`, `withdrawSystemAdminIfAnotherRemains`), und jeder Weg läuft
darüber: Deaktivieren oder Löschen des letzten OIDC-Anbieters; Sperren, Befristen, Rollenentzug und
Löschen eines Systemverwalters über die Admin-API lokaler Konten und über `UserService#updateRole`;
Rollenentzug per Token (`TokenRoleSynchronizer`); Anstoß und Einlösung einer Übergabe; der
Wiederanlauf. Mechanik: Advisory-Lock `UserRepository#lockRoleChanges(Organization.DEFAULT_ID)`,
Prüfung per `SELECT` über `users`, `local_credentials` und `oidc_providers`, dann bedingter `UPDATE`
bzw. Schreibvorgang unter demselben Lock. Die Zählung kennt nur gesicherte Konten; eine
Fehlversuch-Sperre zählt nicht als Verlust, sonst könnte ein Dritter mit fünf Fehlversuchen jede
Anbieter- und Rollenänderung für 15 Minuten blockieren.

Der lokale Decoder (Entscheidung 8) ist unabhängig von `enabled` registriert; bei `enabled = false`
werden Login und Token regulärer lokaler Konten abgewiesen, lokale `SYSTEM_ADMIN`-Konten passieren.
Der Marker `local_accounts_disabled` steht nur auf dem Token-Pfad und wird im Validator **vor** den
Widerrufsprüfungen gelesen (sonst wäre er für ein bestehendes Token unerreichbar); davor
steht nur `malformed_token`, denn ohne `sub` ist nicht feststellbar, ob ein `SYSTEM_ADMIN`-Konto
passieren darf. Der Login antwortet mit derselben Einheitsantwort wie bei falschem Passwort. Das Abschalten widerruft
die Sitzungen regulärer Konten zusätzlich eifrig (`ADMIN`, je Konto ein Audit-Ereignis).

### 5. Erstadministrator: lokales Notanker-Konto beim ersten Start, Einmalpasswort ins Log, Wiederanlauf per Variable

`LocalAdminSeeder` (Markierung `LocalAdminSeedMarker`, nur im `oidc`-Profil) legt immer die `LOCAL`-Anbieterzeile an und ein lokales `SYSTEM_ADMIN`-Konto aus
`OPAA_INITIAL_ADMIN_EMAIL` (Anzeigename „Systemverwaltung", `is_bootstrap = true`,
`password_change_required` mit Grund `INITIAL`). **Neuinstallation und Bestand sind zwei Fälle**,
unterschieden allein daran, ob `users` Konten mit lokalem oder zu einer `oidc_providers`-Zeile
gehörendem Issuer enthält (Dev-Konten und Konten gelöschter Anbieter zählen nicht): Im Bestandsfall
entsteht das Konto als `INVITED` ohne Passwort und ohne Log-Ausgabe, der Betrieb aktiviert es
bewusst mit `OPAA_LOCAL_ADMIN_RESET=force`; ein Opt-out per Variable gibt es nicht. **Passwort:**
`OPAA_INITIAL_ADMIN_PASSWORD`, wenn gesetzt (ohne erzwungenen Wechsel; Weg für CI, E2E, Demo), sonst
ein erzeugtes Passwort (20 Zeichen) einmalig als markierter Block ins Anwendungslog — im Container
ohne Shell (ADR-0029) der Kanal, den der Betrieb liest; gültig nur bis zur ersten Anmeldung. Der
Vorgabewert `admin@opaa.local` wird für das Konto abgelehnt (Fehler im Log, Anlage beim nächsten
Start), bleibt aber in `application.yml` für den `dev`-Modus. **Das Notanker-Konto ist ein
Notfallzugangsmittel, kein Arbeitskonto und kein Sammelkonto:** Nach der Einrichtung legt der erste
Systemverwalter persönliche Verwalterkonten an, je Person eines. Jede Anmeldung damit ist `LOCAL_BOOTSTRAP_ACCOUNT_LOGIN` und löst eine Mail an
alle übrigen Systemverwalter aus; die Fehlversuch-Sperre gilt auch für dieses Konto (sonst wäre das
IP-Limit die einzige Bremse am höchstprivilegierten Konto), auditiert und als `WARN` im Log,
**ohne Mail** (ein Verstärker für jeden, der die Adresse kennt). Identifiziert über `is_bootstrap`,
nicht über die Adresse. Die Erstadministrator-Regel für OIDC-Konten entfällt (`InitialAdminPolicy`
nur noch Dev-Issuer). **Wiederanlauf:** `OPAA_LOCAL_ADMIN_RESET=force` stellt beim Start einmalig
einen anmeldefähigen lokalen Systemverwalter her, aus jedem Zustand (`INVITED`, Passwort unbekannt,
Fehlversuch-Sperre, abgelehnte Adresse; Neuanlage nur nach Datenbankeingriff), widerruft alle
Sitzungen des Kontos, auditiert `LOCAL_ADMIN_RESET`. `OPAA_OIDC_BOOTSTRAP=force` bleibt bis
**31.03.2027** mit `WARN`-Zeile; `OPAA_OIDC_*` behalten ihre Bootstrap-Rolle (ADR-0025).

### 6. Token: HS256-Access-Token mit HKDF-abgeleitetem Schlüssel aus einem validierten Secret

HS256-JWT mit Schlüssel per HKDF-SHA256 aus `OPAA_AUTH_JWT_SECRET`, zweckgebunden
(`LocalAuthKeyService.Purpose`: Signatur, Refresh-Lookup, Aktionstoken-Lookup, seit ADR-0035
Zugangstoken-Lookup). Claims `jti`, `iss = urn:opaa:local`, `sub = users.id`, `iat`, `exp`
(`opaa.auth.local.access-token-ttl`, 15 Minuten), `email`, `name`, `pcr`; keine Rolle im Token.
`LocalAuthProperties` ist `@Validated`, `@ValidSecret` verlangt mindestens 32 Zeichen und lehnt
eine Liste bekannter Platzhalter ab; fehlt das Secret, bricht der Start im `oidc`-Profil mit einer
Meldung ab, die Variable und Erzeugungsbefehl (`openssl rand -base64 48`) nennt
(`LocalAuthSecretGuard`); im `dev`-Profil ist es ohne Wirkung. **Eine Rotation beendet alle lokalen Sitzungen, entwertet alle offenen
Links und alle Zugangstokens** — bewusst, der eine Handgriff nach einer Datenbank-Rücksicherung. Kein
asymmetrischer Schlüssel, kein JWK-Set: derselbe Prozess stellt aus und prüft.

### 7. Sitzung: Refresh-Token als HttpOnly-Cookie, Rotation mit Wiederverwendungserkennung, Höchstdauer, zweistufiger Widerruf, harte Fristen

Refresh-Token 256 Bit, nur der HMAC-Hash gespeichert; Familien mit Rotation (`ROTATED`), Replay
widerruft die ganze Familie (`REUSE_DETECTED`); unbekannt, abgelaufen und wiederverwendet sind für
den Client ununterscheidbar. Zwei Fristen: Leerlauf `refresh-token-ttl` (7 Tage, höchstens 30),
absolute Höchstdauer `session-max-lifetime` (30 Tage, höchstens 90); für lokale `SYSTEM_ADMIN`
`admin-refresh-token-ttl` 4 h und `admin-session-max-lifetime` 12 h; alle vier Werte werden beim
Start validiert wie das Secret. Cookie `opaa_refresh`:
`HttpOnly`, `SameSite=Strict`, `Secure`, `Path=/api/v1/auth/local`; `cookie-secure = false` ist für
lokales HTTP zulässig und erzeugt im `oidc`-Profil eine `WARN`-Zeile. Das Access-Token hält die SPA
nur im Speicher. CSRF nur, wo das Cookie trägt (`refresh`, `logout`, Double-Submit `X-XSRF-TOKEN`).
Widerruf in zwei Schichten: `jti`-Denylist (Caffeine-Cache, `expireAfterWrite = access-token-ttl`)
und `password_invalidated_before`; Logout und Passwortwechsel wirken sofort.
`LocalTokenCleanupScheduler` (täglich 03:20) löscht Token-Zeilen spätestens sieben Tage nach Ablauf
oder Widerruf, sperrt nach Inaktivität und verschickt Erinnerungen. **Keine Oberfläche und keine
Schnittstelle gibt dieses Sitzungsjournal je Person aus.** Nach einer Rücksicherung: Secret rotieren,
Sperren und Rücksetzungen seitdem erneut vornehmen. Voraussetzung `SameSite=Strict`: API unter dem
Origin der SPA; ein getrennter API-Origin wird nicht unterstützt.

### 8. Integration in den Multi-Issuer-Resolver: ein weiterer Decoder, nie ein Provisionierer, jede Abweisung nennt ihren Grund

`OidcProviderRegistry` registriert für den lokalen Issuer einen `NimbusJwtDecoder.withSecretKey`
mit Issuer-, Zeit- und Widerrufs-Validator (`LocalAccessTokenDecoder`: Denylist,
`password_invalidated_before`, Kontozustand, Schalter — bei jeder Anfrage gegen die Datenbank;
Prüfungen in `LocalTokenValidator`, Anlässe als `LocalTokenRejection`). Keine `azp`-Prüfung: es gibt
keine `client_id`. Abgewiesene Tokens tragen in `WWW-Authenticate` den Grund (`LocalTokenMarkers`):
`malformed_token`, `unknown_account`, `local_accounts_disabled`, `account_not_active`, `account_locked` (`admin | failed_logins | inactivity`),
`account_expired`, `session_revoked` (`admin_lock | password_changed | admin_reset | admin_action |
reuse_detected | handed_over`); die SPA startet keine Erneuerung und zeigt den Grund.
`admin_reset` bezeichnet nur ein tatsächlich zurückgesetztes Passwort, `admin_action` den
Verwaltungsakt ohne Passwort (Abschalten). `UserService#provisionFromToken` behandelt den lokalen
Issuer als Finder, nie als Anleger: Ein Token mit unbekanntem `sub` führt zu 401, nicht zu einem
neuen Konto; `email` und `display_name` werden nicht aus dem Token zurückgeschrieben;
`InitialAdminPolicy` wird für den lokalen Issuer nicht konsultiert. `PasswordChangeRequiredFilter`:
`pcr = true` außerhalb `/api/v1/auth/local/` ⇒ 403 `PASSWORD_CHANGE_REQUIRED` mit Anlass;
`change-password` stellt unmittelbar ein neues Access-Token ohne `pcr` aus. Der Aussteller existiert
nur im `oidc`-Profil; der Profilname bleibt (Handbuch: „Betriebsmodus: Identitätsanbieter und lokale
Konten").

### 9. Sicherheitsmindestmaß

- **Passwort:** BCrypt 12 hinter `DelegatingPasswordEncoder` (`PasswordEncoderConfiguration`),
  Vergleich nur über `matches`, bei unbekannter Kennung gegen einen Dummy-Hash; `PasswordPolicy`:
  Mindestlänge `password_min_length`, höchstens 64 Zeichen und 72 Byte, nicht die E-Mail, nicht in
  der Sperrliste; keine Komplexitätsregeln, kein periodischer Wechsel (BSI ORP.4.A8).
- **Ratenbegrenzung** (`OPAA_RATE_LIMIT_LOCAL_AUTH_*`) je Client-Adresse; Passwortwechsel je Konto;
  Registrierung und „Passwort vergessen“ zusätzlich je E-Mail-Adresse; Login, Registrierung und
  „Passwort vergessen“ global, mit `WARN` und Metrik beim Überschreiten. Hinter der Anmeldung
  zählen Abfrage, Chatsuche und Suche je Client-Adresse, je Konto (`max-requests-per-account`,
  geprüft hinter der Sicherheitskette; ein Zugangstoken je Token und zugleich je Person über alle
  ihre Tokens) und global; eine Abweisung je Konto gibt die übrigen Marken zurück, ein Zugangstoken
  seine Adressmarke, eine spätere `429` (Token-Kontingent) die globale.
- **Kontosperre** nach 5 Fehlversuchen für 15 Minuten, auditiert
  (`LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`); der einzelne Fehlversuch steht nur im Anwendungslog
  (Konto-Kennung, nie die Adresse), dessen Aufbewahrung Betriebskonfiguration ist. Ein eingelöster
  Rücksetzlink hebt die Sperre auf (der Besitz des Postfachs ist der Nachweis); der Login antwortet
  in jedem Fall gleich. Die **Sperre nach Inaktivität** (`inactive_days`, Mail, Notanker
  ausgenommen) ist für Verbindungen ein Ruhen (ADR-0041).
- **Client-IP:** `TrustedProxyClientIpResolver` wertet `X-Forwarded-For` nur aus Netzen in
  `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` aus (leer = ignoriert), liest unterhalb der Wrapper von
  Springs `ForwardedHeaderFilter` und nimmt den ersten nicht vertrauten Eintrag von rechts; ein Netz
  weiter als `/8` verweigert den Start (`RateLimitProperties`), eine leere Liste im `oidc`-Profil
  warnt (`TrustedProxyStartupGuard`). Schema und Host übernimmt der Filter ungeprüft (Konsequenzen).
  `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` (`LocalAdminNetworkPolicy`) beschränkt lokale Systemverwalter,
  solange es keinen zweiten Faktor gibt; MFA ist nicht gebaut.
- **Kein Geheimnis im Log** außer dem Einmalpasswort des Seeds (`LocalAuthLogPrivacyIntegrationTest`).

Werte und Diagnose: [Konfiguration](../handbuch/konfiguration.md),
[Vertraute Proxys](../handbuch/betrieb.md#vertraute-proxys-und-client-adresse).

### 10. Mail-Infrastruktur: SMTP in den Systemeinstellungen, Vorlagen mit Code-Standards, Ergebnis statt Exception, sichtbares Scheitern

`io.opaa.mail`: SMTP-Einstellungen in `mail_settings` (Passwort verschlüsselt über
`SettingsEncryptor`, in Antworten `***`, `***` im Request = unverändert), `GET/PUT
/api/v1/system/mail-settings` (nur `SYSTEM_ADMIN`), wirksam ohne Neustart (`MailSenderProvider`);
keine Adressprüfung für den SMTP-Host. Öffentliche Basis-URL `OPAA_PUBLIC_BASE_URL` als
Umgebungsvariable, nie der `Host`-Header; fehlt sie, sind Passwort-vergessen und
Selbstregistrierung nicht einschaltbar, Einladung und Zurücksetzen fallen auf die Link-Anzeige
zurück. `MailService.send` liefert `SendResult { Sent | Skipped | Failed }` und wirft nie; synchron
mit Timeouts, ohne Outbox — außer bei den Selbstbedienungsflüssen „Passwort vergessen" und
Registrierung, deren Kontoarbeit auf `MailDispatchExecutor` läuft und deren Antwort nach fester
Frist kommt, damit die Antwortzeit nichts verrät (#1538). Ein `Failed` ist sichtbar: Log ohne
Adresse, `last_failure_*`, Health-Indikator `mail`. Vorlagen: `MailTemplateKey` mit deutschen
Standards je Schlüssel, Überschreibungen in `mail_templates`, JMustache strikt, nur deklarierte
Platzhalter, gebrandeter HTML-Rahmen; API mit Vorschau, Zurücksetzen und Testversand.

### 11. Kontolebenszyklus: Einladung, Zurücksetzen mit Link-Rückfall, Sperren als Regelweg, kein stiller Eingriff, Löschen nach dem DSGVO-Pfad

- **Anlegen** durch `SYSTEM_ADMIN` mit zweckgebundenem Anlagegrund (für die Person sichtbar, nie als
  Wert im Protokoll) und Ablaufdatum (vorbelegt mit `default_expiry_days`), per Einladung oder mit
  erzeugtem Passwort; ohne Mailversand enthält die Antwort den Link genau einmal, der Zustellweg
  steht im Protokoll. **Zurücksetzen** (Link oder erzeugtes Passwort, `pcr` `ADMIN_RESET`) widerruft
  alle Sitzungen und bestätigt die Adresse nicht (Ausweg ohne Selbstregistrierung: löschen und
  einladen).
- **Sperren ist der Regelweg**, Löschen die Ausnahme: Sperren widerruft alle Sitzungen und hebt eine
  Fehlversuch-Sperre auf, Entsperren setzt den Zähler zurück; Selbstsperre und jede Aktion am
  letzten gesicherten Systemverwalter sind 409 (`SELF_LOCKOUT`, `LAST_LOGIN_CAPABLE_ADMIN`), am
  Notanker immer (`BOOTSTRAP_ACCOUNT`). Rollen laufen über den bestehenden Rollenendpunkt; ein
  Anbieterkonto bietet nur diese Handlung. Ein abgelaufenes Konto ist `EXPIRED`, nicht gelöscht;
  `DELETE` löscht nur ein Konto ohne Assets und ohne Space außer dem persönlichen (sonst 409), bis
  der DSGVO-Pfad aus `security-and-compliance.md` ein gemeinsamer Dienst ist.
- **Kein stiller Eingriff:** Jeder Verwaltungsakt geht per Mail an die Person (`ACCOUNT_LOCKED`,
  `ACCOUNT_UNLOCKED`, `ADMIN_PASSWORD_RESET`, `ACCOUNT_EXPIRING`, `ACCOUNT_HANDOVER_*`) und steht als
  Grund im Sitzungsmarker oder `pcr`-Anlass; nur die Fehlversuch-Sperre schickt keine Mail.
- **Kein Auswertungspfad:** `GET /api/v1/admin/accounts` führt alle Konten mit Herkunft;
  Aktivität erscheint nur als Klasse an lokalen Zeilen, ohne Zeitstempel, Sortierung oder Export,
  Seitengröße höchstens 50 (`LocalUserQuery.MAX_PAGE_SIZE`). Prüffilter, Zähler (Hinweis-Link) und
  Quartals-Wiedervorlage (`ADMIN_REVIEW_REMINDER`, nur Zahlen) meinen dieselbe Menge ohne Notanker
  (`LocalUserOverview#countsAsInactive()`).
- **Selbstbedienung:** Selbstregistrierung nur mit Schalter und Domänenliste (Rolle `USER`, Ablauf
  Pflicht, `VERIFY_EMAIL`, bei belegter Adresse dieselbe `202` ohne Hinweis-Mail, eine unbestätigte
  behält Hash und Namen gegen Pre-Hijacking; das Einschalten ist Dienstvereinbarungspunkt); „Passwort
  vergessen“ nur mit Schalter und Basis-URL, immer `204` nach fester Frist. Ein abgeschalteter Fluss
  ist ohne Sitzung von einer unbekannten Route nicht zu unterscheiden (#1592). Passwort ändern
  widerruft die übrigen Sitzungen. Abläufe und Fristen: [Benutzerverwaltung](../handbuch/benutzerverwaltung.md).

### 12. Übergabe eines lokalen Kontos an eine Anbieteridentität — administrativ angestoßen, von der Person selbst eingelöst

Die eine benannte Ausnahme von „keine Zusammenführung" (ADR-0025): Ein `SYSTEM_ADMIN` stößt an
(`POST /api/v1/admin/local-users/{id}/handover`, Anbieter und Pflicht-`reason`; Aktionstoken
`HANDOVER`, 72 h, Mail an die hinterlegte Adresse, bei Ausfall Link-Anzeige mit protokolliertem
Zustellweg, Audit `LOCAL_USER_HANDOVER_REQUESTED`); das lokale Konto bleibt bis zur Einlösung
benutzbar. Die Person löst ein: Die Einlöseseite zeigt vorher, was mitgeht (persönlicher Space, Zahl
der Mitgliedschaften, Systemrolle; `POST /api/v1/auth/local/handover/preview`), startet den Code-Flow
beim Anbieter und ruft `POST /api/v1/auth/local/handover/redeem` mit dem Anbieter-Token **im
Request-Body** — im `Authorization`-Header hätte `UserProvisioningFilter` das Konto vorher angelegt.
**Invariante der SPA:** Zwischen OIDC-Callback und `redeem` setzt die Einlöseseite keinen
authentifizierten Aufruf ab (auch nicht `/auth/me`); sie hält das Anbieter-Token nur für diesen einen
Aufruf und lädt die Sitzung erst danach. Der Endpunkt ist `permitAll`, prüft das Token über `OidcProviderRegistry` (derselbe Decoder, auch `azp`), verlangt,
dass unter `(issuer, subject)` noch kein Konto existiert (sonst 409), schreibt atomar
`users.issuer`/`subject` um, löscht `local_credentials` und alle Tokens, widerruft die Sitzungen
(`LocalHandoverService`, `LocalHandoverAccountService`). Das Subject kommt aus dem Token der Person,
nie aus einer Eingabe; ein Verwalter allein kann keine Identität übernehmen. Ausgeschlossen: das
Notanker-Konto und jede Übergabe, die den letzten gesicherten Systemverwalter entfernte (Guard beim
Anstoßen und beim Einlösen). Kein Rückweg. Danach Mail `ACCOUNT_HANDED_OVER` an die Adresse und
Audit `LOCAL_USER_HANDED_OVER` mit Anbieter-Kennung (`oidc_providers.id`) und Zahlen, nie mit dem
Subject.

### 13. Audit-Ereignisse

Alle nach ADR-0015 mit `USER_ACCOUNT`/`SYSTEM_SETTING`, Subjekte als Pseudonym, Akteur Person oder
Systemprozess `local-auth`: Notanker (`LOCAL_ADMIN_SEEDED`, `LOCAL_ADMIN_RESET`,
`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN`), Schalter und Einstellungen (`LOCAL_ACCOUNTS_ENABLED/_DISABLED`,
`LOCAL_ACCOUNTS_SETTINGS_CHANGED`), Sperren und Sitzungen (`LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`,
`LOCAL_USER_LOCKED/_UNLOCKED`, `LOCAL_SESSION_REVOKED` nur fremdveranlasst), Passwort
(`LOCAL_PASSWORD_CHANGED/_SET`, `LOCAL_USER_PASSWORD_RESET_REQUESTED`,
`LOCAL_USER_PASSWORD_GENERATED`), Konto (`LOCAL_USER_CREATED/_INVITED/_REGISTERED/_CHANGED/_DELETED`,
`LOCAL_USER_HANDOVER_REQUESTED`, `LOCAL_USER_HANDED_OVER`), Mail (`MAIL_SETTINGS_CHANGED`,
`MAIL_TEMPLATE_CHANGED/_RESET`, `MAIL_TEST_SENT`); neue Werte entstehen in
`io.opaa.api.types.AuditEventType` und der Spec (Parity-Test). `ACCOUNT_DEACTIVATED` und
`ACCOUNT_REAUTHENTICATION_FORCED` bleiben unangetastet, für den Verzeichnis-Lebenszyklus reserviert.
Drei Regeln (`LocalAuthLogPrivacyIntegrationTest`): **kein
Personenbezug im Klartext** (`LOCAL_USER_CHANGED` trägt Vorher/Nachher nur für `expires_at`, sonst
Feldnamen); **kein Anmelde- und Anwesenheitsprotokoll** (erfolgreiche Anmeldung, Abmeldung, Ablauf
erzeugen kein Ereignis; einzige Ausnahme das Notanker-Konto); **kein Fehlversuch im
Nachweisprotokoll** (nur die daraus folgende Zustandsänderung).

### 14. Grenzen

Kein SAML, keine API-Tokens für programmatischen Zugang in diesem ADR (seit ADR-0035 persönliche
Zugangstokens); keine Zusammenführung außer Entscheidung 12; keine MFA; eine Organisation, lokale
Konten entstehen in `Organization.DEFAULT_ID`; Gruppen erhalten lokale Konten nur manuell
(ADR-0036); `dev`-Modus unverändert (kein Aussteller, kein Seed, keine Anmeldeseite), die E2E-Suite
läuft weiter auf `dev`, der lokale Anmeldeweg hat sein eigenes E2E-Ziel (`e2e/local-auth`); der
Demo-Stack meldet sich im Seed als Notanker an und vergibt die Rolle über die reguläre Rollen-API;
Profilname `oidc` bleibt; kein getrennter API-Origin; die Übersicht der eigenen Sitzungen ist nicht
gebaut; Mails nur `de` (Spalte `locale` vorbereitet). Die prozesslokalen Fundstellen (lokaler Decoder,
`jti`-Denylist, Rate-Limit-Buckets, `MailSenderProvider`-Cache, Scheduler) führt das Register nach
ADR-0053 Entscheidung 16; der
Mail-Sender ergänzt ADR-0019.

## Stakeholder-Zusagen

Bewertungen aus Sicht Betrieb/Informationssicherheit und Personalrat
([Berichte](../discussions/discussion-lokale-benutzerverwaltung-stakeholder.md), darin die zwölf
Bedingungen des Personalrats für eine Zustimmung); Stand der Zusagen:

| Zusage | Stand | geändert mit |
|---|---|---|
| Kein Anmeldeprotokoll je Person, nicht durch Konfiguration umkehrbar; einzige Ausnahme das Notanker-Konto (Bedingung 1) | gilt (Entscheidung 13) | — |
| Kein stiller Eingriff: Sperre, Entsperrung, Zurücksetzen, Ablauf und Übergabe werden der Person mitgeteilt, mit Grund (Bedingung 5) | gilt (Entscheidung 11) | #1595 (`admin_action`) |
| Systemverwalter bleiben ohne Inhaltszugriff; Kontenverwaltung und Übergabe ändern daran nichts (Bedingung 10) | gilt (`security-and-compliance.md`) | — |
| Persönliche Verwalterkonten statt Sammelkonto; das Notanker-Konto ist Notfallmittel (Bedingung 12, erster Teil) | gilt als Verfahren (Entscheidung 5, Handbuch); ein Produktzwang besteht nicht | #1543 |
| Auszug für die Personalvertretung führt alle neuen Ereignisarten und Felder (Bedingung 9, Produktteil) | gilt (geschlossene Ereignisliste in `security-and-compliance.md`) | — |
| Seed legt auf Bestandsinstallationen kein scharfes Konto an (`INVITED`) | gilt | #1534 |
| Übergabe zweistufig, Subject aus dem Token der Person, Pflicht-Anlass, Umfangsanzeige | gilt | #1563 |
| Notanker-Konto gegenüber Verwaltungsakten unverletzlich, Fehlversuch-Sperre gilt | gilt, erweitert (kein Rücksetzlink, kein Ablaufdatum) | #1552, #1640, #1988 |
| Letzter OIDC-Anbieter nur mit geprüfter Vorbedingung deaktivierbar | gilt | #1534 |
| Fehlversuche nicht im Nachweisprotokoll, nur die Sperre | gilt | — |
| Kein Personenbezug im Klartext im Protokoll; Vorher/Nachher nur für `expires_at` | gilt | — |
| Kontenliste: Aktivität nur als Klasse, keine Sortierung danach, kein Export, kein Massenabruf | gilt; Liste führt alle Konten, Aktivitätsklasse nur an lokalen Zeilen | #1601 |
| `LOCAL_SESSION_REVOKED` nur fremdveranlasst; Zähler nie ausgegeben | gilt | — |
| Offener Rücksetzweg bei Fehlversuch-Sperre; Anlass bei erzwungenem Wechsel und im Sitzungsmarker | gilt | #1595 (`admin_action`) |
| Anlagegrund zweckgebunden und für die Person sichtbar | gilt | — |
| Pflicht-Befristung selbstregistrierter Konten; Domänenliste | gilt | — |
| Harte Fristen der Token-Tabellen (täglicher Aufräumlauf) | gilt | — |
| Auditierung des Notanker-Kontos als mitgetragene Ausnahme | gilt | — |
| Technisches Log der Fehlversuche höchstens 90 Tage (Bedingung 2) | Handbuchempfehlung: Log-Rotation ist Betriebskonfiguration, das Produkt erzwingt keine Frist; das Handbuch nennt 90 Tage als Beispiel und weist die Festlegung dem Betrieb zu | #2762 |
| Vier-Augen-Prinzip für die Übergabe (Bedingung 6) | nicht übernommen (die Person selbst ist die zweite Partei) | — |
| Sperren und Befristen des Notanker-Kontos (Bedingung 12, zweiter Teil) | grundsätzlich ausgeschlossen (Entscheidung 3) | — |
| Kontozustand nach korrektem Passwort nennen | nicht übernommen (bestätigte ein Passwort) | — |
| Opt-out des Seeds, Audit aller Verwalter-Anmeldungen, exportierbare Liste, Purge-Schalter | nicht übernommen | — |
| Unterrichtung des Personalrats vor dem Einschalten, jährliche Vorlage des Auszugs | Regelung im Handbuch, kein Produktmerkmal | #1543 |

## Konsequenzen

- **Einfacher:** Eine Installation ist nie ohne gesicherten Systemverwalter; der Weg zurück aus jeder
  Anbieter-Fehlkonfiguration ist eine Anmeldung. Interessenten ohne Identitätsanbieter können OPAA
  betreiben; der Umstieg verliert keine Daten. Jede Schwäche des früheren `basic`-Modus ist an einer
  benannten Stelle behoben (Subject-UUID 2, Hashing und konstante Zeit 9, Rate-Limiting und
  Kontosperre 9, Refresh mit Rotation 7, mehrere Konten mit Rollen 11, validiertes Secret 6). Die Mail-Infrastruktur steht für Benachrichtigungen bereit.
- **Schwieriger:** Das Backend verwaltet erstmals Sitzungszustand und ein Signaturgeheimnis,
  begrenzt auf den lokalen Issuer. Lokale Konten laufen am Verzeichnis vorbei — Inaktivitätssperre,
  Anlagegrund, Ablauf, Wiedervorlage machen das beherrschbar; das Handbuch empfiehlt, die Verwaltung
  im Regelbetrieb aus zu lassen. **Bestandsinstallationen haben nach dem Update einen Startabbruch,
  bis `OPAA_AUTH_JWT_SECRET` gesetzt ist**; hinter einem Proxy brauchen sie
  `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` (sonst teilen sich alle Clients einen Rate-Limit-Topf), dazu
  `OPAA_PUBLIC_BASE_URL` und einen echten Wert für `OPAA_INITIAL_ADMIN_EMAIL`, die Adresse des
  Notanker-Kontos.
- **Restrisiken:** Das Einmalpasswort steht einmal im Log; Log-Weiterleitungen sehen es. Der letzte
  OIDC-Anbieter kann deaktiviert werden; ein Verwalter, der das bestätigt, sperrt alle Anbieterkonten
  aus — geprüft und bestätigt. Die
  Vertrauensprüfung aus Entscheidung 9 deckt nur die Client-Adresse; ein `X-Forwarded-Proto`, das
  der äußere Proxy nicht überschreibt, kann die CORS-Prüfung täuschen — die Auflage an den Proxy
  steht im Handbuch, das Produkt erzwingt sie nicht.

## Verworfene Alternativen

- Benutzername als Anmeldekennung — zweiter Aufzählungskanal (1).
- Basis-URL als Issuer — ändert sich beim Umzug (2).
- Lokale Felder als Spalten an `users`; gespeicherter Kontozustand — verwischt die Tabelle; zwei Quellen der Wahrheit (3).
- Eigene Tabelle statt Anbieterzeile; Bestätigungsdialog statt Vorbedingung beim letzten Anbieter — die Zeile erbt Audit, Registry, Konfigurationsendpunkt; ein Klick prüft nichts (4).
- Scharfes Notanker-Konto auf Bestand; Opt-out per Variable; Passwort in Datei oder `stderr`; Notanker nach Einrichtung sperren — Passwort im Log eines Hauses ohne Wissen; widerspricht dem Beschluss; zweiter Ort für ein Geheimnis; Notweg wieder Variable plus Neustart (5).
- Erstadministrator-Regel für OIDC beibehalten — kaperbar über einen zweiten Anbieter (5).
- Asymmetrischer Schlüssel mit JWK-Set; eigener Purge-Schalter — kein zweiter Prüfer; die Rotation leistet es (6).
- Refresh-Token im `localStorage`; serverseitige Sitzungen; Rotation ohne Höchstdauer — per XSS abgreifbar; zweiter Mechanismus; Sitzung ohne Ende (7).
- Mitgelieferter Keycloak oder eingebetteter Authorization Server als lokale Verwaltung — Pflichtkomponente bzw. Sitzungen und Consent im Backend.
- Argon2id; Komplexitätsregeln; progressive Sperrdauer; Fehlversuche im Nachweisprotokoll; Kontozustand nach korrektem Passwort nennen; Rücksetzweg bei Sperre schließen — Abhängigkeit; Zettel; Zustand ohne Gewinn; unlöschbare Einträge; bestätigt ein Passwort; Aussperrung durch Dritte (9, 11).
- Tomcats `RemoteIpValve` mit `forward-headers-strategy: native`; `X-Forwarded-For` ungeprüft; linkester Eintrag — nicht gebaut, der Resolver liest unterhalb der Wrapper; jede IP-Grenze wirkungslos; falsch bei anhängenden Proxys (9).
- SMTP in der Umgebung; Basis-URL als Verwaltungseinstellung; Outbox — kein Testversand; über eine kompromittierte Sitzung umlenkbar; Auth-Mails brauchen ein sofortiges Ergebnis (10).
- Kontenliste mit exakter letzter Anmeldung oder Export; Hinweis-Mail bei belegter Adresse; Selbstregistrierung ohne Domänenliste; hartes Löschen mit Kaskade — Auswertungspfad; Aufzählungskanal; Konten ohne Ablauf; DSGVO-Pfad (11).
- Keine Übergabe; Übergabe mit eingetipptem Subject; Vier-Augen-Prinzip — wiederholt `basic`; fremde private Inhalte lesbar; die Person ist die zweite Partei (12).
- Erfolgreiche Anmeldungen aller Verwalter auditieren; Profil umbenennen — Personen sind keine Notfallmittel; bricht jede Bereitstellung (13, 8).

## Offen

- MFA (TOTP) für lokale Systemverwalter als nächste Härtung.
- Übersicht der eigenen Sitzungen für die Person selbst (`access-control.md`).
- Löschen eines Kontos als gemeinsamer DSGVO-Dienst für alle Konten.
