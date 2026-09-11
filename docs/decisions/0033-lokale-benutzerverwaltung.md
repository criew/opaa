# ADR-0033: Lokale Benutzerverwaltung — lokaler Systemverwalter, Backend als Token-Aussteller, lokaler Anbieter als Anbieterzeile, Mail-Infrastruktur

## Status

Vorgeschlagen (11.09.2026, Issue #1531, Epic #1529). Setzt den Beschluss aus #1368 vom 10.09.2026
um. Nachtrag zu [ADR-0005](0005-authentication-strategy.md) und
[ADR-0025](0025-mehrere-oidc-anbieter.md), die weiter gelten, soweit dieser ADR sie nicht an einer
benannten Stelle präzisiert oder aufhebt (Entscheidungen 4, 5 und 12). Architekturvorbild ist qnop
(`qnophq/qnop`, Vertikale „Identity & administration": dortige ADR-0022, 0023, 0025, 0026, 0027 und
Issues #12, #17, #19, #20). Die Stakeholder-Bewertungen aus Sicht Betrieb/Informationssicherheit und
Personalrat sind eingearbeitet (Abschnitt [Stakeholder-Bewertung](#stakeholder-bewertung)); die
Berichte liegen in
[`docs/discussions/discussion-lokale-benutzerverwaltung-stakeholder.md`](../discussions/discussion-lokale-benutzerverwaltung-stakeholder.md).

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

Zwei Zusagen der Feature-Spezifikationen binden jede Entscheidung unten: **Kein stiller Eingriff in ein
Konto** — „Wer neu anmelden muss, erfährt beim nächsten Aufruf, dass und warum"
(`access-control.md`, „Erzwungene Neuanmeldung") — und **kein personenbezogener Auswertungspfad**,
insbesondere kein Anmelde- oder Anwesenheitsprotokoll und keine Fehlversuche im Nachweisprotokoll
(`security-and-compliance.md`, „Was ausdrücklich nicht protokolliert wird", „Mitbestimmungsfähigkeit").

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
unverändert, `UserProvisioningFilter` findet lokale Konten über denselben Schlüssel wie jedes andere,
und der Pseudonymschlüssel des Protokolls (`users.id`) bleibt über jede Änderung hinweg stabil.

### 3. Datenmodell: lokale Zugangsdaten als 1:1-Tabelle, Kontozustand abgeleitet, Einstellungen als Singleton

Die lokalen Felder liegen in einer eigenen Tabelle `local_credentials` (`user_id` als Primär- und
Fremdschlüssel auf `users(id) ON DELETE CASCADE`), nicht als Spalten an `users`:

| Spalte | Bedeutung |
| --- | --- |
| `password_hash` | nullbar — ein eingeladenes Konto hat noch keines |
| `password_change_required`, `password_change_reason` (`INITIAL` \| `ADMIN_RESET` \| `SECURITY`) | erzwungener Wechsel **mit Anlass**, damit die Person erfährt, warum |
| `password_invalidated_before` | jedes Token mit `iat` davor ist ungültig (Massenwiderruf) |
| `locked_at`, `locked_reason` (`ADMIN` \| `FAILED_LOGINS` \| `INACTIVITY`) | Sperre durch Verwalter, Fehlversuche oder Inaktivität |
| `failed_login_attempts`, `lockout_until` | Zähler und Ende der Fehlversuch-Sperre; der Zähler wird **nie ausgegeben** und geht bei jeder erfolgreichen Anmeldung, jedem Zurücksetzen und jeder Entsperrung auf null |
| `expires_at` | Ablaufdatum (Auflage aus `access-control.md`) |
| `email_verified_at` | gesetzt beim Anlegen durch einen Verwalter (er bürgt für die Adresse), sonst durch Einladungs- oder Bestätigungslink |
| `created_reason` | Anlagegrund, **Pflichtfeld**, höchstens 200 Zeichen, zweckgebunden (siehe Entscheidung 11) |
| `is_bootstrap` | kennzeichnet das eine Notanker-Konto der Systemverwaltung (partieller Unique-Index: höchstens eine Zeile) |
| `created_at`, `updated_at`, `version` | Zeitstempel, optimistische Sperre |

qnop hält dieselben Felder in `qnop_user` mit einem `CHECK` „intern ⇒ Passwort gesetzt". OPAA
wählt die Nebentabelle, weil `users` bereits anbieterbezogene Spalten trägt und in jeder Anfrage
gelesen wird: Ein Dutzend nullbarer Spalten, die für jedes OIDC-Konto leer bleiben, verwischen die
Semantik der Tabelle, und der qnop-`CHECK` passt nicht zu Konten, die eingeladen sind und noch kein
Passwort haben. Die Invariante „eine `local_credentials`-Zeile existiert genau dann, wenn
`users.issuer` der lokale Issuer ist" erzwingt der einzige Schreibpfad (`LocalUserService`); ein
Integrationstest prüft sie.

Der **Kontozustand wird abgeleitet, nicht gespeichert**: `INVITED` (kein Passwort oder E-Mail nicht
bestätigt), `ACTIVE`, `LOCKED` (`locked_at` gesetzt oder `lockout_until` in der Zukunft), `EXPIRED`
(`expires_at` vergangen). Anmeldefähig ist nur `ACTIVE` — und bei abgeschalteter Verwaltung nur ein
`ACTIVE`-Konto mit `SYSTEM_ADMIN`. Ein **anmeldefähiger Systemverwalter** im Sinne dieses ADR ist ein
lokales `ACTIVE`-Konto mit `SYSTEM_ADMIN` oder ein `SYSTEM_ADMIN`-Konto eines aktivierten
OIDC-Anbieters; diese Definition trägt die Invariante aus Entscheidung 4.

Dazu kommen drei Token-Tabellen (qnop `0004-token-schema.yaml`): `local_refresh_tokens`
(Familie mit absolutem Ablauf, HMAC-Lookup-Hash, Ausstellung, Ablauf, Widerruf mit Grund als Enum,
Nachfolger), `local_revoked_tokens` (`jti`-Hash, Ablauf) und `local_action_tokens` (ein Zweck
`SET_PASSWORD` \| `RESET_PASSWORD` \| `VERIFY_EMAIL` \| `HANDOVER`, HMAC-Hash, Ablauf, Verbrauch) —
eine Tabelle mit Zweck statt qnops zwei baugleicher. Roh-Tokens werden nirgends gespeichert. Alle
Hashes sind mit einem aus dem Secret abgeleiteten Schlüssel gebildet (Entscheidung 6), damit eine
Rotation des Secrets jedes offene Token entwertet — der eine Handgriff nach einer
Datenbank-Rücksicherung (Entscheidung 7).

Die Einstellungen der lokalen Verwaltung liegen in der Singleton-Tabelle `local_auth_settings`
(Muster `branding_settings`; der Hauptschalter selbst ist das `enabled` der Anbieterzeile, Entscheidung
4):

| Spalte | Vorgabe | Bedeutung |
| --- | --- | --- |
| `self_registration_enabled` | `false` | Selbstregistrierung; wirkt nur mit nichtleerer Domänenliste |
| `self_registration_allowed_domains` | leer | zulässige Adressdomänen; **leer bedeutet: keine Registrierung möglich**, nicht „alle" |
| `password_reset_enabled` | `true` | Passwort-vergessen-Weg (nur wirksam mit gesetzter Basis-URL, Entscheidung 10) |
| `password_min_length` | 12 | nie unter 8 |
| `invitation_token_ttl_hours` | 72 | Gültigkeit des Einladungslinks |
| `reset_token_ttl_minutes` | 30 | Gültigkeit von Rücksetzlinks, 1–1440 |
| `default_expiry_days` | 90 | Ablauf selbstregistrierter Konten (Pflicht) und Vorbelegung bei Einladungen |
| `inactive_days` | 90 | Sperre nach Inaktivität, mindestens 30; das Notanker-Konto ist ausgenommen |
| `updated_at`, `updated_by`, `version` | | |

Jede Änderung ist `LOCAL_ACCOUNTS_SETTINGS_CHANGED` mit Vorher/Nachher der geänderten Schlüssel.

### 4. Der lokale Anbieter ist eine Anbieterzeile — ihr Schalter ist der Schalter der Verwaltung, und „nie ohne Systemverwalter" ist eine geprüfte Invariante

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
- **Der letzte aktivierte OIDC-Anbieter darf deaktiviert oder gelöscht werden — aber nur, wenn ein
  anmeldefähiger lokaler Systemverwalter existiert.** Das ist keine Bestätigung, sondern eine geprüfte
  Vorbedingung: Fehlt ein lokales `ACTIVE`-Konto mit `SYSTEM_ADMIN` und gesetztem Passwort, antwortet
  die API mit 409 und nennt den fehlenden Schritt („zuerst ein lokales Systemverwalterkonto mit
  Passwort einrichten"). Zusätzlich verlangt der Aufruf `acknowledgeLastProvider = true`, und die
  Oberfläche zeigt die Konsequenz: Danach können sich nur noch lokale Konten anmelden; ein vertippter
  Anbieter lässt sich aus der lokalen Anmeldung heraus korrigieren — ohne Datenbankzugriff und ohne
  Umgebungsvariable. **Aufgehoben** ist damit die Aussage „es gibt keinen Zustand ohne anmeldefähigen
  Anbieter" in ihrer alten Form: Der anmeldefähige Weg ist jetzt immer der lokale Systemverwalter.

**Die Invariante „nie ohne anmeldefähigen Systemverwalter" wird an genau einer Stelle geprüft**
(`LocalAdminAvailabilityGuard`, ein bedingter `UPDATE` unter dem bestehenden Advisory-Lock
`UserRepository#lockRoleChanges`, Fehlercode `LAST_LOGIN_CAPABLE_ADMIN`), und jeder Weg, der die letzte
benutzbare Systemverwalter-Anmeldung entfernen könnte, läuft darüber: das Deaktivieren oder Löschen
des letzten aktivierten OIDC-Anbieters (oben), Sperren, Befristen, Rollenentzug und Löschen eines
Systemverwalters (Entscheidung 11), der Anstoß einer Übergabe für ein Systemverwalterkonto
(Entscheidung 12) und der Wiederanlauf, der ein Konto neu anlegt, wenn keines mehr benutzbar ist
(Entscheidung 5). Die Zählung kennt nur **anmeldefähige** Konten nach Entscheidung 3 — ein
`SYSTEM_ADMIN` eines deaktivierten Anbieters oder ein gesperrtes lokales Konto zählt nicht. Der heutige
Zähler in `TokenRoleSynchronizer`/`UserRepository#withdrawSystemAdminIfAnotherRemains` (#1331, #1349)
wird durch diesen Guard ersetzt, nicht ergänzt.

Der lokale Decoder (Entscheidung 8) wird **unabhängig von `enabled`** registriert. Was der Schalter
bewirkt, entscheidet sich im Token-Validator und im Login: Bei `enabled = false` werden Login und
Token regulärer lokaler Konten abgewiesen (`WWW-Authenticate`-Marker `local_accounts_disabled`),
lokale `SYSTEM_ADMIN`-Konten passieren. Das Abschalten wirkt damit sofort auf laufende Sitzungen, ohne
dass Sitzungen serverseitig aufgezählt werden müssten. Die Anbieter-Registry bleibt eine prozesslokale
Fundstelle nach ADR-0021; dieser ADR trägt die neuen Fundstellen dort ein.

### 5. Erstadministrator: lokales Notanker-Konto beim ersten Start, Einmalpasswort ins Log, Wiederanlauf per Variable

Beim ersten Start im `oidc`-Profil legt `LocalAdminSeeder` (vor dem Webserver, wie
`OidcProviderSeedRunner`; gegen Wiederholung durch eine Markierungszeile gesichert, nicht durch „ist
die Tabelle leer?" — Muster `OidcProviderSeedMarker`/`LlmModelSeedMarker`) die `LOCAL`-Anbieterzeile
und **ein lokales `SYSTEM_ADMIN`-Konto** an: E-Mail aus `OPAA_INITIAL_ADMIN_EMAIL` (die Variable
behält Namen und Bedeutung „Adresse des ersten Systemverwalters"), Anzeigename „Systemverwaltung",
Anlagegrund „Notanker-Konto der Systemverwaltung", `is_bootstrap = true`, `password_change_required =
true` mit Grund `INITIAL`.

**Neuinstallation und Bestand sind zwei Fälle.** Der Beschluss spricht vom *allerersten Start einer
Installation*. Findet der Seed eine Installation vor, die bereits Konten hat (die Markierung des
OIDC-Seeders existiert oder `users` ist nicht leer), legt er das Notanker-Konto **als `INVITED` ohne
Passwort und ohne Log-Ausgabe** an — es ist vorhanden, aber nicht scharf, und der Betrieb aktiviert es
bewusst mit `OPAA_LOCAL_ADMIN_RESET=force` (unten). Ein Haus, das lokale Konten organisatorisch
ausschließt, bekommt damit kein gültiges Passwort in sein Log. Ein Opt-out über eine Variable gibt es
nicht: Der Beschluss will den Systemverwalter immer lokal, und ein `INVITED`-Konto ohne Passwort ist
für dieses Haus dasselbe wie heute — der Notweg bleibt eine Variable plus Neustart.

**Passwort (Neuinstallation).** Ist `OPAA_INITIAL_ADMIN_PASSWORD` gesetzt, wird es verwendet und der
Wechsel **nicht** erzwungen — der Weg für CI, E2E und automatisierte Bereitstellung (qnop
`QNOP_ADMIN_PASSWORD`). Sonst erzeugt der Seed ein Passwort (`PasswordGenerator`, lesbares Alphabet,
20 Zeichen) und schreibt es **einmalig als deutlich markierten Block ins Anwendungslog** — so hat es
der Maintainer entschieden, und im Container ohne Shell (ADR-0029) ist das Log der Kanal, den der
Betrieb ohnehin liest. Die Kehrseite ist bekannt: Log-Weiterleitungen sehen den Wert. Drei Sicherungen
halten den Schaden klein: Der Wert ist nur bis zur ersten Anmeldung gültig (erzwungener Wechsel), das
Konto ist ohne Anmeldung wertlos, und wer das Log nicht belasten will, setzt die Variable. Das reguläre
Log nennt danach nur, **dass** gesät wurde. Audit `LOCAL_ADMIN_SEEDED` als Systemprozess-Ereignis.
qnops Weg (`System.err` am Logger vorbei plus Datei `0600`) wird nicht übernommen: Im Container ist
`stderr` dasselbe Log, und eine Datei im Volume wäre ein zweiter Ort, an dem ein Geheimnis liegen
bleibt.

**Der ausgelieferte Vorgabewert ist kein Anmeldename.** `OPAA_INITIAL_ADMIN_EMAIL` hat heute den
Vorgabewert `admin@opaa.local`. Mit dem Seed würde er zum Anmeldenamen eines privilegierten Kontos an
einem erreichbaren Formular und ist nicht zustellbar. Der Seed im `oidc`-Profil **lehnt diesen Wert
ab**: Fehler im Log mit der zu setzenden Variable, keine Markierung, Anlage beim nächsten Start — exakt
das Verhalten, das ADR-0025 für einen fehlenden OIDC-Bootstrap festlegt. Der `dev`-Modus ist nicht
betroffen (dort wird nicht gesät). `.env.docker.example` liefert die Variable ohne Wert aus; das
Handbuch verlangt ein zustellbares Postfach, am besten ein Funktionspostfach der IT.

**Das Notanker-Konto ist ein Notfallzugangsmittel, kein Arbeitskonto und kein Sammelkonto.** Nach der
Einrichtung legt der erste Systemverwalter **persönliche** Verwalterkonten an (Beschluss: je eigenes
Konto); das Notanker-Konto bleibt anmeldefähig, aber jede erfolgreiche Anmeldung mit ihm ist ein
Audit-Ereignis (`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN`) und löst eine Mail an alle übrigen Systemverwalter
aus. Es ist von der Sperre nach Inaktivität ausgenommen (sein Zweck ist, unbenutzt zu bleiben), kann
nicht übergeben werden (Entscheidung 12) und wird über `is_bootstrap` identifiziert, nicht über seine
Adresse — beides darf sich ändern, ohne dass der Notweg verloren geht. Das Handbuch sagt: Passwort
versiegelt hinterlegen, persönliche Konten für die tägliche Arbeit.

**Die Erstadministrator-Regel für OIDC-Konten entfällt.** `InitialAdminPolicy` vergibt `SYSTEM_ADMIN`
nicht mehr für Konten eines OIDC-Anbieters; die einzige Wirkung der Regel bleibt der **Dev-Issuer**,
damit `dev-admin` wie heute Systemverwalter ist und der `dev`-Modus unverändert bleibt (ADR-0005).
IdP-Konten werden Systemverwalter ausschließlich durch Rollenvergabe (manuell oder über `roles_claim`,
ADR-0025, Entscheidung 4). Damit ist die Kapermöglichkeit über einen zweiten Anbieter, die ADR-0025 nur
eindämmen konnte, ganz weg. Bestandsinstallationen verlieren nichts: Rollen liegen in `users`, die
Regel griff nur beim Anlegen.

**Wiederanlauf.** `OPAA_LOCAL_ADMIN_RESET=force` stellt beim Start einmalig einen anmeldefähigen
lokalen Systemverwalter her: Existiert das Notanker-Konto, wird es entsperrt, sein Ablauf gelöscht,
ein neues Einmalpasswort gesetzt (Log oder `OPAA_INITIAL_ADMIN_PASSWORD`), `password_change_required`
mit Grund `INITIAL`, alle seine Sitzungen widerrufen; existiert es nicht mehr (gelöscht), wird es mit
der konfigurierten Adresse neu angelegt. Laut protokolliert, auditiert (`LOCAL_ADMIN_RESET`), und der
Betrieb entfernt die Variable danach. Das ersetzt den **Zugangsweg** von `OPAA_OIDC_BOOTSTRAP=force`;
die Anbieterreparatur selbst ist künftig die Anmeldung als lokaler Systemverwalter. Die alte Variable
bleibt bis zum **31.03.2027** funktionsfähig, schreibt bei jeder Verwendung eine `WARN`-Zeile mit
Ersatz und Entfernungsdatum, und steht mit diesem Datum in der Tabelle „Migrationen aus älteren
Ständen" des Handbuchs. Die `OPAA_OIDC_*`-Variablen behalten ihre Bootstrap-Rolle für den ersten
OIDC-Anbieter (ADR-0025) — wer sie setzt, bekommt wie heute den Anbieter gesät; wer sie nicht setzt,
legt Anbieter über die Oberfläche an, angemeldet als lokaler Systemverwalter.

Der Seed läuft **nicht** im `dev`-Profil (dort gibt es keine Anbieterzeilen und keinen Aussteller) und
schreibt dort keine Markierung.

### 6. Token: HS256-Access-Token mit HKDF-abgeleitetem Schlüssel aus einem validierten Secret

Das Backend prägt Access-Tokens selbst (Variante a aus #1368) — als **HS256-JWT** (Nimbus
`JwtEncoder`) mit einem Schlüssel, der per HKDF-SHA256 (RFC 5869) aus `OPAA_AUTH_JWT_SECRET`
abgeleitet wird, mit Zweckbindung: `opaa:jwt:access-token` für die Signatur,
`opaa:jwt:refresh-token-lookup` für den HMAC der Refresh-Tokens, `opaa:jwt:action-token-lookup` für
den HMAC der Aktionstoken. Claims: `jti` (UUID, widerrufbar), `iss = urn:opaa:local`, `sub = users.id`,
`iat`, `exp` (15 Minuten, `opaa.auth.local.access-token-ttl`), `email`, `name` (damit `TokenClaims`
wie bei jedem Anbieter liest) und `pcr` (Passwortwechsel erforderlich). Keine Rolle im Token — Rollen
kommen aus `users`, wie heute.

**Das Secret ist Betriebskonfiguration mit Zähnen.** `LocalAuthProperties` ist `@Validated`; ein
`@ValidSecret`-Constraint verlangt mindestens 32 Zeichen und lehnt bekannte Platzhalter ab
(`change_me`, `changeme`, `secret`, `password`, `opaa`, …). Fehlt das Secret oder ist es schwach,
bricht der Start im `oidc`-Profil ab — mit einer Meldung, die die Variable **und** den
Erzeugungsbefehl nennt (`openssl rand -base64 48`), Muster `AuthProfileGuard`; im `dev`-Profil ist es
ohne Wirkung. Für Bestandsinstallationen ist das ein Startabbruch nach dem Update; deshalb steht die
Variable als Pflicht-Vorbereitungsschritt im Handbuchabschnitt „Aktualisierung auf einen neuen
`main`-Stand", in der Variablentabelle und ersetzt den heutigen Absatz des Härtungskapitels, der
behauptet, ein anwendungsseitiges JWT-Secret existiere nicht (#1543). Das ist der Unterschied zum
HMAC-Secret von `basic`, das ADR-0005 als Last nennt: Dort war es ein weiteres unvalidiertes Geheimnis
für einen Modus ohne Gegenwert; hier ist es die eine Wurzel eines Aussteller-Subsystems mit
fail-fast-Prüfung, neben den bestehenden `OPAA_SETTINGS_ENCRYPTION_KEY` und
`OPAA_CREDENTIALS_ENCRYPTION_KEY`. **Eine Rotation des Secrets beendet alle lokalen Sitzungen und
entwertet alle offenen Einladungs-, Rücksetz- und Übergabelinks** (Access-Tokens, Refresh-Lookup und
Aktionstoken-Lookup wechseln mit) — bewusst, und genau deshalb der eine dokumentierte Handgriff nach
einer Datenbank-Rücksicherung (Entscheidung 7).

**Kein asymmetrischer Schlüssel, kein JWK-Set.** #1368 hatte das erwogen. Es gibt keinen zweiten
Prüfer: derselbe Prozess stellt aus und prüft, und ADR-0021 legt eine Instanz fest. Ein Schlüsselpaar
mit `kid`-Rotation und `/.well-known/jwks.json` wäre Schlüsselverwaltung für niemanden. Sollte je ein
externer Dienst lokale Tokens prüfen müssen, ist der Wechsel auf RS256 eine Änderung in
`LocalTokenService` und `NimbusOidcJwtDecoderFactory`, nicht im Datenmodell.

### 7. Sitzung: Refresh-Token als HttpOnly-Cookie, Rotation mit Wiederverwendungserkennung, Höchstdauer, zweistufiger Widerruf, harte Fristen

- **Refresh-Token:** 256 Bit Zufall, base64url, nur der HMAC-Lookup-Hash in
  `local_refresh_tokens`. **Familien mit Rotation:** Jede Vorlage eines aktiven Tokens widerruft es
  (`ROTATED`) und stellt einen Nachfolger derselben Familie aus; die Vorlage eines bereits
  widerrufenen Tokens ist ein Replay und widerruft die **ganze Familie** (`REUSE_DETECTED`).
  Unbekannt, abgelaufen und wiederverwendet sind für den Client ununterscheidbar: 401 und Cookie
  löschen.
- **Zwei Fristen, organisationsweit, wie `access-control.md` sie verlangt:** Die Refresh-Lebensdauer
  ist die **Leerlauffrist** (`opaa.auth.local.refresh-token-ttl`, Vorgabe 7 Tage, **höchstens 30**),
  die Familie trägt eine **absolute Höchstdauer**, die keine Rotation verlängert
  (`opaa.auth.local.session-max-lifetime`, Vorgabe 30 Tage, höchstens 90). Für lokale
  `SYSTEM_ADMIN`-Konten gelten eigene, kürzere Werte (Leerlauf 4 Stunden, Höchstdauer 12 Stunden) —
  ein Notfallkonto mit siebentägiger Sitzung ist kein Notfallkonto. Alle vier Werte werden beim Start
  validiert wie das Secret.
- **Cookie** `opaa_refresh`: `HttpOnly`, `SameSite=Strict`, `Secure`, `Path=/api/v1/auth/local` — es
  reist nur zu den Refresh-, Logout- und Übergabe-Endpunkten und ist für Skripte unsichtbar.
  `opaa.auth.local.cookie-secure = false` ist für lokales HTTP zulässig, erzeugt im `oidc`-Profil aber
  bei jedem Start eine `WARN`-Zeile und steht als eigene Zeile in der Härtungstabelle des Handbuchs.
  Das Access-Token hält die SPA nur im Speicher; nichts im `localStorage`.
- **CSRF** nur dort, wo das Cookie trägt: `POST /api/v1/auth/local/refresh` und `/logout` verlangen ein
  Double-Submit-Token (`CookieCsrfTokenRepository.withHttpOnlyFalse()`, Header `X-XSRF-TOKEN`). Alle
  anderen Endpunkte bleiben Bearer-only und CSRF-frei, wie heute.
- **Widerruf** in zwei Schichten: eine `jti`-Denylist (`local_revoked_tokens`, Caffeine-Cache mit
  `expireAfterWrite = access-token-ttl`) für einzelne Tokens (Logout) und
  `password_invalidated_before` für alles auf einmal (Passwortwechsel, Sperre, Zurücksetzen,
  Abschalten, Übergabe). Logout und Passwortwechsel wirken damit **sofort**, nicht erst beim Ablauf —
  die Zusicherung aus `access-control.md`, „Sitzungsverwaltung".
- **Harte Fristen für die Betriebsdaten.** Ein eigener, täglicher `LocalTokenCleanupScheduler`
  (es gibt bislang keinen täglichen Lauf im Backend; die vorhandenen Scheduler laufen minütlich,
  viertelstündlich oder monatlich) löscht Zeilen der drei Token-Tabellen **spätestens sieben Tage nach
  Ablauf oder Widerruf**, sperrt Konten nach der Inaktivitätsfrist (Entscheidung 11) und verschickt
  die Ablauf-Erinnerungen. `revocation_reason` ist ein Enum, kein Freitext. Es gibt **keine Oberfläche
  und keine Schnittstelle, die dieses Sitzungsjournal je Person ausgibt**; die in `access-control.md`
  zugesagte Übersicht der **eigenen** Sitzungen ist die einzige vorgesehene Ausnahme, nur für die
  Person selbst, und nicht Teil dieses Epics.
- **Rücksicherung einer Datenbank** stellt widerrufene Familien, verbrauchte Links und einen nach
  Verdacht geänderten Passworthash wieder her — ein Zustand, den bisher der Identitätsanbieter hielt.
  Das Handbuch nennt deshalb als verbindlichen Schritt nach jeder Rücksicherung: `OPAA_AUTH_JWT_SECRET`
  rotieren (beendet alle lokalen Sitzungen und entwertet alle offenen Links, Entscheidung 6) und
  Sperren und Rücksetzungen seit dem Sicherungszeitpunkt erneut vornehmen. Kein zusätzlicher
  Startschalter: Die Rotation leistet, was ein Purge leisten müsste.
- **Voraussetzung `SameSite=Strict`:** Die API liegt in allen ausgelieferten Konfigurationen unter dem
  Origin der SPA (`frontend/nginx.conf` proxyt `/api/`, der Vite-Dev-Server ebenso). Ein getrennter
  API-Origin bräuchte `SameSite=None` und CORS mit Credentials — eine Grenze dieses ADR, nicht
  vorgesehen.

### 8. Integration in den Multi-Issuer-Resolver: ein weiterer Decoder, nie ein Provisionierer, jede Abweisung nennt ihren Grund

`OidcProviderRegistry` (ADR-0025) registriert für den lokalen Issuer einen
`NimbusJwtDecoder.withSecretKey(...)` mit Issuer- und Zeitvalidator sowie einem
**Widerrufs-Validator**, der `jti`-Denylist, `password_invalidated_before`, Kontozustand
(`LOCKED`, `EXPIRED`) und den Schalter aus Entscheidung 4 prüft — bei jeder Anfrage, gegen den
Datenbankzustand, mit dem Caffeine-Cache nur für die Denylist (der Kontozustand wird ohnehin vom
`UserProvisioningFilter` gelesen; die Laufzeitkosten sind in #1533 zu messen). Keine `azp`-Prüfung (es
gibt keine `client_id`). Abgewiesene Tokens tragen wie `unknown_issuer` einen `BearerTokenError`, dessen
`error_description` **den Grund nennt**: `local_accounts_disabled`, `account_locked` (mit Anlass
`admin` \| `failed_logins` \| `inactivity`), `account_expired`, `session_revoked` (mit Anlass
`admin_lock` \| `password_changed` \| `admin_reset` \| `reuse_detected` \| `handed_over`). Die SPA
unterscheidet damit den Fall vom abgelaufenen Token, startet **keinen** Erneuerungsversuch und zeigt
den Grund — das ist die technische Form von „Wer neu anmelden muss, erfährt beim nächsten Aufruf, dass
und warum".

`UserService#provisionFromToken` behandelt den lokalen Issuer als **Finder, nie als Anleger**: Ein
Token mit unbekanntem `sub` führt zu 401, nicht zu einem neuen Konto; `email` und `display_name`
werden nicht aus dem Token zurückgeschrieben (die Datenbank ist hier die Quelle, nicht der Claim).
`InitialAdminPolicy` wird für den lokalen Issuer nicht konsultiert. Unterhalb davon ist alles wie bei
jedem Anbieter: `UserProvisioningFilter` legt `CurrentUser` ab, `UserProvisionedEvent` löst den
persönlichen Space aus, Method Security sieht `ROLE_SYSTEM_ADMIN` aus `users.system_role`.

`PasswordChangeRequiredFilter` läuft nach `AuthorizationFilter`: `pcr = true` und Pfad nicht unter
`/api/v1/auth/local/` ⇒ 403 mit Fehlercode `PASSWORD_CHANGE_REQUIRED` **und dem Anlass**
(`password_change_reason`), den die Oberfläche als Klartextsatz zeigt („Ihr Passwort wurde von der
Systemverwaltung zurückgesetzt" ist etwas anderes als „Bitte legen Sie Ihr erstes Passwort fest").
`change-password` stellt unmittelbar ein neues Access-Token ohne `pcr` aus.

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
- **Passwortrichtlinie:** mindestens 12 Zeichen (`local_auth_settings.password_min_length`, nie unter
  8), höchstens 64 Zeichen und 72 Byte (BCrypt verarbeitet 72 Byte), nicht gleich der E-Mail-Adresse,
  **nicht in der mitgelieferten Sperrliste** der rund 1000 häufigsten Passwörter (Abgleich in
  Kleinschreibung, Datei im Image) — der übliche Ausgleich für den Verzicht auf Komplexitätsregeln.
  **Keine Komplexitätsregeln** (Sonderzeichenpflicht verschiebt Passwörter auf Zettel), kein
  erzwungener periodischer Wechsel (BSI ORP.4.A8 verlangt ihn in der aktuellen Fassung nicht mehr;
  ob der Grundschutz die Sperrliste ausdrücklich fordert, ist nicht geprüft — sie ist Prüfmaßstab,
  nicht belegte Anforderung). Die Oberfläche zeigt die Regel an und bietet „sicheres Passwort
  erzeugen".
- **Rate-Limiting je Adresse, je Konto und global** (qnop ADR-0027, ergänzt um die globale Grenze,
  die jeder bestehende Endpunkt hat): Login 10/60 s je IP, Refresh 30/60 s je IP, Passwortwechsel
  5/300 s je Subject, Registrierung 5/3600 s je IP und 3/3600 s je Adresse, Passwort vergessen
  5/3600 s je IP und 3/3600 s je Adresse, Passwort setzen 10/900 s je IP; dazu für Login,
  Registrierung und Passwort vergessen eine **globale Grenze je Fenster** (`globalMaxRequests`, wie
  `OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS`) gegen verteiltes Credential Stuffing, deren
  Überschreiten eine `WARN`-Zeile und eine Metrik erzeugt — das Frühwarnsignal des Betriebs. Antwort
  429 mit `Retry-After`.
- **Kontosperre** nach 5 Fehlversuchen für 15 Minuten (feste Dauer; eine progressive Verlängerung wäre
  eine spätere Verschärfung), Zähler atomar (`UPDATE … SET n = n + 1`), Sperre auditiert
  (`LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`) — **der einzelne Fehlversuch nicht**: Er steht nur im
  technischen Anwendungslog (Konto-Kennung, nie die Adresse; kurze Frist; keine Auswertungsoberfläche)
  und später in der SIEM-Ausleitung, wie `security-and-compliance.md` es für Sicherheitsereignisse
  festlegt. Die Fehlversuch-Sperre **schneidet die Selbsthilfe nicht ab**: „Passwort vergessen" bleibt
  wirksam, und ein erfolgreich eingelöster Rücksetzlink hebt sie auf — der Besitz des Postfachs ist
  der Nachweis, den die Sperre verlangt. Die Antwort des Logins bleibt in **allen** Fällen dieselbe
  (falsche Kennung, falsches Passwort, gesperrt, abgelaufen, nicht bestätigt): Würde sie nach
  korrektem Passwort den Zustand nennen, bestätigte sie einem Angreifer das Passwort eines gesperrten
  Kontos. Dass und warum ein Konto gesperrt ist, erfährt die Person auf dem Weg, den Entscheidung 11
  festlegt — per Mail zum Zeitpunkt der Handlung und als Grund im Sitzungsmarker.
- **Sperre nach Inaktivität:** Ein lokales Konto ohne Aktivität über `inactive_days` (Vorgabe 90,
  mindestens 30) wird vom Cleanup-Lauf gesperrt (`locked_reason = INACTIVITY`, Audit
  `LOCAL_USER_LOCKED`), Mail an die Person, Entsperren wie jede Sperre. Persönliche
  Systemverwalterkonten eingeschlossen; nur das Notanker-Konto ist ausgenommen. Das ist die eine
  Automatik, die den Satz „lokale Konten laufen am Ausscheideprozess vorbei" entschärft, statt ihn
  nur sichtbar zu machen.
- **Client-IP nur von vertrauten Proxys.** `X-Forwarded-For` wird ausschließlich ausgewertet, wenn
  `getRemoteAddr()` in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` liegt; die Vorgabe ist **leer** (Header
  ignoriert). Der bestehende `RateLimitFilter` liest den Header heute ungeprüft — hinter dem
  Compose-nginx kann damit jeder Client seinen Bucket frei wählen. Er wird auf dieselbe Auflösung
  umgestellt; der Compose-Stack bekommt ein festes Subnetz und eine passende Vorgabe in
  `.env.docker.example`. Weil das Fehlerbild einer leeren Liste hinter einem Proxy still ist (alle
  Clients teilen einen Bucket: „zehn Anmeldungen pro Minute für das ganze Haus"), schreibt der Start
  im `oidc`-Profil bei leerer Liste eine `WARN`-Zeile mit der Folge im Klartext, lehnt `0.0.0.0/0`
  und `::/0` ab, und die Diagnose-Ansicht zeigt die aufgelöste Client-Adresse der eigenen Anfrage.
- **Netzbeschränkung für lokale Systemverwalter** als Kompensation, solange es keinen zweiten Faktor
  gibt: `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` (leer = keine Beschränkung) lässt die Anmeldung lokaler
  `SYSTEM_ADMIN`-Konten nur aus den genannten Netzen zu — dieselbe CIDR-Auswertung auf derselben
  aufgelösten Client-Adresse, kein neuer Mechanismus. Reguläre lokale Konten sind nicht betroffen.
- **Kein Geheimnis im Log** außer dem Einmalpasswort des Seeds (Entscheidung 5): keine Roh-Tokens,
  keine Passwörter, keine SMTP-Passwörter; ein Test nach dem Muster von qnops `LogPrivacyTest`
  belegt es — und prüft zugleich die Regel aus Entscheidung 13, dass kein Protokollsatz Adressen,
  Namen, Subjects oder Freitext trägt.
- **MFA (TOTP)** ist **nicht** Teil dieses Epics. Für lokale Systemverwalter wäre sie die
  naheliegende nächste Härtung; sie braucht eine eigene Enrollment-Oberfläche, Wiederherstellungscodes
  und eine zweite Anmeldestufe im Aussteller — ein eigenes Issue nach dem Epic. Bis dahin
  kompensieren Netzbeschränkung, kurze Sitzungen, Auditierung des Notanker-Kontos und die
  Handbuchempfehlung „lokale Verwaltung im Regelbetrieb aus, Systemverwalterkonten befristen".

### 10. Mail-Infrastruktur: SMTP in den Systemeinstellungen, Vorlagen mit Code-Standards, Ergebnis statt Exception, sichtbares Scheitern

`io.opaa.mail` ist ein eigenes Subsystem nach qnop #19:

- **SMTP-Einstellungen** in der Singleton-Tabelle `mail_settings` (Muster `branding_settings`):
  `enabled`, `host`, `port`, `username`, `password_ciphertext` (verschlüsselt über `SettingsEncryptor`,
  `OPAA_SETTINGS_ENCRYPTION_KEY`), `encryption` (`NONE` \| `STARTTLS` \| `SSL`), `from_address`,
  `from_name`, dazu `last_success_at`, `last_failure_at`, `last_failure_reason`. Gepflegt über
  `GET/PUT /api/v1/system/mail-settings` (nur `SYSTEM_ADMIN`); das Passwort erscheint in Antworten als
  `***`, und `***` im Request bedeutet „unverändert" — ein Secret läuft nie durch den Browser zurück.
  Snapshot mit Neuaufbau nach Commit; `MailSenderProvider` verwirft den gecachten `JavaMailSenderImpl`
  bei jeder Änderung — **ohne Neustart**. Keine Adressprüfung für den SMTP-Host: Ein interner
  Mailserver ist der Regelfall, und die Einstellung ist `SYSTEM_ADMIN` vorbehalten; dieselbe Abwägung
  wie bei den Modell-Endpunkten.
- **Öffentliche Basis-URL** für Links in Mails: `OPAA_PUBLIC_BASE_URL` (`opaa.public-base-url`) als
  **Umgebungsvariable**, nicht als Verwaltungseinstellung — sie ist eine Eigenschaft der
  Bereitstellung wie `OPAA_CORS_ALLOWED_ORIGINS` und `OPAA_CSP_CONNECT_SRC_EXTRA`, und wer sie im
  Browser änderbar macht, öffnet Phishing-Links über eine kompromittierte Verwaltersitzung. Der
  `Host`-Header der Anfrage wird **nie** als Basis verwendet. **Fehlt sie, gibt es keine Flüsse, die
  auf einen Link angewiesen sind, den niemand sieht:** `/auth/config` meldet `passwordResetEnabled =
  false` und `selfRegistrationEnabled = false`, die beiden Schalter lassen sich in der Verwaltung nicht
  einschalten (409 mit Begründung), und Einladung und administratives Zurücksetzen fallen auf die
  Link-Anzeige zurück (Entscheidung 11).
- **`MailService.send(key, locale, recipient, vars)`** liefert ein versiegeltes
  `SendResult { Sent \| Skipped(reason) \| Failed(reason) }` und wirft nie; `Skipped`, wenn SMTP nicht
  konfiguriert ist. Versand **synchron** mit Timeouts, ohne Outbox und ohne Wiederholung: Die
  Auth-Mails sind an eine Nutzeraktion gebunden, deren Ergebnis der Aufrufer sofort braucht. **Ein
  `Failed` bleibt nicht unsichtbar:** Log-Zeile mit Grund und Vorlagenschlüssel (ohne Adresse),
  Fortschreibung von `last_failure_at`/`last_failure_reason`, Anzeige „letzter erfolgreicher Versand"
  und „letzter Fehler" auf der Einstellungsseite und ein Health-Indikator `mail` (Zustand ohne
  Details) für die Überwachung. Ein späterer Digest aus #1297 kann eine Warteschlange ergänzen, ohne
  den Vertrag zu ändern.
- **Vorlagen:** `MailTemplateKey` ist die Registry mit deutschen Standards je Schlüssel (Betreff,
  Text, HTML-Fragment, Schaltflächentext, deklarierte Platzhalter): `LOCAL_ACCOUNT_INVITATION`,
  `PASSWORD_RESET`, `ADMIN_PASSWORD_RESET`, `REGISTRATION_VERIFICATION`, `ACCOUNT_LOCKED`,
  `ACCOUNT_UNLOCKED`, `ACCOUNT_EXPIRING`, `ACCOUNT_HANDOVER_REQUESTED`, `ACCOUNT_HANDED_OVER`,
  `BOOTSTRAP_ACCOUNT_USED`, `ADMIN_REVIEW_REMINDER`, `TEST_MAIL`. Überschreibungen liegen in
  `mail_templates(template_key, locale, subject, body_plain, body_html)`; Auflösung Datenbank →
  Standard. Rendering mit JMustache, strikt (fehlender Platzhalter ist ein Fehler), zwei Compiler
  (Betreff/Text ohne, HTML mit Escaping); `MailPlaceholderValidator` lässt nur deklarierte Platzhalter
  zu; `EmailLayoutBuilder` liefert den gebrandeten HTML-Rahmen aus `BrandingSettings` (Produktname,
  Farbe). Vorlagen-API mit Vorschau, Zurücksetzen und Testversand.
- **Verhältnis zu #1297:** Der Kanal E-Mail des Benachrichtigungssystems nutzt `MailService` und die
  Registry unverändert und ergänzt Vorlagen und gegebenenfalls einen Digest. ADR-0019 bleibt gültig;
  er erhält den Hinweis, dass ein Mail-Sender existiert.

### 11. Kontolebenszyklus: Einladung, Zurücksetzen mit Link-Rückfall, Sperren als Regelweg, kein stiller Eingriff, Löschen nach dem DSGVO-Pfad

- **Anlegen** durch `SYSTEM_ADMIN` mit E-Mail, Name, Rolle, **Anlagegrund (Pflicht)** und
  Ablaufdatum (vorbelegt mit `default_expiry_days`, vom Verwalter änderbar oder löschbar) — entweder
  **per Einladung** (Konto `INVITED`, Aktionstoken `SET_PASSWORD`, Mail `LOCAL_ACCOUNT_INVITATION`)
  oder **mit erzeugtem Anfangspasswort** (einmalig in der Antwort, `pcr` mit Grund `INITIAL`).
  Schlägt der Versand fehl, ist SMTP aus oder die Basis-URL nicht gesetzt, enthält die Antwort die
  Einladungs-URL **genau einmal**, damit der Verwalter sie auf anderem Weg übergibt; die Oberfläche
  zeigt sie mit dem Hinweis, dass sie nicht erneut abrufbar ist. **Der Zustellweg steht im
  Protokoll** (`MAIL_SENT` \| `MAIL_FAILED` \| `LINK_DISPLAYED`) — eine Weitergabe außerhalb des
  Systems muss von einer Zustellung unterscheidbar sein.
- **Der Anlagegrund ist zweckgebunden:** dienstlicher Anlass der Kontoanlage und Grund der
  Befristung, höchstens 200 Zeichen; der Hilfetext des Formulars nennt, was **nicht** hineingehört
  (Angaben zu Gesundheit, Beschäftigungsverhältnis, Leistung, Disziplinarsachverhalten, Dritten). Die
  betroffene Person sieht ihn in ihren eigenen Einstellungen, er ist Teil der Selbstauskunft, und er
  erscheint **nie als Wert** im Protokoll (Entscheidung 13). Selbstregistrierte Konten tragen den
  festen Grund „Selbstregistrierung".
- **Zurücksetzen** durch den Verwalter: Aktionstoken `RESET_PASSWORD` per Mail (`ADMIN_PASSWORD_RESET`)
  mit demselben Link-Rückfall und derselben Protokollierung des Zustellwegs; oder ein erzeugtes
  Passwort mit `pcr` (Grund `ADMIN_RESET`). Beides widerruft alle Sitzungen des Kontos.
- **Sperren ist der Regelweg**, Löschen die Ausnahme — wie `access-control.md` es für das Ausscheiden
  vorsieht. Sperren widerruft alle Sitzungen sofort und hebt eine Fehlversuch-Sperre auf; Entsperren
  setzt Zähler zurück. Selbstsperre und Sperre, Ablauf, Rollenentzug oder Löschung des **letzten
  anmeldefähigen Systemverwalters** werden abgelehnt (409 `SELF_LOCKOUT`,
  `LAST_LOGIN_CAPABLE_ADMIN`) — über den Guard aus Entscheidung 4. Rollenänderungen an lokalen Konten
  laufen über die bestehenden Rollenereignisse (`SYSTEM_ADMIN_ROLE_GRANTED`/`_REVOKED`,
  `AUDITOR_*`), nicht über ein zweites.
- **Kein stiller Eingriff.** Jeder Verwaltungsakt an einem Konto wird der Person mitgeteilt — per Mail
  zum Zeitpunkt der Handlung (`ACCOUNT_LOCKED` bei Sperre durch Verwalter oder Inaktivität,
  `ACCOUNT_UNLOCKED`, `ADMIN_PASSWORD_RESET`, `ACCOUNT_EXPIRING` 14 Tage vor dem Ablauf,
  `ACCOUNT_HANDOVER_REQUESTED`/`ACCOUNT_HANDED_OVER`) und als Grund im Sitzungsmarker beziehungsweise
  im `pcr`-Anlass beim nächsten Aufruf (Entscheidung 8). Die Fehlversuch-Sperre löst **keine** Mail
  aus (sie wäre ein Belästigungskanal für jeden, der die Adresse kennt); ihr Ausweg ist der offene
  Rücksetzweg (Entscheidung 9).
- **Ablaufdatum:** Ein abgelaufenes Konto ist `EXPIRED` — kein Login, laufende Tokens abgewiesen
  (`account_expired`), in der Liste hervorgehoben, nicht gelöscht. **Die Prüfung der Auflage ist ein
  Vorgang, kein Zähler:** Die Verwaltung führt die Liste der lokalen Konten (Zustand, Rolle, Ablauf,
  Anlagegrund, Aktivität als Klasse) mit Filtern „ohne Ablaufdatum", „länger als 90 Tage nicht
  genutzt", „offene Einladungen"; 14 Tage vor einem Ablauf erhalten Person und Systemverwalter eine
  Mail; einmal im Quartal erhalten die Systemverwalter eine Wiedervorlage (`ADMIN_REVIEW_REMINDER`)
  mit der Zahl der Konten ohne Ablaufdatum und dem Link zur Liste — keine Namen in der Mail.
  Systemverwalterkonten sind nicht ausgenommen.
- **Die Kontenliste ist kein Auswertungspfad:** Sie führt **ausschließlich lokale Konten** (die Rolle
  eines OIDC-Kontos wird nicht in dieser Sicht verwaltet — ein fehlender Aufrufer dafür ist ein
  eigenes Thema, keine Rechtfertigung für eine Beschäftigtenliste), zeigt Aktivität nur als Klasse
  („nie", „länger als 90 Tage nicht", „aktiv") ohne exakten Zeitstempel und ohne Sortierung danach,
  und kennt **keinen Export und keinen Massenabruf** (keine CSV-Ausgabe, Seitengröße höchstens 50).
  Das ist eine dauerhafte Eigenschaft, keine Umfangsentscheidung eines Issues. `users.last_login_at`
  ist ein bei jeder Anfrage gedrosselt fortgeschriebener **Aktivitätszeitstempel** (fünf Minuten
  Auflösung, `UserService#updateExistingUser`), kein Anmeldezeitpunkt — er wird nirgends als solcher
  ausgegeben.
- **Löschen** folgt dem Pfad in `docs/features/security-and-compliance.md`, „Löschung eines
  Benutzerkontos" (Zugang deaktivieren, Assets „Nachfolge offen", aus Spaces und Gruppen entfernen,
  Konto/Sitzungen/Tokens löschen, Pseudonymzuordnung entfernen). Solange dieser Pfad nicht als
  gemeinsamer Dienst für alle Konten gebaut ist, löscht `DELETE` ein lokales Konto nur, wenn es keine
  Assets und keinen Space außer dem persönlichen besitzt; sonst 409 mit dem Hinweis, zu sperren. Die
  Kaskade auf `local_credentials` und alle Token-Tabellen erledigt das Schema.
- **Selbstregistrierung** nur bei `self_registration_enabled` **und** nichtleerer Domänenliste
  (Standard aus): Rolle immer `USER`, Ablaufdatum **Pflicht** (`default_expiry_days`), Anlagegrund
  „Selbstregistrierung", Konto bis zur Bestätigung (`VERIFY_EMAIL`, 24 Stunden) nicht anmeldefähig;
  bei belegter Adresse entsteht kein zweites Konto, die Antwort ist identisch (202); keine Hinweis-Mail
  an die belegte Adresse (sie wäre selbst ein Aufzählungskanal). Das Einschalten ist ein Audit-Ereignis
  und nach `security-and-compliance.md` ein Punkt der Dienstvereinbarung; das Handbuch sagt das.
- **Passwort vergessen** nur bei `password_reset_enabled` und gesetzter Basis-URL: immer 204 nach
  konstanter Zeitklasse, unabhängig davon, ob ein aktives Konto existiert; Konten mit
  Verwalter- oder Inaktivitätssperre, abgelaufene und eingeladene Konten erhalten keine Mail — ein
  Konto in Fehlversuch-Sperre schon (Entscheidung 9). **Abgeschaltete Flüsse antworten 404 wie eine
  unbekannte Route**, damit ihre Existenz nicht sondiert wird; die SPA kennt den Zustand aus
  `/auth/config`.
- **Passwort ändern** (mit aktuellem Passwort) steht jedem lokalen Konto in den
  Benutzereinstellungen offen und widerruft die übrigen Sitzungen.

### 12. Übergabe eines lokalen Kontos an eine Anbieteridentität — administrativ angestoßen, von der Person selbst eingelöst

Zuschnitt B (Anlaufbetrieb ohne IdP, später Umstellung) war der Grund, aus dem `basic` an der Migration
scheiterte: Spaces, Mitgliedschaften und Rollen blieben an der alten Identität zurück. ADR-0025
verbietet jede Zusammenführung, und dabei bleibt es für alles, was **automatisch** oder über die
**E-Mail** liefe. Dieser ADR fügt **eine benannte Ausnahme** hinzu — und zwar so, dass kein
Systemverwalter allein ein Konto einer Identität zuschreiben kann, die er kontrolliert, und sich auch
nicht vertippen kann:

1. Ein `SYSTEM_ADMIN` **stößt die Übergabe an** (`POST /api/v1/admin/local-users/{id}/handover` mit der
   Kennung eines aktivierten OIDC-Anbieters und einem **Pflicht-`reason`**). Das Backend erzeugt ein
   Aktionstoken `HANDOVER` (72 Stunden), schickt den Link an die **hinterlegte** Adresse des lokalen
   Kontos (`ACCOUNT_HANDOVER_REQUESTED`; bei Ausfall die Link-Anzeige mit protokolliertem Zustellweg)
   und auditiert `LOCAL_USER_HANDOVER_REQUESTED`. Das lokale Konto bleibt bis zur Einlösung benutzbar.
2. **Die Person löst ein:** Der Link führt auf eine Seite der SPA, die den Code-Flow beim gewählten
   Anbieter startet; nach dem Callback ruft die SPA `POST /api/v1/auth/local/handover/redeem` mit dem
   **Token des Anbieters** und dem Übergabecode auf. Dieser Endpunkt liegt **vor** der Provisionierung
   (er legt nie ein Konto an), prüft das Anbieter-Token wie jedes andere über die Registry, verlangt,
   dass unter `(issuer, subject)` **noch kein Konto** existiert (sonst 409 — es wird nichts
   zusammengeführt; wer sich vorher schon über den Anbieter angemeldet hat, hat zwei Konten und keinen
   Übergabeweg), und schreibt dann atomar `users.issuer`/`users.subject` auf die Identität aus dem
   Token um, löscht `local_credentials` und alle Tokens des Kontos, widerruft dessen Sitzungen und
   verbraucht den Code. Die Einlöseseite zeigt vorher, was mitgeht (persönlicher Space, Zahl der
   Mitgliedschaften, Systemrolle) — es sind die eigenen Inhalte der Person. Danach
   `ACCOUNT_HANDED_OVER` an die Adresse und Audit `LOCAL_USER_HANDED_OVER` mit der Anbieter-Kennung
   und den Zahlen, **nie mit dem Subject**.

Das Subject kommt damit aus dem Token der Person, nie aus einer Eingabe; die E-Mail wählt niemanden
aus, sie ist nur der Zustellweg eines Einmalcodes (die Regel „keine Zusammenführung über die E-Mail"
bleibt gewahrt); ein Verwalter allein kann keine Identität übernehmen; und die Person erfährt vom
Vorgang, weil sie ihn selbst abschließt. `system_role` bleibt (bei einem Anbieter mit `roles_claim`
wird sie ohnehin beim nächsten Token geführt); die nächste Anmeldung über den Anbieter findet das
Konto über den bestehenden Schlüssel — kein Sonderpfad im Provisionierer. Ausgeschlossen sind das
Notanker-Konto und jede Übergabe, die den letzten anmeldefähigen Systemverwalter entfernen würde
(Guard aus Entscheidung 4). Einen Rückweg gibt es nicht; das ist vertretbar, weil die Person selbst
einlöst. Die Umsetzung ist ein eigenes Sub-Issue des Epics nach der Admin-API und der Anmeldeseite.

### 13. Audit-Ereignisse

Alle Ereignisse nach ADR-0015 mit den bestehenden Objekttypen (`USER_ACCOUNT`, `SYSTEM_SETTING`),
Subjekte als Pseudonym, Akteur die handelnde Person oder der Systemprozess `local-auth`; neue Werte
in `io.opaa.api.types.AuditEventType` und der Spec (Parity-Test, keine Migration).
`security-and-compliance.md` führt sie in der geschlossenen Liste, damit der Auszug für die
Personalvertretung sie kennt, **bevor** die lokale Verwaltung erstmals eingeschaltet wird.

| Bereich | Ereignisse |
| --- | --- |
| Notanker | `LOCAL_ADMIN_SEEDED`, `LOCAL_ADMIN_RESET`, `LOCAL_BOOTSTRAP_ACCOUNT_LOGIN` |
| Schalter und Einstellungen | `LOCAL_ACCOUNTS_ENABLED`, `LOCAL_ACCOUNTS_DISABLED`, `LOCAL_ACCOUNTS_SETTINGS_CHANGED` |
| Sperren und Sitzungen | `LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`, `LOCAL_USER_LOCKED` (Grund `ADMIN` \| `INACTIVITY`), `LOCAL_USER_UNLOCKED`, `LOCAL_SESSION_REVOKED` (nur fremdveranlasst, mit Grund) |
| Passwort | `LOCAL_PASSWORD_CHANGED`, `LOCAL_PASSWORD_SET` (per Link), `LOCAL_USER_PASSWORD_RESET_REQUESTED` (mit Zustellweg), `LOCAL_USER_PASSWORD_GENERATED` |
| Konto | `LOCAL_USER_CREATED`, `LOCAL_USER_INVITED` (mit Zustellweg), `LOCAL_USER_REGISTERED`, `LOCAL_USER_CHANGED`, `LOCAL_USER_DELETED`, `LOCAL_USER_HANDOVER_REQUESTED` (mit Zustellweg), `LOCAL_USER_HANDED_OVER` |
| Mail | `MAIL_SETTINGS_CHANGED`, `MAIL_TEMPLATE_CHANGED`, `MAIL_TEMPLATE_RESET`, `MAIL_TEST_SENT` |

Drei Regeln, die der `LogPrivacyTest` aus Entscheidung 9 mitprüft:

- **Kein Personenbezug im Klartext.** In keinem `LOCAL_*`-Ereignis stehen E-Mail-Adressen, Namen,
  Anbieter-Subjects oder Freitext. `LOCAL_USER_CHANGED` trägt Vorher/Nachher **nur für `expires_at`**
  (eine Frist ist rechtlich erheblich — „seit wann trägt dieses Konto kein Ablaufdatum mehr, und wer
  hat es entfernt?") und sonst die **Namen** der geänderten Felder (`email`, `display_name`,
  `created_reason`), nicht ihre Werte. `LOCAL_USER_HANDED_OVER` trägt `oidc_providers.id` und Zahlen,
  nicht das Subject.
- **Kein Anmelde- und Anwesenheitsprotokoll.** Erfolgreiche Anmeldungen, die eigene Abmeldung und
  der routinemäßige Ablauf einer Sitzung erzeugen **kein** Ereignis; `LOCAL_SESSION_REVOKED` entsteht
  ausschließlich beim fremdveranlassten Widerruf (Sperre, Zurücksetzen, Abschalten, Wiederverwendung
  eines Refresh-Tokens, Übergabe) — dort, wo ein Verwaltungsakt gegenüber einer Person vorliegt, den
  sie nachvollziehen können muss. Die eine Ausnahme ist das Notanker-Konto
  (`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN`): ein privilegiertes Notfallzugangsmittel, keine Person, und „wurde
  der Notfallzugang benutzt?" ist die Prüferfrage zu jedem Notfallkonto. Persönliche
  Systemverwalterkonten sind Personen; ihre Verwaltungsakte sind ohnehin protokolliert.
- **Kein Fehlversuch im Nachweisprotokoll.** Der einzelne Fehlversuch und die Wiederverwendung eines
  Refresh-Tokens sind Sicherheitsereignisse für Anwendungslog und SIEM
  (`security-and-compliance.md`, „Was ausdrücklich nicht protokolliert wird"); protokolliert wird die
  daraus folgende Zustandsänderung (Sperre, Familienwiderruf). Der Fehlversuchszähler wird nirgends
  ausgegeben und nicht historisiert.

Die vorhandenen, ungenutzten Werte `ACCOUNT_DEACTIVATED` und `ACCOUNT_REAUTHENTICATION_FORCED` bleiben
unangetastet — sie sind für den Verzeichnis-Lebenszyklus reserviert.

### 14. Grenzen

- Kein SAML, keine anderen Protokolle; keine API-Tokens für programmatischen Zugang (eigenes Thema).
- Keine automatische oder E-Mail-basierte Zusammenführung; nur die von der Person eingelöste Übergabe
  aus Entscheidung 12, ohne Rückweg.
- Keine MFA in diesem Epic (Entscheidung 9); Netzbeschränkung, kurze Sitzungen und Auditierung des
  Notanker-Kontos sind die Kompensation.
- Eine Organisation; lokale Konten entstehen in `Organization.DEFAULT_ID`.
- Der `dev`-Modus ist unverändert: kein Aussteller, kein Seed, keine Anmeldeseite; die E2E-Suite läuft
  weiter auf `dev`, der lokale Anmeldeweg bekommt ein eigenes E2E-Ziel (#1543).
- Der Profilname `oidc` bleibt (Entscheidung 8).
- Lokale Konten erhalten Gruppen nur manuell; der Verzeichnisabgleich bleibt an den
  Standard-OIDC-Anbieter gebunden (ADR-0025).
- Ein getrennter API-Origin wird nicht unterstützt (Entscheidung 7).
- Die Übersicht der eigenen Sitzungen aus `access-control.md` ist nicht Teil dieses Epics.
- Kein Mehrsprachigkeitsversprechen für Mails über `de` hinaus; die `locale`-Spalte ist vorbereitet.

## Konsequenzen

### Positiv

- Eine Installation ist **nie ohne anmeldefähigen Systemverwalter** — nicht durch eine Regel über
  OIDC-Anbieter und nicht durch einen Bestätigungsdialog, sondern durch eine an einer Stelle geprüfte
  Invariante über ein Konto, das OPAA selbst führt. Der Weg zurück aus jeder
  Anbieter-Fehlkonfiguration ist eine Anmeldung, kein Datenbankeingriff.
- Interessenten ohne Identitätsanbieter können OPAA betreiben (ADR-0005, negative Konsequenz Nr. 1
  entfällt), und der spätere Umstieg auf einen Anbieter verliert keine Daten (Entscheidung 12).
- Jede Lehre aus `basic` ist an einer benannten Stelle eingelöst: Subject-UUID (2), Hashing und
  konstante Zeit (9), Rate-Limiting und Kontosperre (9), Refresh mit Rotation (7), mehrere Konten mit
  Rollen (11), validiertes Secret statt Betriebs-HMAC (6).
- Die Zusagen der Feature-Spezifikationen bleiben intakt: kein Anmelde- oder Anwesenheitsprotokoll,
  keine Fehlversuche im Nachweisprotokoll, kein Personenbezug im Klartext, kein Auswertungspfad über
  die Kontenliste, kein stiller Eingriff in ein Konto.
- OIDC bleibt, wie ADR-0025 es festgelegt hat; der Aussteller berührt den OIDC-Pfad nur an der
  Registry und im Provisionierer.
- Die Mail-Infrastruktur steht #1297 fertig zur Verfügung.

### Negativ

- Das Backend verwaltet erstmals Sitzungszustand (Refresh-Familien, Denylist) und ein
  Signaturgeheimnis. Beides ist auf den lokalen Issuer begrenzt, aber es ist Verantwortung, die vorher
  beim Anbieter lag — einschließlich der Nacharbeit nach einer Datenbank-Rücksicherung.
- Das Einmalpasswort steht bei einer Neuinstallation einmal im Log. Log-Weiterleitungen sehen es; der
  erzwungene Wechsel und `OPAA_INITIAL_ADMIN_PASSWORD` sind die Gegenmittel, kein Ausschluss.
- Lokale Konten laufen am Verzeichnis vorbei. Sperre nach Inaktivität, Pflicht-Anlagegrund,
  Ablaufdatum, Erinnerungen und Wiedervorlage machen das beherrschbar; das Handbuch empfiehlt, die
  Verwaltung im Regelbetrieb aus zu lassen und Systemverwalterkonten zu befristen.
- Der letzte OIDC-Anbieter kann deaktiviert werden. Ein Verwalter, der das bestätigt, sperrt alle
  Nutzer außer den lokalen Konten aus — beabsichtigt, geprüft, aber ein größerer Hebel als bisher.
- Bestandsinstallationen haben nach dem Update einen Startabbruch, bis `OPAA_AUTH_JWT_SECRET` gesetzt
  ist, und drei weitere Variablen sind dringend empfohlen (`OPAA_PUBLIC_BASE_URL`,
  `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`, ein echter Wert für `OPAA_INITIAL_ADMIN_EMAIL`); das Handbuch
  trägt sie als Vorbereitungsschritte.
- Mehr Mails: Sperre, Entsperrung, Zurücksetzen, Ablauf, Übergabe, Notanker-Nutzung, Wiedervorlage —
  jede davon ist ein Vorlagenschlüssel, den die Verwaltung anpassen kann, aber es sind zwölf Vorlagen
  statt fünf.
- BCrypt begrenzt Passwörter auf 72 Byte; die Richtlinie macht die Grenze sichtbar.

### Neutral

- `OPAA_INITIAL_ADMIN_EMAIL` wechselt die Bedeutung von „E-Mail, die beim ersten OIDC-Login Admin
  wird" zu „E-Mail des lokalen Notanker-Kontos"; der Name bleibt, der Vorgabewert entfällt.
- `OPAA_OIDC_BOOTSTRAP=force` wird bis zum 31.03.2027 durch `OPAA_LOCAL_ADMIN_RESET=force` und die
  lokale Anmeldung abgelöst.
- ADR-0021 erhält neue prozesslokale Fundstellen (lokaler Decoder in der Registry, `jti`-Denylist,
  Rate-Limit-Buckets der Auth-Endpunkte, `MailSenderProvider`-Cache, Snapshots von `mail_settings` und
  `local_auth_settings`) und den `LocalTokenCleanupScheduler`.
- `docs/features/access-control.md` („Anmeldung und Identität") und
  `docs/features/security-and-compliance.md` (geschlossene Ereignisliste, „Was ausdrücklich nicht
  protokolliert wird", „Export und Auskunft") sind mit diesem ADR nachgezogen;
  `docs/features/user-frontends.md` trägt den Hinweis. Die Absätze „gebaut" folgen mit den Sub-Issues.

## Stakeholder-Bewertung

Der Entwurf wurde aus zwei Perspektiven bewertet (Berichte in
[`docs/discussions/discussion-lokale-benutzerverwaltung-stakeholder.md`](../discussions/discussion-lokale-benutzerverwaltung-stakeholder.md)):

**Betrieb und Informationssicherheit** — „tragfähig mit Auflagen", 25 Befunde, vier blockierend:
der Seed legte auf Bestandsinstallationen ungefragt ein scharfes Konto an (→ Bestandsfall als
`INVITED`, Entscheidung 5); der Übergabepfad ließ einen Verwalter allein eine Identität übernehmen
(→ zweistufig, Subject aus dem Token der Person, Entscheidung 12); der Notanker war nicht gegen die
eigenen Verwaltungswege geschützt (→ `is_bootstrap`, Wiederanlauf legt neu an, Übergabe
ausgeschlossen, Entscheidung 5); das Deaktivieren des letzten OIDC-Anbieters war ein Klick statt
einer Prüfung (→ `LocalAdminAvailabilityGuard`, Entscheidung 4). Übernommen wurden außerdem: die
Ablehnung des Vorgabewerts `admin@opaa.local`, ein Kalenderdatum für `OPAA_OIDC_BOOTSTRAP=force`, die
Handbuchstellen zum JWT-Secret, der Zustellweg im Protokoll, Vorher/Nachher für `expires_at`, die
Domänenliste und Pflicht-Befristung der Selbstregistrierung, die Sperre nach Inaktivität, die globale
Rate-Grenze, die Höchstdauer von Sitzungen, der tägliche Aufräumlauf (den der Entwurf fälschlich als
vorhanden annahm), `local_auth_settings` im Datenmodell, `WARN` bei `cookie-secure = false` und bei
leerer Proxy-Liste, sichtbares Scheitern des Mailversands, die Basis-URL als Vorbedingung der
Link-Flüsse, die Passwort-Sperrliste, die Netzbeschränkung für lokale Systemverwalter und die
Nacharbeit nach einer Rücksicherung. **Nicht übernommen:** ein Opt-out des Seeds per Variable
(widerspricht dem Beschluss; der `INVITED`-Bestandsfall leistet dasselbe), die Auditierung
erfolgreicher Anmeldungen **aller** lokalen Systemverwalter (nur das Notanker-Konto — persönliche
Konten sind Personen), eine exportierbare Kontenliste (kein Export, siehe Personalrat) und ein
eigener Purge-Schalter (die Secret-Rotation leistet es).

**Personalrat** — „tragfähig mit Auflagen", 14 Befunde, drei blockierend: Fehlversuche wanderten
entgegen `security-and-compliance.md` in das Nachweisprotokoll (→ nur die Sperre, Entscheidung 13);
der Übergabepfad war ein stiller Weg in private Inhalte (→ zweistufig, von der Person eingelöst,
Pflicht-Anlass, Unterrichtung, Umfangsanzeige); `security-and-compliance.md` fehlte in der
Nachzugsliste (→ nachgezogen). Übernommen wurden außerdem: kein Personenbezug im Klartext im Protokoll,
Korrektur der Behauptung zu `last_login_at`, Kontenliste nur lokal mit Aktivität als Klasse und ohne
Export, offener Rücksetzweg bei Fehlversuch-Sperre, Anlass bei erzwungenem Wechsel und im
Sitzungsmarker, Zweckbindung und Sichtbarkeit des Anlagegrunds, harte Fristen für die Token-Tabellen,
`LOCAL_SESSION_REVOKED` nur fremdveranlasst, Zähler nie ausgeben, Pflicht-Befristung
selbstregistrierter Konten, kein Export der Liste, und die Auditierung des Notanker-Kontos als
ausdrücklich mitgetragene Ausnahme. **Nicht übernommen:** ein Vier-Augen-Prinzip für die Übergabe
(die Person selbst ist die zweite Partei), die Nennung des Kontozustands nach korrektem Passwort
(bestätigte einem Angreifer das Passwort eines gesperrten Kontos; die Unterrichtung läuft per Mail und
Sitzungsmarker) und das Sperren des Notanker-Kontos nach der Einrichtung (es bleibt anmeldefähig,
seine Nutzung ist auditiert und wird allen Systemverwaltern gemeldet). Die zwölf Bedingungen für eine
Zustimmung sind im ADR abgebildet; die als Regelung (nicht als Produkt) zu treffenden — Unterrichtung
des Personalrats vor dem Einschalten der Schalter, jährliche Vorlage des Auszugs — gehören ins
Handbuch (#1543).

## Zuschnitt der Sub-Issues (gegen diesen ADR geprüft)

| Issue | Folgt aus diesem ADR | Zu korrigieren |
| --- | --- | --- |
| #1532 Schema und Krypto | 1:1-Tabelle `local_credentials` mit `is_bootstrap`, `password_change_reason`, `locked_reason` inkl. `INACTIVITY`, `created_reason NOT NULL varchar(200)` (3); drei Token-Tabellen mit `HANDOVER`-Zweck und Enum-Gründen (3); `local_auth_settings` (3); `provider_type` mit den drei `CHECK`s (4); BCrypt 12 hinter `DelegatingPasswordEncoder` (9); HKDF mit drei Zwecken, `@ValidSecret`, TTL-Obergrenzen (6, 7) | `local_auth_settings` und `mail_settings`-Statusspalten ergänzen; Passwortmaximum 64 Zeichen/72 Byte statt 200; Aktionstoken mit HMAC statt SHA-256 |
| #1533 Token-Aussteller | Claims, Fristen inkl. Höchstdauer, Cookie, CSRF, Rotation, Widerruf (6, 7), Marker mit Gründen und Finder-Regel (8), `pcr`-Filter mit Anlass (8), `LocalTokenCleanupScheduler` (7) | Aufräumlauf, Höchstdauer je Familie, Admin-Fristen, `WARN` bei `cookie-secure = false`, Marker-Gründe; `change-password` stellt sofort ein neues Token aus; keine Audits für Login/Logout/Ablauf (13) |
| #1534 Erstadministrator | Seed mit Bestandsfall `INVITED`, Ablehnung des Vorgabewerts, `is_bootstrap`, Notanker-Login auditiert und gemeldet, `InitialAdminPolicy` nur Dev, Wiederanlauf legt neu an, `LocalAdminAvailabilityGuard` ersetzt den heutigen Zähler, letzter OIDC-Anbieter nur mit Guard **und** `acknowledgeLastProvider` (4, 5) | Bestandsfall, Vorgabewert, Guard als gemeinsamer Dienst, Datum 31.03.2027 mit `WARN`, Netzbeschränkung `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` (9) |
| #1535 Rate-Limiting | Grenzen inkl. globaler Grenze, Trusted-Proxy mit `WARN`/Ablehnung `0.0.0.0/0`, Diagnose-Anzeige, Kontosperre 5/15 min, Fehlversuche nur ins Anwendungslog (9) | globale Grenze, `WARN`, Diagnose; **kein** Audit je Fehlversuch; Rücksetzweg bleibt bei Fehlversuch-Sperre offen |
| #1536 Mail | `mail_settings` mit Statusspalten, `OPAA_PUBLIC_BASE_URL` als Umgebung und Vorbedingung, `SendResult`, sichtbares Scheitern, Health-Indikator, Registry mit zwölf Schlüsseln, JMustache (10) | Statusspalten, Log-Zeile je `Failed`, Health-Indikator, sieben weitere Vorlagen |
| #1537 Admin-API | Einladung mit Link-Rückfall und Zustellweg, Zurücksetzen, Sperren als Regelweg, Anlagegrund Pflicht und zweckgebunden, Ablauf mit Erinnerungen, Kontenliste nur lokal ohne Export, Aktivität als Klasse, Guard, `local_auth_settings`-API mit Domänen und Fristen (4, 11) | `created_reason` Pflicht/200 Zeichen/für die Person sichtbar; Zustellweg im Protokoll; Liste nur lokal, Seitengröße ≤ 50, kein Export; `DELETE` nur ohne Besitz; Mails bei Sperre/Entsperrung/Zurücksetzen; Vorher/Nachher nur für `expires_at` |
| #1538 Selbstbedienung | `set-password` für beide Zwecke, `forgot-password` 204 konstant und offen bei Fehlversuch-Sperre, Registrierung nur mit Domänenliste und Pflicht-Ablauf, 404 für abgeschaltete Flüsse (11) | Domänenliste, Pflicht-Ablauf, fester Anlagegrund; Fehlversuch-Sperre blockiert den Rücksetzweg nicht; keine Hinweis-Mail an belegte Adressen |
| #1539 Anmeldeseite und Sitzung | `sessionKind`, Refresh nur mit CSRF-Cookie, Marker mit Gründen ohne Erneuerung, `/login/system`, `pcr`-Anlass als Klartext (7, 8) | Grund-Anzeige für alle Marker und den `pcr`-Anlass |
| #1540 Selbstbedienungsseiten | eine Seite für Einladung und Zurücksetzen, Richtlinie sichtbar, Sperrliste im Feldfehler, Anlagegrund in den eigenen Einstellungen (9, 11) | Maximum 64 Zeichen; Anlagegrund einsehbar |
| #1541 Benutzerverwaltung | Zustände mit Grund, Schalter mit Konsequenz-Dialog und Vorbedingungen (Basis-URL, Domänenliste), Filter statt Zähler, Aktivität als Klasse, kein Export (4, 11) | **nur lokale Konten**; Aktivität als Klasse ohne Sortierung; kein Export; Aktion „Übergabe anstoßen" mit Pflicht-Anlass; Konsequenz-Dialog des letzten OIDC-Anbieters zeigt den Guard-Fehler |
| #1542 E-Mail-Einstellungen | Maskierung `***`, Testversand, „letzter Erfolg / letzter Fehler", Vorlagen mit Vorschau (10) | Statusanzeige; Hinweis und Sperre der Schalter ohne `OPAA_PUBLIC_BASE_URL` |
| #1543 E2E und Handbuch | E2E-Ziel ohne Keycloak, Handbuchkapitel, Variablen (5, 6, 9, 10) | Vorbereitungsschritte für Bestandsinstallationen (`OPAA_AUTH_JWT_SECRET` mit Erzeugungsbefehl, echter Wert für `OPAA_INITIAL_ADMIN_EMAIL`, Proxy-Liste), Härtungskapitel-Absatz zum JWT-Secret ersetzen, Härtungstabelle um `cookie-secure`, Tabelle „Migrationen aus älteren Ständen" mit 31.03.2027, Nacharbeit nach Rücksicherung, Notanker-Prozedur (versiegeltes Passwort, persönliche Konten), Dienstvereinbarungs-Hinweise (Schalter, Auszug vor dem Einschalten, jährliche Vorlage), MFA-Folgeschritt, `oidc` als „Betriebsmodus" |
| **neu** | Übergabe eines lokalen Kontos an eine Anbieteridentität, zweistufig (12) — Sub-Issue nach #1537, #1538 und #1539 | anzulegen |

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
- **Bestätigungsdialog statt Vorbedingung beim letzten OIDC-Anbieter:** ein Klick prüft nichts; mit
  einem `INVITED`-, gesperrten oder abgelaufenen Notanker sperrte er die Installation aus (4).
- **Modell II (lokaler Admin nur als Notanker) und Modell III ohne Sonderstatus:** von der
  Abstimmung verworfen; Modell I mit Zeile nach III ist der Beschluss (5).
- **Scharfes Notanker-Konto auch auf Bestandsinstallationen:** ein gültiges Passwort im Log eines
  Hauses, das lokale Konten ausschließt, ohne dass jemand davon weiß; der Beschluss spricht vom
  allerersten Start (5).
- **Opt-out des Seeds per Variable:** widerspricht „Systemverwalter immer lokal"; der
  `INVITED`-Bestandsfall ist für ein solches Haus dasselbe wie heute (5).
- **Einmalpasswort in eine Datei oder auf `stderr` am Logger vorbei** (qnop): im Container ist
  `stderr` das Log, und eine Datei ist ein zweiter Ort für ein Geheimnis; der Maintainer hat das Log
  entschieden (5).
- **Notanker-Konto nach der Einrichtung sperren:** dann ist der Notweg wieder eine Variable plus
  Neustart; stattdessen bleibt es anmeldefähig, und seine Nutzung ist auditiert und gemeldet (5, 13).
- **Erstadministrator-Regel für OIDC beibehalten:** zwei Wege zum `SYSTEM_ADMIN`, davon einer über
  eine E-Mail beim Anbieter kaperbar; der Beschluss will ihn weg (5).
- **Asymmetrischer Schlüssel mit JWK-Set** (#1368, Variante a in der Ausprägung): kein zweiter
  Prüfer, Schlüsselverwaltung ohne Nutzer (6).
- **Eigener Purge-Schalter nach einer Rücksicherung:** die Rotation des Secrets entwertet alle
  Sitzungen und Links, weil jeder Lookup-Hash daran hängt (6, 7).
- **Refresh-Token im `localStorage` oder im Body:** per XSS abgreifbar; das HttpOnly-Cookie ist
  unsichtbar (7).
- **Serverseitige Sitzungen** (`HttpSession`): das Backend bleibt für alles außer dem Refresh-Cookie
  zustandslos; ein Session-Store wäre ein zweiter Mechanismus neben dem Token (7).
- **Unbegrenzte Rotation ohne Höchstdauer:** eine Sitzung, die nie endet, solange der Browser
  wöchentlich aufgeht; `access-control.md` verlangt eine Höchstdauer (7).
- **Mitgelieferter Keycloak als lokale Verwaltung** (#1368, Variante b): macht Keycloak zur
  Pflichtkomponente gerade für Installationen ohne IdP; Admin-Credentials von Keycloak in OPAA.
- **Eingebetteter Authorization Server** (#1368, Variante c): Sitzungen, Login-Seiten und Consent im
  Backend, die ADR-0025 gerade vermieden hat; erheblicher Konfigurationsumfang.
- **Argon2id:** zusätzliche Abhängigkeit; BCrypt hinter `DelegatingPasswordEncoder` hält den Wechsel
  offen (9).
- **Komplexitätsregeln und periodischer Wechsel:** verschieben Passwörter auf Zettel; BSI verlangt
  beides nicht mehr; die Sperrliste ist der Ausgleich (9).
- **Progressive Sperrdauer:** mehr Zustand für wenig Gewinn, solange IP-Grenze, globale Grenze und
  15 Minuten je Konto gelten; nachrüstbar (9).
- **Fehlversuche im Nachweisprotokoll:** von `security-and-compliance.md` ausdrücklich
  ausgeschlossen; drei Jahre unlöschbare Einträge für ein vertipptes Passwort und ein Rohdatenbestand,
  der eine Zählung je Person als Nebenprodukt ermöglicht (9, 13).
- **Kontozustand nach korrektem Passwort nennen:** bestätigte einem Angreifer das Passwort eines
  gesperrten Kontos; Unterrichtung läuft per Mail und Sitzungsmarker (9, 11).
- **Rücksetzweg für Konten in Fehlversuch-Sperre schließen:** schneidet genau die Selbsthilfe ab, die
  „ich habe es vergessen" braucht, und macht die Sperre zur wiederholbaren Aussperrung durch Dritte
  (9, 11).
- **`X-Forwarded-For` unverändert ungeprüft lassen:** hinter dem Compose-nginx wäre jede IP-Grenze am
  Login wirkungslos (9).
- **SMTP-Einstellungen in der Umgebung:** kein Testversand aus der Oberfläche, Neustart je Änderung;
  die Datenbank mit Snapshot ist das Muster der Anbieter (10).
- **Basis-URL als Verwaltungseinstellung** (qnop `general.base_url`): über eine kompromittierte
  Verwaltersitzung umlenkbar; die Umgebung ist die passende Vertrauensstufe (10).
- **Outbox mit Wiederholung:** die Auth-Mails brauchen ein sofortiges Ergebnis; ein Digest kann
  später eine Warteschlange ergänzen (10).
- **Zähler „Konten ohne Ablaufdatum" als einzige Form der Auflage:** eine Zahl ist kein Vorgang;
  Filter, Erinnerungen und Wiedervorlage sind es (11).
- **Kontenliste mit allen Konten und exakter „letzter Anmeldung":** eine nach Aktivität sortierbare
  Beschäftigtenliste — genau der Auswertungspfad, den `security-and-compliance.md` ausschließt; und
  `last_login_at` ist ohnehin ein Aktivitäts-, kein Anmeldezeitstempel (11).
- **Exportierbare Kontenliste** (Betrieb): ein Vollabzug mit anderem Namen; die Prüferfrage
  beantwortet die gefilterte Liste (11).
- **Hinweis-Mail „Konto existiert bereits" bei der Registrierung:** selbst ein Aufzählungskanal (11).
- **Selbstregistrierung ohne Domänenliste und ohne Befristung:** jeder mit irgendeinem Postfach
  bekäme ein Konto ohne Ablauf; genau die Konten, die die Auflage aushöhlen (11).
- **Hartes Löschen mit Kaskade auf alles** (qnop): OPAA hat einen definierten DSGVO-Pfad; bis er als
  Dienst existiert, bleibt Löschen auf Konten ohne Besitz beschränkt (11).
- **Keine Übergabe an eine Anbieteridentität:** wiederholt das Migrationsproblem von `basic` für
  Zuschnitt B. **Übergabe mit eingetipptem Subject durch den Verwalter allein:** ein Verwalter mit
  einem Testkonto beim Anbieter läse fremde private Inhalte; ein Tippfehler wäre endgültig.
  **Vier-Augen-Prinzip** (Personalrat): löst das Tippfehler- und das Wissensproblem nicht; die Person
  selbst als zweite Partei löst beides (12).
- **Erfolgreiche Anmeldungen aller lokalen Systemverwalter auditieren** (Betrieb): persönliche
  Verwalterkonten sind Personen; die Prüferfrage gilt dem Notfallzugangsmittel (13).
- **Profil umbenennen** (`oidc` → `prod`): bricht jede Bereitstellung für einen Namen (8).

## Referenzen

- #1368 (Diskussionsgrundlage und Beschluss vom 10.09.2026), #1529 (Epic), #1531 (dieses ADR)
- [Stakeholder-Bewertungen Betrieb und Personalrat](../discussions/discussion-lokale-benutzerverwaltung-stakeholder.md)
- [ADR-0005](0005-authentication-strategy.md) — Authentifizierungsstrategie, Historie `basic`
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md) — Audit-Ereignisse
- [ADR-0019](0019-minimale-benachrichtigungsinfrastruktur.md) — Benachrichtigungen
- [ADR-0021](0021-single-instance-betrieb.md) — prozesslokale Fundstellen und Scheduler
- [ADR-0025](0025-mehrere-oidc-anbieter.md) — mehrere OIDC-Anbieter, Identität `(issuer, subject)`
- [ADR-0029](0029-schlankes-backend-laufzeitimage.md) — Container ohne Shell
- `docs/features/access-control.md` — Anmeldung und Identität, Lebenszyklus, Sitzungsverwaltung,
  Erzwungene Neuanmeldung
- `docs/features/security-and-compliance.md` — Protokollsatz, geschlossene Ereignisliste, „Was
  ausdrücklich nicht protokolliert wird", Auszug für die Personalvertretung, Löschung eines
  Benutzerkontos, Mitbestimmungsfähigkeit
- qnop: `docs/adr/0022-security-crypto-foundation.md`, `0023-identity-and-access-model.md`,
  `0025-application-settings-architecture.md`, `0026-jwt-session-and-revocation.md`,
  `0027-auth-rate-limiting.md`; `io.qnop.security` (`Hkdf`, `JwtKeyService`, `ValidSecret`),
  `io.qnop.service.{JwtTokenService,RefreshTokenService,TokenRevocationService,AdminUserService}`,
  `io.qnop.service.auth`, `io.qnop.service.mail`, `io.qnop.web.security`
  (`DelegatingJwtDecoder`, `PasswordChangeRequiredFilter`, `ratelimit`),
  `io.qnop.bootstrap.AdminInitializationRunner`
- BSI IT-Grundschutz ORP.4 (Identitäts- und Berechtigungsmanagement), Anforderung A8 (Regelungen für
  Passwörter)
