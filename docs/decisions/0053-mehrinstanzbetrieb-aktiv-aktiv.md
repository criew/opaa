# ADR-0053: Mehrinstanzbetrieb — Aktiv/Aktiv in Stufen, koordiniert über Postgres

## Status

Akzeptiert · zuletzt geändert 2026-10-22 · ersetzt [ADR-0021](0021-single-instance-betrieb.md) ·
ergänzt ADR-0032 Entscheidung 3 und ADR-0042 Entscheidung 2

## Kontext

ADR-0021 legt OPAA auf genau einen Backend-Prozess fest und führt die Stellen, die diese Annahme
tragen, im Register [backend/docs/prozesslokaler-zustand.md](../../backend/docs/prozesslokaler-zustand.md).
Hochverfügbarkeit und ein Update ohne Ausfall verlangen mehrere Instanzen gegen eine Datenbank.
Schon ein Rolling Update lässt kurz zwei Versionen nebeneinander laufen. Die Analyse in #1292
(Inventar vom 17.09., Aktiv/Aktiv-Analyse vom 14.10. mit den Bauteilen B1–B9, Abgleich vom 19.10.)
zeigt, dass dann drei Bereiche brechen:

- **Sicherheit:** Anspruchszuordnung der Anbieter, Schalter der lokalen Konten, Rechte-Caches,
  Abmeldung und Sitzungswiderruf wirken nur auf der Instanz, die die Änderung annimmt. Eine fremde
  Instanz schreibt Systemrollen nach der alten Zuordnung zurück.
- **Indexierung und geplante Läufe:** Jeder Cron feuert auf jeder Instanz, Erinnerungsmails und
  Audit-Einträge entstehen doppelt. Die Start-Recovery gegen `ProcessStart` macht fremde Läufe zu
  Zombies, die neben einem zweiten Lauf derselben Bibliothek weiterarbeiten.
- **Rolling Updates:** `DROP COLUMN`, verengte `CHECK`s, `DROP NOT NULL` und neue Enum-Werte, auch
  solche aus `opaa-api` ganz ohne Changeset, machen die noch laufende alte Version funktionsunfähig.
  Liquibase und `initialize-schema` laufen beim Start jedes Pods.

Dazu kommen: Budgets und Anzeigen gelten je Prozess, Mails warten in einer Warteschlange im
Speicher, Uhr und Zeitzone jeder Instanz gehen in instanzübergreifende Vergleiche ein, und weder der
Helm-Chart noch Compose sind auf mehrere Instanzen zugeschnitten. An anderen Stellen koordiniert OPAA
bereits über die Datenbank (ADR-0021 Entscheidung 3). Diese Stellen tragen schon heute über
Instanzgrenzen.

## Entscheidung

### 1. Zielbild Aktiv/Aktiv, geliefert in Stufen

Mehrere gleichrangige Backend-Instanzen bedienen gleichzeitig Anfragen und Hintergrundarbeit, ohne
Sitzungsaffinität: Alle Sicherheitskonfigurationen sind zustandslos (`STATELESS`), der CSRF-Schutz
der lokalen Anmeldung liegt in einem Cookie, und es gibt keinen SSE-, Streaming- oder
WebSocket-Endpunkt; jede Instanz beantwortet jede Anfrage. Die erste nutzbare Stufe heißt „zwei
Instanzen, Update ohne Ausfall“; ihre Grenze steht unter Konsequenzen. Eine zweite Stufe macht
Budgets und Anzeigen installationsweit (Entscheidung 12). Die Instanzzahl ist eine feste Größe der
Bereitstellung, ein automatisches Skalieren des Backends ist nicht vorgesehen. Eine einzelne Instanz
bleibt die Vorgabe beider Betriebswege.

Aktiv/Passiv spart wenig. Ein Rolling Update lässt auch dort zwei Versionen gegen dieselbe Datenbank
laufen und braucht Expand/Contract (13), den Migrationsschritt (14) und den Laufbesitz (8) ebenso.
Dazu käme eine Leader-Election mit Übernahme, und die passive Instanz müsste beim Übernehmen
dieselben Caches, Läufe und Uhren richtig behandeln, nur seltener und damit schlechter erprobt.

### 2. Koordination nur über Postgres

Sperren, Signale, Zähler, Warteschlangen und die gemeinsame Zeitquelle liegen in der
Anwendungsdatenbank: Advisory-Locks, bedingte `UPDATE`s, `SELECT … FOR UPDATE SKIP LOCKED`,
Unique-Indizes und `LISTEN`/`NOTIFY`. Vorbild sind die Stellen, die zwei Läufe schon immer über die
Datenbank gegeneinander sperren: `DirectorySyncRunLock`, `UserRepository#lockRoleChanges`,
`uk_indexing_jobs_library_running`, `uk_chat_messages_chat_sequence`, `SELECT … FOR UPDATE` in
Aufbewahrungs- und Löschläufen und Seed-Marker. Keine neue Infrastruktur wie Redis, ein
Message-Broker oder Kubernetes-Leases. Eine solche Komponente braucht den Nachweis, dass Postgres
für eine benannte Last nicht reicht, und eine eigene Entscheidung.

Postgres ist in jeder Installation vorhanden, gesichert und überwacht. Jede weitere Komponente wäre
für den Betrieb einer Behörde eine weitere Pflicht und nähme dem Compose-Weg seine Einfachheit. Die
Last (wenige Instanzen, Signale im Sekundenbereich) liegt weit unter dem, was Postgres trägt.

