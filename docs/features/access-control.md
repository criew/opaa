# Identität, Rechte & Mandanten: Systemverwaltung, Anmeldung und Kontenlebenszyklus

> **Status: Entwurf — wesentliche Festlegungen stehen, einzelne Fragen sind offen.**
>
> **Phasenlage:** Phase 1. Anmeldung über den Verzeichnisdienst, Kontenlebenszyklus, Gruppen als
> Rechtesubjekt und rechtebewusste Suche gehören zum Fundament; ohne sie ergibt ein Start in einer
> Behörde keinen Sinn. Feinschliff an Sitzungsverwaltung und Rezertifizierung folgt in Phase 2.

> **Abgrenzung:** Das Space-, Asset- und Rechtemodell ist in
> [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) beschrieben und entschieden und wird hier
> **nicht wiederholt**. Die Sicherheits-, Nachweis- und Prüfbarkeitsthemen — revisionssicheres
> Protokoll, Rechtehistorie, DSGVO-Vollständigkeit, C5-Fähigkeit, Mitbestimmungsfähigkeit — stehen in
> [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md). Dieses Dokument behandelt
> Systemverwaltung, Identität und den Lebenszyklus der Konten. Das frühere Workspace-Konzept ist
> abgelöst.

## Motivation

Nicht alles Organisationswissen ist für jeden bestimmt. Ein Haus hat öffentliche Richtlinien,
fachbereichsbezogene Dokumentation, besonders geschützte Bestände und Unterlagen, die nur der Revision
zugänglich sind.

Wer was sehen darf, regelt das Rechtemodell in
[Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md). Wer das System verwaltet, woher Identitäten
kommen, wie sie entstehen und wie sie wieder verschwinden, regelt dieses Dokument. Der Lebenszyklus ist
dabei der sicherheitskritische Teil: Ein Konto, das nach dem Ausscheiden weiterbesteht, ist ein
Zugangsweg, den niemand mehr beobachtet.

---

## Überblick

Die Zugangskontrolle in OPAA hat vier Schichten:

1. **Asset-Rechte** — wer auf Wissensbibliotheken, Agenten und Prompt-Bibliotheken zugreift →
   [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md)
2. **Space-Rollen** — wer in einem Arbeitsraum mitarbeitet, kuratiert und verwaltet → ebenda
3. **Systemverwaltung** — wer das System als Ganzes verwaltet → dieses Dokument
4. **Identität und Kontenlebenszyklus** — woher Nutzer kommen, wie sie sich anmelden und wie ihr Zugang
   endet → dieses Dokument

Die fünfte Schicht — der **Nachweis**, dass diese vier Schichten gewirkt haben — steht in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md).

---

## Systemverwaltung

### System-Admin-Rolle

Über den Space- und Asset-Rollen steht die **System-Admin**-Rolle für organisationsweite Administration.
Sie ist eine systemweite Rolle und wird auf der Benutzer-Entität gespeichert.

System-Admins können:

