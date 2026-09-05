# ADR-0025: Mehrere OIDC-Anbieter — Identität als (Issuer, Subject), Anbieter in der Datenbank, Anmeldefluss bleibt in der SPA

## Status

Vorgeschlagen (05.09.2026, Issue #1327, Epic #1294). Nachtrag zu
[ADR-0005](0005-authentication-strategy.md), das unverändert gilt, soweit dieser ADR es nicht
ausdrücklich präzisiert oder — an einer benannten Stelle (Entscheidung 4) — aufhebt.

## Kontext

OPAA kennt genau einen OIDC-Issuer: `OPAA_OIDC_ISSUER_URI`, `OPAA_OIDC_JWK_SET_URI`,
`OPAA_OIDC_AUTHORITY` und `OPAA_OIDC_CLIENT_ID` konfigurieren beim Start den Resource-Server
(Spring Security, `spring.security.oauth2.resourceserver.jwt`) und den Anmeldefluss der SPA
(`GET /api/v1/auth/config` liefert `authority` und `clientId`, `oidc-client-ts` führt den
Autorisierungscode-Fluss mit PKCE gegen diese eine Authority). Die Identität eines Kontos ist das
Paar `users(subject, issuer)` (`uq_users_subject_issuer`), die Erstadministrator-Regel
`opaa.auth.initial-admin-email` gilt für jedes neu angelegte Konto mit dieser Adresse, und
`DirectorySyncPlanExecutor` löst Gruppenmitglieder über den `subject` innerhalb einer Organisation
auf (`UserRepository#findByOrganizationIdAndSubjectIn`) — was nur eindeutig ist, solange es einen
einzigen Issuer gibt. Rollen oder Gruppen liest der Provisionierungsfilter heute **nicht** aus dem
Token (`UserProvisioningFilter`: `sub`, `iss`, `email`, `name`); ADR-0005 legt fest, dass Gruppen
über `DirectoryClient` kommen.

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
3. **Bestandsübernahme** — wie wird der heutige `OPAA_OIDC_*`-Anbieter überführt, wie meldet
   sich der Erstadministrator an, bevor ein Anbieter angelegt ist, und wie kommt ein Betrieb aus
   einer Fehlkonfiguration wieder heraus?
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
  Auflösung; `OidcSecurityConfig` wechselt dafür von `oauth2ResourceServer().jwt(...)` auf
  `authenticationManagerResolver(...)` — die beiden schließen sich aus —, `UserProvisioningFilter`
  bleibt hinter `BearerTokenAuthenticationFilter`). Ein `JwtDecoder` je **aktiviertem** Anbieter
  liegt in einer prozesslokalen Registry, die nach jedem Commit einer Anbieteränderung neu
  aufgebaut wird (Muster `ActiveChatModelResolver`, `@TransactionalEventListener(AFTER_COMMIT)`;
  Eintrag in ADR-0021, siehe dort). Ein Token mit einem `iss`, zu dem kein aktivierter Anbieter
  existiert, wird mit `401` abgewiesen — ein deaktivierter Anbieter ist damit **sofort**
  unwirksam, ohne dass laufende Sitzungen serverseitig verwaltet werden müssten.
- **Ein unbekannter Issuer ist vom abgelaufenen Token unterscheidbar:** Die Antwort trägt
  `WWW-Authenticate: Bearer error="invalid_token", error_description="unknown_issuer"`. Die SPA
  wertet das im 401-Interceptor aus ADR-0005 (#737) aus: kein stiller Erneuerungsversuch (die
  Sitzung beim Anbieter ist ja intakt, ein neues Token würde genauso abgewiesen), sondern lokale
  Abmeldung mit der Meldung, dass der Anmeldeanbieter dieser Sitzung nicht mehr verfügbar ist —
  sonst liefe der Nutzer in eine Schleife aus Anmelden und „Sitzung abgelaufen".
