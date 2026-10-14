# ADR-0033: Lokale Benutzerverwaltung — lokaler Systemverwalter, Backend als Token-Aussteller, lokaler Anbieter als Anbieterzeile, Mail-Infrastruktur

## Status

Akzeptiert · zuletzt geändert 2026-10-11 · ergänzt ADR-0005 und ADR-0025 (ersetzt dort Entscheidung 3 teilweise) · ergänzt durch ADR-0035 (Zugangstokens) und ADR-0041 (Konto-Lebenszyklus)

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
INACTIVITY`), `failed_login_attempts`/`lockout_until` (Zähler wird nie ausgegeben), `expires_at`,
`email_verified_at`, `created_reason` (Pflicht, 200 Zeichen, zweckgebunden), `is_bootstrap`
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
(leer = keine Registrierung), `password_reset_enabled`, `password_min_length` (12, nie unter 8),
`invitation_token_ttl_hours` (72), `reset_token_ttl_minutes` (30), `default_expiry_days` (90),
`inactive_days` (90, mindestens 30); jede Änderung `LOCAL_ACCOUNTS_SETTINGS_CHANGED`.

### 4. Der lokale Anbieter ist eine Anbieterzeile — ihr Schalter ist der Schalter der Verwaltung, und „nie ohne Systemverwalter" ist eine geprüfte Invariante

`oidc_providers.provider_type` (`OIDC | LOCAL`; `LOCAL ⇒ is_default = false`, höchstens eine
`LOCAL`-Zeile, nicht löschbar, Issuer nicht änderbar, ohne Adressprüfung). Ihr `enabled` ist der
Schalter der lokalen Verwaltung (Standard `false`, Audit `LOCAL_ACCOUNTS_ENABLED/_DISABLED`);
`GET /api/v1/auth/config` führt sie als `localAccounts { enabled, selfRegistrationEnabled,
passwordResetEnabled, passwordMinLength }`. Gegenüber ADR-0025, Entscheidung 3: `is_default` steuert
nur noch Anmeldevorschlag und Löschschutz (der Verzeichnisabgleich hängt seit #1816 an der
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
Widerrufsprüfungen gelesen (seit #1595, sonst wäre er für ein bestehendes Token unerreichbar); der
Login antwortet mit derselben Einheitsantwort wie bei falschem Passwort. Das Abschalten widerruft
die Sitzungen regulärer Konten zusätzlich eifrig (`ADMIN`, je Konto ein Audit-Ereignis).

### 5. Erstadministrator: lokales Notanker-Konto beim ersten Start, Einmalpasswort ins Log, Wiederanlauf per Variable

`LocalAdminSeeder` (`SmartInitializingSingleton`, Markierung `LocalAdminSeedMarker`, nur im
`oidc`-Profil) legt immer die `LOCAL`-Anbieterzeile an und ein lokales `SYSTEM_ADMIN`-Konto aus
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
Start), bleibt aber in `application.yml` für den `dev`-Modus. Das Notanker-Konto ist ein
Notfallzugangsmittel: Jede Anmeldung damit ist `LOCAL_BOOTSTRAP_ACCOUNT_LOGIN` und löst eine Mail an
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
Platzhalter ab; fehlt das Secret, bricht der Start im `oidc`-Profil mit Variable und Erzeugungsbefehl
ab (`LocalAuthSecretGuard`). **Eine Rotation beendet alle lokalen Sitzungen, entwertet alle offenen
Links und alle Zugangstokens** — bewusst, der eine Handgriff nach einer Datenbank-Rücksicherung. Kein
asymmetrischer Schlüssel, kein JWK-Set: derselbe Prozess stellt aus und prüft.

### 7. Sitzung: Refresh-Token als HttpOnly-Cookie, Rotation mit Wiederverwendungserkennung, Höchstdauer, zweistufiger Widerruf, harte Fristen

Refresh-Token 256 Bit, nur der HMAC-Hash gespeichert; Familien mit Rotation (`ROTATED`), Replay
widerruft die ganze Familie (`REUSE_DETECTED`); unbekannt, abgelaufen und wiederverwendet sind für
den Client ununterscheidbar. Zwei Fristen: Leerlauf `refresh-token-ttl` (7 Tage, höchstens 30),
absolute Höchstdauer `session-max-lifetime` (30 Tage, höchstens 90); für lokale `SYSTEM_ADMIN`
`admin-refresh-token-ttl` 4 h und `admin-session-max-lifetime` 12 h. Cookie `opaa_refresh`:
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
`password_invalidated_before`, Kontozustand, Schalter — bei jeder Anfrage gegen die Datenbank).
Abgewiesene Tokens tragen in `WWW-Authenticate` den Grund (`LocalTokenMarkers`):
`local_accounts_disabled`, `account_locked` (`admin | failed_logins | inactivity`),
`account_expired`, `session_revoked` (`admin_lock | password_changed | admin_reset | admin_action |
reuse_detected | handed_over`); die SPA startet keine Erneuerung und zeigt den Grund.
`admin_reset` bezeichnet seit #1595 nur ein tatsächlich zurückgesetztes Passwort, `admin_action` den
Verwaltungsakt ohne Passwort (Abschalten). `UserService#provisionFromToken` behandelt den lokalen
Issuer als Finder, nie als Anleger; `email` und `display_name` werden nicht aus dem Token
zurückgeschrieben. `PasswordChangeRequiredFilter`: `pcr = true` außerhalb `/api/v1/auth/local/` ⇒ 403
`PASSWORD_CHANGE_REQUIRED` mit Anlass. Der Aussteller existiert nur im `oidc`-Profil; der Profilname
bleibt (Handbuch: „Betriebsmodus (OIDC-Anbieter und lokale Konten)").

### 9. Sicherheitsmindestmaß

- **Hashing:** BCrypt 12 hinter `DelegatingPasswordEncoder` (`PasswordPolicy`), Vergleich nur über
  `matches`, bei unbekannter Kennung gegen einen Dummy-Hash (konstante Antwortzeit).
- **Passwortrichtlinie:** Mindestlänge aus `password_min_length`, höchstens 64 Zeichen und 72 Byte,
  nicht gleich der E-Mail, nicht in der mitgelieferten Sperrliste häufiger Passwörter; keine
  Komplexitätsregeln, kein periodischer Wechsel (BSI ORP.4.A8).
- **Rate-Limiting je Adresse, je Konto und global:** Login 10/60 s je IP, Refresh 30/60 s je IP,
  Passwortwechsel 5/300 s je Subject, Registrierung und Passwort vergessen 5/3600 s je IP und
  3/3600 s je Adresse, Passwort setzen und E-Mail-Bestätigung 10/900 s je IP; für Login,
  Registrierung und Passwort vergessen eine globale Grenze je Fenster mit `WARN` und Metrik.
- **Kontosperre** nach 5 Fehlversuchen für 15 Minuten, Zähler atomar, Sperre auditiert
  (`LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`) — **der einzelne Fehlversuch nicht**: nur
  Anwendungslog (Konto-Kennung, nie die Adresse) und SIEM. Die Sperre schneidet die Selbsthilfe nicht
  ab: „Passwort vergessen" bleibt wirksam. Die Antwort des Logins ist in allen Fällen dieselbe
  (falsche Kennung, falsches Passwort, gesperrt, abgelaufen, nicht bestätigt, Verwaltung
  abgeschaltet).
- **Sperre nach Inaktivität** (`inactive_days`) durch den Cleanup-Lauf, `locked_reason = INACTIVITY`,
  Mail an die Person; nur das Notanker-Konto ist ausgenommen. Seit #2260 ist sie für den
  Konto-Lebenszyklus der Verbindungen ein Ruhen, keine Deaktivierung (ADR-0041).
- **Client-IP nur von vertrauten Proxys:** `TrustedProxyClientIpResolver` wertet `X-Forwarded-For`
  nur aus, wenn die Verbindung aus einem Netz in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` kommt (Vorgabe
  leer = Header ignoriert), liest Adresse und Header unterhalb der Request-Wrapper (Springs
  `ForwardedHeaderFilter` bei `forward-headers-strategy: framework` überschreibt `getRemoteAddr()`
  sonst mit dem linkesten, vom Client wählbaren Eintrag) und nimmt den ersten nicht vertrauten
  Eintrag von rechts; ein Wildcard-Netz verweigert den Start, eine leere Liste im `oidc`-Profil
  erzeugt eine `WARN`-Zeile (`TrustedProxyStartupGuard`). In die Liste gehört jeder Hop zwischen
  Client und Backend.
- **Netzbeschränkung für lokale Systemverwalter:** `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS`
  (`LocalAdminNetworkPolicy`, leer = keine Beschränkung) auf derselben aufgelösten Adresse, als
  Kompensation, solange es keinen zweiten Faktor gibt.
- **Kein Geheimnis im Log** außer dem Einmalpasswort des Seeds: keine Roh-Tokens, keine Passwörter,
  keine SMTP-Passwörter (`LogPrivacyTest`).
- **MFA (TOTP)** ist nicht gebaut.

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

- **Anlegen** durch `SYSTEM_ADMIN` mit Anlagegrund (Pflicht, zweckgebunden: dienstlicher Anlass und
  Grund der Befristung, nichts zu Gesundheit, Beschäftigungsverhältnis, Leistung, Dritten; für die
  Person sichtbar, nie als Wert im Protokoll) und Ablaufdatum, per Einladung (`SET_PASSWORD`) oder mit
  erzeugtem Anfangspasswort. Bei Versandfehler, SMTP aus oder fehlender Basis-URL enthält die Antwort
  die Einladungs-URL genau einmal; der Zustellweg steht im Protokoll (`MAIL_SENT | MAIL_FAILED |
  LINK_DISPLAYED`).
- **Zurücksetzen** per Link oder erzeugtem Passwort (`pcr` Grund `ADMIN_RESET`); beides widerruft alle
  Sitzungen.
- **Sperren ist der Regelweg**, Löschen die Ausnahme; Selbstsperre und jede Aktion am letzten
  gesicherten Systemverwalter werden abgelehnt (409 `SELF_LOCKOUT`, `LAST_LOGIN_CAPABLE_ADMIN`), am
  Notanker-Konto unabhängig davon (409 `BOOTSTRAP_ACCOUNT`).
- **Kein stiller Eingriff:** Jeder Verwaltungsakt wird der Person mitgeteilt — per Mail
  (`ACCOUNT_LOCKED`, `ACCOUNT_UNLOCKED`, `ADMIN_PASSWORD_RESET`, `ACCOUNT_EXPIRING` 14 Tage vorher,
  `ACCOUNT_HANDOVER_*`) und als Grund im Sitzungsmarker oder `pcr`-Anlass. Die Fehlversuch-Sperre
  löst keine Mail aus.
- **Ablaufdatum:** Ein abgelaufenes Konto ist `EXPIRED`, nicht gelöscht. Die Prüfung der Auflage ist
  ein Vorgang: Liste mit Filtern („ohne Ablaufdatum", „länger als 90 Tage nicht genutzt", „offene
  Einladungen"), Mail 14 Tage vor Ablauf, Wiedervorlage im Quartal (`ADMIN_REVIEW_REMINDER`, Zahl
  der Konten ohne Ablaufdatum, keine Namen). Zähler, Filter und Wiedervorlage meinen dieselbe Menge
  und nehmen das Notanker-Konto aus (#1603, #1641; `LocalUserOverview#countsAsInactive()`).
- **Die Kontenliste ist kein Auswertungspfad:** Aktivität nur als Klasse („nie", „länger als 90
  Tage nicht", „aktiv"), ohne Zeitstempel, ohne Sortierung danach, kein Export, kein Massenabruf
  (Seitengröße höchstens 50, `LocalUserQuery.MAX_PAGE_SIZE`). Seit #1601 führt
  `GET /api/v1/admin/accounts` alle Konten der Organisation mit Herkunft je Zeile; die
  Aktivitätsklasse erscheint nur an lokalen Zeilen, die Prüffilter grenzen auf lokale Konten ein,
  sortierbar sind Name, E-Mail, Herkunft, Rolle, Zustand, Ablauf, Anlagedatum — nie die Aktivität.
  `users.last_login_at` ist ein gedrosselter Aktivitätszeitstempel, kein Anmeldezeitpunkt.
- **Löschen** folgt dem Pfad in `security-and-compliance.md`; bis er als gemeinsamer Dienst existiert,
  löscht `DELETE` nur ein Konto ohne Assets und ohne Space außer dem persönlichen (sonst 409).
- **Selbstregistrierung** nur mit Schalter **und** Domänenliste: Rolle `USER`, Ablauf Pflicht,
  Bestätigung `VERIFY_EMAIL` (24 h), bei belegter Adresse identische Antwort (202) und keine
  Hinweis-Mail; eine unbestätigte Registrierung derselben Adresse erhält den Link erneut, Hash und
  Name bleiben (gegen Pre-Hijacking). Das Einschalten ist Audit-Ereignis und Dienstvereinbarungspunkt.
- **Passwort vergessen** nur mit Schalter und Basis-URL: immer 204 nach konstanter Zeitklasse;
  gesperrte, abgelaufene und eingeladene Konten erhalten keine Mail, ein Konto in Fehlversuch-Sperre
  schon. Ein abgeschalteter Fluss ist für einen Aufrufer ohne Sitzung von einer unbekannten Route
  nicht zu unterscheiden (#1592): Der Pfad ist nicht freigegeben und wird von der Ratenbegrenzung
  nicht gezählt; ob ein Fluss aktiv ist, sagt allein `/auth/config`.
- **Passwort ändern** mit aktuellem Passwort widerruft die übrigen Sitzungen.

### 12. Übergabe eines lokalen Kontos an eine Anbieteridentität — administrativ angestoßen, von der Person selbst eingelöst

Die eine benannte Ausnahme von „keine Zusammenführung" (ADR-0025): Ein `SYSTEM_ADMIN` stößt an
(`POST /api/v1/admin/local-users/{id}/handover`, Anbieter und Pflicht-`reason`; Aktionstoken
`HANDOVER`, 72 h, Mail an die hinterlegte Adresse, Audit `LOCAL_USER_HANDOVER_REQUESTED`); die
Person löst ein: Die SPA startet den Code-Flow beim Anbieter und ruft `POST
/api/v1/auth/local/handover/redeem` mit dem Anbieter-Token **im Request-Body** — im
`Authorization`-Header hätte `UserProvisioningFilter` das Konto vorher angelegt. Der Endpunkt ist
`permitAll`, prüft das Token über `OidcProviderRegistry` (derselbe Decoder, auch `azp`), verlangt,
dass unter `(issuer, subject)` noch kein Konto existiert (sonst 409), schreibt atomar
`users.issuer`/`subject` um, löscht `local_credentials` und alle Tokens, widerruft die Sitzungen
(`LocalHandoverService`, `LocalHandoverAccountService`). Das Subject kommt aus dem Token der Person,
nie aus einer Eingabe; ein Verwalter allein kann keine Identität übernehmen. Ausgeschlossen: das
Notanker-Konto und jede Übergabe, die den letzten gesicherten Systemverwalter entfernte (Guard beim
Anstoßen und beim Einlösen). Kein Rückweg. Audit `LOCAL_USER_HANDED_OVER` mit Anbieter-Kennung und
Zahlen, nie mit dem Subject.

### 13. Audit-Ereignisse

Alle nach ADR-0015 mit `USER_ACCOUNT`/`SYSTEM_SETTING`, Subjekte als Pseudonym, Akteur Person oder
Systemprozess `local-auth`: Notanker (`LOCAL_ADMIN_SEEDED`, `LOCAL_ADMIN_RESET`,
`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN`), Schalter und Einstellungen (`LOCAL_ACCOUNTS_ENABLED/_DISABLED`,
`LOCAL_ACCOUNTS_SETTINGS_CHANGED`), Sperren und Sitzungen (`LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`,
`LOCAL_USER_LOCKED/_UNLOCKED`, `LOCAL_SESSION_REVOKED` nur fremdveranlasst), Passwort
(`LOCAL_PASSWORD_CHANGED/_SET`, `LOCAL_USER_PASSWORD_RESET_REQUESTED`,
`LOCAL_USER_PASSWORD_GENERATED`), Konto (`LOCAL_USER_CREATED/_INVITED/_REGISTERED/_CHANGED/_DELETED`,
`LOCAL_USER_HANDOVER_REQUESTED`, `LOCAL_USER_HANDED_OVER`), Mail (`MAIL_SETTINGS_CHANGED`,
`MAIL_TEMPLATE_CHANGED/_RESET`, `MAIL_TEST_SENT`). Drei Regeln (`LogPrivacyTest`): **kein
Personenbezug im Klartext** (`LOCAL_USER_CHANGED` trägt Vorher/Nachher nur für `expires_at`, sonst
Feldnamen); **kein Anmelde- und Anwesenheitsprotokoll** (erfolgreiche Anmeldung, Abmeldung, Ablauf
erzeugen kein Ereignis; einzige Ausnahme das Notanker-Konto); **kein Fehlversuch im
Nachweisprotokoll** (nur die daraus folgende Zustandsänderung).

### 14. Grenzen

Kein SAML, keine API-Tokens für programmatischen Zugang in diesem ADR (seit ADR-0035 persönliche
Zugangstokens); keine Zusammenführung außer Entscheidung 12; keine MFA; eine Organisation; `dev`-Modus
unverändert; Profilname `oidc` bleibt; kein getrennter API-Origin; die Übersicht der eigenen
Sitzungen ist nicht gebaut; Mails nur `de` (Spalte `locale` vorbereitet).

## Stakeholder-Zusagen

Bewertungen aus Sicht Betrieb/Informationssicherheit und Personalrat
([Berichte](../discussions/discussion-lokale-benutzerverwaltung-stakeholder.md)); Stand der Zusagen:

| Zusage | Stand | geändert mit |
|---|---|---|
| Seed legt auf Bestandsinstallationen kein scharfes Konto an (`INVITED`) | gilt | #1534 |
| Übergabe zweistufig, Subject aus dem Token der Person, Pflicht-Anlass, Umfangsanzeige | gilt | #1563 |
| Notanker-Konto gegenüber Verwaltungsakten unverletzlich, Fehlversuch-Sperre gilt | gilt, erweitert (kein Rücksetzlink, kein Ablaufdatum) | #1552, #1640, #1988 |
| Letzter OIDC-Anbieter nur mit geprüfter Vorbedingung deaktivierbar | gilt | #1534 |
| Fehlversuche nicht im Nachweisprotokoll, nur die Sperre | gilt | — |
| Kein Personenbezug im Klartext im Protokoll; Vorher/Nachher nur für `expires_at` | gilt | — |
| Kontenliste: Aktivität nur als Klasse, keine Sortierung danach, kein Export, kein Massenabruf | gilt; Liste führt seit #1601 alle Konten, Aktivitätsklasse nur an lokalen Zeilen | #1601 |
| `LOCAL_SESSION_REVOKED` nur fremdveranlasst; Zähler nie ausgegeben | gilt | — |
| Offener Rücksetzweg bei Fehlversuch-Sperre; Anlass bei erzwungenem Wechsel und im Sitzungsmarker | gilt | #1595 (`admin_action`) |
| Anlagegrund zweckgebunden und für die Person sichtbar | gilt | — |
| Pflicht-Befristung selbstregistrierter Konten; Domänenliste | gilt | — |
| Harte Fristen der Token-Tabellen (täglicher Aufräumlauf) | gilt | — |
| Auditierung des Notanker-Kontos als mitgetragene Ausnahme | gilt | — |
| Technisches Log der Fehlversuche höchstens 90 Tage | nur Handbuchempfehlung (Log-Rotation ist Betriebskonfiguration) | — |
| Vier-Augen-Prinzip für die Übergabe | nicht übernommen (die Person selbst ist die zweite Partei) | — |
| Sperren und Befristen des Notanker-Kontos | grundsätzlich ausgeschlossen (Entscheidung 3) | — |
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
  im Regelbetrieb aus zu lassen. Bestandsinstallationen brauchen `OPAA_AUTH_JWT_SECRET`, hinter einem
  Proxy `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`, dazu `OPAA_PUBLIC_BASE_URL` und einen echten Wert für
  `OPAA_INITIAL_ADMIN_EMAIL`.
- **Restrisiken:** Das Einmalpasswort steht einmal im Log; Log-Weiterleitungen sehen es. Der letzte
  OIDC-Anbieter kann deaktiviert werden — ein größerer Hebel als bisher, geprüft und bestätigt.

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

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-11 | #1552 | Trusted-Proxy-Auflösung, Bestandserkennung des Seeds, Unverletzlichkeit des Notankers, Anti-Aufzählung | 3, 4, 5, 9 |
| 2026-09-11 | #1535 | Trusted-Proxy-Auflösung als `TrustedProxyClientIpResolver` (`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`), nicht als `RemoteIpValve` | 9 |
| 2026-09-12 | #1601 | Kontenliste führt alle Konten, Aktivitätsklasse nur an lokalen Zeilen | 11 |
| 2026-09-13 | #1563 | Übergabe eines lokalen Kontos an eine Anbieteridentität gebaut | 12 |
| 2026-09-13 | #1595 | Schalter vor Widerrufsprüfungen; `admin_action` getrennt von `admin_reset` | 4, 8 |
| 2026-09-13 | #1603 | Notanker-Konto nicht im Zähler „ohne Ablaufdatum" | 11 |
| 2026-09-17 | #1592, #1641 | Abgeschalteter Fluss wie unbekannte Route; Inaktivitätsfilter ohne Notanker | 11 |
| 2026-09-19 | #1349, #1537 | Guard auch am generischen Rollenendpunkt | 4 |
| 2026-09-20 | #1816 | `is_default` nur noch Anmeldevorschlag und Löschschutz; `TrustedProvider` entfällt (ADR-0036) | 4 |
| 2026-09-26 | #1640 | Kein Ablaufdatum am Notanker-Konto (409) | 3, 11 |
| 2026-10-05 | #2260 | Inaktivitätssperre ist für den Konto-Lebenszyklus ein Ruhen (ADR-0041) | 9 |
| 2026-10-11 | #1988 | Kein Rücksetzlink und kein erzeugtes Passwort für das Notanker-Konto | 3, 5 |