- Gruppengebundene Spaces (`memberSource = GROUP`) anlegen — alle anderen Spaces legen Nutzer selbst an
  (der Standard-Space entsteht automatisch bei der ersten Anmeldung); Löschbefugnis regelt der
  Space-Verantwortliche, siehe [Löschung eines Space](#löschung-eines-space)
- Konnektoren konfigurieren
- Quell-Zuordnungen definieren — welche Quelle in welche Wissensbibliothek indiziert
- Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken setzen
- Benutzerverzeichnis-Synchronisation und Gruppen konfigurieren
- Globale Modell-Policies und Governance-Einstellungen setzen
- Assets im Zustand „Nachfolge offen" einer neuen Zuständigkeit zuweisen

**System-Admins existieren je Organisation.** Die Mandantengrenze gilt auch für sie; es gibt keine
organisationsübergreifende Sicht.

Wichtige Abgrenzung: System-Admins verwalten das System, sind aber **nicht automatisch berechtigt, jeden
Inhalt zu lesen**. Der Zugriff auf Wissensbibliotheken folgt der Rechteliste des Assets. Wo eine Übernahme
nötig ist (offene Nachfolge, Offboarding), ist sie ein protokollierter Verwaltungsakt und keine
stillschweigende Leseberechtigung. Private Inhalte bleiben auch dabei unlesbar — in jedem Space.

### Dokumentenfluss: Konnektoren gegen Benutzer-Uploads

Die zwei Wege, auf denen Dokumente in OPAA gelangen, haben unterschiedliche Autorisierungsanforderungen:

- **Konnektoren (System-Admin):** System-Admins konfigurieren Konnektoren und legen fest, welche Quelle in
  welche Wissensbibliothek indiziert. Der primäre Weg für automatisierte Massenaufnahme.
- **Manuelle Uploads:** Wer an einer Wissensbibliothek mindestens `EDITOR` ist, kann Dokumente hochladen —
  in eine eigene Bibliothek oder in jede andere, an der er dieses Recht hat.

Wesentliche Verschiebung gegenüber dem alten Modell: Der System-Admin entscheidet, **wohin** indiziert
wird; der Bibliotheks-Eigentümer entscheidet, **wer es sieht**.

**Die Freigabe-Obergrenze ist die einzige technische Sicherung zwischen „Fachverfahrensdaten eingespeist"
und „organisationsweit lesbar" und deshalb genau zu bestimmen:**

- Gedeckelt werden `visibility`, `listed` und Grants an Gruppen oberhalb einer festgelegten Größe.
- Wird die Obergrenze **nachträglich gesenkt**, werden bereits erteilte weitergehende Grants
  **ausgesetzt, nicht stillschweigend entzogen**: Sie stehen auf einer Liste des Bibliotheks-Eigentümers
  und wirken nicht mehr, bis er sie anpasst. Für eine Prüfung ist das der Unterschied zwischen „behoben"
  und „nicht behoben"; ein stilles Weiterwirken wäre das eine, ein stiller Entzug das andere Extrem.
- Eine Bibliothek, die sowohl aus einem Konnektor als auch aus manuellem Upload gespeist wird, **trägt die
  Obergrenze ebenfalls** — sonst wäre der manuelle Upload der Weg an ihr vorbei.

### Löschung eines Space

Ein Space zu löschen ist unter dem neuen Modell ein vergleichsweise harmloser Vorgang: Er vernichtet
**keine Dokumente**, weil diese in Wissensbibliotheken liegen, die anderen gehören. Gelöst werden die
Assoziationen; die Assets selbst bleiben unberührt.

**Space-eigene Inhalte werden dabei nicht gelöscht.** Die Regel „Zurückziehen statt Löschen" gilt auch
hier: Geteilte Chats und Artefakte werden zurückgezogen und bleiben für ihre Autoren und im Nachweis
erhalten; private Inhalte bleiben ohnehin ihren Erstellern erhalten. Andernfalls wäre die Space-Löschung
ein Massenlöschpfad für die Arbeitsspuren fremder Beschäftigter — genau das, was der Schutz an anderer
Stelle ausschließt. Ein Protokolleintrag hält den Vorgang fest.

Löschen darf nur der im Space als Verantwortlicher hinterlegte Nutzer oder ein System-Admin.

---

## Anmeldung und Identität

Benutzer authentifizieren sich über:

- **Single Sign-On (SSO)** — OIDC (empfohlen für den Regelbetrieb; SAML nur über eine Föderation)
- **Lokale Konten** — E-Mail-Adresse und Passwort, von OPAA selbst geführt (optional, Standard aus;
  das Konto der Systemverwaltung ist immer ein lokales Konto)
- **API-Tokens** — für programmatischen Zugang (noch nicht gebaut)

**Empfohlen ist die SSO-Anbindung an das im Haus vorhandene Identitätsmanagement.** Sie ist nicht nur
bequemer, sondern die Voraussetzung dafür, dass der Kontenlebenszyklus überhaupt an einer Stelle geführt
werden kann. Lokale Konten laufen am zentralen Ausscheideprozess vorbei; jedes dauerhaft betriebene
lokale Konto ist deshalb eine Ausnahme, die begründet und regelmäßig überprüft gehört. OPAA macht das
sichtbar statt es zu verbieten: Jedes lokale Konto trägt einen Anlagegrund und ein Ablaufdatum, die
Verwaltung zeigt dauerhaft, wie viele lokale Konten ohne Ablaufdatum bestehen, und die lokale
Benutzerverwaltung ist im Regelbetrieb abgeschaltet.

> **Lokale Benutzerverwaltung** (Epic #1529, Beschluss aus #1368 vom 10.09.2026): Der
> **Erstadministrator ist immer ein lokales Konto**, das beim allerersten Start entsteht (Einmalpasswort
> einmalig im Log, Wechsel bei der ersten Anmeldung erzwungen); damit ist eine Installation nie ohne
> anmeldefähigen Systemverwalter, auch ohne konfigurierten Identitätsanbieter. Die lokale Verwaltung für
> reguläre Konten ist per Schalter zuschaltbar: Anlegen mit Einladung per E-Mail, Sperren, Zurücksetzen,
> Ablaufdatum, Passwort vergessen, optionale Selbstregistrierung — dafür bekommt OPAA eine
> Mail-Infrastruktur (SMTP). Solange die Verwaltung aus ist, melden sich lokale Systemverwalter über eine
> eigene Anmeldeseite an. Die Architektur legt
> [ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md) fest: lokaler Issuer `urn:opaa:local` mit
> der Konto-UUID als Subject, Backend als Aussteller kurzlebiger Access-Tokens mit rotierendem
> Refresh-Cookie und sofortigem Widerruf, lokaler Anbieter als Anbieterzeile, Rate-Limiting je Adresse
> und je Konto mit Sperre nach Fehlversuchen, keine Zusammenführung über die E-Mail (nur eine
> administrativ angestoßene, von der betroffenen Person selbst durch Anmeldung beim Anbieter
> eingelöste Übergabe eines lokalen Kontos an ihre Anbieteridentität). Kein stiller Eingriff in ein
> Konto: Sperre, Entsperrung, Zurücksetzen, bevorstehender Ablauf und Übergabe werden der Person per
> E-Mail mitgeteilt, und eine beendete Sitzung nennt beim nächsten Aufruf ihren Grund. Der Ist-Stand
> der Umsetzung steht im Epic.

**Lokale Anmeldung (gebaut, #1533).** Das Backend ist Token-Aussteller für lokale Konten:
`POST /api/v1/auth/local/login` prüft E-Mail-Adresse (groß-/kleinschreibungsunabhängig, nur unter
dem lokalen Issuer `urn:opaa:local`) und Passwort und stellt ein HS256-Access-Token (15 Minuten;
Claims `jti`, `iss`, `sub` = Konto-ID, `iat`, `exp`, `email`, `name`, `pcr`, keine Rolle) sowie ein
rotierendes Refresh-Token im Cookie `opaa_refresh` aus (`HttpOnly`, `SameSite=Strict`, `Secure`
nach `OPAA_AUTH_LOCAL_COOKIE_SECURE`, `Path=/api/v1/auth/local`). Jede abgewiesene Anmeldung —
unbekannte Adresse, falsches Passwort, gesperrtes, abgelaufenes oder eingeladenes Konto,
abgeschaltete Verwaltung — ist dieselbe Antwort mit derselben Antwortzeitklasse (Hash-Vergleich
auch gegen einen Dummy-Hash); der Fehlversuchszähler wird atomar geführt, die Sperre nach fünf
Fehlversuchen steht unten (#1535). Anmeldefähig ist nur ein aktives Konto; bei ausgeschalteter
Verwaltung (Schalter = `enabled` der `LOCAL`-Anbieterzeile) nur ein lokaler `SYSTEM_ADMIN`.
`POST …/refresh` rotiert das Cookie innerhalb seiner Familie (Leerlauffrist 7 Tage, absolute
Höchstdauer 30 Tage, für lokale Systemverwalter 4 h / 12 h; keine Rotation verlängert das
Familienende); die erneute Vorlage eines bereits rotierten Tokens widerruft **alle** Familien und
alle Access-Tokens des Kontos (`REUSE_DETECTED`), wird als `LOCAL_SESSION_REVOKED` protokolliert und
als Warnung ins Anwendungslog geschrieben (Konto-ID, nie die Adresse). Zwei gleichzeitige Vorlagen
desselben Tokens gelten als Wiederverwendung — die Oberfläche serialisiert `refresh` (ein Aufruf zur
Zeit). Ein `refresh` für ein nicht mehr anmeldefähiges Konto oder ein reguläres Konto bei
abgeschalteter Verwaltung wird wie ein unbekanntes Token behandelt (`401`, Cookie gelöscht). `POST …/logout` widerruft Familie und das
vorgelegte Access-Token sofort (`jti`-Sperrliste), ohne Protokolleintrag. `refresh` und `logout`
verlangen das CSRF-Double-Submit-Token (Cookie `XSRF-TOKEN`, Header `X-XSRF-TOKEN`; fehlt es:
`403` mit Code `CSRF_TOKEN_MISSING`); kein anderer Endpunkt tut das. `login`, `refresh` und
`logout` werden ohne Bearer-Token aufgerufen — ein abgelaufenes Bearer-Token würde vor dem Handler
mit `401` abgewiesen. `POST …/change-password` (Bearer) prüft das aktuelle Passwort, wendet die
Passwortrichtlinie an (Mindestlänge aus den Einstellungen, höchstens 64 Zeichen/72 Byte, nicht die
eigene Adresse, nicht in der mitgelieferten Sperrliste häufiger Passwörter — Verstöße als
`fieldErrors` mit Codes `TOO_SHORT`, `TOO_LONG`, `EQUALS_EMAIL`, `TOO_COMMON`), setzt alle bis zum
Wechsel ausgestellten Access-Tokens außer Kraft, widerruft alle Refresh-Familien
(`PASSWORD_CHANGED`), protokolliert `LOCAL_PASSWORD_CHANGED` und antwortet wie eine Anmeldung: neues
Token ohne `pcr` und ein neues Refresh-Cookie für die laufende Sitzung. Ein Token mit `pcr = true` erreicht außer `/api/v1/auth/local/*` nichts: jede andere
Anfrage endet mit `403`, Code `PASSWORD_CHANGE_REQUIRED` und dem Anlass (`INITIAL`,
`ADMIN_RESET`, `SECURITY`). Lokale Tokens prüft ein eigener Decoder vor der Anbieter-Registry,
unabhängig von der `LOCAL`-Zeile und ihrem Schalter; jede Abweisung nennt im Header
`WWW-Authenticate` ihren Grund als `error_description`: `local_accounts_disabled`,
`account_locked:<admin|failed_logins|inactivity>`, `account_expired`, `account_not_active`,
`session_revoked[:<admin_lock|password_changed|admin_reset|reuse_detected|handed_over>]`,
`unknown_account`, `malformed_token` — die Oberfläche unterscheidet sie so vom abgelaufenen Token
und startet keinen Erneuerungsversuch. Nur ein anmeldefähiges (`ACTIVE`) Konto passiert den
Validator; der Anlass von `session_revoked` ist der letzte Verwaltungsakt an den Refresh-Familien
des Kontos. Der lokale Issuer legt nie ein Konto an (unbekanntes `sub` → `401`) und
schreibt E-Mail und Anzeigename nicht aus dem Token zurück. `GET /api/v1/auth/config` führt die
`LOCAL`-Zeile nicht unter `providers`, sondern als `localAccounts { enabled,
selfRegistrationEnabled, passwordResetEnabled, passwordMinLength }`; die beiden Selbstbedienungs-
flüsse gelten nur mit gesetzter öffentlicher Basis-URL als verfügbar. Ein täglicher Lauf löscht
Zeilen der drei Token-Tabellen spätestens sieben Tage nach Ablauf oder Widerruf; Inaktivitätssperre
und Ablauf-Erinnerungen hängen sich dort ein (#1537, #1538). Alles davon existiert nur im
`oidc`-Betriebsmodus; der `dev`-Modus kennt weder Aussteller noch Endpunkte. Die Anmeldeseite
(#1539) und die Systemverwalter-Anmeldung (#1534) folgen.

**Erstadministrator und Notanker-Konto (gebaut, #1534).** Beim allerersten Start im
`oidc`-Betriebsmodus legt `LocalAdminSeeder` — vor dem Webserver, gegen Wiederholung durch eine
Markierungszeile gesichert — die `LOCAL`-Anbieterzeile („Lokale Konten", deaktiviert, nie
Standard) und das lokale Notanker-Konto der Systemverwaltung an: Adresse aus
`OPAA_INITIAL_ADMIN_EMAIL` (der alte Vorgabewert `admin@opaa.local`, eine fehlende oder keine
E-Mail-Adresse werden abgelehnt — Fehler im Log, keine Markierung, Anlage beim nächsten Start),
Anzeigename „Systemverwaltung", Anlagegrund „Notanker-Konto der Systemverwaltung", `is_bootstrap`,
`SYSTEM_ADMIN`, Adresse als bestätigt. Auf einer **Neuinstallation** (keine Konten in `users` —
nicht die OIDC-Übernahmemarkierung, die im selben Start auch nach einer abgelehnten Adresse
entsteht) ist das Konto scharf: mit `OPAA_INITIAL_ADMIN_PASSWORD`, falls gesetzt (CI/E2E, kein
erzwungener Wechsel), sonst mit einem erzeugten Einmalpasswort, das **einmalig** als deutlich
markierter Block ins Anwendungslog geschrieben wird und bei der ersten Anmeldung gewechselt werden
muss (`pcr` mit Anlass `INITIAL`). Auf einer **Bestandsinstallation** entsteht das Konto als
`INVITED` ohne Passwort und ohne Log-Ausgabe; `OPAA_LOCAL_ADMIN_RESET=force` aktiviert es beim
nächsten Start einmalig (entsperrt, Ablauf gelöscht, neues Einmalpasswort, `SYSTEM_ADMIN`
wiederhergestellt, alle Sitzungen widerrufen; ein gelöschtes Konto wird neu angelegt) — auditiert
als `LOCAL_ADMIN_RESET`; die Anlage als `LOCAL_ADMIN_SEEDED`. Jede erfolgreiche Anmeldung mit dem
Notanker-Konto (erkannt über `is_bootstrap`, nicht über die Adresse) ist ein Audit-Ereignis
`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN`; die Mail an die übrigen Systemverwalter folgt mit #1537. Die
Erstadministrator-Regel für OIDC-Konten ist aufgehoben: `InitialAdminPolicy` wirkt nur noch für den
Dev-Issuer (`dev-admin` bleibt Systemverwalter); IdP-Konten werden Systemverwalter allein durch
Rollenvergabe. `OPAA_OIDC_BOOTSTRAP=force` funktioniert bis zum 31.03.2027 weiter und warnt bei
jeder Verwendung mit Ersatz und Datum. Mit `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` (IPv4/IPv6, leer =
keine Beschränkung) melden sich lokale `SYSTEM_ADMIN`-Konten nur aus den genannten Netzen an — die
Abweisung ist dieselbe wie bei einem falschen Passwort, liegt aber vor der Fehlversuchszählung (aus einem nicht erlaubten Netz lässt sich das Konto nicht sperren); geprüft
wird die aufgelöste Client-Adresse (siehe Rate-Limiting unten).

**Rate-Limiting und Kontosperre (gebaut, #1535).** Die Client-Adresse jeder Anfrage bestimmt
`io.opaa.security.ClientIpResolver` (`TrustedProxyClientIpResolver`), die eine Stelle für
Rate-Limits und die Netzbeschränkung: `X-Forwarded-For` zählt nur, wenn die Verbindung selbst aus
einem Netz in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` kommt (Vorgabe leer = Header ignoriert), und
dann ist der Client der erste Eintrag von rechts, der kein vertrauter Proxy ist — der Eintrag, den
der nächste vertraute Proxy angehängt hat; ein vom Client mitgeschickter Anfang der Kette und
weitere vertraute Zwischenstationen zählen nicht. Das Ergebnis ist immer eine numerische Adresse
(ein Eintrag, der keine ist, fällt auf die Verbindungsadresse zurück; kein DNS). Verbindungsadresse
und Header liest der Resolver unterhalb aller Request-Wrapper, denn Springs `ForwardedHeaderFilter`
(`server.forward-headers-strategy: framework`) schreibt `getRemoteAddr()` bereits aus dem
**linkesten** `X-Forwarded-For`-Eintrag um und versteckt den Header — `getRemoteAddr()` direkt zu
lesen wäre von jedem Client wählbar; kein Code liest es mehr direkt. `0.0.0.0/0` und
`::/0` lehnt der Start ab; der `oidc`-Betriebsmodus warnt bei leerer Liste mit der Folge im
Klartext; mehrere `X-Forwarded-For`-Zeilen gelten als eine Kette, ein eingehendes `Forwarded`
(RFC 7239) verwirft der Frontend-nginx. Der Compose-Stack hat dafür ein festes Netz und eine feste
Adresse des Frontend-Containers (`docker-compose.yml`, `OPAA_COMPOSE_SUBNET`/
`OPAA_COMPOSE_FRONTEND_ADDRESS`), `.env.docker.example` vertraut genau dieser Adresse (`/32`, nicht
dem Netz — die Gateway-Adresse spräche jeder Host-Prozess), und der Backend-Port ist nur an
`127.0.0.1` gebunden. `GET /api/v1/admin/diagnostics/client-address`
(Systemverwaltung) zeigt für genau diese Anfrage Verbindungsadresse, empfangenen Header, ob er
gezählt hat, und die aufgelöste Adresse. Der bestehende `RateLimitFilter` (Sliding Window über
Caffeine, ein Limiter je Regel, Schlüsselzahl begrenzt — darüber fällt die Grenze für die
vergessenen Schlüssel offen aus, was die globale Grenze auffängt) nutzt dieselbe Auflösung, matcht
gegen den dekodierten Pfad innerhalb der Anwendung (kein `X-Forwarded-Prefix`, keine
Prozentkodierung führt an einer Regel vorbei), prüft erst das Kontingent des Clients und verbraucht
die globale Grenze nur für durchgelassene Anfragen, zählt IPv6-Clients je `/64` und antwortet auf
jede Überschreitung mit `429`, `Retry-After` und dem einen deutschen Fehlertext; CORS-Preflights
zählen nicht. Grenzen der lokalen Anmeldung (`opaa.rate-limit.local-auth.*`): Login 10/60 s je
Adresse, Refresh 30/60 s je Adresse, Passwortwechsel 5/300 s je Konto (vor dem Vergleich des
aktuellen Passworts), Registrierung 5/3600 s je Adresse und 3/3600 s je E-Mail-Adresse, „Passwort
vergessen" 5/3600 s je Adresse und 3/3600 s je E-Mail-Adresse, Passwort setzen 10/900 s je
Adresse; Login, Registrierung und „Passwort vergessen" haben zusätzlich eine globale Grenze je
Fenster (100 bzw. 50), deren Überschreiten eine Warnung und die Metrik `opaa.rate_limit.rejected`
(`limit`, `scope=global`) erzeugt. Die adress- und kontobezogenen Grenzen liegen in
`LocalAuthRateLimiter` (E-Mail-Adressen nur als Hash im Speicher); die Endpunkte der
Selbstbedienung (#1538) rufen `requireAddressAllowance` auf, die Pfadregeln stehen bereit. Nach
fünf Fehlversuchen (`OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS`) sperrt `LocalAccountLockoutListener`
das Konto für feste 15 Minuten (`OPAA_AUTH_LOCAL_LOCKOUT_DURATION`, `locked_reason =
FAILED_LOGINS`, keine progressive Verlängerung; die Sperre setzt den Zähler auf null, während der
Sperre wird nichts gezählt — nach ihrem Ende gilt wieder das volle Kontingent): auditiert **einmal** als `LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`
(Systemakteur `local-auth`, Subjekt als Pseudonym, ohne Zähler) und als Metrik
`opaa.auth.local_account_lockout`; der einzelne Fehlversuch steht nur im Anwendungslog mit der
Konto-ID, nie der Adresse. Die Anmeldung antwortet während der Sperre exakt wie bei einem
falschen Passwort; Tokens des gesperrten Kontos weist der Validator über den Zustand ab
(`account_locked:failed_logins`). Das Ende der Sperre erzeugt kein Ereignis; der Zähler geht auch
bei jeder erfolgreichen Anmeldung auf null. Keine Mail bei dieser Sperre; „Passwort vergessen"
bleibt offen, und ein eingelöster Rücksetzlink hebt sie auf (#1538).

**Aussperrschutz (gebaut, #1534).** `LocalAdminAvailabilityGuard` ist die eine Stelle für „nie ohne
anmeldefähigen Systemverwalter": Unter dem Advisory-Lock je Organisation zählt er nur
**anmeldefähige** Systemverwalter — lokale Konten nach derselben `isLoginCapable`-Regel wie die
Anmeldung (aktiv, mit Passwort, nicht gesperrt oder abgelaufen) und Konten eines **aktivierten**
OIDC-Anbieters (im `dev`-Modus: des Dev-Issuers). Über ihn laufen der Rollenentzug per Token
(`TokenRoleSynchronizer`) und per Verwaltung (`POST /api/v1/admin/users/{id}/role`, 409 mit Code
`LAST_LOGIN_CAPABLE_ADMIN`) sowie das Deaktivieren und Löschen jedes aktivierten
OIDC-Anbieters; Sperren, Befristen und Löschen lokaler Systemverwalter folgen mit #1537. Die
`LOCAL`-Zeile ist über die Anbieter-API weder löschbar noch Standard, ihr Issuer nicht änderbar
(nur der Anzeigename), Adressprüfung und Verbindungstest entfallen für sie; ihr
Aktivieren/Deaktivieren ist der Schalter der lokalen Verwaltung (`LOCAL_ACCOUNTS_ENABLED`/
`_DISABLED`), und das Abschalten beendet die Sitzungen aller regulären lokalen Konten (ein
`UPDATE` von `password_invalidated_before`; `LOCAL_SESSION_REVOKED` je Konto mit tatsächlich
aktiver Sitzung), nicht die der Systemverwalter. Der erste OIDC-Anbieter wird
auch neben der `LOCAL`-Zeile automatisch Standard; der Standard kann deaktiviert oder gelöscht
werden, sobald er der letzte aktivierte OIDC-Anbieter ist — nur mit `acknowledgeLastProvider=true`
(sonst 409 `LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED`) und nur, wenn ein lokales
Systemverwalterkonto mit Passwort besteht (sonst 409 `LAST_LOGIN_CAPABLE_ADMIN`).

Die Mandantengrenze gilt auch für die Anmeldung: Eine Identität gehört zu **genau einer** Organisation.
Es gibt kein Konto, das mehrere Mandanten sieht, und keinen Wechsel zwischen ihnen innerhalb einer
Sitzung.

> **Mehrere OIDC-Anbieter** (Epic #1294): Eine Installation kann mehrere Identitätsanbieter
> gleichzeitig anbieten — die Anmeldeseite zeigt sie zur Auswahl. Die Architektur legt
> [ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md) fest: Anbieter in der Datenbank mit
> Admin-Oberfläche, Identität strikt als `(Issuer, Subject)` ohne Zusammenführung über die E-Mail,
> Erstadministrator-Regel nur für den Standardanbieter, Verzeichnisabgleich an den Standardanbieter
> gebunden. Der Ist-Stand der Umsetzung steht im Epic.

**Anbieterverwaltung (gebaut, #1329).** Die Identitätsanbieter liegen in der Tabelle
`oidc_providers` und werden über die Admin-API `/api/v1/admin/oidc-providers` (nur `SYSTEM_ADMIN`)
gepflegt: Anzeigename, Issuer-URI (eindeutig), Client-ID, optional die Backend-seitige
JWK-Set-Adresse, die Claim-Zuordnung, Reihenfolge, Standardanbieter, aktiviert/deaktiviert. Jede
Änderung ist ein Audit-Ereignis (`OIDC_PROVIDER_*`) und wirkt ohne Neustart des Backends: Das
Backend prüft jedes Token gegen den Anbieter seines Issuers, ein deaktivierter Anbieter wird ab dem
nächsten Token abgewiesen (`unknown_issuer`). Der erste Anbieter ist automatisch der Standardanbieter;
er kann weder deaktiviert noch gelöscht werden, bevor ein anderer aktivierter Anbieter Standard ist.
Die Issuer-URI eines Anbieters, über den bereits Konten angelegt wurden, ist nicht änderbar. Beim
ersten Start im `oidc`-Modus übernimmt OPAA den bisherigen `OPAA_OIDC_*`-Anbieter einmalig als
Standardanbieter „Verzeichnisdienst"; `OPAA_OIDC_BOOTSTRAP=force` stellt ihn im Notfall wieder
her. Vom Betreiber eingegebene Adressen durchlaufen eine eigene Adressprüfung
(`OPAA_OIDC_TARGET_VALIDATION_*`); die Bootstrap-Adressen sind immer erlaubt. Ein Verbindungstest
prüft Discovery-Dokument und JWK-Set vor dem Speichern. Die Anmeldeseite (#1332) und die
Verwaltungsoberfläche (#1333) folgen.

**Kontenmodell je Anbieter (gebaut, #1330).** Die Identität eines Kontos ist das Paar
`(Issuer, Subject)`. Dieselbe Person bei zwei Anbietern hat zwei Konten mit getrennten
persönlichen Spaces, Systemrollen und Space-Rechten — eine Zusammenführung über die E-Mail findet
nie statt, auch nicht stillschweigend bei gleicher Adresse (Anbieter B kann kein Konto aus Anbieter
A übernehmen). Die Erstadministrator-Regel (`OPAA_INITIAL_ADMIN_EMAIL`) greift nur beim Anlegen
eines Kontos und nur über den **Standardanbieter** (im `dev`-Modus: den Dev-Issuer); dieselbe
Adresse aus einem zweiten Anbieter ergibt einen regulären Nutzer, ebenso ein Konto, das angelegt
wird, solange noch kein Standardanbieter existiert — beides wird als Warnung protokolliert, da die
Regel nur einmal, beim Anlegen, greift. Ein Token eines deaktivierten oder gelöschten Anbieters wird
mit `401` und `error_description="unknown_issuer"` abgewiesen, sodass die Oberfläche den Fall vom
abgelaufenen Token unterscheiden kann.

**Claim-Zuordnung je Anbieter (gebaut, #1331).** Jede Anbieterzeile legt fest, aus welchen
Token-Claims E-Mail und Anzeigename gelesen werden (Vorgabe `email`/`name`, Rückfall
`preferred_username`, nie das Subject) und optional, aus welchem Claim Rollen und Gruppen kommen —
dieselbe Token-Struktur ergibt unter zwei Anbietern zwei verschieden gelesene Identitäten. Ist ein
**Rollen-Claim** gesetzt (Pfad in Punktnotation, z. B. `realm_access.roles`, mit den Rollenwerten
für `SYSTEM_ADMIN` und `AUDITOR`), ist der Anbieter für diese Systemrollen führend: Bei jeder
Anfrage wird die Rolle aus dem Token abgeleitet (`SYSTEM_ADMIN` vor `AUDITOR`, sonst regulärer
Nutzer) und nur bei Abweichung geschrieben — als Rollenänderung mit Audit-Ereignis unter dem
Systemprozess-Akteur `identity-provider`. Drei Sicherungen: Der letzte `SYSTEM_ADMIN` wird nie per
Token entzogen (bedingter, je Organisation serialisierter `UPDATE`; der abgelehnte Entzug wird
protokolliert und als `SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED` auditiert), die manuelle Rollenvergabe
ist für Konten eines solchen Anbieters gesperrt (409), und die Oberfläche (#1333) verlangt beim
Setzen des Rollen-Claims eine Bestätigung. `AUDITOR` ist nicht geschützt. Ist ein
**Gruppen-Claim** gesetzt, werden die Gruppennamen des Tokens bei jeder Anmeldung zu
Mitgliedschaften in Gruppen der Art „Gruppe aus dem Identitätsanbieter" (`IDENTITY_PROVIDER`) im
Namensraum des Anbieters (`oidc:<Anbieter-ID>:<Name>`, Namen bis 213 Zeichen): gleichnamige
Gruppen zweier Anbieter sind zwei Gruppen, ein Anbieter erreicht nie die Gruppen eines anderen;
Mitgliedschaften folgen dem Token (Historie `IDENTITY_PROVIDER_ADDED`/`_REMOVED`, Audit unter
`identity-provider`), die Gruppen selbst bleiben bestehen und sind in der Gruppenverwaltung
schreibgeschützt; sie sind weder Gegenstand des Verzeichnisabgleichs noch als „Sicht als"-Bereich
wählbar. Der **Verzeichnisabgleich** ist an den Standardanbieter gebunden: Er löst die Subjects
des Verzeichnisses nur unter dessen Konten auf (ein gleichnamiges Subject eines zweiten Anbieters
erbt keine Mitgliedschaft) und verwaltet ausschließlich Organisationseinheiten; ohne
Standardanbieter bricht ein Lauf ohne Änderungen ab.

**Anmeldeseite mit mehreren Anbietern (gebaut, #1332).** `GET /api/v1/auth/config` liefert ohne
Anmeldung die aktivierten Anbieter, deren Schlüssel das Backend abrufen konnte — Anzeigename,
Issuer-URI (zugleich die Authority des Anmeldeflusses), Client-ID, Standard-Kennzeichen und
Reihenfolge, nichts über Claim-Zuordnung oder Konten. Die Anmeldeseite zeigt je Anbieter eine
Schaltfläche in der konfigurierten Reihenfolge; vorgeschlagen (die eine primäre Schaltfläche)
wird der zuletzt im Browser benutzte Anbieter, sonst der Standardanbieter, sonst der erste. Mit
genau einem Anbieter bleibt es beim direkten Einstieg. „Mit anderem Konto anmelden" schickt
`prompt=login` an den vorgeschlagenen Anbieter. Die SPA hält je Anbieter einen eigenen
OIDC-Client; der Anbieter des laufenden Flusses und der aktiven Sitzung ist je Tab gemerkt
(`sessionStorage`), der zuletzt benutzte nur als Vorschlag (`localStorage`). Der Callback
`<Origin>/auth/callback` ist für alle Anbieter derselbe; wurde der Anbieter während des Flusses
deaktiviert, erklärt die Seite das und führt zurück zur Anmeldung. Die Abmeldung ist ein
RP-initiierter Logout beim Anbieter der aktiven Sitzung; ein Anbieter ohne
`end_session_endpoint` endet in einer lokalen Abmeldung mit Hinweis. Antwortet das Backend auf
ein Token mit `unknown_issuer` (Anbieter deaktiviert oder gelöscht), wird die Sitzung ohne
Erneuerungsversuch beendet und der Grund benannt.

**Verwaltungsoberfläche (gebaut, #1333).** Unter Administration → Identitätsanbieter (nur
`SYSTEM_ADMIN`) stehen die Anbieter in Anmeldereihenfolge mit Standard-Kennzeichen, Zustand
(erreichbar, nicht erreichbar mit Grund aus der Registry, deaktiviert) und dem Hinweis, wenn ein
Anbieter die Rollen führt. Reihenfolge per Pfeilschaltflächen, Standardanbieter wechseln,
aktivieren/deaktivieren und löschen jeweils mit Konsequenz-Hinweis (Nutzer des Anbieters können
sich nicht mehr anmelden, Konten bleiben); der Standardanbieter bietet weder Deaktivieren noch
Löschen an. Der Formulardialog führt Anzeigename, Issuer-URI, Client-ID, die Backend-seitige
JWK-Set-Adresse (als Vertrauensanker benannt) und die Claim-Zuordnung — kein Secret-Feld, die SPA
ist Public Client. Das Setzen eines Rollen-Claims verlangt eine ausdrückliche Bestätigung; ein
Verbindungstest prüft Discovery-Dokument und JWK-Set vor dem Speichern; Fehlermeldungen des
Backends (etwa die verweigerte Issuer-Änderung eines Anbieters mit Konten) erscheinen im Dialog.
Eine Anleitung nennt die aus dem eigenen Origin zusammengesetzte Weiterleitungs-URI
`<Origin>/auth/callback` und den Origin, den CSP-Schritt (`OPAA_CSP_CONNECT_SRC_EXTRA`, Neustart
des Frontend-Containers) und die Adress-Allowlist des Backends. Im `dev`-Modus weist die Seite
darauf hin, dass Anbieter erst im OIDC-Modus wirken.

---

## Verzeichnisdienst: Synchronisation und Kontenlebenszyklus

### Was übernommen wird

OPAA gleicht mit dem Verzeichnisdienst ab — bevorzugt über eine Bereitstellungsschnittstelle nach
**SCIM**, die Änderungen aktiv meldet, ersatzweise über einen wiederkehrenden Abgleich:

```
Abgleich: ereignisgesteuert, ersatzweise turnusmäßig (z. B. alle 6 Stunden)

Aus dem Verzeichnis:
  - Benutzernamen und E-Mail-Adressen
  - Gruppenmitgliedschaften
  - Organisationseinheit (Referat, Abteilung, Amt)
  - Funktionsbezeichnung
  - Kontostatus (aktiv / gesperrt / ausgeschieden)
```

Das Verzeichnis ist die **führende Quelle**. Wer dort gesperrt ist, ist in OPAA gesperrt; ein
abweichender Zustand in OPAA ist kein Zustand, den ein Admin von Hand herstellen können sollte.

**Je Anbieter (ADR-0025):** Der Verzeichnisabgleich gilt für die Konten des **Standardanbieters** —
nur unter ihnen werden die Subjects des Verzeichnisses aufgelöst, und nur seine Organisationseinheiten
entstehen daraus. Ein zweiter Anbieter (Partnerportal, Landesanbieter) hat keinen Verzeichnisabgleich;
seine Gruppen kommen, wenn überhaupt, aus seinem Gruppen-Claim (siehe [„Claim-Zuordnung je
Anbieter"](#anmeldung-und-identität)) und leben im Namensraum dieses Anbieters. Ein gleichnamiges
Subject bei zwei Anbietern ergibt zwei Konten, und nur das des Standardanbieters erhält die
Verzeichnisgruppen.

### Der Lebenszyklus eines Kontos

```
   Anlage im            erster            Rollenwechsel /        Ausscheiden
   Verzeichnis          Login             Gruppenänderung        im Verzeichnis
        │                 │                     │                     │
        ▼                 ▼                     ▼                     ▼
   Konto bereit  →   Konto aktiv   →   Rechte neu berechnet   →   sofort deaktiviert
                                              │                          │
                                              ▼                          ▼
                                    Rechteereignis im Protokoll   Assets: „Nachfolge offen"
```

1. **Anlage.** Ein Konto entsteht durch die Bereitstellung aus dem Verzeichnis, nicht durch eine
   Einladung im Produkt. Wer im Verzeichnis nicht existiert, hat in OPAA nichts. Konten anderer
   Anbieter entstehen bei der ersten Anmeldung über diesen Anbieter — mit eigener Identität
   `(Issuer, Subject)`, ohne Verzeichnisgruppen und ohne Erstadministrator-Regel.
2. **Erste Anmeldung.** Der Standard-Space (`isDefault`) entsteht dabei; eine Wissensbibliothek legt die Person
   bei Bedarf selbst an (siehe [Wissensquellen](./knowledge-sources.md)). Ein Anlaufbestand an
   Assets ergibt sich aus den Gruppen der Person.
3. **Änderung.** Wechselt jemand das Referat, ändern sich seine Gruppenmitgliedschaften — und damit
   seine Rechte, **ohne dass jemand in OPAA etwas tut**. Das ist der Regelfall und der Grund, warum die
   Synchronisation als Rechteereignis behandelt wird (siehe unten).
4. **Ausscheiden.** Verschwindet ein Konto im Verzeichnis oder wird es dort gesperrt, wird der Zugang in
   OPAA **beim nächsten Abgleich automatisch entzogen** — ohne Ticket, ohne Handgriff und ohne
   Bedingung. Das ist die Anforderung, an der der IT-Grundschutz und jede Prüfung als Erstes ansetzen.
   Für Konten anderer Anbieter ist der Hebel der Anbieter selbst (gesperrtes Konto: keine Anmeldung
   mehr, Token-Gruppen enden mit der nächsten Anmeldung) oder das Deaktivieren des ganzen Anbieters in
   der Anbieterverwaltung (ab dem nächsten Token abgewiesen, Konten bleiben).

**Die Deaktivierung wird nie durch offene Eigentumsfragen aufgehalten.** Eine Regel, die verlangt, erst
die Nachfolge für dutzende Assets zu klären, wird am Freitagnachmittag umgangen und schützt dann gerade
nicht. Was mit den Assets geschieht, steht unter [Offboarding](#offboarding).

### Gruppensynchronisation ist ein Rechteereignis

Die übernommenen **Gruppen sind Rechtesubjekt**: Rechte an Assets werden an Nutzer oder an Gruppen
vergeben, und die Verteilungsstufe „Fachbereich" ist ein Grant an die Abteilungs- oder Amts-Gruppe.
Details im [Rechtemodell](./spaces-and-assets.md#gruppen-als-rechtesubjekt).

Daraus folgt eine Festlegung, die leicht übersehen wird: Ein Synchronisationslauf ist **keine technische
Wartungsroutine, sondern eine Rechteänderung im laufenden Betrieb**. Eine einzige geänderte
Gruppenmitgliedschaft kann Zugriff auf ganze Bestände geben oder nehmen.

- Jede **bewirkte** Rechteänderung wird einzeln festgehalten — je Änderung, nicht je Lauf. Ein Lauf, der
  meldet „412 Objekte verarbeitet", beantwortet keine Prüferfrage.
- Änderungen wirken **sofort** auf die rechtebewusste Suche; es gibt keinen zwischengespeicherten
  Rechtestand, der eine Entziehung überdauert.
- Ein Lauf, der eine **auffällig große** Zahl an Entzügen oder Zuweisungen bewirken würde — etwa weil im
  Verzeichnis eine Gruppe umbenannt wurde —, wird angezeigt und ist bestätigungspflichtig, statt
  stillschweigend durchzulaufen. Der häufigste Fehlerfall ist nicht der Angriff, sondern die
  fehlgeschlagene Umstellung.
- Fällt der Verzeichnisdienst aus, gilt der **letzte bekannte Stand weiter**, und der Ausfall wird
  gemeldet. Ein leeres Abgleichergebnis darf nie als „alle Gruppenmitgliedschaften entfallen" gedeutet
  werden.

Die Synchronisation ändert nur die **Herkunft** von Gruppenmitgliedschaften, nicht das Rechtemodell. In
der ersten Ausbaustufe werden Gruppen im System gepflegt.

Der Nachweis, worauf eine Person zu einem beliebigen Stichtag Zugriff hatte, entsteht aus der
Historisierung dieser drei Quellen und ist in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten)
beschrieben.

---

## Sitzungen, Netzbereiche und erzwungene Neuanmeldung

### Einschränkung auf Netzbereiche

Der Zugang lässt sich auf Netzbereiche einschränken (CIDR-Notation), getrennt für die interaktive
Anmeldung und für API-Identitäten:

```
Netzbereiche (organisationsweite Vorgabe):
  interaktiv:   Hausnetz + VPN-Bereich der Dienststelle
  API-Tokens:   je Token eng gesetzt, Voreinstellung: nur Hausnetz
  Ausnahmen:    benannt, befristet, begründet
```

Die Einschränkung ist eine **Zugangs-, keine Auswertungsfunktion**. Sie prüft, ob eine Verbindung
zulässig ist; sie erzeugt keinen Aufenthaltsnachweis. Die Netzadresse ist deshalb auch **nicht Teil des
Standard-Protokollsatzes** — begründet in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#der-protokollsatz). Der abgewiesene
Verbindungsversuch wird als Sicherheitsereignis festgehalten, der zulässige nicht.

### Sitzungsverwaltung

- **Höchstdauer und Leerlauffrist** sind organisationsweit gesetzt, nicht je Nutzer verhandelbar.
- Eine Sitzung ist an ihre Identität gebunden. Endet die Gültigkeit beim Identitätsanbieter, endet sie in
  OPAA — spätestens beim nächsten Erneuerungsversuch, nicht erst nach Ablauf der eigenen Frist.
- **Übersicht der eigenen Sitzungen** für jede Person, mit der Möglichkeit, einzelne oder alle zu
  beenden. Das ist eine Selbstauskunfts- und Selbstschutzfunktion und für niemanden sonst sichtbar.
- **Laufende Antworten und Agentenläufe** werden beim Ende einer Sitzung abgebrochen, nicht im
  Hintergrund fortgeführt. Ein Lauf, der die Rechte einer beendeten Sitzung weiterträgt, wäre genau die
  Lücke, die die sofortige Wirkung von Rechteänderungen aushebelt.

### Erzwungene Neuanmeldung

Eine erzwungene Neuanmeldung beendet bestehende Sitzungen und verlangt eine erneute Authentisierung. Sie
wird ausgelöst:

- durch die Systemverwaltung — für eine Person, eine Gruppe oder alle, etwa nach einem
  Sicherheitsvorfall oder einer Änderung an den Modell- und Governance-Vorgaben, die vor der
  Weiterarbeit zur Kenntnis zu nehmen ist;
- **automatisch** bei Sperrung oder Ausscheiden im Verzeichnis;
- **automatisch** bei einer Rechteänderung, die den Zugang selbst betrifft (Entzug der
  System-Admin-Rolle, Wechsel der Organisationseinheit).

Der Vorgang ist protokollpflichtig. Er ist ein Verwaltungsakt gegenüber der betroffenen Person und kein
stiller Eingriff: Wer neu anmelden muss, erfährt beim nächsten Aufruf, dass und warum.

---

## API-Tokens und Service-Accounts

```
API-Token erstellen:
  Name:        "Fachverfahren-Anbindung"
  Rechte:      erbt die Asset-Rechte des ausstellenden Nutzers oder Service-Accounts
  Umfang:      [read_documents, ask_questions]
  Rotation:    90 Tage
  Netzbereich: optional (CIDR)
  Rate-Limit:  konfigurierbar
```

Ein Token kann **nie mehr Rechte haben als sein Inhaber**. Service-Accounts sind reine API-Identitäten
ohne interaktive Anmeldung; sie erhalten ihre Rechte wie jeder andere Träger von Rechten über Grants oder
Gruppen.

Für den Lebenszyklus gilt dieselbe Logik wie für Personen: Ein Token, dessen ausstellender Nutzer
ausscheidet, **verliert seine Wirkung mit dessen Konto**. Ein Token, das die Deaktivierung überdauert,
wäre der bequemste Weg, den Kontenlebenszyklus zu umgehen. Service-Accounts brauchen deshalb einen
benannten menschlichen Verantwortlichen, der selbst dem Lebenszyklus unterliegt — fällt er weg, greift
dieselbe Nachfolgeregelung wie bei Assets.

---

## Offboarding

Wenn ein Nutzer die Organisation verlässt:

1. Die Verzeichnis-Synchronisation entfernt oder sperrt ihn; er kann sich nicht mehr anmelden, bestehende
   Sitzungen enden, seine Tokens wirken nicht mehr.
2. **Die Deaktivierung wird nie durch offene Eigentumsfragen aufgehalten.**
3. Seine Assets gehen in den Zustand **„Nachfolge offen"**: nutzbar und mit unveränderten Rechten, aber
   mit **eingefrorener Reichweite** — keine neuen Grants, keine höhere Freigabestufe, keine neue
   Bereitstellung. Zuständig für die Nachfolge ist der Kurator der Organisationseinheit, ersatzweise der
   System-Admin; der Vorgang erscheint mit Frist auf der Governance-Arbeitsliste.
4. Für zentral gepflegte Bestände ist Gruppen-Eigentum der Regelfall und verhindert das Problem von
   vornherein.
5. Sein Standard-Space (`isDefault`) wird deaktiviert, nicht gelöscht (Nachweisgründe) — und **nicht lesbar
   gemacht**. Private Chats und Artefakte darin bleiben unzugänglich. Geteilte Inhalte unterliegen der
   Aufbewahrungsregel.

Die **Löschung** eines Kontos ist davon zu unterscheiden: Sie ist ein Vorgang nach DSGVO und in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#vollständigkeit-nach-dsgvo-löschung-und-export)
beschrieben.

---

## Sonderfälle

### Breiter Lesezugriff für Stabsstellen und Leitung

Nicht über eine Sonderrolle, sondern über **Gruppen**: Die Stabsstelle erhält als Gruppe Leserechte an den
einschlägigen Wissensbibliotheken. Das skaliert, ist im Katalog nachvollziehbar und läuft über denselben
Weg wie jede andere Freigabe — kein Sonderpfad, der bei einer Prüfung erklärt werden müsste.

### Revision und Rechnungsprüfung

Prüfende Stellen brauchen Unabhängigkeit. Empfohlen ist ein eigener Space im **Strikt-Modus** (nur
Bibliotheken, deren Leserkreis alle Mitglieder umfasst), damit in der Prüfung keine Inhalte an
Unberechtigte gelangen und die Prüfakte sauber abgegrenzt bleibt.

**Der Preis gehört an dieselbe Stelle wie die Empfehlung:** Ein hausweit geteilter Agent ist in aller
Regel an mindestens eine Bibliothek gebunden, deren Leserkreis die Prüfstelle nicht umfasst — im
Strikt-Modus ist er dort nicht aufrufbar. Die Prüfstelle verliert damit faktisch den größten Teil der
geteilten Agenten des Hauses. Das ist vertretbar und für die Unabhängigkeit sogar folgerichtig, muss aber
vor der Entscheidung bekannt sein und nicht drei Monate später auffallen.

### Externe Beteiligte

```
Nutzer:      externe Beraterin
Spaces:      [Projekt-X]
Assets:      Grant USER auf genau die benötigte Bibliothek
Befristung:  bis 2026-03-31
Hinweis:     Sie sieht alle Chats und Artefakte des Space — vor der Aufnahme prüfen
```

Die Aufnahme externer Personen ist besonders folgenreich, weil ihnen damit alle **geteilten** Inhalte des
Space offenstehen — also die Arbeitsergebnisse namentlich bekannter Beschäftigter. Externe Konten sind
gekennzeichnet, die Aufnahme verlangt eine ausdrückliche Bestätigung und wird protokolliert. Ein bloßer
Hinweistext genügt hier nicht. Für solche Fälle ist ein eigener, eng geschnittener Space der richtige Weg.

Externe Konten stammen häufig **nicht** aus dem Verzeichnis des Hauses. Für sie ist die Befristung des
Kontos deshalb Pflicht und nicht Option — sie ersetzt den Ausscheideprozess, der bei eigenen Beschäftigten
automatisch greift.

---

## Integrationspunkte

- **Authentifizierung:** SSO-Anbieter und Verzeichnisdienst
- **Benutzer-Frontends:** Rechte an jeder Schnittstelle durchsetzen →
  [user-frontends.md](./user-frontends.md)
- **Daten-Indizierung:** Zuordnung von Quellen zu Wissensbibliotheken →
  [data-indexing-rag.md](./data-indexing-rag.md)
- **RAG-Engine:** Filter über die lesbaren Bibliotheken des Nutzers, als Teil der Vektorsuche
- **Modelle:** zentrale Vorgaben gelten je Organisation → [llm-integration.md](./llm-integration.md)
- **Sicherheit und Nachweis:** jede Rechte- und Kontenänderung erzeugt einen Protokolleintrag →
  [security-and-compliance.md](./security-and-compliance.md)
- **Betrieb:** Nutzer- und Gruppendaten aus dem Verzeichnis →
  [deployment-infrastructure.md](./deployment-infrastructure.md)

---

## Offene Fragen

- Attributbasierte Zugangskontrolle (ABAC) zusätzlich zu Rollen und Gruppen?
- Zeitlich befristete Rechte mit automatischem Verfall und turnusmäßiger Rezertifizierung — als Arbeit
  erfasst, im Schnitt aber noch offen.
- Genehmigungsworkflows für besonders geschützte Bestände?
- Klassifizierungsstufen (offen, intern, vertraulich) als eigenes Merkmal?
- **Rechte aus Quellsystemen:** Sollen Berechtigungen des Quellsystems zusätzlich zu den
  Bibliotheksrechten durchgesetzt werden? Grundsätzlich erwünscht, aber aufwendig — Benutzerkennungen und
  Rechtemodelle stimmen zwischen Quellsystem und OPAA nicht notwendig überein.
- **Mehrfachzugehörigkeit:** Wie werden Beschäftigte abgebildet, die zwei Organisationseinheiten
  angehören? Das Verzeichnis kennt den Fall, das Aggregationsmodell der Auswertung muss ihn ebenfalls
  vertragen.
- Welche Ereignisse eine erzwungene Neuanmeldung auslösen, ist als Ausgangsliste festgehalten und wird
  sich im Betrieb schärfen.

---

## Erfolgs-Metriken

- **Wirksamkeit des Lebenszyklus:** Zeit zwischen Sperrung im Verzeichnis und Wirkungslosigkeit des
  Zugangs in OPAA, einschließlich aller Sitzungen und Tokens. Ziel ist eine Größenordnung von Minuten,
  nicht von Tagen.
- **Leistung:** Rechteprüfung erhöht die Abfragezeit um weniger als 50 ms.
- **Genauigkeit:** keine unbeabsichtigten Zugriffe; kein Synchronisationslauf, der eine Rechteänderung
  bewirkt hat, ohne sie einzeln festzuhalten.
- **Verständlichkeit:** Der Anteil der Support-Anfragen, die sich auf „warum sehe ich das nicht" beziehen,
  sinkt über die ersten drei Monate.

---

## Verwandte Dokumente

- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — das Rechtemodell
- [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md) — Protokoll, Rechtehistorie, DSGVO,
  C5-Fähigkeit, Mitbestimmungsfähigkeit
- [Monitoring, Kosten & Governance](./monitoring-and-governance.md) — Grenzen, Kosten und Auswertung
- [Daten-Indizierung & RAG](./data-indexing-rag.md)