### 3. Instanzregister mit Heartbeat

Jede Instanz trägt sich beim Start in eine Registertabelle ein: Kennung (neu je Prozessstart; Pod-
oder Hostname geht nur zur Anzeige mit), Version, Startzeit, Konfigurationsabdruck (Entscheidung 4)
und Heartbeat. Der Heartbeat wird alle 15 s nach Datenbankuhr geschrieben. Eine Instanz ohne
Heartbeat seit 120 s gilt als tot. Beide Werte sind konfigurierbar, die Frist beträgt mindestens das
Vierfache des Abstands. Beim geordneten Stopp meldet sich eine Instanz ab und gilt sofort als tot.
Kann sie ihren Heartbeat nicht innerhalb der Frist schreiben, geht sie von ihrer Ablösung aus: Ihre
Läufe enden kooperativ (Entscheidung 8). Tote Zeilen bleiben eine Woche zur Anzeige und werden dann
gelöscht. Umsetzung: #2945.

Die Frist wiegt Recovery-Tempo gegen Fehlalarme ab: Acht verpasste Schläge überdauern eine GC-Pause
oder einen kurzen Datenbankaussetzer, ein ausgefallener Pod gibt seine Läufe nach zwei Minuten frei.

### 4. Konfigurationsabdruck und zulässige Abweichungen

Der Abdruck umfasst jeden `opaa.*`-Schlüssel mit `@DataImpact`, also die maschinelle Liste aus dem
Fakten-Manifest (ADR-0052 Entscheidung 9). Neue datenwirksame Schlüssel gehen damit von selbst ein.
Dazu kommen die als installationsweit gekennzeichneten technischen Werte: `OPAA_AUTH_JWT_SECRET`,
`OPAA_CREDENTIALS_ENCRYPTION_KEY`, `OPAA_SETTINGS_ENCRYPTION_KEY`, die Embedding-Identität (Modell,
Basis-URL, Dimension), Chunking, Auth-Profil, `OPAA_DB_SCHEMA`, Originalablage samt Bucket bzw. Pfad,
Allowlists, Proxy-Vertrauensliste, `opaa.public-base-url`, die Feature-Flags und die Zeitzone der
JVM. Gekennzeichnet sind sie mit `@InstallationWide`, jeder übrige `opaa.*`-Schlüssel mit
`@PerInstance` als „je Instanz zulässig“ (Zeitgrenzen, Pool-Größen); `FingerprintKeysTest` erzwingt die
Einordnung wie `ConfigurationSettingsTest`. Gespeichert wird je Schlüssel ein HMAC unter einem
Schlüssel, den die erste Instanz im Register anlegt, nie der Wert; die Meldung nennt bei einem
Geheimnis nur den Schlüssel.

Regeln beim Start, serialisiert über einen Advisory-Lock auf das Register:

- Weicht ein Wert von dem einer lebenden Instanz ab, verweigert die neue Instanz den Start und nennt
  die Schlüssel.
- Verglichen wird die Schnittmenge der Schlüssel, die beide Versionen kennen.
- Die Version darf abweichen, aber nur im Rolling-Update-Fenster. Jede Version nennt die älteste
  Vorgängerversion, neben der sie laufen darf (`instance/rolling-update.properties`, vom Release
  gesetzt): Vorgabe ist das unmittelbar vorige Release, an einer Versionsgrenze ohne Rolling Update
  (Entscheidung 13) die Version selbst. Wer außerhalb des Fensters liegt, ob älter oder neuer,
  startet nicht.
- Einen Abdruckwert ändert nur ein Neustart aller Instanzen, nie ein Rolling Update; ein Rolling
  Update mit geändertem Wert bleibt am ersten neuen Pod stehen, ohne Ausfall. Ändert ein Release die
  Vorgabe eines Abdruckwerts, ist es eine Versionsgrenze ohne Rolling Update.

Die Auskunft über die Datenerhebung (ADR-0052 Entscheidung 11) gibt den gemeinsamen Abdruck aus und
weist eine Abweichung aus. Umsetzung: #2945.

Ein abweichender Wert zeigt sich sonst nur als sporadischer Fehler je nach getroffener Instanz: 401
bei abweichendem Secret, unlesbare Geheimnisse, Neueinbettung im Wechsel, Löschung nach der kürzesten
Frist. Der Schnitt über die Schnittmenge lässt neue Schlüssel zu, ohne dass jedes Release eine
Versionsgrenze wird.

### 5. Invalidierungsbus über `LISTEN`/`NOTIFY`

Ein Cache, der nur über ein prozessinternes Ereignis verworfen wird oder dessen TTL eine Rechte- oder
Sicherheitslücke offen ließe, ist an einen Bus über Postgres angebunden:

- **Senden** mit `pg_notify` in der Transaktion der Änderung. Postgres stellt die Nachricht erst mit
  dem Commit zu, bei einem Rollback nie, und fasst gleiche Nachrichten einer Transaktion zusammen.
  Steht die Quelle eines Caches in einer Tabelle, sendet ein Trigger der Tabelle
  (`notify_cache_invalidation`), damit jeder Schreibweg und jede Version meldet; Spalten, die nur
  einen Status tragen, kann er ausnehmen. Ein Ereignis ohne eigene Tabelle meldet die Anwendung. Die
  Nutzlast nennt nur den Cache, nie eine Person oder eine Zeile; der Empfänger verwirft alles.
