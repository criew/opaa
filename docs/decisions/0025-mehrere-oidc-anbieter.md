# ADR-0025: Mehrere OIDC-Anbieter — Identität als (Issuer, Subject), Anbieter in der Datenbank, Anmeldefluss bleibt in der SPA

## Status

Vorgeschlagen (05.09.2026, Issue #1327, Epic #1294). Nachtrag zu
[ADR-0005](0005-authentication-strategy.md), das unverändert gilt, soweit dieser ADR es nicht
ausdrücklich präzisiert.

## Kontext

OPAA kennt genau einen OIDC-Issuer: `OPAA_OIDC_ISSUER_URI`, `OPAA_OIDC_JWK_SET_URI`,
`OPAA_OIDC_AUTHORITY` und `OPAA_OIDC_CLIENT_ID` konfigurieren beim Start den Resource-Server
(Spring Security, `spring.security.oauth2.resourceserver.jwt`) und den Anmeldefluss der SPA
(`GET /api/v1/auth/config` liefert `authority` und `clientId`, `oidc-client-ts` führt den
Autorisierungscode-Fluss mit PKCE gegen diese eine Authority). Die Identität eines Kontos ist das
Paar `users(subject, issuer)` (`uq_users_subject_issuer`), die Erstadministrator-Regel
`opaa.auth.initial-admin-email` gilt für jede erste Anmeldung, und `DirectorySyncService` löst
Gruppenmitglieder über den `subject` innerhalb einer Organisation auf — was nur eindeutig ist,
solange es einen einzigen Issuer gibt.

Epic #1294 verlangt, dass eine Installation **mehrere Identitätsanbieter gleichzeitig** nutzen
kann: der Keycloak der Verwaltung für Beschäftigte und ein zweiter Anbieter für externe Partner, ein
Landes-IdP neben dem kommunalen, oder ein Anbieterwechsel ohne Stichtag. Das Epic nennt als
Vorbild das in qnop (`qnophq/qnop`, Paket `io.qnop.service.oidc`) und Plugwerk
(`plugwerk/plugwerk`, `plugwerk-server`, ADR-0029 dort) erprobte Muster: Anbieter als
Datenbankzeilen mit Admin-UI, wirksam ohne Neustart; Identität strikt als (Anbieter, Subject);
SSRF-Schutz für vom Betreiber eingegebene Adressen; Anmeldeseite mit Anbieter-Buttons aus einem
öffentlichen Endpunkt.

Vier Punkte sind zu entscheiden, bevor die übrigen Sub-Issues (#1329–#1334) geschnitten werden
können:

1. **Anmeldefluss** — bleibt OPAA zustandsloser Resource-Server mit dem Code-Flow in der SPA, oder
   übernimmt das Backend den Code-Flow (`oauth2Login`) und prägt eine eigene Sitzung?
2. **Identitätsmodell** — `users(subject, issuer)` weiterverwenden oder eine eigene
   Identitätstabelle mit Anbieter-Referenz?
3. **Bestandsübernahme** — wie wird der heutige `OPAA_OIDC_*`-Anbieter überführt, und wie meldet
   sich der Erstadministrator an, bevor ein Anbieter angelegt ist?
4. **Abmeldung, Token-Lebensdauer, Erstadmin-Regel** je Anbieter, und die Grenzen.

## Entscheidung

### 1. Anmeldefluss: die SPA bleibt der OIDC-Client, das Backend bleibt zustandsloser Resource-Server

OPAA folgt hier **nicht** dem qnop/Plugwerk-Muster (`oauth2Login` im Backend, Confidential
Clients, app-eigene Sitzung mit Refresh-Ledger), sondern bleibt bei der Architektur von ADR-0005:

- Die SPA führt den Autorisierungscode-Fluss mit PKCE gegen den **gewählten** Anbieter — ein
  `UserManager` (`oidc-client-ts`) je Anbieter, konstruiert aus `issuerUri` (Authority) und
  `clientId` des Anbieters. Alle Anbieter sind **Public Clients**; es gibt kein Client-Secret.
- Das Backend prüft jedes Bearer-Token über einen **dynamischen Multi-Issuer-Resolver**
  (`JwtIssuerAuthenticationManagerResolver` mit einer eigenen `issuer → AuthenticationManager`-
  Auflösung): Ein `JwtDecoder` je **aktiviertem** Anbieter, gehalten in einer prozesslokalen
  Registry, die nach jedem Commit einer Anbieteränderung neu aufgebaut wird (Muster
  `ActiveChatModelResolver`, `@TransactionalEventListener(AFTER_COMMIT)`). Ein Token mit einem
  `iss`, zu dem kein aktivierter Anbieter existiert, wird mit `401` abgewiesen — ein deaktivierter
  Anbieter ist damit **sofort** unwirksam, ohne dass laufende Sitzungen serverseitig verwaltet
  werden müssten.
- Die Sitzungsverwaltung bleibt beim Anbieter (Access-Token-Lebensdauer, Refresh, SSO-Sitzung);
  OPAA speichert keine Tokens und prägt keine eigenen.

**Warum nicht Variante B (qnop-treu, `oauth2Login` im Backend)?** Plugwerks ADR-0029 benennt
selbst, was Variante B voraussetzt: eine HTTP-Session für `code_verifier` und `state` zwischen
Anmeldebeginn und Callback, ein eigenes Token-Format samt Signaturgeheimnis, ein Refresh-Ledger mit
Rotation und Wiederverwendungserkennung, CSRF-Schutz für den Refresh-Endpunkt und einen
RP-Logout-Umweg über gespeicherte `id_token_hint`s. qnop und Plugwerk hatten diese Infrastruktur
bereits für ihre lokale Anmeldung mit Passwort — OPAA hat sie **absichtlich nicht**: ADR-0005 hat
den passwortbasierten Modus `basic` gerade deshalb entfernt (Signaturgeheimnis in der
Betriebskonfiguration, kein Refresh, Brute-Force-Ziel). Variante B würde all das für den einen
Zweck „mehrere Anbieter" neu einführen, obwohl Spring Security den Mehr-Issuer-Fall im
Resource-Server fertig mitbringt. ADR-0021 (Single Instance) würde den Sitzungszustand zwar
tragbar machen, ändert aber nichts daran, dass er unnötig ist. Verworfen.

**Was Variante A kostet:**

- Jeder Anbieter muss einen **Public Client mit PKCE** und den Origin der OPAA-Oberfläche als
  erlaubte Redirect-URI (`<Origin>/auth/callback`) und Web-Origin (CORS am Token-Endpunkt)
  zulassen. Keycloak, Entra ID, Authentik und jeder OIDC-konforme Anbieter mit SPA-Unterstützung
  können das; ein Anbieter, der ausschließlich Confidential Clients zulässt, ist nicht anbindbar
  (siehe Grenzen). Die Anbieterverwaltung zeigt die einzutragenden Werte an (#1333).
- Die Content-Security-Policy des Frontend-nginx muss jeden Anbieter-Origin in `connect-src`
  führen (`OPAA_CSP_CONNECT_SRC_EXTRA`, heute schon für den einen Anbieter nötig). Ein per UI neu
  angelegter Anbieter auf fremdem Origin ist erst anmeldefähig, wenn der Betrieb diese Variable
  ergänzt hat — der Verbindungstest der Admin-API prüft den Anbieter aus Backend-Sicht, nicht
  aus Browser-Sicht; das Handbuch (#1334) muss das benennen.
- Die Prüfung der Zielgruppe (`aud`/`azp` gegen `clientId`) bleibt wie heute **aus**: Keycloak
  setzt `aud` standardmäßig nicht auf den anfragenden Client, und eine erzwungene Prüfung würde
  jede bestehende Installation brechen. Ein Token desselben Anbieters, das für eine andere
  Anwendung ausgestellt wurde, wird damit weiterhin angenommen — unverändert gegenüber heute,
  als bekannte Grenze festgehalten, nicht Gegenstand dieses Epics.

### 2. Identitätsmodell: `(issuer, subject)` bleibt der Schlüssel, der Anbieter ist über den Issuer eindeutig

Die Identität eines Kontos bleibt `users(subject, issuer)`. Eine eigene Identitätstabelle
(`oidc_identity` in qnop/Plugwerk mit Anbieter-UUID als Fremdschlüssel) wird **nicht** eingeführt:

- qnop und Plugwerk brauchen die Anbieter-UUID als Identitätsanker, weil sie auch reine
  OAuth2-Anbieter ohne Issuer (GitHub, Facebook) anbinden. OPAA bindet ausschließlich OIDC an;
  jedes Token trägt einen `iss`, gegen den es validiert wurde. Der Issuer **ist** die
  Anbieteridentität.
- Die Tabelle `oidc_providers` führt deshalb `issuer_uri` als **eindeutige** Spalte: zwei Anbieter
  mit demselben Issuer sind ausgeschlossen (ein Token wäre sonst nicht einem Anbieter zuzuordnen).
  Ein Anbieter kann umbenannt, deaktiviert, gelöscht und unter demselben Issuer neu angelegt werden,
  ohne dass Konten ihre Identität verlieren — das ist zugleich der Mechanismus der
  Bestandsübernahme (Entscheidung 3): Der heutige Anbieter erhält als Zeile denselben Issuer, den
  die bestehenden `users`-Zeilen bereits tragen. **Keine Datenmigration an `users`.**
- **Keine Zusammenführung über die E-Mail**, weder automatisch noch stillschweigend bei gleicher
  Adresse: Ein unbekanntes `(issuer, subject)` provisioniert ein **neues** Konto, auch wenn ein
  anderes Konto dieselbe E-Mail trägt. Anbieter B kann kein Konto aus Anbieter A übernehmen. Diese
  Regel wird mit einem Test belegt (#1330). Dieselbe Person mit zwei Anbietern hat zwei Konten mit
  getrennten persönlichen Spaces und Rechten; eine manuelle Zusammenführung durch die
  Systemverwaltung liegt außerhalb des Epics (Grenzen).
- `email` und `display_name` werden wie heute bei jeder Anmeldung aus den Claims des jeweiligen
  Anbieters aktualisiert (Entscheidung 4 legt fest, aus welchen).

Die Anbieterzeile trägt: `display_name`, `enabled`, `is_default` (genau einer), `sort_order`,
`issuer_uri` (eindeutig; zugleich die Authority der SPA — `OPAA_OIDC_AUTHORITY` war nie etwas
anderes als der Issuer), `client_id`, optional `jwk_set_uri` (Backend-seitige Überschreibung des
per Discovery gefundenen JWK-Sets — dieselbe Rolle wie heute `OPAA_OIDC_JWK_SET_URI`: Im
Compose-Betrieb erreicht das Backend Keycloak nur unter `keycloak:8180`, der Browser unter
`localhost:8180`; ohne dieses Feld wäre der gebündelte Stack nicht abbildbar), die
Claim-Zuordnung aus Entscheidung 4 sowie `created_at`/`updated_at`. **Keine Secret-Spalte** (Public
Clients, Entscheidung 1); das in #1329 genannte `SourceCredentialsConverter`-Muster entfällt damit.

### 3. Konfiguration in der Datenbank, Bootstrap aus der Umgebung, SSRF-Schutz über die bestehende Allowlist

**Anbieter liegen in der Datenbank** und werden über eine Admin-API (nur `SYSTEM_ADMIN`,
OpenAPI-first nach ADR-0006) und die Systemeinstellungen gepflegt. Änderungen wirken **ohne
Neustart**: Die Registry aus Entscheidung 1 und der öffentliche Konfigurationsendpunkt lesen nach
`AFTER_COMMIT` neu. Ein Anbieter, dessen Discovery beim Aufbau scheitert, wird protokolliert
übersprungen und blockiert die übrigen nicht (qnop `DbClientRegistrationRepository`). Anlegen,
Ändern, Aktivieren/Deaktivieren und Löschen sind Audit-Ereignisse nach dem Muster der
Modellverwaltung (`LLM_MODEL_*`).

**Bootstrap aus `OPAA_OIDC_*` — einmalig, dann führt die Datenbank.** Beim ersten Start im
`oidc`-Profil nach dieser Änderung übernimmt ein Seeder die vier Umgebungsvariablen als erste,
aktivierte Standard-Anbieterzeile (`display_name` „Verzeichnisdienst", derselbe Issuer wie in den
bestehenden `users`-Zeilen). Der Seeder ist über eine Markierung gegen Wiederholung gesichert —
**nicht** über „ist die Tabelle leer?" —, exakt wie `LlmModelSeeder`/`LlmModelSeedMarker` (#756):
Wer alle Anbieter löscht, bekommt die alte Umgebungskonfiguration nicht still zurückgespielt.
Danach werden die `OPAA_OIDC_*`-Variablen **nicht mehr ausgewertet**; eine spätere Änderung an
ihnen ist wirkungslos, und das Handbuch sagt das. Für eine **Neuinstallation** ist derselbe Weg der
Einstieg: Der Betrieb setzt `OPAA_OIDC_*` für den ersten Anbieter, meldet sich damit als
Erstadministrator an und legt weitere Anbieter über die Oberfläche an. Startet das `oidc`-Profil
ohne einen einzigen aktivierten Anbieter, startet die Anwendung trotzdem (ein Betriebsfehler in
der Anbieterverwaltung darf sie nicht unstartbar machen) und protokolliert beim Start und im
Konfigurationsendpunkt, dass keine Anmeldung möglich ist. Der `dev`-Modus bleibt der
vollwertige zweite Modus aus ADR-0005 und kennt keine Anbieterzeilen.

**Erstadministrator-Regel je Anbieter:** `opaa.auth.initial-admin-email` gilt ausschließlich für
Konten, die über den **Standardanbieter** (`is_default`) provisioniert werden, und wie heute nur
beim Anlegen des Kontos. Ein zweiter Anbieter, dessen Betreiber ein Konto mit der
Erstadmin-Adresse ausstellt, erhält damit kein `SYSTEM_ADMIN` — die Regel lässt sich nicht über
einen zweiten Anbieter kapern. Wer den Standardanbieter wechselt, verschiebt damit auch die
Wirkung dieser Regel; die Oberfläche benennt das beim Umstellen.

**SSRF-Schutz** für alle vom Betreiber eingegebenen Adressen (`issuer_uri`, `jwk_set_uri`):
`TargetAddressValidator` mit der bestehenden Allowlist (`opaa.indexing.target-validation`,
`OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`), so wie der Confluence-Konnektor sie bereits für
Instanzadressen nutzt (ADR-0023). Ein Identitätsanbieter einer Behörde steht im Regelfall auf
einer privaten Adresse; die Ablehnung nennt deshalb — wie `ConfluenceHttp.ALLOWLIST_HINT` — die
Variable, über die der Betrieb den Host freigibt. Die Prüfung läuft beim Speichern **und** beim
Aufbau der Registry (auch für die gesäte Zeile), damit eine nachträglich geänderte Allowlist oder
eine direkt in der Datenbank geänderte Adresse nicht an ihr vorbeikommt. Der Compose-Stack, die
E2E-Umgebung und der Demo-Smoke müssen den Hostnamen `keycloak` in die Allowlist aufnehmen
(#1329/#1334). Eine eigene qnop-artige Property „private Adressen erlauben" gibt es nicht — die
Allowlist ist die feinere und bereits bekannte Form.

### 4. Claim-Zuordnung, Rollen, Gruppen und Organisation je Anbieter

Jede Anbieterzeile trägt, mit Vorgaben für Keycloak:

| Feld | Vorgabe | Bedeutung |
| --- | --- | --- |
| `email_claim` | `email` | Quelle für `users.email` |
| `display_name_claim` | `name` | Quelle für `users.display_name`; Rückfall `preferred_username`, dann `subject` — die heutige Kette aus `JwtUserClaims` |
| `roles_claim` | leer | Pfad zum Rollen-Claim (Punktnotation, z. B. `realm_access.roles`); leer = keine Rollenableitung |
| `system_admin_role` | leer | Rollenwert, der `SYSTEM_ADMIN` bedeutet |
| `auditor_role` | leer | Rollenwert, der `AUDITOR` bedeutet |
| `groups_claim` | leer | Pfad zum Gruppen-Claim; leer = keine Gruppen aus dem Token |

**Rollen:** Ist `roles_claim` gesetzt, ist der Anbieter für `SYSTEM_ADMIN` und `AUDITOR` **führend**:
Bei jeder Anmeldung wird die Systemrolle aus dem Token abgeleitet und eine Abweichung als
Rollenänderung mit Audit-Ereignis (`SYSTEM_ADMIN_ROLE_GRANTED`/`_REVOKED`, `AUDITOR_*`) angewandt —
das ist die Lesart von `docs/features/access-control.md` („Das Verzeichnis ist die führende
Quelle"). Ohne `roles_claim` bleibt es beim heutigen Verhalten: reguläre Nutzer, Rollen werden in
OPAA verwaltet. Die Erstadministrator-Regel (Entscheidung 3) greift in beiden Fällen nur beim
Anlegen.

**Gruppen:** Ist `groups_claim` gesetzt, werden die Gruppennamen des Tokens bei der Anmeldung zu
Mitgliedschaften in Gruppen, deren `external_id` im **Namensraum des Anbieters** liegt
(`oidc:<anbieter-id>:<gruppenname>`). Gleichnamige Gruppen zweier Anbieter sind damit zwei Gruppen;
eine Gruppe eines Anbieters ist nie eine Gruppe eines anderen. Der bestehende Verzeichnisabgleich
(`DirectorySyncService`, ADR-0005 „Gruppenzugehörigkeiten kommen nicht aus dem Token") bleibt der
zweite Weg und wird an den **Standardanbieter** gebunden: Er löst Mitglieder über `subject` nur
unter dessen Issuer auf (heute: unter dem einen Issuer, implizit) und verwaltet ausschließlich
Gruppen, die er selbst angelegt hat — nie solche im `oidc:`-Namensraum. Die Präzisierung von
ADR-0005s Neutral-Punkt „`DirectorySyncService` setzt einen einzelnen OIDC-Issuer je Organisation
voraus" lautet damit: **einen einzelnen Verzeichnis-Anbieter je Organisation, den
Standardanbieter.** Das Gruppen- und Rollenmodell selbst bleibt Epic #1295.

**Organisation:** Alle Anbieter provisionieren in `Organization.DEFAULT_ID`, wie heute. Eine
Anbieter-zu-Organisation-Bindung ist der naheliegende spätere Schritt für echten Mehrmandantenbetrieb
(eine Spalte `organization_id` an der Anbieterzeile), wird hier aber nicht gebaut — OPAA betreibt
heute genau eine Organisation, und eine leere Vorbereitung dafür wäre spekulativ.

### 5. Abmeldung und Token-Lebensdauer je Anbieter

Die Abmeldung ist ein RP-initiierter Logout beim Anbieter der **aktiven Sitzung**: Die SPA merkt
sich, über welchen Anbieter die Sitzung entstand, und ruft `signoutRedirect()` des zugehörigen
`UserManager` auf, der den `end_session_endpoint` aus der Discovery dieses Anbieters nutzt, mit
`post_logout_redirect_uri = <Origin>`. Ein Anbieter ohne `end_session_endpoint` endet in einer
lokalen Abmeldung (Store zurücksetzen), was die Oberfläche als Hinweis anzeigt. Access-Token- und
SSO-Lebensdauern sind Sache des jeweiligen Anbieters; die stille Erneuerung und die
401-Behandlung aus ADR-0005 (#737) arbeiten unverändert auf dem `UserManager` der aktiven Sitzung.
„Mit anderem Konto anmelden" schickt `prompt=login` an den gewählten Anbieter (Plugwerk
`PromptAwareOAuth2AuthorizationRequestResolver`, hier als `extraQueryParams` der SPA).

### 6. Grenzen

- **Kein SAML**, keine anderen Protokolle (Kerberos weiterhin über eine Keycloak-Föderation).
- **Keine Kontenzusammenführung**, weder automatisch noch manuell — zwei Anbieter, zwei Konten.
- **Nur Public Clients mit PKCE.** Ein Anbieter, der für SPAs kein PKCE oder keinen Public Client
  zulässt, ist nicht anbindbar; ein Confidential-Client-Modus wäre Variante B.
- **Keine Zielgruppenprüfung** (`aud`) — wie heute.
- **Eine Organisation** für alle Anbieter.
- Der `dev`-Modus bleibt unverändert.

## Konsequenzen

### Positiv

- ADR-0005 bleibt in seinen Grundsätzen bestehen: zustandslos, kein eigenes Signaturgeheimnis,
  kein Anmeldeformular, Entwicklung und Betrieb üben denselben Pfad.
- Bestehende Installationen migrieren ohne Datenänderung an `users`; der Bootstrap aus der
  Umgebung ist derselbe Mechanismus wie bei der Modellverwaltung (#756).
- Ein deaktivierter Anbieter ist sofort unwirksam, ohne Sitzungsverwaltung.
- Die Identitätsregel ist am Schema durchgesetzt (`uq_users_subject_issuer`, eindeutiger Issuer je
  Anbieter), nicht nur in einem Service.

### Negativ

- Jeder zusätzliche Anbieter braucht Betriebsschritte außerhalb von OPAA (Public Client mit
  Redirect-URI und Web-Origin beim Anbieter, `connect-src` im Frontend-nginx, ggf. Allowlist im
  Backend) — die Anbieterverwaltung kann sie anzeigen, aber nicht ausführen.
- Dieselbe Person mit zwei Anbietern lebt in zwei Konten; ein Anbieterwechsel ohne Stichtag ist
  damit ein Nebeneinander alter und neuer Konten, bis die alten auslaufen.
- Rollen aus einem Token (Entscheidung 4) machen den Anbieter führend für `SYSTEM_ADMIN`; ein
  Fehler in der Rollenzuordnung eines Anbieters wirkt bei der nächsten Anmeldung.

### Neutral

- `opaa.auth.oidc.*` und `spring.security.oauth2.resourceserver.jwt.*` in `application.yml`
  verlieren ihre Laufzeitwirkung und bleiben nur als Quelle des Seeders bestehen.
- `GET /api/v1/auth/config` liefert statt `authority`/`clientId` eine Anbieterliste; beide Seiten
  liegen im selben Repository, es gibt keinen externen Client dieser Antwort.

## Zuschnitt der übrigen Sub-Issues (gegen diesen ADR geprüft)

| Issue | Folgt aus diesem ADR |
| --- | --- |
| #1329 Anbieterverwaltung | Tabelle `oidc_providers` **ohne** Secret-Spalte, mit `issuer_uri` (eindeutig), optionalem `jwk_set_uri` und den Claim-Feldern aus Entscheidung 4 (Spalten hier, Auswertung in #1331); Seeder mit Markierung; Registry mit `AFTER_COMMIT`-Neuaufbau; SSRF über `TargetAddressValidator` und die bestehende Allowlist; Discovery-Probe; Audit-Ereignisse `OIDC_PROVIDER_*` |
| #1330 Anmeldefluss und Kontenmodell | `JwtIssuerAuthenticationManagerResolver` gegen die Registry; `UserProvisioningFilter` unverändert auf `(issuer, subject)`; Erstadmin-Regel nur für den Standardanbieter; Test „gleiche E-Mail, zwei Anbieter, zwei Konten"; Test „deaktivierter Anbieter → 401" |
| #1331 Claim-Zuordnung | Auswertung der Felder aus Entscheidung 4; Rollen führend bei gesetztem `roles_claim`; Gruppen im Namensraum `oidc:<anbieter-id>:`; Verzeichnisabgleich an den Standardanbieter gebunden |
| #1332 Anmeldeseite | `GET /api/v1/auth/config` mit Anbieterliste (`id`, `displayName`, `issuerUri`, `clientId`, `isDefault`, `sortOrder`); ein `UserManager` je Anbieter; gemerkter Anbieter in `localStorage`; `prompt=login`; Abmeldung beim Anbieter der Sitzung |
| #1333 Anbieterverwaltung (UI) | Kein Secret-Feld; Felder aus Entscheidung 2 und 4; Verbindungstest; Anleitung mit `<Origin>/auth/callback` (Redirect-URI), `<Origin>` (Post-Logout-Redirect und Web-Origin) und dem Hinweis auf `connect-src`; Konsequenz-Hinweis beim Deaktivieren |
| #1334 E2E und Handbuch | Zweiter Realm im gebündelten Keycloak als zweiter Public Client; Allowlist `keycloak` in Compose-/E2E-Umgebung; Handbuchkapitel mit Bootstrap aus `OPAA_OIDC_*`, Betriebsschritten je Anbieter und Grenzen |

## Verworfene Alternativen

- **Variante B — `oauth2Login` im Backend mit app-eigener Sitzung** (qnop/Plugwerk): siehe
  Entscheidung 1. Führt Sitzungszustand, Token-Prägung, Refresh-Ledger und CSRF ein, die ADR-0005
  bewusst vermeidet; Spring Security deckt den Mehr-Issuer-Fall im Resource-Server ab.
- **Eigene Identitätstabelle mit Anbieter-UUID** (qnop `oidc_identity`): nötig nur für Anbieter
  ohne Issuer; für OIDC ist der Issuer der stabilere Anker und macht die Bestandsübernahme
  datenmigrationsfrei.
- **Zusammenführung über die E-Mail**: bequem und ein Übernahmerisiko — verworfen, wie schon das
  Epic selbst vorschlägt.
- **Anbieter weiter in Umgebungsvariablen (Liste)**: bräuchte einen Neustart je Änderung und ließe
  sich nicht aus der Oberfläche pflegen; der Verbindungstest und die Anleitung je Anbieter (#1333)
  wären nicht möglich.
- **Umgebung als dauerhafter Rückfall neben der Datenbank**: zwei Quellen der Wahrheit, deren
  Vorrang bei jedem Lesen zu erklären wäre; der einmalige Seed mit Markierung ist das Muster, das
  #756 bereits etabliert hat.
- **Home-Realm-Discovery über die E-Mail-Domäne** statt Anbieterwahl: erfordert eine
  Domänen-zu-Anbieter-Tabelle und verrät bei der Eingabe, welche Domänen bekannt sind; die
  Anbieterwahl per Button ist einfacher und entspricht dem Vorbild. Kann später auf der
  Anbieterzeile ergänzt werden, ohne diesen ADR zu ändern.
- **Eigene Property „private Adressen erlauben"** (qnop `OidcSsrfPolicy`): die bestehende
  Host-Allowlist ist feiner und dem Betrieb aus dem Confluence-Konnektor bereits bekannt.

## Referenzen

- [ADR-0005](0005-authentication-strategy.md) — Authentifizierungsstrategie (zwei Modi, ein Issuer)
- [ADR-0006](0006-openapi-dto-generation.md) — OpenAPI-first
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md) — Audit-Ereignisse
- [ADR-0021](0021-single-instance-betrieb.md) — prozesslokale Registry ohne verteilte Invalidierung
- [ADR-0023](0023-confluence-konnektor.md) — `TargetAddressValidator` und Allowlist für
  Instanzadressen
- `docs/features/access-control.md` — Anmeldung, Kontenlebenszyklus, Gruppensynchronisation
- Vorbilder: qnop `io.qnop.service.oidc` (`OidcProvider`, `DbClientRegistrationRepository`,
  `OidcIdentityService`, `OidcSsrfPolicy`), Plugwerk `docs/adrs/0029-oidc-web-login-via-spring-oauth2login.md`,
  `OidcEndSessionUrlResolver`, `PromptAwareOAuth2AuthorizationRequestResolver`,
  `components/auth/OidcProviderButton.tsx`
- Spring Security: `JwtIssuerAuthenticationManagerResolver` (Multi-Tenancy im Resource-Server)
