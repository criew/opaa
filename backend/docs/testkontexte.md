# Spring-Testkontexte

Nachschlagewissen zu den Regeln in [backend/AGENTS.md](../AGENTS.md#spring-testkontexte): welche
Signaturen es gibt und warum, was einen Kontext spaltet, wie die Suite ihre eine Datenbank sauber
hält und welche Wächter das prüfen.

## Die zehn Signaturen

Die gesamte Backend-Suite läuft auf **zehn** Spring-Kontexten und damit zehn Testcontainers-Postgres
je Test-JVM (Issues #1481, #1543 und #1563; `TestcontainersConfiguration` deklariert den Container
als gewöhnliches Singleton-`@Bean`, es gilt also exakt: ein Kontext = ein Container). Jeder
Backend-Test, der einen Anwendungskontext startet, trägt genau eine der zehn Meta-Annotationen aus
`io.opaa.test` (`backend/src/test/java/io/opaa/test/`) — niemals eine eigene
`@SpringBootTest`/`@ActiveProfiles`/`@Import`/`@Testcontainers`-Kombination. Vier davon bilden die
Familie des `local,dev`-Profils, sechs die des Betriebsmodus `oidc`:

- **`@OpaaIntegrationTest`** — die kanonische Signatur, die drei Viertel der Suite tragen: echtes
  Postgres, ganze Anwendung auf `RANDOM_PORT`, MockMvc (`@AutoConfigureMockMvc`),
  `@ActiveProfiles({"local","dev"})`, die festen Test-Properties der Annotation (Chunking,
  Upload-Grenzen, abgeschaltetes Rate-Limit, OIDC-Allowlist, nie antwortende Adressen für Chat-
  und Embedding-Modell, siehe unten) und die geteilten Ersatz-Beans aus
  `OpaaTestBeans` (`FakeEmbeddingModel`, `ChatModel`-Mock, `FakeDirectoryClient`, die Umlenkung
  von OneDrive auf die Testdoppel, siehe unten) sowie die
  `@MockitoSpyBean`-Spies auf `UserRepository`, `ChatMessageRepository` und
  `DirectorySyncStatusRecorder`.
- **`@OpaaMockedChatModelIntegrationTest`** — dieselbe Basis, aber `ActiveChatModelResolver` als
  Mock und der Chat-Titel-Executor synchron. Technischer Grund: zwei Klassen prüfen den **echten**
  Resolver, davon eine innerhalb der Produktionsverdrahtung.
- **`@OpaaMockedDocumentServiceIntegrationTest`** — dieselbe Basis, aber `DocumentService` (und der
  Resolver) als Mock, Chat-Titel-Executor asynchron wie in Produktion. Technischer Grund: zwei
  Klassen brauchen ein gescriptetes `parseDocument`, was für die zwei Dutzend Klassen, die echte
  Fixtures indexieren, nicht neutral ist.
- **`@OpaaPropertyVariantIntegrationTest`** — dieselbe Basis plus die Properties, die selbst
  Prüfgegenstand sind (abweichende Embedding-Basis-URL, feste pgvector-Dimension ohne
  Schema-Initialisierung). Technischer Grund: eine Nachbarklasse prüft genau den Default, und der
  Abgleich des Einbettungsindex (`EmbeddingIndexReconciler`) zerstört und erzeugt `vector_store` neu.
  Mit fährt das abgeschaltete Feature-Flag `EXTERNAL_ACCESS` (ADR-0044): Ob Filterkette, Routen und
  MCP-Server fehlen, zeigt nur ein ohne sie gestarteter Kontext.

Sechs weitere tragen den Betriebsmodus **`oidc`**, den die vier oben nicht liefern können: Unter
`local,dev` authentifiziert `DevAuthFilter` jede Anfrage, bevor ein Bearer-Token gelesen wird, eine
lokale Sitzung ist dort nicht fahrbar (ADR-0033, #1543):

- **`@OpaaLocalAuthMockMvcTest`** — die Basis: Profil `oidc`, MockMvc, geteiltes Postgres, ein
  starkes Test-Secret (sonst verweigert `LocalAuthSecretGuard` den Start) und angehobene
  `opaa.rate-limit.local-auth.*`-Grenzen, weil alle Anmeldungen von der einen Adresse von MockMvc
  kommen.
- **`@OpaaLocalAuthLinkTest`** — plus `opaa.public-base-url` und Einstellungs-Schlüssel; ohne
  Basis-URL sind die Link-Flüsse abgeschaltet, und Klassen auf der Basis prüfen genau das.
- **`@OpaaLocalAdminRestrictionTest`** — plus Netzbeschränkung lokaler Systemverwalter, die alle
  übrigen Klassen der Familie aussperren würde.
  Mit fährt das abgeschaltete Feature-Flag `EXTERNAL_ACCESS`: was ein Tokenwert unter `oidc` ohne
  die Filterkette des Kanals trifft.
- **`@OpaaLocalAuthRateLimitTest`** — mit den **echten** Grenzen, die hier Prüfgegenstand sind.
- **`@OpaaLocalAuthProviderTest`** — plus `OidcProviderTokenTestConfiguration` (echter
  `NimbusJwtDecoder` über einen lokal erzeugten Schlüssel statt eines JWK-Sets aus dem Netz) für
  ein prüfbares Anbieter-Token bei der Übergabe eines lokalen Kontos (ADR-0033, Entscheidung 12).
  Nicht in die Basis ziehen: Klassen daneben fahren den Decoder des lokalen Issuers.
- **`@OpaaManagementPortTest`** — Profil `oidc` auf `RANDOM_PORT` mit eigenem Management-Port
  (`management.server.port=0`), wie das Helm-Chart den Dienst betreibt. Technischer Grund: Der
  eigene Port startet einen Kindkontext mit eigenem Webserver, den es nur neben einem echten Server
  gibt, und nimmt alle Actuator-Pfade vom API-Port, auf dem Klassen der Basis Health und Info über
  MockMvc lesen. Nicht über die Basis meta-annotiert, weil diese an MockMvc ohne echten Port hängt.

Die Varianten sind jeweils über die Basis ihrer Familie meta-annotiert und ergänzen nur ihre
Abweichung. Eine Variante muss **nicht fachlich zusammengehören**: Wo eine Abweichung ohnehin einen
eigenen Kontext kostet, fährt fachlich Unverwandtes bewusst mit (der S3-Upload-Speicher in der
Chat-Modell-Signatur, das Test-Dokumentformat in der `DocumentService`-Signatur). **Ein Kontext mehr
braucht eine harte technische Begründung, nicht eine fachliche Zugehörigkeit** (Maßgabe des
Maintainers vom 11.09.2026).

## Was einen Kontext spaltet

**Was einen Kontext spaltet — und wohin es stattdessen gehört.** Spring cached Kontexte anhand der
exakten, zusammengeführten Konfiguration (`MergedContextConfiguration`). Eine klassenlokale
`@DynamicPropertySource`, ein klassenlokales `@Import`, ein `@MockitoBean`/`@MockitoSpyBean`/
`@TestBean`-Feld oder eine innere `@TestConfiguration` erzeugt trotz gemeinsamer Meta-Annotation
einen eigenen Kontext und einen eigenen Container. Deshalb:

- **Konstanter Wert** → `properties` der Meta-Annotation.
- **Zur Laufzeit aus einer Ressource gelesener Wert** (Containeradresse, prozessweites
  Basisverzeichnis) → ein `ApplicationContextInitializer` der Meta-Annotation
  (`OpaaTestPathInitializer`, `OpaaTestTargetAllowlistInitializer`, `OpaaS3UploadStoreInitializer`).
  Eine klassenlokale `@DynamicPropertySource` spaltet den Kontext auch bei identischem Wert, weil
  Spring die Methode selbst in den Cache-Schlüssel einbezieht.
- **Ersetzte oder ergänzte Bean** → `OpaaTestBeans` (bzw. die `@MockitoSpyBean`-Liste der
  Meta-Annotation). Ein Spy delegiert an die echte Bean und ist damit für alle anderen Klassen
  verhaltensneutral; ein voller Mock ist es fast nie.
- **Eigenes Verzeichnis, dessen Pfad in eine Property fließt** → `OpaaTestDirectory.subdirectory(name)`
  unter dem einen prozessweiten Basisverzeichnis. Ein `@TempDir` bliebe je Klasse verschieden und
  spaltete damit den Kontext. Ein `@TempDir`, das nur im Test selbst verwendet wird und keine
  Property speist, ist unverändert in Ordnung.

## Kein Test erreicht ein echtes Modell

**Kein Test erreicht ein echtes Modell.** Die Testdatenbank richtet sich beim ersten Start ein wie
der Modus `dev` (ADR-0060): mit dem Chat-Modell unter `spring.ai.openai.base-url` und dem
Embedding-Modell unter `spring.ai.openai.embedding.base-url`; die `oidc`-Familie bekommt dafür die
Test-Hilfe `TestInstallation`, die vor der Startentscheidung abschließt und das Embedding-Modell
einrichtet, weil nur der Modus `dev` sich selbst einrichtet. Der Vorgabewert aus `application.yml` wäre ein lokal laufendes Ollama, das die Suche
(Zerlegung in Teilfragen) dann wirklich aufruft. Deshalb setzen `@OpaaIntegrationTest` und
`@OpaaLocalAuthMockMvcTest` die gemeinsame und die Embedding-Adresse auf Hosts unter `.invalid`
(`OpaaIntegrationTest.CHAT_BASE_URL`, `EMBEDDING_BASE_URL`). `TestChatModelEndpointTest` prüft das für jede Signatur ohne Docker,
`ModelBaseUrlDefaultsTest` die Vorgabewerte aus `application.yml`. Eine neue Basis-Signatur
übernimmt die Properties (#2600).

## Reset geteilter Mocks und Fakes

**Jeder geteilte Mock oder Fake braucht einen Reset je Testmethode.** Für `@MockitoBean`/
`@MockitoSpyBean` erledigt das Spring selbst; für die Fakes aus `OpaaTestBeans` tut es
`OpaaTestBeanResetListener`. Ohne ihn erbt eine künftige Klasse stillschweigend den Zustand, den die
vorherige Testmethode hinterlassen hat. `@TestExecutionListeners` gehen nicht in den Cache-Schlüssel
ein, erzeugen also keinen zusätzlichen Kontext.

**Microsoft Graph und Entra im Kontext.** Die festen Adressen von OneDrive lenkt
`OneDriveTestSeam` um, ein `BeanPostProcessor` aus `OpaaTestBeans` (ADR-0058, Entscheidung 6). Er
ersetzt `oneDriveSourceConnector` durch einen Konnektor gegen `FakeGraphServer.shared()` und
`FakeAuthorizationServer.shared()` und `sharePointGraphConnections` durch Verbindungen mit dem
HTTP-Client des Fakes. Danach vertrauen **alle** Graph-Verbindungen des Kontexts, auch die von
SharePoint, nur dem Zertifikat des Fakes, mit 5 s Verbindungs-Timeout; ein künftiger verdrahteter
SharePoint-Test läuft deshalb gegen denselben Fake. SharePoint behält seine feste Adresse. Den
geteilten `FakeGraphServer` setzt `OpaaTestBeanResetListener` vor jeder Testmethode zurück
(`FakeGraphServer.resetShared()`); eine Klasse richtet ihre Laufwerke in `@BeforeEach` ein.

## Eine Datenbank für die ganze Suite

**Eine Datenbank für die ganze Suite.** Eine Klasse räumt in `@AfterEach` hinter sich auf und prüft
**nie** gegen eine ungefilterte Tabelle, sondern nur gegen Zeilen ihrer eigenen IDs. **Nur** in
`@BeforeEach` aufzuräumen ist unzulässig (#1510): Es verlagert die Arbeit auf die nächste Klasse und
setzt voraus, dass die den fremden Bestand kennt. In beiden Haken aufzuräumen ist dagegen richtig —
der `@BeforeEach`-Teil schützt gegen eine Methode, die auf halbem Weg abgebrochen ist. Ein
pauschales `deleteAll()` über eine Tabelle, in die auch andere Klassen schreiben (`users`,
`knowledge_libraries`), ist ein Fehler, kein Aufräumen — es scheitert spätestens an einer
RESTRICT-Fremdschlüsselbeziehung einer fremden, noch gebrauchten Zeile. Ebenso wenig räumt eine
Klasse vorsorglich hinter einer namentlich genannten anderen her; stattdessen wird die verursachende
Klasse repariert. `LeftoverRowGuard` zählt `documents`, `knowledge_libraries`, `assets`, `vector_store`,
`diagnostic_impersonation_grants` und `audit_incident_scope_grants` zu Beginn und am Ende jeder
Testklasse, jeweils erst wenn alle Task-Executor des Kontexts leer sind, und nennt die Klasse, nach
der eine Zählung gewachsen ist — nur sie, nicht jede folgende.

**Installationsweite Einstellungszeilen** (`branding_settings`, `audit_retention_settings`,
`diagnostic_context_retention_settings`, `local_auth_settings`, `mail_settings`, `mail_templates`,
die Seed-Marker, die Tagesmerker der Erinnerung zur Kontenprüfung in `admin_review_reminders`) haben keine eigene ID, auf die eine Klasse ihr Aufräumen eingrenzen könnte.
`SeededRowRestorer` sichert sie zu Beginn jeder Klasse und stellt sie nach jeder Testmethode wieder
her, nach deren `@AfterEach` — auch wenn dort eine Aufräumanweisung geworfen hat. Eine weitere
solche Tabelle gehört in seine Liste. **Trägt die Tabelle auch Zeilen, die eine Testklasse selbst
schreibt** (`capability_grants`, `capability_grant_history`), wird sie dort als `BY_ID` geführt: Der
Restorer merkt sich die vorgefundenen IDs und fasst nur die an. Ein Prädikat über die Zeilenform
träfe die eigene Zeile einer Klasse mit — und nähme ihr damit das Aufräumen ab, das
`LeftoverRowGuard` einfordert.

## Wächter der Signaturen

**`SpringContextSignatureTest` zieht die Grenze maschinell.** Er baut über
`BootstrapUtils.resolveTestContextBootstrapper(...)` je Testklasse die `MergedContextConfiguration`,
ohne einen Kontext zu starten, gruppiert danach und schlägt fehl, sobald eine Klasse ihre Signatur
verlässt oder eine zehnte Signatur entsteht. Eine neue kanonische Signatur wird dort bewusst
eingetragen; ein Code-Kommentar als Begründung genügt nicht mehr (nach #843 hatte jede der
gewachsenen 23 Kontext-Varianten einen formal regelkonformen Kommentar). Er prüft außerdem je
Klasse, dass `LeftoverRowGuard` und `SeededRowRestorer` als Listener aufgelöst werden — eine neue
Basis-Signatur muss beide registrieren.

## Objektspeicher-Fixture

**Objektspeicher-Fixture (`io.opaa.s3.S3TestFixture`, foundation):** läuft in `test`, sobald Docker
erreichbar ist, mit **einem** geteilten S3-Speicher je Test-JVM (Image `rustfs/rustfs`) und im
`@OpaaIntegrationTest`-Kontext, ohne zweiten Container. Eingeschränkte Schlüssel legt
`S3TestFixture.createUser(policyJson)` über die MinIO-kompatible Admin-API des Images an (SigV4,
Klartext-Nutzlast). Wer das Image wechselt, prüft zuerst diese Aufrufe und die beiden Fehlerformen
„darf auflisten, aber nicht lesen" und gefilterte Bucket-Liste (ADR-0027, #1949).

## Ausnahmen

Ein neuer Postgres-Container wird nie manuell deklariert; `@ServiceConnection` kommt aus der
Meta-Annotation. Ausnahme: `io.opaa.migration`-Tests booten bewusst einen eigenen Container mit
Template-Datenbank pro Klasse (siehe `AbstractMigrationTest`) — das Muster ist dort nötig und keine
Abweichung von dieser Regel. Ebenfalls außerhalb: die `@WebMvcTest`-Slices, die weder einen
Anwendungskontext noch eine Datenbank starten.