- **Empfangen** auf jeder Instanz, auch der sendenden, über eine eigene Verbindung außerhalb des
  Hikari-Pools. Die Ereignisse nach Commit bleiben für die sendende Instanz bestehen; eine
  Invalidierung ist idempotent.
- **Abriss:** Die Hörverbindung wird alle 5 s geprüft. Nach einem Abriss oder einer fehlgeschlagenen
  Prüfung verwirft die Instanz alle angebundenen Caches vollständig und liest an ihnen vorbei aus der
  Datenbank, bis `LISTEN` wieder steht; dann verwirft sie ein zweites Mal.
- **Angebunden** werden die Caches aus dem Register, die sicherheitsrelevanten zuerst
  (`OidcProviderRegistry`, `GroupMembershipResolver`, `AssetAccessService`,
  `MetadataFilterOptionsCache`). Neue Regel in `backend/AGENTS.md`: Ein ereignisgesteuerter Cache ohne
  TTL braucht die Anbindung.

Die Hörverbindung braucht eine direkte Verbindung oder Session-Pooling; Transaction-Pooling
(PgBouncer) trägt `LISTEN` nicht. Im Rolling-Update-Fenster hört die Vorgängerversion den Bus
nicht: Ihre Caches folgen einer Änderung erst nach ihrer TTL, die Anbieter-Registry erst mit dem
Ersatz der Instanz; ihre eigenen Änderungen erreichen die neuen Instanzen über die Trigger.
Umsetzung: #2946.

Nachrichten während eines Abrisses sind unwiederbringlich verloren. Ein Nachholen bräuchte ein
eigenes Protokoll. Das vollständige Verwerfen ist einfach, und die Lücke ist auf den Prüfabstand
begrenzt. Kurze TTLs statt eines Busses ließen die Lücke für die ganze TTL offen.

### 6. Widerruf ohne Negativ-Cache und ohne Zeitvergleich

- Die Denylist der lokalen Zugangstokens (`LocalTokenRevocationService`) cacht nur noch „widerrufen“.
  „Nicht widerrufen“ wird bei jeder Prüfung aus `local_revoked_tokens` gelesen.
- Der Sitzungswiderruf (Passwortwechsel, Sperre, Admin-Reset, Ausschalten der lokalen Konten,
  Wiederverwendungserkennung) erhöht einen Zähler je Konto. Das Token trägt den Zählerstand als Claim,
  die Prüfung verlangt Gleichheit. `iat` wird nicht mehr gegen einen Cutoff aus einer anderen Uhr
  verglichen.
- **Rolling-Update-Fenster:** Die Vorgängerversion kennt den Claim nicht. Die neue Version schreibt
  den Cutoff weiter mit, damit die Vorgängerversion widerruft, und prüft ein Token ohne Claim, das
  nur die Vorgängerversion ausstellt, weiter gegen den Cutoff. Schreibt die Vorgängerversion nur den
  Cutoff, erhöht ein Trigger den Zähler; er lässt ihn nie sinken. Das Folgerelease lehnt ein Token
  ohne Claim ab.

Umsetzung: #2946. Ein Negativ-Cache ist auf jeder Instanz außer der widerrufenden falsch, und zwar für
die volle Token-Lebensdauer. Ein Zeitvergleich über zwei Hosts scheitert schon an einer Sekunde
Drift; ein Zähler kennt keine Uhr.

### 7. Geplante Läufe: ShedLock und Merker mit Datenbankzusage

- **Sperre je Lauf:** Jede `@Scheduled`-Methode trägt eine ShedLock-Sperre (`@SchedulerLock`,
  `JdbcTemplateLockProvider` mit `usingDbTime()`); die Sperrtabelle legt ein Changeset nach ADR-0034
  an, die Version steht in `backend/gradle/libs.versions.toml`. `lockAtLeastFor` liegt über der
  größten erwarteten Uhrabweichung und unter dem Abstand des Laufs, damit eine Fälligkeit genau
  einmal läuft, auch wenn eine zweite Instanz Sekunden später tickt. `lockAtMostFor` ist nur das Netz
  für eine tote Instanz. Ein Strukturtest verlangt die Sperre an jeder `@Scheduled`-Methode.
- **Merker:** Korrektheit hängt nicht an der Sperre. Jede nach außen sichtbare Wirkung eines Laufs
  (Mail, Benachrichtigung, Audit, Rechte- und Sichtbarkeitshistorie) steht hinter einem Merker mit
  Datenbankzusage: bedingtes `UPDATE … WHERE <merker> IS NULL` mit Prüfung der Zeilenzahl oder
  Auswahl mit `FOR UPDATE SKIP LOCKED` (Vorbild `ConnectionExpiryWatch`, `PendingConsentSweep`).
  Mails gehen in derselben Transaktion in die Outbox (Entscheidung 11).
- **Bibliothekszeitplan:** Die Fälligkeit wird je Bibliothek per bedingtem `UPDATE` in der Datenbank
  verbucht, nicht in `lastTickAt`.
- **Sondierung der Modellrollen** (Rerank, Transkription) nur auf der Instanz, die ihre Sperre hält;
  das Ergebnis liegt in der Datenbank (Entscheidung 12).

Umsetzung: #2947.

