# ADR-0021: OPAA ist bis auf Weiteres Single-Instance

## Status

Akzeptiert (Maintainer-Entscheidung vom 24.08.2026)

## Kontext

Das Backend trifft an mehreren voneinander unabhängigen Stellen die Annahme, dass zur Laufzeit genau
ein Backend-Prozess läuft: prozesslokale Caches ohne verteilte Invalidierung, `@Scheduled`-Methoden
ohne Leader-Election, und mindestens eine Recovery-Routine, deren Korrektheit bei mehreren
gleichzeitig laufenden Instanzen zusammenbricht. Keine dieser Stellen ist falsch für den heutigen
Betrieb — die Demo-Instanz (`opaa.ewerlin.com`) und jede bekannte Zielumgebung laufen mit genau einem
Backend-Prozess. Problematisch ist, dass die Annahme nirgends als Entscheidung festgehalten ist,
sondern nur implizit aus der Summe dieser Stellen erschließbar war. Das führt zu zwei konkreten
Schäden:

- Neue Stellen wiederholen dieselbe Annahme, ohne dass sie geprüft oder auch nur bewusst getroffen
  wird — sie entsteht als Nebenwirkung der einfachsten Implementierung, nicht als Entscheidung.
- Javadoc an verschiedenen Stellen widerspricht sich in der Frage, ob Multi-Instanz-Betrieb bereits
  teilweise berücksichtigt ist oder nicht (siehe `IndexingJobService.recoverJobsOrphanedByRestart`,
  Abschnitt „Widerspruch zwischen Scheduler-Javadoc und Recovery-Verhalten" unten).

Dieses ADR macht die Annahme explizit, listet die bekannten Fundstellen und legt fest, wie mit neuen
Stellen dieser Art umzugehen ist. Es trifft **keine** Entscheidung, Multi-Instanz-Betrieb zu bauen —
das bleibt eine spätere, eigene Entscheidung, sobald ein tatsächlicher Bedarf (Hochverfügbarkeit,
horizontale Skalierung) entsteht.

## Entscheidung

OPAA läuft bis auf Weiteres als **genau ein Backend-Prozess** pro Installation. Prozesslokaler
Zustand (In-Memory-Caches, `@Scheduled`-Zustand, Recovery-Annahmen über "die eine laufende Instanz")
ist unter dieser Annahme korrekt und braucht keine verteilte Koordination.

### Bekannte Fundstellen

**Prozesslokale Caches ohne verteilte Invalidierung** (prozesslokal, meist Caffeine-basiert,
invalidiert nur im eigenen Prozess — bei mehreren Instanzen sieht jede Instanz ihre eigene,
potenziell veraltete Kopie):

| Fundstelle | Zustand | Invalidierung |
| --- | --- | --- |
| `GroupMembershipResolver.groupIdsByUser` | Gruppenmitgliedschaften pro Nutzer (max. 50.000 Einträge) | 10 Minuten TTL, gezielt bei Mitgliedschaftsänderung (`invalidate`/`invalidateAll`) |
| `AssetAccessService.grantsByAsset` | Zugriffsrechte pro Asset (max. 50.000 Einträge) | 10 Minuten TTL, gezielt nach Commit bei Grant-Änderung — mirrort `GroupMembershipResolver`s Muster bewusst |
| `SpaceService.personalSpaceProvisioned` | Flag "persönlicher Space bereits angelegt" pro Nutzer (max. 50.000 Einträge) | kein TTL, nur additiv gesetzt (Flag kann nie fälschlich `true` werden, nur fälschlich fehlen) |
| `RateLimitService.requestLog` | Zeitfenster-Anfragehistorie pro Client-IP | `expireAfterAccess`, kein aktives Invalidieren |
| `ActiveChatModelResolver.cache` | Der eine `ChatClient` des systemweit aktiven LLM-Modells (Single-Slot, kein Map-Cache) | Ereignisgesteuert via `ActiveChatModelChangedEvent` nach Commit (`TransactionalEventListener`) |
| `OidcProviderRegistry` (ADR-0025, #1329) | Ein `JwtDecoder` samt `AuthenticationManager` je aktiviertem Identitätsanbieter, geschlüsselt nach Issuer; dazu der Fehlzustand nicht aufbaubarer Anbieter | Ereignisgesteuert via `OidcProvidersChangedEvent` nach Commit (`TransactionalEventListener`); fehlerhafte Anbieter werden beim nächsten Token ihres Issuers nach kurzer Wartezeit erneut versucht |
| `CaffeineChatMemoryRepository` | Chatverlauf, LRU auf 50 gleichzeitige Konversationen begrenzt | TTL nach letztem Zugriff. Seit #525 nicht mehr die Wahrheit, sondern eine Leseoptimierung vor `chat_messages`: `QueryService` lädt den Eintrag bei einem Fehlgriff aus dem persistierten Verlauf nach. Ein Instanzwechsel mitten im Gespräch kostet damit einen Fehlgriff, keinen Gesprächskontext — anders als bei den übrigen Zeilen dieser Tabelle |
| `MetadataFilterOptionsCache` | Die Filteroptionen je Person **und** dem Suchraum, auf den ihre Rechte aufgelöst haben (max. 10.000 Einträge) | `expireAfterWrite` aus `opaa.query.metadata-filter.options-cache-ttl`, gezielt bei jeder Rechteänderung, die die Person berührt — dieselben Ereignisse und Haken wie bei `GroupMembershipResolver`/`AssetAccessService`, und damit dieselbe Rechtelücke bei mehreren Instanzen |
| `RerankClient.dialect` | Welche Sprechweise der konfigurierte Rerank-Endpunkt spricht, aus dem ersten erfolgreichen Aufruf gelernt | Kein Verfall; bei mehreren Instanzen handelt jede einmal selbst aus. Einzige Zeile dieser Tabelle ohne Konsistenzfolge |
| `OidcProviderRegistry`, Eintrag des lokalen Issuers ([ADR-0033](0033-lokale-benutzerverwaltung.md), #1533) | Der HS256-Decoder des lokalen Ausstellers samt Widerrufs-Validator, unabhängig vom `enabled` der `LOCAL`-Anbieterzeile registriert | Wie die übrigen Einträge ereignisgesteuert nach Commit; der Schlüssel selbst ist aus `OPAA_AUTH_JWT_SECRET` abgeleitet und ändert sich nur mit einem Neustart |
| `LocalTokenRevocationService`-Denylist (ADR-0033, #1533) | Widerrufene `jti`-Hashes lokaler Access-Tokens (Caffeine, `expireAfterWrite = access-token-ttl`) vor der Tabelle `local_revoked_tokens` | Additiv (ein Widerruf wird eingetragen, nie zurückgenommen); Verfall mit der Token-Lebensdauer. Bei mehreren Instanzen sähe Instanz B einen auf A widerrufenen `jti` erst nach dem Cache-Miss — die Tabelle bleibt die Wahrheit, der Cache nur ein Negativ-Cache für „nicht widerrufen" und müsste dann entfallen |
| Rate-Limit-Buckets der lokalen Anmeldung (ADR-0033, #1535) | Zähler je Adresse, je Konto und je Subject für Login, Refresh, Passwortwechsel, Registrierung und Passwort-vergessen; die Fehlversuch-Sperre selbst liegt in `local_credentials` | `expireAfterAccess` wie `RateLimitService.requestLog`; die Sperre nach Fehlversuchen ist bewusst **in der Datenbank**, damit sie einen Neustart überlebt |
| `ExternalAccessQuota.windows` ([ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md), #1720/#1721) | Gleitendes Stundenfenster der Aufrufe je Zugangstoken — Suchendpunkt und MCP-Werkzeuge zählen in dasselbe Fenster | `expireAfterAccess` wie `RateLimitService.requestLog`; bewusst nur im Arbeitsspeicher, weil eine persistierte Zählung je Token die Abfragehistorie einer Person wäre. Bei mehreren Instanzen bekäme ein Token das N-fache Kontingent |
| `ExternalAccessMassRetrievalAlarm` ([ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md), #1720/#1721) | Gleitendes Zählfenster der Abrufe des **ganzen Kanals** samt Abklingzeit nach einem Alarm | Wie oben: nur im Arbeitsspeicher, überlebt keinen Neustart, kein Nutzungsnachweis. Bei mehreren Instanzen schlägt der Alarm erst an, wenn eine einzelne Instanz die Schwelle für sich sieht |
| `MailSenderProvider` und die Snapshots von `mail_settings`/`local_auth_settings` (ADR-0033, #1536, #1537) | Der aus den Einstellungen gebaute `JavaMailSenderImpl` und die effektiven, entschlüsselten Einstellungswerte | Ereignisgesteuert nach Commit (`TransactionalEventListener`), Muster `ActiveChatModelResolver` |

Bei mehreren Instanzen sieht jede ihre eigene Kopie: eine Rechteänderung, die auf Instanz A verarbeitet
wird, invalidiert nicht den Cache auf Instanz B — eine Anfrage, die zufällig auf B landet, sieht bis
zum TTL-Ablauf den alten Stand. Für `personalSpaceProvisioned` ist das harmlos (das Flag kann nur
fälschlich fehlen, nie fälschlich vorhanden sein), für die anderen wäre es eine echte
Rechte-/Konsistenzlücke.

**Die rein ereignisgesteuerten Einträge sind der härtere Fall, nicht der mildere.** `ActiveChatModelResolver`,
beide `OidcProviderRegistry`-Zeilen, `MailSenderProvider` und die Einstellungs-Snapshots werden
**ausschließlich** über `TransactionalEventListener` invalidiert, also über einen rein prozessinternen
Bus, und haben keinen Zeitverfall, der die Lücke irgendwann von selbst schließt. Bei mehreren
Instanzen bliebe eine Änderung auf jeder Instanz, die sie nicht selbst entgegengenommen hat, **bis zu
deren Neustart** wirkungslos — ein Modellwechsel, geänderte Mail-Einstellungen und, sicherheitsrelevant,
ein deaktivierter Identitätsanbieter. Ein TTL-Verfall begrenzt eine Lücke, hier gibt es keinen.

**`@Scheduled` ohne Leader-Election, und eine vergleichbare Start-Aktion** (bei mehreren Instanzen
feuert jede ihre eigene Kopie, unkoordiniert):

| Fundstelle | Auslöser | Bemerkung |
| --- | --- | --- |
| `AuditRetentionScheduler.deleteExpiredAuditLogPartitions` | monatlich | Ein doppelter Lauf löscht nichts zusätzlich: Der Schnitt folgt seit Changeset 078 (#1851) allein der eingestellten Frist, ist also für jeden Aufruf desselben Monats derselbe, und der zweite findet die Partitionen bereits entfernt. `SELECT ... FOR UPDATE` serialisiert zwei gleichzeitige Aufrufe, und `last_cutoff` ist eine Hochwassermarke, die nie zurückgeht. Die Sicherheit steckt in der Idempotenz des Partitions-Drops und dieser Sperre, nicht in einer Kalenderlogik |
| `LibraryIndexingScheduler.triggerDueLibraries` | jede Minute | Doppelte Trigger derselben fälligen Bibliothek werden durch `uk_indexing_jobs_library_running` (Migration 028) auf Datenbankebene abgefangen — die Instanz, die den Unique-Constraint verletzt, bucht das als Skip. `lastTickAt` (Rückschaufenster gegen Jitter zwischen zwei Ticks) ist zusätzlich rein prozesslokaler Zustand, der bei mehreren Instanzen pro Prozess getrennt geführt wird |
| `IndexingJobRecoveryScheduler.recoverStaleRunningJobs` | alle 15 Minuten | Fails jeden Job, dessen `lastProgressAt`-Heartbeat zu alt ist |
| `LocalTokenCleanupScheduler` ([ADR-0033](0033-lokale-benutzerverwaltung.md), #1533) | täglich | Löscht Zeilen aus `local_refresh_tokens`, `local_revoked_tokens` und `local_action_tokens` spätestens sieben Tage nach Ablauf **oder** Widerruf, sperrt lokale Konten nach der konfigurierten Inaktivitätsfrist und verschickt Ablauf-Erinnerungen. Doppelte Läufe sind idempotent (Löschung nach Zeitstempel, Sperre als bedingter `UPDATE`); bei mehreren Instanzen liefe er mehrfach, ohne Schaden, aber mit doppelten Erinnerungsmails |
| `DiagnosticContextRetentionScheduler.deleteExpiredPartitions` (ADR-0015) | monatlich | Wie `AuditRetentionScheduler` und aus demselben Grund: gleicher Schnitt je Aufruf, `SELECT ... FOR UPDATE` in `opaa_diagnostic_context_delete_expired_partitions`; ein doppelter Lauf löscht nichts zusätzlich |
| `RerankModelRole.probePeriodically` | alle `PROBE_INTERVAL_MILLIS` | Reine Sondierung ohne Schreibwirkung. Bei N Instanzen die N-fache Sondierungslast gegen den Rerank-Endpunkt, und jede Instanz kennt nur ihr eigenes Ergebnis (siehe „Prozesslokale Zähler" unten) |
| `IndexingJobRecoveryScheduler.recoverOnStartup` | Prozessstart (`ApplicationReadyEvent`, kein `@Scheduled`) | Ruft `IndexingJobService.recoverJobsOrphanedByRestart` auf — siehe Widerspruch unten |
| `UploadPendingRecoveryRunner` (#614, ADR-0030 Entscheidung 7) | Prozessstart (`ApplicationRunner`, kein `@Scheduled`) | Dieselbe Prämisse wie `recoverJobsOrphanedByRestart`, nur für den Upload-Pfad: setzt jede `PENDING`-Zeile auf `FAILED`, die älter ist als `opaa.upload.pending-recovery-threshold-minutes`. Die Schwelle mildert den Multi-Instanz-Fall, beseitigt ihn nicht — ein groß geratenes, langsam geparstes Dokument einer anderen Instanz überschreitet sie. Anschließend `UploadedOriginalStore.recoverAfterRestart()`, in der S3-Ausprägung ein `sweepTempDirectory()`: auf einem geteilten Temp-Volume löschte der Start der einen Instanz die Arbeitsdateien laufender Uploads der anderen |

**Widerspruch zwischen Scheduler-Javadoc und Recovery-Verhalten:**
`LibraryIndexingScheduler`s Javadoc beschreibt explizit ein Szenario mit mehreren Instanzen ("Multiple
backend instances ticking the same due library at the same minute...") und erklärt, warum der
Unique-Index diesen einen Fall absichert. Das erweckt den Eindruck, Multi-Instanz-Betrieb sei für die
Indizierung bereits teilweise tragfähig. `IndexingJobRecoveryScheduler.recoverOnStartup` widerlegt das:
bei jedem Prozessstart ruft es `IndexingJobService.recoverJobsOrphanedByRestart` auf, das **jede** noch
`RUNNING` markierte Job-Zeile failt, mit der Begründung, "a fresh JVM cannot possibly still be running
the task any such row refers to". Diese Prämisse gilt nur, wenn genau eine Instanz existiert. Bei
mehreren Instanzen würde der Neustart einer Instanz A die noch laufenden, legitimen Jobs einer Instanz
B als verwaist abbrechen — der Unique-Index schützt vor doppelten Läufen, nicht vor diesem Fall. Die
Javadoc von `LibraryIndexingScheduler`, `IndexingJobService.recoverJobsOrphanedByRestart` und
`IndexingJobRecoveryScheduler` sind im Zuge dieses ADR korrigiert: Der Unique-Index macht
ausschließlich das gleichzeitige Anstoßen desselben fälligen Laufs sicher, nicht Multi-Instanz-Betrieb
im Allgemeinen.

**Fehlende Serialisierung konkurrierender Läufe:**

`DirectorySyncService` stand hier ursprünglich als erste Fundstelle und ist mit #1711 erledigt:
`DirectorySyncRunLock` hält ein `pg_try_advisory_xact_lock` auf der `organizationId` über den
gesamten Lauf, den Verzeichnisabruf eingeschlossen, und weist einen zweiten Lauf derselben
Organisation mit einer `ConflictException` (409) ab, statt ihn warten zu lassen. Der Lauf ist damit
schon im Einprozessbetrieb serialisiert — zwei Administratoren oder ein Doppelklick genügten vorher
für eine Überlappung. Weil die Sperre in der Datenbank liegt und nicht im Prozess, trägt sie auch
über mehrere Instanzen.

| Fundstelle | Zustand |
| --- | --- |
| `MetadataBackfillService`, `ContextPrefixRerunService`, `PipelineReindexService` | Die drei ausdrücklich angestoßenen Wartungsläufe haben keine Entsprechung zu `uk_indexing_jobs_library_running`. Sie sind idempotent und wiederaufnehmbar, die Korrektheit hängt also nicht daran — wohl aber die Kosten: zwei gleichzeitige Läufe über derselben Bibliothek bedeuten doppelte Einbettungs- und Modellaufrufe auf demselben Endpunkt. Heute braucht es dafür zwei Administratoren im selben Moment; bei mehreren Instanzen genügt ein Doppelklick, den der Lastverteiler auf zwei Instanzen legt |

**Prozesslokale Task-Executor-Warteschlangen** (Grund, warum eine `RUNNING`-Zeile implizit "läuft auf
mir" statt "läuft auf irgendeiner Instanz" bedeutet - der `@Async`-Task, der sie abarbeitet, ist immer
an den JVM-Prozess gebunden, der ihn eingereiht hat):

| Fundstelle | Zustand |
| --- | --- |
| `IndexingConfiguration.indexingTaskExecutor`, `.embeddingTaskExecutor`, `.uploadTaskExecutor` | Drei `ThreadPoolTaskExecutor`-Bohnen mit eigener, rein prozessinterner Warteschlange - eine Zeile, die auf Instanz A als `RUNNING` eingereiht wurde, hat auf Instanz B keinen wartenden Task, den ein Neustart von B jemals hätte abbrechen können |

**Prozesslokale Warteschlange mit eigener Entprellung** (kein `@Scheduled` und kein Task-Executor,
sondern eine dritte Bauart — eine Map im Prozess plus ein eigener `TaskScheduler`):

| Fundstelle | Zustand |
| --- | --- |
| `SourceEventIntake.pending` | Der gesamte Push-Pfad (Confluence-Webhooks, S3-Ereignisbenachrichtigungen) sammelt die gemeldeten Schlüssel je Bibliothek in einer prozesslokalen `HashMap` und stößt `debounce` später einen gezielten Lauf an. Bei mehreren Instanzen verteilt der Lastverteiler die Benachrichtigungen, es entstehen also mehrere Entprellfenster je Bibliothek. Schwerer wiegt, dass die Zusage der Klasse bricht: „ein verworfener Stapel kostet Aktualität, nie Korrektheit, weil der nächste Lauf dieselben Schlüssel abdeckt" gilt nur, solange der laufende Lauf der eigene ist — ist es ein gezielter Lauf einer anderen Instanz über deren Schlüsselmenge, gehen die Schlüssel des verworfenen Stapels bis zum nächsten Vollabgleich verloren |

**Prozesslokale Parallelitätsbudgets** (eine Obergrenze, die als Semaphore im Prozess geführt wird,
gilt bei N Instanzen N-fach):

| Fundstelle | Zustand |
| --- | --- |
| `AttachmentExtractionLimiter` | `Semaphore` für die installationsweit gleichzeitigen Anhangsextraktionen, dazu eine Sperre je Elterndokument (`parentLocks`). Beide wirken nur im eigenen Prozess: das Budget wird vervielfacht, und zwei Instanzen können denselben Elternteil gleichzeitig extrahieren |

**Prozesslokale Zähler und Schätzwerte mit nutzersichtbarer Wirkung** (kein Konsistenzproblem der
Daten, aber eine Zahl, die je nach getroffener Instanz anders lautet):

| Fundstelle | Zustand |
| --- | --- |
| `EmbeddingRateEstimator` | Der gemessene Einbettungsdurchsatz dieses Prozesses, Grundlage der Folgekosten-Vorschau („rund 40 Minuten"). Bei mehreren Instanzen misst jede nur ihre eigenen Aufrufe — und die Schätzung wird zusätzlich inhaltlich zu optimistisch, sobald sich zwei Instanzen denselben Einbettungsendpunkt teilen und gegenseitig ausbremsen |
| `MetadataBackfillService.lastSkippedByLibrary`, `ContextPrefixRerunService.lastSkippedByLibrary` | Was der jeweils letzte Lauf einer Bibliothek nicht voranbringen konnte — veröffentlicht als `lastSkippedDocuments` in `MetadataBackfillProgress` bzw. `ContextPrefixRerunProgress`. Zwei Instanzen geben für dieselbe Bibliothek verschiedene Werte aus, je nachdem, wo der Lauf lief und wo die Abfrage landet |
| `RerankModelRole.lastKnown`, `.degradedCalls` | Zuletzt sondierter Zustand der Rerank-Rolle und die Zahl der Aufrufe ohne verwertbare Rangfolge seit Prozessstart. Der Zähler ist ausdrücklich als „zwei Lesungen vergleichen" gedacht; über mehrere Instanzen vergliche man zwei verschiedene Zähler |
| Die Micrometer-Zähler und -Gauges allgemein, z. B. `opaa.conversations.active` | Je Prozess geführt. Ein Prometheus muss jede Instanz abgreifen, und jede Oberfläche, die einen solchen Wert direkt zeigt, zeigt einen Teilwert |

**Prozesslokaler Zeitgeber** (eine Zusage, die aus einer prozessweit geführten Variable folgt — bei
mehreren Instanzen führt jede ihre eigene):

| Fundstelle | Zustand |
| --- | --- |
| `PermissionHistoryClock` ([ADR-0032](0032-zeitquelle-rechtehistorie.md), #1497) | Zuletzt vergebene Intervallgrenze der Rechtehistorie. Garantiert streng aufsteigende Grenzen für aufeinanderfolgende Zustandsänderungen desselben Objekts — je Prozess. Bei mehreren Instanzen könnten zwei Änderungen am selben Objekt aus verschiedenen Prozessen wieder dieselbe Grenze bekommen, und die Wanduhren zweier Hosts können gegeneinander driften; das Ergebnis wäre erneut ein leeres Intervall, das die Stichtags-Rekonstruktion nie meldet |
| Die Heartbeat-Fristen von `IndexingJobRecoveryScheduler.recoverStaleRunningJobs` | Kein eigener Zustand, aber dieselbe Abhängigkeit von der Wanduhr des eigenen Hosts: der Vergleich von `lastProgressAt` gegen `IndexingProperties#staleJobTimeout` ist heute ein Vergleich innerhalb eines Prozesses und deshalb driftfrei. Sobald zwei Instanzen die Läufe der jeweils anderen anhand dieses Heartbeats beurteilen sollen — die im Abschnitt „Skizze" genannte Lösungsrichtung —, entscheidet die Uhrendrift zwischen den Hosts mit, und eine vorgehende Uhr failt einen fremden, gesunden Lauf vorzeitig. Beide Seiten müssen dann aus derselben Quelle kommen, naheliegend der Datenbankuhr — dieselbe Umstellung, die `PermissionHistoryClock` ohnehin braucht (#1517) |

**Prozesslokale/knotenlokale Dateiablage:**

| Fundstelle | Zustand |
| --- | --- |
| `LibraryDocumentService` (Upload-Pfad, `opaa.upload.storage-path`, Default `./uploads`) | Speichert hochgeladene Dokumente unter `<storagePath>/<organizationId>/<libraryId>/<random-uuid><extension>` auf dem lokalen Dateisystem des Prozesses. Bei zwei Instanzen ohne geteiltes Volume: ein Upload, der auf Instanz A ankommt, ist über Instanz B nicht lesbar - 404/`FileNotFoundException`, sobald eine spätere Anfrage (Download, Re-Indizierung) zufällig auf B landet. Härteste Annahme dieser Liste: kein Cache-Verfall oder Retry hilft hier, die Datei existiert auf B schlicht nicht |
| `FilesystemPathAllowlist` (`FILESYSTEM`-Quellentyp, #484, ADR-0018) | Vom Betreiber gemounteter Nachbarfall, keine eigene Annahme dieser Anwendung: das Backend liest von per `opaa.indexing.filesystem.allowlist` konfigurierten Basisverzeichnissen. Ob mehrere Instanzen dasselbe Verzeichnis sehen, hängt vollständig davon ab, ob der Betreiber es auf jeder Instanz gleich mountet - anders als beim Upload-Pfad gibt es hier keinen anwendungsseitigen Schreibpfad, der bei fehlendem geteiltem Mount silently divergieren könnte |

### Annahmen außerhalb des Anwendungscodes

Drei Stellen sind keine Fundstelle im obigen Sinne — sie stehen in keiner Klasse, würden aber beim
Umbau gleichermaßen gebraucht und sind deshalb hier notiert, damit der spätere Scan sie nicht
übersieht.

**Betrieb: sanftes Herunterfahren und eine Readiness ohne fremde Dienste — inzwischen gebaut
(#1710).** Ursprünglich stand hier, dass `server.shutdown` nicht auf `graceful` steht und der
aggregierte `/actuator/health` über `ChatHealthIndicator`, `EmbeddingsHealthIndicator` und
`VectorStoreHealthIndicator` an der Erreichbarkeit von LLM und Vektorspeicher hängt. Beides ist
erledigt: Der Stopp lässt laufende HTTP-Anfragen innerhalb von
`spring.lifecycle.timeout-per-shutdown-phase` auslaufen — das Zeitfenster der Phase, in der die
Task-Executor stoppen, ist über `ShutdownLifecycleConfiguration` bewusst auf null gesetzt, damit ein
Indexierungslauf den Stopp nicht verzögert und wie bisher beim nächsten Start wiederanläuft —, und
`readiness` entscheidet aus Prozesszustand und Datenbank, während die drei genannten Indikatoren in
eigenen Gruppen stehen. (Die Probenpfade selbst gab es schon vorher: Spring Boot aktiviert
`management.endpoint.health.probes` in einer Webanwendung ohnehin; neu ist, was in `readiness`
steht.) Diese beiden Punkte sind damit erledigt; die übrigen Voraussetzungen dieses Abschnitts und
die knotenlokale Upload-Ablage aus dem Abschnitt darüber bleiben offen.

**Gleichzeitiger Kaltstart zweier Prozesse gegen dieselbe Datenbank.**
`spring.ai.vectorstore.pgvector.initialize-schema: true` lässt jede startende Instanz
`CREATE TABLE/INDEX IF NOT EXISTS` gegen `vector_store` absetzen; Postgres serialisiert DDL dieser
Form nicht verlässlich. Liquibase ist über seinen eigenen Lock sicher, verschiebt die Frage aber auf
die Zeitachse: die wartende Instanz kann das Start- und Healthcheck-Zeitfenster ihrer Umgebung
reißen. Ein Multi-Instanz-Deployment braucht deshalb entweder einen gestaffelten Start oder einen
eigenen Migrationsschritt vor den Anwendungsinstanzen.

**Keine Erkennung widersprüchlicher Konfiguration zwischen Instanzen.** `AuthProfileGuard`,
`PgVectorDimensionsGuard`, `OpenAiBaseUrlGuard`, `RerankRoleStartupCheck`, `LocalAuthSecretGuard` und
`TrustedProxyStartupGuard` prüfen jede Instanz für sich gegen ihre Umgebung und die Datenbank. Keiner
von ihnen bemerkt, dass eine **zweite** Instanz mit unvereinbarer Konfiguration gegen dieselbe
Datenbank läuft. Praktisch relevant für die Einbettungsdimension, den Einbettungs- und
Chat-Endpunkt, das Auth-Profil, die Proxy-Vertrauensliste und `OPAA_AUTH_JWT_SECRET`: ein
abweichendes Secret macht die lokalen Tokens der einen Instanz auf der anderen ungültig, ohne dass
irgendwo mehr auffiele als sporadische 401. Eine Instanztabelle mit Heartbeat und einem Abdruck der
harten Konfigurationswerte schlösse diese Lücke und lieferte zugleich die Instanzkennung, die der
Recovery-Umbau ohnehin braucht.

### Geprüft und ausdrücklich unproblematisch

Damit ein späterer Umbau diese Fragen nicht erneut aufwirft — der Stand zum Zeitpunkt des
Nachtrags, jeweils mit dem Grund:

- **Keine Sitzungsaffinität.** Alle Sicherheitskonfigurationen sind `STATELESS`; der CSRF-Schutz der
  lokalen Anmeldung liegt in einem Cookie (`CookieCsrfTokenRepository`), nicht in serverseitigem
  Sitzungszustand. Sticky Sessions wären nicht nötig.
- **Keine an eine Instanz gebundene Verbindung.** Es gibt keinen SSE-, Streaming- oder
  WebSocket-Endpunkt.
- **Audit-Partitionen entstehen nicht zur Laufzeit,** sondern mit festem Horizont in der Baseline —
  im Betrieb gibt es hier kein DDL-Rennen zwischen Instanzen.
- **Mehrere Stellen koordinieren bereits über die Datenbank und tragen deshalb schon heute:** die
  Advisory-Locks in `UserRepository.lockRoleChanges`, `GroupRepository` und `AssetGrantRepository`
  (und damit `LocalAdminAvailabilityGuard`), der Forward-only-Cap beider Aufbewahrungsläufe, die
  Sequenzvergabe in `ChatService.appendTurn` gegen `uk_chat_messages_chat_sequence`, die
  Prüfsummen-Deduplizierung gegen `uk_documents_library_checksum`, die adressbasierte
  Erstadministrator-Regel (`InitialAdminPolicy`, kein „erster Nutzer"-Zähler) sowie alle drei
  Seed-Läufe (`OidcProviderSeedRunner`, `LlmModelSeedRunner`, `LocalAdminSeeder`), die ihren
  Marker über eine Datenbankzusage schützen und eine `DataIntegrityViolationException` ausdrücklich
  als „eine andere Instanz war schneller" behandeln. Diese Stellen sind zugleich das Vorbild, an dem
  sich ein Umbau orientieren kann; Postgres reicht dafür.
- **Check-then-act ohne Datenbankzusage bleibt unscharf, aber nicht anders als heute:**
  `LibraryStorageQuotaService.wouldExceedQuota` (im Javadoc bereits so beschrieben) und die
  Sortiernummer in `LibraryMetadataFieldService` (`max(sortOrder) + 10`) sind schon bei zwei
  nebenläufigen Anfragen einer Instanz unscharf. Mehrere Instanzen machen das häufiger, nicht
  qualitativ anders.

### Regel für neue Stellen

Wer einen neuen prozesslokalen Cache, einen neuen `@Scheduled`-Job oder eine neue Annahme über "die
eine laufende Instanz" einführt, trägt die Stelle in die obigen Tabellen dieses ADR nach. Das ist keine
zusätzliche Hürde für die Umsetzung selbst — Single-Instance bleibt bis auf Weiteres die geltende
Annahme, prozesslokaler Zustand ist also weiterhin die richtige, einfachste Lösung. Die Eintragung
macht nur sichtbar, was bei einem späteren Multi-Instanz-Umbau geprüft werden muss, statt dass diese
Prüfung erneut durch Code-Archäologie entstehen muss.

### Skizze: Was ein Multi-Instanz-Umbau je Fundstelle bedeuten würde

Diese Skizze ist keine Umsetzungsplanung, nur eine Einordnung der Größenordnung je Kategorie:

- **Prozesslokale Caches** (`GroupMembershipResolver`, `AssetAccessService`, `SpaceService`,
  `ActiveChatModelResolver`, `CaffeineChatMemoryRepository`): Ersatz durch einen verteilten Cache
  (z. B. Redis) oder ein Pub/Sub-Invalidierungssignal, das jede Instanz zwingt, ihre lokale Kopie beim
  Empfang zu verwerfen (etwa über Postgres `LISTEN`/`NOTIFY` oder einen Message-Broker). Der reine
  TTL-Verfall reicht für die rechteempfindlichen Caches nicht aus, weil er die Lücke nur begrenzt statt
  schließt.
- **`RateLimitService.requestLog`**: Rate-Limiting über mehrere Instanzen hinweg braucht einen
  gemeinsamen Zähler (z. B. Redis mit `INCR`/TTL) statt einer prozesslokalen Deque — sonst limitiert
  jede Instanz unabhängig, und ein Client, der auf N Instanzen verteilt wird, bekommt effektiv das
  N-fache Kontingent.
- **`@Scheduled`-Jobs ohne Leader-Election** (`AuditRetentionScheduler`,
  `LibraryIndexingScheduler.triggerDueLibraries`, `IndexingJobRecoveryScheduler`): Leader-Election oder
  ein verteilter Scheduler-Lock (z. B. ShedLock), sodass nur eine Instanz pro Tick tatsächlich feuert -
  vermeidet unnötige doppelte Arbeit und Skip-Events, die sonst bei jedem gleichzeitigen Tick anfallen.
  Für `LibraryIndexingScheduler` ist ein Lock eine **Ergänzung**, kein Ersatz für
  `uk_indexing_jobs_library_running`: der Unique-Index schließt zusätzlich die In-Prozess-TOCTOU-Lücke
  zwischen dem eigenen Pre-Check und dem Insert (siehe `IndexingJobService#startJob`s Javadoc) - die
  bliebe auch mit einem Lock bestehen, der nur zwischen Instanzen koordiniert, nicht innerhalb einer.
- **`recoverJobsOrphanedByRestart`**: Darf bei Multi-Instanz-Betrieb nicht mehr pauschal jede
  `RUNNING`-Zeile failen. Braucht entweder eine Instanz-Kennung pro Job (nur Zeilen der eigenen Instanz
  beim eigenen Neustart failen) oder eine Umstellung auf ausschließlich heartbeat-basierte Erkennung
  (`recoverStaleJobs`s Ansatz), sodass ein Neustart einer Instanz die Jobs anderer, weiterhin laufender
  Instanzen nicht mehr anfasst.
- **`PermissionHistoryClock`**: Ersatz der prozesslokalen Monotonie durch eine datenbankseitige —
  die Datenbankuhr als Anker plus eine Sicherung, die die Ordnung über Verbindungen hinweg erzwingt
  (Bauart offen, siehe #1517). Eine zweite prozesslokale Variable je Instanz genügt hier ausdrücklich
  nicht (ADR-0032).
- **Task-Executor-Warteschlangen** (`IndexingConfiguration`): Folgt aus dem `LibraryDocumentService`-
  bzw. `recoverJobsOrphanedByRestart`-Umbau, kein eigenständiges Problem - sobald eine `RUNNING`-Zeile
  eine Instanz-Kennung trägt, kann jede Instanz an ihrer eigenen Warteschlange festhalten und muss nur
  noch die Zeilen der *eigenen* Instanz beim eigenen Neustart als verwaist behandeln.
- **`LibraryDocumentService`s Upload-Ablage**: Härteste Fundstelle dieser Liste - ein geteiltes Volume
  (NFS/EFS o. ä., über alle Instanzen gleich gemountet) oder ein S3-kompatibler Objektspeicher statt
  des lokalen Dateisystems. `FilesystemPathAllowlist`s Nachbarfall braucht keinen Anwendungs-Umbau,
  nur eine Betriebsvoraussetzung: dieselben Basisverzeichnisse müssen auf jeder Instanz identisch
  gemountet sein.
- **`UploadPendingRecoveryRunner`**: Derselbe Umbau wie bei `recoverJobsOrphanedByRestart` — nur eigene
  Zeilen failen, sobald eine Instanzkennung existiert. Der angeschlossene `sweepTempDirectory()`-Teil
  braucht zusätzlich ein je Instanz eigenes Arbeitsverzeichnis, sonst räumt ein Start fremde,
  laufende Uploads weg.
- **`SourceEventIntake`**: Die Entprellung müsste ihren Stapel in der Datenbank halten statt im
  Prozess, oder der Push-Pfad koordiniert sich über denselben Sperrmechanismus wie der Zeitplan.
  Nicht mit den `@Scheduled`-Jobs zusammenfassen: der Verlust liegt hier nicht im doppelten Anlaufen,
  sondern im Verwerfen eines Stapels gegen einen fremden Lauf.
- **Ereignisgesteuerte Konfigurations-Caches**: Ein Invalidierungssignal über Prozessgrenzen hinweg,
  naheliegend Postgres `LISTEN`/`NOTIFY`, weil Postgres ohnehin vorhanden ist. Ohne TTL gibt es hier
  keine Rückfallebene, die die Lücke von selbst schlösse — dieser Punkt ist damit nach der
  Dateiablage der zweitwichtigste.
- **Prozesslokale Zähler und Schätzwerte**: Entweder aus der Datenbank speisen (die beiden
  `lastSkipped`-Werte gehören ohnehin zum Lauf, nicht zum Prozess) oder bewusst als „Wert dieser
  Instanz" ausweisen. Kein Korrektheitsproblem, aber eine Entscheidung, die pro Wert fallen muss.

## Konsequenzen

**Einfacher:**

- Prozesslokaler Zustand bleibt die richtige, einfachste Lösung für alle oben gelisteten Fälle, solange
  Single-Instance gilt — keine vorzeitige Komplexität durch verteilte Caches oder Leader-Election ohne
  tatsächlichen Bedarf.
- Ein späterer Multi-Instanz-Umbau hat eine vollständige Startliste statt erneuter Code-Archäologie.
- Der Widerspruch zwischen `LibraryIndexingScheduler`s und `recoverJobsOrphanedByRestart`s Javadoc ist
  aufgelöst; beide beschreiben nun konsistent, wofür der Unique-Index tatsächlich schützt und wofür
  nicht.

**Schwieriger / bewusst in Kauf genommen:**

- Horizontale Skalierung oder Hochverfügbarkeit über mehrere Backend-Prozesse ist mit dem heutigen
  Stand nicht möglich, ohne mindestens die oben genannten Stellen umzubauen.
- Die Pflege der Fundstellenliste ist manuell — sie wird nicht durch einen automatisierten Check
  erzwungen, sondern durch Review und die Regel oben.