- **Ein Anbieter, dessen Decoder beim Aufbau nicht gebaut werden kann** (Discovery gerade nicht
  erreichbar — im Compose-Stack startet Keycloak regelmäßig nach OPAA —, DNS-Fehler, eine
  Adresse, die die Adressprüfung ablehnt), wird protokolliert übersprungen und blockiert die
  übrigen nicht (qnop `DbClientRegistrationRepository`). Er fällt damit aber nicht bis zum
  nächsten Neustart aus: Die Registry merkt sich den Fehlzustand und versucht den Aufbau beim
  nächsten Token dieses Issuers erneut, frühestens nach einer kurzen Wartezeit; die
  Anbieterverwaltung zeigt einen dauerhaft fehlerhaften Anbieter als solchen an (#1333).
- Die Sitzungsverwaltung bleibt beim Anbieter (Access-Token-Lebensdauer, Refresh, SSO-Sitzung);
  OPAA speichert keine Tokens und prägt keine eigenen.
- **`azp` wird geprüft, `aud` nicht.** Trägt ein Token einen `azp`-Claim (authorized party),
  muss er der `client_id` der Anbieterzeile entsprechen; fehlt der Claim, gilt keine Prüfung.
  Keycloak und Entra ID setzen `azp` immer auf den anfragenden Client, die Prüfung bricht also
  keine bestehende Installation, und sie ist bei einem Public Client die einzige Kontrolle
  dagegen, dass ein Token, das derselbe Anbieter für eine *andere* Anwendung ausgestellt hat,
  von OPAA angenommen wird — bei einem Partner-IdP, den OPAA nicht kontrolliert, kein
  theoretischer Fall. `aud` bleibt dagegen ungeprüft, wie heute: Keycloak setzt `aud` nicht auf
  den anfragenden Client, eine erzwungene `aud`-Prüfung bräche jede bestehende Installation.

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
tragbar machen, ändert aber nichts daran, dass er unnötig ist. Verworfen. Die Kehrseite — ohne
lokale Anmeldung gibt es keinen anbieterunabhängigen Notzugang — beantwortet Entscheidung 3 mit
Regeln, die eine Installation nie ohne anmeldefähigen Anbieter zurücklassen.

**Was Variante A kostet:**

- Jeder Anbieter muss einen **Public Client mit PKCE** und den Origin der OPAA-Oberfläche als
  erlaubte Redirect-URI (`<Origin>/auth/callback`), Post-Logout-Redirect (`<Origin>`) und
  Web-Origin (CORS am Token-Endpunkt) zulassen. Keycloak, Entra ID, Authentik und jeder
  OIDC-konforme Anbieter mit SPA-Unterstützung können das; ein Anbieter, der ausschließlich
  Confidential Clients zulässt, ist nicht anbindbar (siehe Grenzen). Die Anbieterverwaltung zeigt
  die einzutragenden Werte an (#1333).
- **„Ohne Neustart" gilt für das Backend, nicht für die Content-Security-Policy.** Die CSP des
  Frontend-nginx muss jeden Anbieter-Origin in `connect-src` führen
  (`OPAA_CSP_CONNECT_SRC_EXTRA`, heute schon für den einen Anbieter nötig), und
  `frontend/nginx.conf` ist ein `envsubst`-Template, das **beim Containerstart** ersetzt wird. Ein
  über die Oberfläche angelegter Anbieter auf einem **neuen Origin** ist deshalb erst anmeldefähig,
  wenn der Betrieb die Variable ergänzt und den Frontend-Container neu gestartet hat; ein Anbieter
  auf einem bereits erlaubten Origin (ein zweiter Realm desselben Keycloak) ist es sofort. Die
  Anbieterverwaltung zeigt diesen Betriebsschritt beim Anlegen an (#1333), der Verbindungstest
  prüft den Anbieter aus Backend-Sicht, nicht aus Browser-Sicht; das E2E-Szenario aus #1334 läuft
  bewusst mit einem zweiten Realm auf demselben Origin und deckt den Neustart-Fall nicht ab.

### 2. Identitätsmodell: `(issuer, subject)` bleibt der Schlüssel, der Anbieter ist über den Issuer eindeutig

Die Identität eines Kontos bleibt `users(subject, issuer)`. Eine eigene Identitätstabelle
(`oidc_identity` in qnop/Plugwerk mit Anbieter-UUID als Fremdschlüssel) wird **nicht** eingeführt:

- qnop und Plugwerk brauchen die Anbieter-UUID als Identitätsanker, weil sie auch reine
  OAuth2-Anbieter ohne Issuer (GitHub, Facebook) anbinden. OPAA bindet ausschließlich OIDC an;
  jedes Token trägt einen `iss`, gegen den es validiert wurde. Der Issuer **ist** die
  Anbieteridentität.
- Die Tabelle `oidc_providers` führt deshalb `issuer_uri` als **eindeutige** Spalte (normalisiert:
  ohne abschließenden Schrägstrich): zwei Anbieter mit demselben Issuer sind ausgeschlossen (ein
  Token wäre sonst nicht einem Anbieter zuzuordnen). Ein Anbieter kann umbenannt, deaktiviert,
  gelöscht und unter demselben Issuer neu angelegt werden, ohne dass Konten ihre Identität
  verlieren — das ist zugleich der Mechanismus der Bestandsübernahme (Entscheidung 3): Der heutige
  Anbieter erhält als Zeile denselben Issuer, den die bestehenden `users`-Zeilen bereits tragen.
  **Keine Datenmigration an `users`.**
- **Keine Zusammenführung über die E-Mail**, weder automatisch noch stillschweigend bei gleicher
  Adresse: Ein unbekanntes `(issuer, subject)` provisioniert ein **neues** Konto, auch wenn ein
  anderes Konto dieselbe E-Mail trägt. Anbieter B kann kein Konto aus Anbieter A übernehmen. Diese
  Regel wird mit einem Test belegt (#1330). Dieselbe Person mit zwei Anbietern hat zwei Konten mit
  getrennten persönlichen Spaces und Rechten; eine manuelle Zusammenführung durch die
  Systemverwaltung liegt außerhalb des Epics (Grenzen).
- `email` und `display_name` werden wie heute bei jeder Anfrage aus den Claims des jeweiligen
  Anbieters aktualisiert, geschrieben nur bei Abweichung (Entscheidung 4 legt fest, aus welchen).

Die Anbieterzeile trägt: `display_name`, `enabled`, `is_default` (höchstens einer, siehe
Entscheidung 3), `sort_order`, `issuer_uri` (eindeutig; zugleich die Authority der SPA —
`OPAA_OIDC_AUTHORITY` und `OPAA_OIDC_ISSUER_URI` waren in jeder mitgelieferten Konfiguration
derselbe Wert), `client_id`, optional `jwk_set_uri`, die Claim-Zuordnung aus Entscheidung 4 sowie
`created_at`/`updated_at`. **Keine Secret-Spalte** (Public Clients, Entscheidung 1); das in #1329
genannte `SourceCredentialsConverter`-Muster entfällt damit.

**`jwk_set_uri` ist ein Vertrauensanker, nicht nur Netzwerkkomfort.** Das Feld überschreibt die
per Discovery gefundene JWK-Set-Adresse und hat zwei Zwecke: den Compose-Betrieb, in dem das
Backend Keycloak nur unter `keycloak:8180` erreicht und der Browser unter `localhost:8180` —
dieselbe Rolle wie heute `OPAA_OIDC_JWK_SET_URI` —, und die Bindung an eine feste Schlüsselquelle
ohne Discovery. Wer es setzt, bestimmt, welche Schlüssel Token dieses Issuers beglaubigen. Es ist
deshalb nur für `SYSTEM_ADMIN` änderbar, jede Änderung ist ein eigenes Audit-Ereignis, und die
Anbieterverwaltung benennt das Feld als das, was es ist (#1333).

### 3. Konfiguration in der Datenbank, Bootstrap aus der Umgebung, Adressprüfung mit eigener Allowlist, kein Weg ohne Anbieter

**Anbieter liegen in der Datenbank** und werden über eine Admin-API (nur `SYSTEM_ADMIN`,
OpenAPI-first nach ADR-0006) und die Systemeinstellungen gepflegt. Änderungen wirken **ohne
Neustart des Backends**: Die Registry aus Entscheidung 1 und der öffentliche
Konfigurationsendpunkt lesen nach `AFTER_COMMIT` neu (zur CSP siehe Entscheidung 1). Anlegen,
Ändern, Aktivieren/Deaktivieren und Löschen sind Audit-Ereignisse `OIDC_PROVIDER_*` nach dem
Muster der Modellverwaltung (`LLM_MODEL_*`) — mit dem bestehenden `AuditObjectType.SYSTEM_SETTING`,
so dass keine Änderung an `chk_audit_log_object_type` nötig ist; `AuditEventType` hat seit der
Baseline keine Datenbank-Prüfregel mehr. Der Handelnde ist der Systemverwalter, der die Änderung
auslöst.

**Bootstrap aus `OPAA_OIDC_*` — einmalig, dann führt die Datenbank.** Beim ersten Start im
`oidc`-Profil nach dieser Änderung übernimmt ein Seeder die Umgebungsvariablen als erste,
aktivierte Standard-Anbieterzeile (`display_name` „Verzeichnisdienst"): `issuer_uri` aus
`OPAA_OIDC_ISSUER_URI` (das muss zum `iss` der Bestandskonten passen), `client_id` aus
`OPAA_OIDC_CLIENT_ID`, `jwk_set_uri` aus `OPAA_OIDC_JWK_SET_URI`; eine von der Issuer-URI
abweichende `OPAA_OIDC_AUTHORITY` wird beim Start protokolliert und verworfen. Der Seeder ist über
eine Markierung gegen Wiederholung gesichert — **nicht** über „ist die Tabelle leer?" —, exakt wie
`LlmModelSeeder`/`LlmModelSeedMarker` (#756): Wer alle Anbieter löscht, bekommt die alte
Umgebungskonfiguration nicht still zurückgespielt. Danach werden die `OPAA_OIDC_*`-Variablen
**nicht mehr ausgewertet**; das Handbuch sagt das. Dafür verschwinden die
`spring.security.oauth2.resourceserver.jwt.*`-Einträge aus dem `oidc`-Profil — ein
Framework-Namensraum darf nicht mit einer Zweitbedeutung („Seeder-Quelle") stehen bleiben; die
Werte ziehen in einen eigenen Block `opaa.auth.oidc.*`, aus dem allein der Seeder liest. Für eine
**Neuinstallation** ist derselbe Weg der Einstieg: Der Betrieb setzt `OPAA_OIDC_*` für den ersten
Anbieter, meldet sich damit als Erstadministrator an und legt weitere Anbieter über die
Oberfläche an. Der `dev`-Modus bleibt der vollwertige zweite Modus aus ADR-0005 und kennt keine
Anbieterzeilen; er schreibt auch keine Markierung, damit ein späterer Wechsel auf `oidc` die
Übernahme noch vornehmen kann.

**Es gibt keinen Zustand ohne anmeldefähigen Anbieter, und es gibt einen Weg zurück.** Ohne
lokale Anmeldung (Variante B verworfen) wäre „letzten Anbieter deaktiviert" oder „Issuer-URI
vertippt" ein Totalausfall der Verwaltung mit Datenbankzugriff als einzigem Ausweg. Deshalb:

- Solange Anbieter existieren, ist **genau einer der Standardanbieter**, und der Standardanbieter
  ist immer aktiviert: Der erste angelegte Anbieter wird automatisch Standard; der
  Standardanbieter kann weder deaktiviert noch gelöscht werden, bevor ein anderer aktivierter
  Anbieter zum Standard gemacht wurde (`409` mit Handlungsanweisung). In der Datenbank ist nur
  „höchstens einer" durchsetzbar (partieller Unique-Index `WHERE is_default`); „genau einer bei
  nichtleerer Tabelle" ist eine Regel des Service, und ein Zustand ohne Standardanbieter ist über
  die API nicht erreichbar. Sollte er durch einen direkten Datenbankeingriff doch entstehen, greift
  die Erstadministrator-Regel nicht, und die Anbieterverwaltung weist darauf hin.
- **Wiederanlauf:** `OPAA_OIDC_BOOTSTRAP=force` lässt den Seeder die Markierung einmalig
  ignorieren und den Umgebungsanbieter wiederherstellen — existiert eine Zeile mit diesem Issuer,
  wird sie aktiviert, mit den Umgebungswerten überschrieben und zum Standard gemacht; sonst wird
  sie neu angelegt. Das ist der dokumentierte Weg aus einer vertippten Issuer-URI des einzigen
  Anbieters, laut protokolliert, und der Betrieb entfernt die Variable danach wieder (#1334).
- Startet das `oidc`-Profil ohne einen einzigen aktivierten Anbieter — nur möglich, wenn die
  Umgebung beim allerersten Start keinen Issuer nannte —, startet die Anwendung trotzdem (ein
  Betriebsfehler darf sie nicht unstartbar machen), protokolliert als Fehler, dass keine Anmeldung
  möglich ist und welche Variablen zu setzen sind, schreibt **keine** Markierung und holt die
  Übernahme beim nächsten Start nach.

**Erstadministrator-Regel je Anbieter:** `opaa.auth.initial-admin-email` gilt ausschließlich für
Konten, die über den **Standardanbieter** (`is_default`) provisioniert werden, und wie heute nur
beim Anlegen des Kontos. Ein zweiter Anbieter, dessen Betreiber ein Konto mit der
Erstadmin-Adresse ausstellt, erhält damit kein `SYSTEM_ADMIN` — die Regel lässt sich nicht über
einen zweiten Anbieter kapern. Wer den Standardanbieter wechselt, verschiebt damit auch die
Wirkung dieser Regel; die Oberfläche benennt das beim Umstellen. Was bleibt (Grenzen): Die Regel
greift für *jedes* neu angelegte Konto mit dieser Adresse, nicht nur für das allererste, prüft
`email_verified` nicht, und der Betreiber des Standardanbieters kann so jederzeit weitere
Systemverwalter ausstellen — das ist die Vertrauensstellung des Standardanbieters, wie heute die
des einen Issuers.

**Adressprüfung (SSRF) mit eigener Allowlist, die den Bootstrap nie aussperrt.** Issuer und
JWK-Set-Adresse werden vom Backend abgerufen und laufen deshalb durch den bestehenden
`TargetAddressValidator` — aber nicht über die Indexing-Konfiguration: Ein Betreiber, der
`OPAA_INDEXING_TARGET_VALIDATION_ENABLED=false` setzt, um einen Konnektor zum Laufen zu bringen,
darf damit nicht unbemerkt die Anmeldung mitschalten, und ein Auth-Betreiber sucht seine
Freigabe nicht im Indexing-Namensraum. Die Anmeldung bekommt ihren eigenen Block
`opaa.auth.oidc.target-validation.{enabled,allowlist}` (`OPAA_OIDC_TARGET_VALIDATION_ENABLED`,
Standard `true`; `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST`), und die Ablehnung nennt diese Variable.
**Die Hosts aus `OPAA_OIDC_ISSUER_URI` und `OPAA_OIDC_JWK_SET_URI` sind immer erlaubt** — sie
stammen aus der Betriebskonfiguration, derselben Vertrauensstufe wie die Allowlist selbst — und
damit auch die gesäte Zeile: Ein Upgrade einer Installation mit Keycloak auf `localhost` oder einer
privaten Adresse, der Compose-Stack (`keycloak:8180`), die E2E-Umgebung und der Demo-Smoke laufen
ohne zusätzliche Freigabe weiter. Ein Identitätsanbieter einer Behörde steht im Regelfall auf einer
privaten Adresse; wer einen **weiteren** internen Anbieter über die Oberfläche anlegt, trägt dessen
Host in die Allowlist ein — der Verbindungstest und das Speichern sagen ihm das. Die Prüfung läuft
beim Speichern (der Betreiber sieht die Meldung), beim Aufbau der Registry (eine nachträglich
verengte Allowlist oder eine direkt in der Datenbank geänderte Adresse kommt nicht vorbei; die
Zeile wird übersprungen und wie in Entscheidung 1 wieder versucht) und vor jedem Abruf des
Verbindungstests. **Auch die aus der Discovery gelesene `jwks_uri` wird vor dem ersten Abruf
geprüft** — die Registry holt das Discovery-Dokument selbst, prüft die darin genannte Adresse und
baut den Decoder erst dann auf einer festen JWK-Set-Adresse; Weiterleitungen werden bei beiden
Abrufen nicht gefolgt. Eine eigene qnop-artige Property „private Adressen erlauben" gibt es nicht —
die Allowlist ist die feinere Form.

### 4. Claim-Zuordnung, Rollen, Gruppen und Organisation je Anbieter

Jede Anbieterzeile trägt, mit Vorgaben für Keycloak:

| Feld | Vorgabe | Bedeutung |
| --- | --- | --- |
| `email_claim` | `email` | Quelle für `users.email` |
| `display_name_claim` | `name` | Quelle für `users.display_name`; Rückfall `preferred_username`, sonst leer — die heutige Kette aus `JwtUserClaims`, ohne Rückfall auf den Subject (eine rohe Kennung ist kein Anzeigename) |
| `roles_claim` | leer | Pfad zum Rollen-Claim (Punktnotation, z. B. `realm_access.roles`); leer = keine Rollenableitung |
| `system_admin_role` | leer | Rollenwert, der `SYSTEM_ADMIN` bedeutet |
| `auditor_role` | leer | Rollenwert, der `AUDITOR` bedeutet |
| `groups_claim` | leer | Pfad zum Gruppen-Claim; leer = keine Gruppen aus dem Token |

**Rollen — neu, nicht verallgemeinert.** Heute leitet OPAA keine Rolle aus dem Token ab
(Kontext); dieser ADR führt das ein. Ist `roles_claim` gesetzt, ist der Anbieter für
`SYSTEM_ADMIN` und `AUDITOR` **führend**: Bei jeder Anfrage wird die Systemrolle aus dem Token
abgeleitet — `SYSTEM_ADMIN` vor `AUDITOR`, wenn ein Token beide Werte trägt (`SystemRole` ist
einwertig) — und nur bei Abweichung geschrieben, als Rollenänderung mit Audit-Ereignis
(`SYSTEM_ADMIN_ROLE_GRANTED`/`_REVOKED`, `AUDITOR_*`) unter einem Systemprozess-Akteur nach dem
Muster von `DirectorySyncPlanExecutor.DIRECTORY_SYNC_ACTOR`; die Schreibdrosselung aus #833 bleibt
unberührt. Begründung: In einer Behörde ist die Rollenvergabe im Identitätsanbieter der
etablierte Verwaltungsweg mit eigener Nachweisführung; OPAA folgt ihm, wenn der Betreiber das
ausdrücklich konfiguriert. Drei Sicherungen gegen das Aussperren:

- **Der letzte `SYSTEM_ADMIN` wird nie per Token entzogen.** Würde ein Entzug die Installation
  ohne Systemverwalter zurücklassen, unterbleibt er, wird protokolliert und in der
  Anbieterverwaltung angezeigt — dieselbe Regel wie „der letzte Eigentümer" an anderer Stelle des
  Produkts. Ein Betreiber, der `roles_claim` setzt, ohne im Anbieter die Rollenzuordnung angelegt
  zu haben, verliert damit nicht die gesamte Systemverwaltung.
- **Die manuelle Rollenvergabe** (`AdminController`) ist für Konten eines Anbieters mit
  `roles_claim` gesperrt (`409`, „Rolle wird vom Anbieter verwaltet") — sonst schreibt ein Admin
  eine Rolle, die die nächste Anfrage wieder überschreibt.
- **Das Setzen von `roles_claim` verlangt in der Oberfläche eine ausdrückliche Bestätigung** mit
  dem Hinweis, dass der Anbieter ab jetzt führend ist (#1333).

Ohne `roles_claim` bleibt es beim heutigen Verhalten: reguläre Nutzer, Rollen werden in OPAA
verwaltet. Die Erstadministrator-Regel (Entscheidung 3) greift in beiden Fällen nur beim Anlegen.

**Gruppen — hebt eine Festlegung von ADR-0005 auf.** ADR-0005 entscheidet: „Gruppenzugehörigkeiten
kommen nicht aus dem Token, sondern über `DirectoryClient`." Für Anbieter mit gesetztem
`groups_claim` gilt das nicht mehr: Die Gruppennamen des Tokens werden bei der Anfrage zu
Mitgliedschaften in Gruppen mit eigenem `GroupKind` `IDENTITY_PROVIDER` (Erweiterung von
`chk_groups_kind` per Changeset) und `external_id` im **Namensraum des Anbieters**
(`oidc:<anbieter-id>:<gruppenname>`; bei `varchar(255)` bleiben rund 210 Zeichen für den Namen,
ein längerer wird abgelehnt und protokolliert). Gleichnamige Gruppen zweier Anbieter sind damit
zwei Gruppen; eine Gruppe eines Anbieters ist nie eine Gruppe eines anderen. Der Nachtrag in
ADR-0005 benennt die Aufhebung ausdrücklich.

**Der Verzeichnisabgleich wird an den Standardanbieter gebunden — das ist eine Codeänderung, kein
Ist-Zustand.** Heute löst `DirectorySyncPlanExecutor#resolveMembers` Mitglieder über
`findByOrganizationIdAndSubjectIn` auf, **ohne Issuer**: Sobald zwei Issuer in einer Organisation
existieren, bekäme ein Konto aus Anbieter B die Gruppenmitgliedschaften des gleichnamigen
`subject` aus Anbieter A — ein Rechteübergriff. Die Repository-Abfrage erhält den Issuer des
Standardanbieters als Parameter. Und der Abgleich verwaltet ausschließlich Gruppen des
`GroupKind` `ORG_UNIT`: Token-Gruppen (`IDENTITY_PROVIDER`) werden weder aufgelöst noch in die
Plausibilitätsschwelle des Abgleichs gezählt — gefiltert in der Abfrage, nicht erst im Plan.
Die Präzisierung von ADR-0005s Neutral-Punkt „`DirectorySyncService` setzt einen einzelnen
OIDC-Issuer je Organisation voraus" lautet damit: **einen einzelnen Verzeichnis-Anbieter je
Organisation, den Standardanbieter.** Das Gruppen- und Rollenmodell selbst bleibt Epic #1295.

**Organisation:** Alle Anbieter provisionieren in `Organization.DEFAULT_ID`, wie heute. Eine
Anbieter-zu-Organisation-Bindung ist der naheliegende spätere Schritt für echten Mehrmandantenbetrieb
(eine Spalte `organization_id` an der Anbieterzeile), wird hier aber nicht gebaut — OPAA betreibt
heute genau eine Organisation, und eine leere Vorbereitung dafür wäre spekulativ.

### 5. Abmeldung, Callback und Token-Lebensdauer je Anbieter

**Alle Anbieter teilen sich die Redirect-URI `<Origin>/auth/callback`.** Beim Callback muss die
SPA den `UserManager` des Anbieters wählen, der den Fluss gestartet hat — `oidc-client-ts` prüft
den gespeicherten Anmeldezustand gegen Authority und Client-ID der Instanz. Die SPA legt den
Anbieter des laufenden Flusses deshalb vor `signinRedirect()` im `sessionStorage` ab (per Tab, wie
der Anmeldezustand selbst — zwei Tabs mit zwei Anbietern stören sich nicht) und liest ihn im
Callback; die aktive Sitzung merkt sich ihren Anbieter ebenso. Der `localStorage` hält nur den
**zuletzt benutzten** Anbieter als Vorschlag für die nächste Anmeldung (#1332). Eine Redirect-URI
je Anbieter wäre die Alternative gewesen — sie hätte jede Anleitung in #1333 anbieterabhängig
gemacht, ohne etwas zu gewinnen.

Die Abmeldung ist ein RP-initiierter Logout beim Anbieter der **aktiven Sitzung**:
`signoutRedirect()` des zugehörigen `UserManager`, der den `end_session_endpoint` aus der
Discovery dieses Anbieters nutzt, mit `post_logout_redirect_uri = <Origin>`. Ein Anbieter ohne
`end_session_endpoint` endet in einer lokalen Abmeldung (Store zurücksetzen), was die Oberfläche
als Hinweis anzeigt. Access-Token- und SSO-Lebensdauern sind Sache des jeweiligen Anbieters; die
stille Erneuerung und die 401-Behandlung aus ADR-0005 (#737) arbeiten unverändert auf dem
`UserManager` der aktiven Sitzung, ergänzt um den `unknown_issuer`-Fall aus Entscheidung 1.
„Mit anderem Konto anmelden" schickt `prompt=login` an den gewählten Anbieter (Plugwerk
`PromptAwareOAuth2AuthorizationRequestResolver`, hier als `extraQueryParams` der SPA).

**Was der öffentliche Konfigurationsendpunkt preisgibt, ist bewusst und begrenzt:** Anzeigename,
Issuer-URI und Client-ID jedes aktivierten Anbieters — genau das, was jeder sieht, der auf der
Anmeldeseite einen Anbieter anklickt (der Browser ruft dessen Discovery und Autorisierungsendpunkt
selbst auf). Keine Claim-Zuordnung, kein Standardanbieter-Kennzeichen jenseits der Vorauswahl,
nichts über Konten. Dieselbe Abwägung wie beim Branding (#583).

### 6. Grenzen

- **Kein SAML**, keine anderen Protokolle (Kerberos weiterhin über eine Keycloak-Föderation).
- **Keine Kontenzusammenführung**, weder automatisch noch manuell — zwei Anbieter, zwei Konten.
- **Nur Public Clients mit PKCE.** Ein Anbieter, der für SPAs kein PKCE oder keinen Public Client
  zulässt, ist nicht anbindbar; ein Confidential-Client-Modus wäre Variante B.
- **Keine `aud`-Prüfung**, wie heute; `azp` wird geprüft, sofern vorhanden (Entscheidung 1).
- **Die Erstadministrator-Regel** bleibt auf der Vertrauensstellung des Standardanbieters
  gegründet (Entscheidung 3).
- **Ein neuer Anbieter-Origin braucht einen Neustart des Frontend-Containers** (CSP,
  Entscheidung 1).
- **Eine Organisation** für alle Anbieter.
- Der `dev`-Modus bleibt unverändert.

## Konsequenzen

### Positiv

- ADR-0005 bleibt in seinen Grundsätzen bestehen: zustandslos, kein eigenes Signaturgeheimnis,
  kein Anmeldeformular, Entwicklung und Betrieb üben denselben Pfad.
- Bestehende Installationen migrieren ohne Datenänderung an `users` und ohne neue
  Freigabe; der Bootstrap aus der Umgebung ist derselbe Mechanismus wie bei der
  Modellverwaltung (#756), mit einem dokumentierten Wiederanlauf.
- Ein deaktivierter Anbieter ist sofort unwirksam, ohne Sitzungsverwaltung; die SPA kann den Fall
  vom abgelaufenen Token unterscheiden.
- Die Identitätsregel ist am Schema durchgesetzt (`uq_users_subject_issuer`, eindeutiger Issuer je
  Anbieter), nicht nur in einem Service.

### Negativ

- Jeder zusätzliche Anbieter braucht Betriebsschritte außerhalb von OPAA (Public Client mit
  Redirect-URI und Web-Origin beim Anbieter, `connect-src` im Frontend-nginx samt Neustart bei
  neuem Origin, ggf. Allowlist im Backend) — die Anbieterverwaltung kann sie anzeigen, aber nicht
  ausführen.
- Dieselbe Person mit zwei Anbietern lebt in zwei Konten; ein Anbieterwechsel ohne Stichtag ist
  damit ein Nebeneinander alter und neuer Konten, bis die alten auslaufen.
- Rollen aus einem Token (Entscheidung 4) machen den Anbieter führend für `SYSTEM_ADMIN` und
  `AUDITOR`; ein Fehler in der Rollenzuordnung eines Anbieters wirkt bei der nächsten Anfrage —
  begrenzt durch den Schutz des letzten Systemverwalters, aber für jeden weiteren spürbar.
- Der Verzeichnisabgleich und die Gruppentabelle bekommen anbieterbewusste Änderungen
  (Issuer-Parameter, neuer `GroupKind`), die #1331 größer machen als das Issue heute annimmt.

### Neutral

- `opaa.auth.oidc.*` wird zum Bootstrap-Block; `spring.security.oauth2.resourceserver.jwt.*`
  verschwindet aus dem `oidc`-Profil.
- `GET /api/v1/auth/config` liefert statt `authority`/`clientId` eine Anbieterliste; beide Seiten
  liegen im selben Repository, es gibt keinen externen Client dieser Antwort.
- Die Anbieter-Registry ist eine neue prozesslokale Fundstelle im Sinne von ADR-0021 und dort
  eingetragen.

## Zuschnitt der übrigen Sub-Issues (gegen diesen ADR geprüft)

| Issue | Folgt aus diesem ADR |
| --- | --- |
| #1329 Anbieterverwaltung | Tabelle `oidc_providers` **ohne** Secret-Spalte, mit `issuer_uri` (eindeutig, normalisiert), optionalem `jwk_set_uri` und den Claim-Feldern aus Entscheidung 4 (Spalten hier, Auswertung in #1331); Seeder mit Markierung, `OPAA_OIDC_BOOTSTRAP=force`; Registry mit `AFTER_COMMIT`-Neuaufbau, Wiederholung fehlerhafter Anbieter, `azp`-Prüfung, Discovery mit geprüfter `jwks_uri` und ohne Weiterleitungen; `OidcSecurityConfig` auf `authenticationManagerResolver`, `unknown_issuer` in `WWW-Authenticate`; eigener Block `opaa.auth.oidc.target-validation.*` mit implizit erlaubten Bootstrap-Hosts; Regeln „erster Anbieter ist Standard", „Standard weder deaktivierbar noch löschbar"; Discovery-Probe; Audit-Ereignisse `OIDC_PROVIDER_*` mit `SYSTEM_SETTING` (kein Changeset am Audit-Log) |
| #1330 Anmeldefluss und Kontenmodell | `UserProvisioningFilter` unverändert auf `(issuer, subject)`; Erstadmin-Regel nur für den Standardanbieter; Test „gleiche E-Mail, zwei Anbieter, zwei Konten"; Test „deaktivierter Anbieter → 401 mit `unknown_issuer`"; Abmeldung beim Anbieter der Sitzung |
| #1331 Claim-Zuordnung | Auswertung der Felder aus Entscheidung 4; Rollen führend bei gesetztem `roles_claim` mit den drei Sicherungen; Gruppen als `GroupKind.IDENTITY_PROVIDER` im Namensraum `oidc:<anbieter-id>:` (Changeset für `chk_groups_kind`); `findByOrganizationIdAndIssuerAndSubjectIn` im Verzeichnisabgleich; Abgleich filtert `ORG_UNIT` in der Abfrage |
| #1332 Anmeldeseite | `GET /api/v1/auth/config` mit Anbieterliste (`id`, `displayName`, `issuerUri`, `clientId`, `isDefault`, `sortOrder`); ein `UserManager` je Anbieter; Anbieter des Flusses im `sessionStorage`, Vorschlag im `localStorage`; `prompt=login`; `unknown_issuer`-Behandlung im Interceptor; Abmeldung beim Anbieter der Sitzung |
| #1333 Anbieterverwaltung (UI) | Kein Secret-Feld; Felder aus Entscheidung 2 und 4, `jwk_set_uri` als Vertrauensanker benannt; Bestätigung beim Setzen von `roles_claim`; Verbindungstest; Anleitung mit `<Origin>/auth/callback`, `<Origin>`, dem `connect-src`-Schritt samt Frontend-Neustart und der Allowlist; Anzeige fehlerhafter Anbieter; Konsequenz-Hinweis beim Deaktivieren und beim Wechsel des Standardanbieters |
| #1334 E2E und Handbuch | Zweiter Realm im gebündelten Keycloak als zweiter Public Client auf demselben Origin; Szenario im **Demo-Smoke-Ziel** (`e2e/demo-smoke.env`, `docker,oidc` — die reguläre Suite fährt `dev`); Handbuchkapitel mit Bootstrap aus `OPAA_OIDC_*`, `OPAA_OIDC_BOOTSTRAP=force`, `OPAA_OIDC_TARGET_VALIDATION_*`, Betriebsschritten je Anbieter (Client, CSP, Neustart) und Grenzen; `.env.docker.example` und `docs/handbuch/deployment.md` ziehen nach |

### Was in den Sub-Issues zu korrigieren ist

- **#1329:** Abnahmekriterium „Secret verschlüsselt gespeichert, nie lesbar" entfällt (Public
  Clients); ersetzt durch „kein Secret im Datenmodell, `azp`-Prüfung und Adressprüfung mit Test
  belegt". Die Aufgabe „private Ziele nur per Betriebs-Property, Muster qnop `OidcSsrfPolicy`"
  wird zur eigenen Allowlist mit implizit erlaubten Bootstrap-Hosts.
- **#1331:** Die Prämisse „Zuordnung von Rollen und Gruppen aus den Token-Claims, die heute für den
  einen Issuer fest verdrahtet ist" ist falsch — es gibt sie heute nicht; das Issue führt sie ein
  und wächst um den Verzeichnisabgleich (Issuer-Parameter, `GroupKind`-Filter) und den Schutz des
  letzten Systemverwalters.
- **#1333:** Aufgabe und Abnahmekriterium zum Client-Secret („write-only, leer = unverändert")
  entfallen; hinzu kommen die Bestätigung für `roles_claim`, die CSP/Neustart-Anleitung und die
  Anzeige fehlerhafter Anbieter.
- **#1330/#1332:** ergänzt um den `unknown_issuer`-Fall.

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
  Vorrang bei jedem Lesen zu erklären wäre; der einmalige Seed mit Markierung plus
  `OPAA_OIDC_BOOTSTRAP=force` ist das Muster, das #756 etabliert hat, um den Wiederanlauf ergänzt.
- **Rollen aus dem Token nur additiv** (geben, nie entziehen): sicherer gegen Aussperren, aber
  ein entzogenes Recht im Anbieter bliebe in OPAA bestehen — genau das Prüferproblem, das
  `access-control.md` für Gruppen beschreibt. Stattdessen Entzug mit Schutz des letzten
  Systemverwalters.
- **Redirect-URI je Anbieter** für den Callback: macht jede Anleitung anbieterabhängig; der
  Anbieter des laufenden Flusses im `sessionStorage` löst dasselbe Problem.
- **Home-Realm-Discovery über die E-Mail-Domäne** statt Anbieterwahl: erfordert eine
  Domänen-zu-Anbieter-Tabelle und einen zweiten Eingabeschritt vor jeder Anmeldung; die
  Anbieterwahl per Button ist einfacher und entspricht dem Vorbild. Kann später auf der
  Anbieterzeile ergänzt werden, ohne diesen ADR zu ändern.
- **Wiederverwendung der Indexing-Allowlist** für die Anmeldung: koppelt zwei Betriebsentscheidungen
  aneinander (M10 des Reviews) — eigener Block stattdessen.
- **Eigene Property „private Adressen erlauben"** (qnop `OidcSsrfPolicy`): eine Host-Allowlist ist
  die feinere Form und dem Betrieb aus dem Confluence-Konnektor bereits bekannt.

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