ShedLock statt eines eigenen `pg_try_advisory_lock`: Ein Advisory-Lock schließt nur Überlappung aus,
nicht Wiederholung. Ein kurzer Lauf, der endet, bevor die zweite Instanz tickt, liefe ein zweites
Mal, genau der Fehler von `LibraryIndexingScheduler`. Die Sperre je Fälligkeit braucht ein
gespeichertes „gesperrt bis“ nach Datenbankuhr; eine Session-Sperre hielte zudem eine
Pool-Verbindung für die ganze Laufdauer. ShedLock ist genau diese Tabelle, Apache-2.0, in Version 7
für Spring Framework 7 und Spring Boot 4 gepflegt. Weil ShedLock allein bei einem Lauf über
`lockAtMostFor` nicht hält, tragen die Merker die Zusage.

### 8. Laufbesitz und Recovery über Heartbeats

- **Besitz:** Jeder `RUNNING`-Indexierungslauf, jeder `PENDING`-Upload und der Worker der
  Neueinbettung trägt die Kennung der besitzenden Instanz.
- **Recovery** fragt „lebt die Besitzerin?“. Aufgegeben wird nur, was einer Instanz mit abgelaufenem
  Heartbeat (Entscheidung 3) oder einer abgemeldeten gehört. `ProcessStart` entfällt als Kriterium,
  „nicht meiner“ ebenso: Ein ersetzter Pod hat eine neue Kennung, die Zeilen des alten fänden sich
  nie. Der Sweep läuft periodisch unter der Sperre aus Entscheidung 7 und einmal beim Start. Die
  Frist für hängende Läufe einer lebenden Instanz (`staleJobTimeout`) bleibt daneben bestehen.
- **Schreiben unter Besitz:** Fortschritt, Abschluss und `source_sync_state` schreiben bedingt
  (`… WHERE status = 'RUNNING'` und Besitzerin bzw. Version), nie `findById` + `save`. Bei jedem
  Fortschritt prüft der Lauf seinen Status und endet kooperativ, wenn er aufgegeben oder abgebrochen
  wurde.
- **Arbeitsdateien** liegen in einem Unterverzeichnis je Instanz; ein Sweep räumt das eigene und die
  toter Instanzen.
- **Neueinbettung:** Start, Abbruch und Besitz über die Zeile in `embedding_index`. Die DDL des
  Startabgleichs (`EmbeddingIndexReconciler`) läuft nur unter einem Advisory-Lock und nur, wenn alle
  lebenden Instanzen dieselbe Embedding-Identität melden, was Entscheidung 4 ohnehin erzwingt.
- **Wartungsläufe** (Metadaten-Bestandslauf, Kontextpräfix- und Pipeline-Nachlauf, Schemaänderung)
  bleiben ohne Exklusivsperre: Sie sind je Aufruf idempotent und wiederaufnehmbar, ein Doppelstart
  kostet nur Modellaufrufe, wie schon im Einzelbetrieb bei zwei Personen.

Umsetzung: #2948. Ein Kriterium aus Prozessstart oder Instanzkennung kann nicht unterscheiden, ob
eine fremde Instanz noch arbeitet; nur ihr Heartbeat weiß das. Die bedingten Schreibzugriffe machen
einen Zombie unschädlich, falls er die Frist doch überlebt.

### 9. Datenbankuhr für instanzübergreifende Vergleiche

Ein Zeitpunkt, den eine andere Instanz liest und vergleicht, stammt aus der Datenbankuhr. Ein
Zeitpunkt, den nur dieser Prozess vergleicht, stammt aus der `Clock`-Bean.

- **Datenbankuhr**, wo möglich im Statement selbst (`clock_timestamp()`, `now()`): Heartbeats und
  ihre Fristen (3, 8), Fälligkeiten (Bibliothekszeitplan, `DirectorySyncScheduler`), Sperren
  (`usingDbTime`), Outbox (11), Zählerfenster (12) und `AuditLogEntry.recordedAt`.
  `PermissionHistoryClock` bekommt die Datenbankuhr samt datenbankseitiger Monotonie nach ADR-0032
  Entscheidung 3, **installationsweit**: Eine Sequenz hält die zuletzt vergebene Grenze aller
  Historientabellen, jede neue Grenze liegt über jeder zuvor vergebenen, gleich für welches Objekt.
- **`Clock`-Bean** (Wanduhr der JVM): Zeitgrenzen und Dauern im Prozess, TTLs prozesslokaler Caches,
  Fenster der Budgets je Instanz, der Ablauf von Tokens (`exp` mit Toleranz), Protokolle und
  Anzeigen. Die Naht für die Datenbankuhr ist eine eigene, benannte Bean neben der `Clock`; die
  Ratsche gegen `Instant.now()` (ADR-0051 Entscheidung 6) bleibt.
- **Kalendergrenzen** (Crons, Jahres- und Quartalsgrenzen, Erinnerungsfenster, Warntage) folgen der
  Zeitzone der JVM. Sie gehört zum Abdruck (4); Compose und Helm setzen `TZ` ausdrücklich.

Umsetzung: #2945. Wanduhren zweier Hosts driften trotz NTP um Millisekunden bis Sekunden, ein
Vergleich über Instanzen ist so gut wie die schlechteste Uhr. Die Datenbank ist die eine Uhr, die
alle sehen; für Lokales kostete sie einen Roundtrip ohne Gewinn. Die Monotonie gilt installationsweit,
weil ADR-0032 Entscheidung 2 die Rekonstruktion von Kombinationszuständen aus mehreren Tabellen auf
die globale Ordnung aller Grenzen stützt; je Objekt fielen zwei Änderungen an verschiedenen Objekten
in dieselbe Mikrosekunde oder nach einem Rücksprung der Datenbankuhr in vertauschte Reihenfolge. Sie
kostet eine kurze, über einen Advisory-Lock serialisierte Vergabe je Grenze, keine Sperre für die
Dauer der Transaktion.

### 10. Zustand aus dem Speicher in die Datenbank

- **Gesprächsfenster:** Bei einem gespeicherten Chat liest jede Runde das Fenster aus
  `chat_messages`; der Gesprächsspeicher ist dort keine Quelle mehr. Ephemere Gespräche ohne
  gespeicherten Chat bleiben bewusst instanzgebunden, ein Instanzwechsel kostet ihren Kontext. Sie zu
  speichern, schüfe Gesprächsdaten, die die Person nicht gespeichert hat.
- **Vorschau der Rechteübertragung:** zustandslos als HMAC-signierte Vorschau-Kennung über Aufrufer,
  Signatur, Fingerabdruck und Ablauf, Schlüssel aus `LocalAuthKeyService`. Eine Wiederholung nach der
  Ausführung scheitert am geänderten Fingerabdruck.
- **Persönliches Speicherkontingent:** `pg_advisory_xact_lock` je Eigentümerin in der Transaktion,
  die prüft und das Dokument schreibt.
- **Push-Eingang:** Stapel je Bibliothek in einer Tabelle, entprellt über Zeitstempel der
  Datenbankuhr. Genau eine Instanz holt einen fälligen Stapel ab (`FOR UPDATE SKIP LOCKED`);
  Schlüssel überleben Instanzwechsel und Neustart.
- **Widerrufe beim Anbieter** (`GrantRevocations`): Warteschlange in der Datenbank nach demselben
  Muster, damit ein Pod-Tausch keinen Widerruf einer Notabschaltung verliert.

Umsetzung: #2949. Die signierte Vorschau braucht anders als eine Tabelle nach dem Vorbild
`directory_sync_pending_plans` weder Aufräumlauf noch neue Daten.

### 11. Mail-Outbox

- Jede Mail, auf deren Ergebnis niemand in derselben Anfrage wartet (Selbstbedienung über
  `MailDispatchExecutor`, Anmeldehinweise, Erinnerungen und Ablaufmails der geplanten Läufe), wird in
  der fachlichen Transaktion in eine Outbox-Tabelle geschrieben; ein Rollback verwirft sie mit.
- Zugestellt wird von einem Worker auf jeder Instanz. Er übernimmt fällige Zeilen mit
  `FOR UPDATE SKIP LOCKED` und einer Frist nach Datenbankuhr und wiederholt mit wachsendem Abstand bis
  zu einer Höchstzahl, danach scheitert die Zeile mit maskiertem Grund.
- Zusage: höchstens ein Zustellversuch gleichzeitig, mindestens eine Zustellung. Ein Absturz zwischen
  der Annahme durch den Mailserver und dem Vermerk kann eine Mail ein zweites Mal senden; eine stabile
  `Message-ID` je Zeile macht das erkennbar.
- Empfänger und Inhalt liegen mit dem Einstellungsschlüssel verschlüsselt (`SettingsEncryptor`), weil
  Links Einmal-Geheimnisse tragen. Die Zeile wird nach der Zustellung oder dem endgültigen Scheitern
  gelöscht; das Fakten-Manifest führt die Tabelle.
- Synchron bleiben Mails, deren Ergebnis die anfragende Person sofort sieht: die Testmail der
  Verwaltung und die Zustellung mit Ausweichweg (`MailDeliveryPath`), bei der ein Fehlschlag den Link
  anzeigt.

Umsetzung: #2949. Mit Rolling Updates wird der Stopp zum Routinevorgang, und eine Warteschlange im
Speicher verlöre dabei regelmäßig Reset- und Einladungslinks. Genau-einmal-Zustellung gibt SMTP nicht
her; „mindestens einmal“ ist die ehrliche Zusage.

### 12. Installationsweite Budgets

| Budget | Entscheidung |
|---|---|
| Globale Deckel `opaa.rate-limit.*.global-max-requests` (auch `local-auth`) | Zählerzeile je Grenze und Fensterlänge nach Datenbankuhr, ein Upsert je gezählter Anfrage |
| Abflussalarm `ExternalAccessMassRetrievalAlarm` (kanalweites Fenster) | Zählerzeile des Kanals, ohne Token- oder Personenbezug; die Sperrfrist nach einem Alarm steht in derselben Zeile |
| Grenzen je Client, Adresse oder Konto (`max-requests`, `max-requests-per-account`, `LocalAuthRateLimiter`) | je Instanz, durch N geteilt |
| Kontingent je Zugangstoken (`ExternalAccessQuota`) | je Instanz; der Wert der Einstellung geteilt durch die lebenden Instanzen im Register, aufgerundet |
| Pool `embeddingTaskExecutor` | je Instanz, durch N geteilt |
| `TranscriptionModelRole.slots`, `AttachmentExtractionLimiter` | je Instanz, durch N geteilt |
| Pools `indexingTaskExecutor`, `uploadTaskExecutor`, Chat-Titel und Gesprächsnotiz; `modelExtractionTaskExecutor` folgt den Pools der Läufe und Uploads | je Instanz, ohne Teilung |
| Rate-Limit-Behandlung der Quellsysteme | je Lauf, unverändert |

Eine Zählerzeile (`installation_counters`) hält das laufende und das vorige feste Fenster; das
gleitende Fenster schätzt jede Instanz gleich, indem sie das vorige Fenster mit seinem Anteil am
gleitenden gewichtet. Der Upsert prüft die Grenze in derselben Anweisung, unter der Sperre der
Zeile. Ist der Zähler nicht erreichbar, lässt der Deckel die Anfrage durch und schreibt eine Warnung.

„Durch N geteilt“ heißt: Der Wert je Instanz ist das Budget der Installation durch die Zahl der
Instanzen. Handbuch und Compose nennen die Formel, der Helm-Chart rechnet sie aus einem Budget der
Installation je Variable (`backend.installationBudgets`). Das Kontingent je Token ist eine
Einstellung der Oberfläche ohne Wert je Instanz; deshalb teilt die Instanz selbst.

Der Zustand der Modellrollen Rerank und Transkription liegt in der Datenbank
(`model_role_observations`): Die Instanz mit der Sondierungssperre (7) und jede, die den Endpunkt
aufruft, schreibt einen geänderten Zustand, jede Instanz hält ihn am Invalidierungsbus (5) und
entscheidet auf ihm über die Umsortierung. Ein erfolgreicher Aufruf verlängert die Beobachtung
höchstens einmal je Minute, bei kürzerem Prüfabstand je halbem Abstand. Aus der Datenbank kommen auch die Erreichbarkeit von Chat- und
Einbettungsmodell auf der Statusseite (`search_status_probes`, geteilt für die Frist der Probe),
die übersprungenen Dokumente der Wartungsläufe (`library_maintenance_skips`), der Sendestatus der
Mail und der Durchsatz der Kostenvorschau (`embedding_rate_measurements`, je Embedding-Modell
summiert über alle Instanzen). Je Instanz und in der Oberfläche so gekennzeichnet bleibt der Status
eines Anmeldeanbieters, weil jede Instanz ihren Token-Decoder selbst baut; Micrometer-Metriken wie
`opaa.conversations.active` gelten je Pod. Umsetzung: #2950.

Zählerzeilen nur, wo die absolute Zahl die Zusage ist (Kostendeckel, Frühwarnung gegen
Credential-Stuffing, Abflussalarm) und es wenige Schlüssel gibt. Das Kontingent je Token bleibt
bewusst im Speicher: Ein persistierter Zähler wäre ein Nutzungsdatum über eine Person. Die Pools
schützen eigene Endpunkte und den eigenen Speicher; ein Datenbank-Slot je Einbettungsaufruf kostete
mehr, als er schützt. Läufe und Uploads skalieren bewusst mit N, das ist der Sinn mehrerer Instanzen;
die Modellextraktion ist an ihre Threads gebunden und wächst mit ihnen. Feste Fenster mit Gewichtung
statt eines Zeitstempels je Anfrage halten die Zeile klein und die Prüfung in einer Anweisung.

### 13. Migrationen nach Expand/Contract über zwei Releases

Jede Schemaänderung, die die Vorgängerversion bräche, verteilt sich auf zwei Releases. Release n
erweitert nur: neue Tabellen, Spalten nullable oder mit bleibendem Default, zusätzliche erlaubte
Werte. Release n+1 zieht frühestens zusammen (`DROP`, `RENAME`, `SET NOT NULL`, `DROP DEFAULT`,
verengte `CHECK`s), in ausdrücklich gekennzeichneten Contract-Changesets. Ein neuer Wert eines
persistierten Enums ist eine Erweiterung, auch ohne Changeset (`opaa-api`): Er wird erst
geschrieben, wenn die Vorgängerversion ihn lesen kann. Ein sicherheitsrelevanter neuer Zustand wird
in der Datenbank durchgesetzt (Trigger oder Constraint, Vorbild `assets.owner_only`), damit die alte
Version ihn nicht umgeht. Eine Baseline-Zusammenfassung oder ein unvermeidbarer Bruch ist eine
ausgewiesene „Versionsgrenze ohne Rolling Update“ (Entscheidung 4). Ein Wächter vergleicht den
Katalog vor und nach jedem Changeset; ein Verzeichnis der persistierten Enums verlangt für einen
neuen Wert den Erweiterungsschritt. Regel, Wächter, `lock_timeout` je Changeset und die Bewertung
der Altlasten: [backend/docs/liquibase.md](../../backend/docs/liquibase.md) (#2944).

Die Regel gilt sofort für jedes neue Changeset, unabhängig vom Rest des Umbaus. Ein Rolling Update
wird erst freigegeben, wenn sie mindestens ein Release gegolten hat.

### 14. Migration als eigener Schritt

Das Backend-Image hat einen Startmodus, der nur Liquibase ausführt und endet. Bei mehr als einer
Instanz migriert nur dieser Schritt, die Anwendungsinstanzen laufen mit
`SPRING_LIQUIBASE_ENABLED=false`. `vector_store` legt Liquibase an, `initialize-schema` von pgvector
ist aus. Eine einzelne Instanz darf weiter beim Start migrieren (ADR-0042 Entscheidung 2). Einmalige
Startaktionen (Seeds, `OPAA_LOCAL_ADMIN_RESET`) laufen unter einem Advisory-Lock genau einmal je
Anforderung, ein Passwort erscheint erst nach dem Commit im Log. Ein zweiter Startmodus
`await-migration` wartet nur lesend, bis die Datenbank jedes Changeset seiner Version verzeichnet
(ausgenommen solche, die eine Vorbedingung mit `onFail: CONTINUE` auf einen späteren Lauf schiebt),
und endet dann. Helm startet die Migration als Job ohne Hook, die Pods warten in einem Init-Container
mit diesem Modus; unter Compose hängen die Instanzen per `service_completed_successfully` am
Migrationsdienst. Umsetzung: #2944, #2951.

Ein abgeschossener Pod hinterlässt sonst einen hängenden `DATABASECHANGELOGLOCK`, die Startup-Probe
müsste die Migrationsdauer abdecken, und zwei gleichzeitige Kaltstarts liefern sich DDL-Rennen. Ein
Helm-Hook vor dem Upgrade fände bei der Installation weder das Secret noch die Erprobungsdatenbank des
Releases vor und bände GitOps-Werkzeuge an Hooks; der Wartemodus ordnet Job und Pods ohne Zugriff auf
die Kubernetes-API.

### 15. Betriebsmodell für Compose und Kubernetes

**Update-Reihenfolge, beide Wege:** Erst der Migrationsschritt der neuen Version, die gegenüber der
Vorgängerin nur erweitert (13). Dann die Instanzen einzeln ersetzen, die nächste erst nach
bestandener Readiness der neuen. Ein Rückweg auf die Vorgängerversion bleibt im
Rolling-Update-Fenster möglich, über eine Versionsgrenze ohne Rolling Update nur über die
Datenbanksicherung.

**Kubernetes (Helm-Chart, ADR-0042):**

- `backend.replicas` größer 1 und `RollingUpdate` rendern nur mit aktivem Migrations-Job und
  gemeinsamer Originalablage (`s3` oder PVC mit `ReadWriteMany`); sonst bricht das Rendern mit
  Meldung ab. Der Name des Jobs folgt Image und Konfiguration, ein Upgrade mit neuem Stand startet
  ihn neu; er hat eine eigene NetworkPolicy, die Erprobungsdatenbank lässt ihn ein.
- Strategie `RollingUpdate` (`maxUnavailable: 0`, `maxSurge: 1`) ab zwei Replikaten, `Recreate`
  bleibt wählbar und ist die Vorgabe für eins. PodDisruptionBudget (`maxUnavailable: 1`) ab zwei
  Replikaten, `lifecycle.preStop.sleep` (das Image hat keine Shell; das Frontend bekommt dieselbe Pause), `OPAA_SHUTDOWN_TIMEOUT`
  mindestens Read-Timeout des Frontends, `terminationGracePeriodSeconds` darüber plus `preStop`.
- Hikari-Poolgröße als Wert mit der Grenze `(replicas + maxSurge) × (Pool + 1) + Migration ≤
  max_connections`; die 1 ist die Hörverbindung des Busses (Entscheidung 5), die Migration braucht
  zwei. `/tmp` bleibt `emptyDir` je Pod, die Alarme zu Scrape, Heap und Indexierung gelten je Pod
  statt über alle summiert. Einen Abdruckwert ändert `Recreate` bzw. Skalieren auf null (4).

**Docker Compose:**

- Kein Rolling Update. Eine Mehrinstanz-Variante (`docker-compose.multi.yml`) mit Migrationsdienst
  und zwei benannten Diensten `backend-a` und `backend-b` unter dem gemeinsamen Netzalias `backend`,
  je mit eigenem `/tmp`, ohne Host-Portbindung. Jedes Backend hat einen Healthcheck auf die
  Readiness ohne Shell (eine Java-Klasse neben dem Jar), das Frontend wartet auf „healthy“.
  Originalablage `s3` oder ein gemeinsames Bind-Mount auf demselben Host.
- Update: Migrationsdienst, dann `backend-a` ersetzen und auf „healthy“ warten, nach einer Pause von
  15 s `backend-b`, zuletzt das Frontend. nginx lässt eine Instanz, die beim Start Verbindungen
  ablehnt, in der Verteilung (`max_fails=0`); die Pause gilt für ein Frontend der Vorgängerversion
  ohne diese Einstellung. Dessen Austausch ist der einzige Moment ohne Ziel: Für einige Sekunden lehnt
  der Host-Port Verbindungen ab, beantwortet aber keine Anfrage mit einem Fehler.
- nginx löst den Backend-Namen zur Laufzeit auf (`upstream` mit `resolve` und dem DNS-Server von
  Docker): Jede Adresse des Alias ist ein Ziel, ein neuer Container ist ohne nginx-Neustart
  erreichbar, und die URI-Ersetzung von `/api/` bleibt erhalten. `docker compose --scale` ist nicht
  der Weg: Es teilt das benannte `/tmp`-Volume und scheitert an der festen Portbindung.
- Die Adresse eines gestoppten Containers bleibt bis zur nächsten Auflösung ein Ziel, eine Verbindung
  dorthin läuft ins Leere. nginx gibt deshalb nach 4 s Verbindungsaufbau auf
  (`proxy_connect_timeout`) und versucht das nächste Ziel. Hat die Auflösung die Zielliste in der
  Zwischenzeit geändert, vergibt nginx kein weiteres Ziel mehr; eine Anfrage, die noch kein Backend
  erreicht hat, beginnt dann einmal neu auf der aktuellen Liste (`/api/` und `/mcp`, nicht das
  Diktat ohne Request-Puffer).
- Eine Anfrage, die ein Backend angenommen hat, geht nur bei `GET`, `HEAD` und `OPTIONS` an ein
  zweites, wenn die Verbindung abreißt oder die Antwort ausbleibt. Alle anderen Methoden laufen
  höchstens einmal: `POST` und `PATCH` schließt nginx selbst aus, `PUT` und `DELETE` nimmt ein
  interner Pfad ohne nächstes Ziel; dort beginnt nur eine Anfrage ohne Verbindung neu, bis zu
  dreimal. Ein gescheitertes Ziel setzt dort eine Sekunde aus, damit der Neustart ein anderes
  trifft; setzen alle aus, nehmen dieselben Adressen als Reserve die Anfrage. Ein Backend kann eine
  Änderung ausgeführt haben, ohne zu antworten; eine zweite Ausführung wäre nicht für jeden
  Endpunkt folgenlos.
- Die feste Frontend-Adresse in der Proxy-Vertrauensliste trägt nur einen Frontend-Container; jeder
  weitere braucht eine eigene feste Adresse und einen eigenen Eintrag.

Der Einzelinstanzbetrieb bleibt auf beiden Wegen unverändert. Umsetzung: #2951. Den Betrieb mit zwei
Instanzen belegen die Szenarien unter `e2e/multi-instance/` auf beiden Wegen, einschließlich des
Rolling Updates von der Vorgängerversion unter Last (#2952).

### 16. Register des prozesslokalen Zustands

Jede Stelle, die Zustand im Prozess hält (Cache, `@Scheduled`, Executor, Semaphore, Zähler,
Startaktion), steht im Register
[backend/docs/prozesslokaler-zustand.md](../../backend/docs/prozesslokaler-zustand.md) mit ihrer
Einordnung: am Invalidierungsbus (5), unter einer Sperre oder einem Merker (7), in der Datenbank,
gewollt je Instanz mit der Formel aus 12, oder hingenommen mit der Folge, die das Handbuch nennt. Wer
eine neue Stelle einführt, trägt sie dort ein und nennt „(ADR-0053)“ in der Javadoc.

Das Register war die Startliste dieses Umbaus und bleibt der Ort, an dem eine neue Stelle sichtbar
wird, bevor sie zwischen zwei Instanzen auseinanderläuft; die Strukturtests aus 4, 5 und 7 fangen nur,
was sie kennen.

## Konsequenzen

**Einfacher:** Eine Installation übersteht den Ausfall einer Instanz und ein Update ohne Unterbrechung.
Alle Koordination liegt an einer Stelle, die ohnehin gesichert wird. Abweichende Konfiguration
scheitert beim Start statt als sporadischer Fehler. Ein Rückweg auf die Vorgängerversion ist im
Rolling-Update-Fenster ohne Datenbanksicherung möglich.

**Schwieriger:** Jede Schemaänderung kostet zwei Releases. Jeder neue Cache, jeder neue geplante
Lauf und jede neue Startaktion muss sich an Bus, Sperre oder Register binden; die Strukturtests aus
den Entscheidungen 4 und 7 und die Regel aus Entscheidung 5 halten das fest. Eine Änderung an einem
Abdruckwert verlangt einen Neustart aller Instanzen. ShedLock ist eine neue Abhängigkeit.

**Restrisiken:** Nach einem Abriss der Hörverbindung kann ein Cache bis zum Prüfabstand veraltet
sein. Ein Absturz während der Zustellung kann eine Mail doppelt senden. Ephemere Gespräche verlieren
bei einem Instanzwechsel ihren Kontext. Die Budgets je Instanz gelten nur über die Formel, nicht durch
eine Zählung; eine ungleiche Verteilung der Anfragen lässt sie früher greifen.

**Liefergrenze der ersten nutzbaren Stufe „zwei Instanzen, Update ohne Ausfall“:** die Entscheidungen
2 bis 9, 13 bis 15, aus Entscheidung 10 das Gesprächsfenster und Entscheidung 11 (#2944 bis #2948,
#2951, #2952 und die genannten Teile von #2949). Ohne das Gesprächsfenster verlöre bei abwechselnder
Verteilung jeder gespeicherte Chat jede zweite Runde aus dem Kontext. Die zweite Stufe bringt den Rest
von Entscheidung 10 und Entscheidung 12 (#2949, #2950). Chart und Compose bieten mehrere Instanzen nur
unter den Voraussetzungen von Entscheidung 15 an.

## Verworfene Alternativen

- Aktiv/Passiv mit Leader-Election — spart wenig, siehe Entscheidung 1.
- Redis, Message-Broker oder Kubernetes-Leases zur Koordination — zusätzliche Betriebspflicht ohne Lastnachweis (Entscheidung 2).
- Eigener `pg_try_advisory_lock` je Lauf statt ShedLock — schließt Wiederholung nicht aus, siehe Entscheidung 7.
- Sticky Sessions — verdecken nur Gesprächsspeicher und Vorschau, nicht Caches, Läufe und Uhren, und fallen beim Pod-Tausch ohnehin weg.
- Recovery nur eigener Zeilen nach Instanzkennung — ein ersetzter Pod hat eine neue Kennung (Entscheidung 8).
- Kurze TTLs statt eines Invalidierungsbusses — lassen die Rechtelücke für die TTL offen (Entscheidung 5).
- Zählerzeilen für alle Budgets — für Pools teurer als der Schutz, für das Token-Kontingent ein neues Nutzungsdatum (Entscheidung 12).
- Vorschau der Rechteübertragung als Tabelle — braucht Aufräumlauf und neue Daten (Entscheidung 10).

## Offen

- Horizontale Skalierung der Indexierung über eine Auftragswarteschlange in der Datenbank statt der
  Übergabe an den Executor der tickenden Instanz; erst nach der ersten Stufe und bei Bedarf.
