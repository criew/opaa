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
| `LibraryAccessService.grantsByLibrary` | Zugriffsrechte pro Bibliothek (max. 50.000 Einträge) | 10 Minuten TTL, gezielt nach Commit bei Grant-Änderung — mirrort `GroupMembershipResolver`s Muster bewusst |
| `SpaceService.personalSpaceProvisioned` | Flag "persönlicher Space bereits angelegt" pro Nutzer (max. 50.000 Einträge) | kein TTL, nur additiv gesetzt (Flag kann nie fälschlich `true` werden, nur fälschlich fehlen) |
| `RateLimitService.requestLog` | Zeitfenster-Anfragehistorie pro Client-IP | `expireAfterAccess`, kein aktives Invalidieren |
| `ActiveChatModelResolver.cache` | Der eine `ChatClient` des systemweit aktiven LLM-Modells (Single-Slot, kein Map-Cache) | Ereignisgesteuert via `ActiveChatModelChangedEvent` nach Commit (`TransactionalEventListener`) |
| `OidcProviderRegistry` (ADR-0025, #1329) | Ein `JwtDecoder` samt `AuthenticationManager` je aktiviertem Identitätsanbieter, geschlüsselt nach Issuer; dazu der Fehlzustand nicht aufbaubarer Anbieter | Ereignisgesteuert via `OidcProvidersChangedEvent` nach Commit (`TransactionalEventListener`); fehlerhafte Anbieter werden beim nächsten Token ihres Issuers nach kurzer Wartezeit erneut versucht |
| `CaffeineChatMemoryRepository` | Chatverlauf, LRU auf 50 gleichzeitige Konversationen begrenzt | TTL nach letztem Zugriff |
| `FullTextIndexCompleteness.complete`/`.incompleteUntil` | Ob der Volltextindex einer Bibliothek vollständig ist — rein meldend im Erklärprotokoll der Suche (#1270), nie den Suchbereich verengend | Vollständig: kein Verfall für die Prozesslaufzeit (kann nur ein Deployment mit erhöhter `content_tsv_version` ungültig machen, und das startet den Prozess neu). Unvollständig: 60 Sekunden, danach neu gezählt |

Bei mehreren Instanzen sieht jede ihre eigene Kopie: eine Rechteänderung, die auf Instanz A verarbeitet
wird, invalidiert nicht den Cache auf Instanz B — eine Anfrage, die zufällig auf B landet, sieht bis
zum TTL-Ablauf den alten Stand. Für `personalSpaceProvisioned` ist das harmlos (das Flag kann nur
fälschlich fehlen, nie fälschlich vorhanden sein), für die anderen wäre es eine echte
Rechte-/Konsistenzlücke.

**`@Scheduled` ohne Leader-Election, und eine vergleichbare Start-Aktion** (bei mehreren Instanzen
feuert jede ihre eigene Kopie, unkoordiniert):

| Fundstelle | Auslöser | Bemerkung |
| --- | --- | --- |
| `AuditRetentionScheduler.deleteExpiredAuditLogPartitions` | monatlich | Kein reiner No-Op bei doppeltem Aufruf: der DB-seitige Forward-only-Cap (`last_cutoff`/`last_run_month`, Migration 023) begrenzt den Fortschritt auf die seit dem letzten Lauf tatsächlich vergangenen Kalendermonate, nicht auf "einmal pro Aufruf" - `SELECT ... FOR UPDATE` serialisiert zwei gleichzeitige Aufrufe, sodass der zweite im selben Monat `last_run_month` bereits aktualisiert vorfindet und nichts zusätzlich löscht. Die Sicherheit steckt also in dieser Sperre und Kalenderlogik, nicht in der Löschung selbst |
| `LibraryIndexingScheduler.triggerDueLibraries` | jede Minute | Doppelte Trigger derselben fälligen Bibliothek werden durch `uk_indexing_jobs_library_running` (Migration 028) auf Datenbankebene abgefangen — die Instanz, die den Unique-Constraint verletzt, bucht das als Skip. `lastTickAt` (Rückschaufenster gegen Jitter zwischen zwei Ticks) ist zusätzlich rein prozesslokaler Zustand, der bei mehreren Instanzen pro Prozess getrennt geführt wird |
| `IndexingJobRecoveryScheduler.recoverStaleRunningJobs` | alle 15 Minuten | Fails jeden Job, dessen `lastProgressAt`-Heartbeat zu alt ist |
| `IndexingJobRecoveryScheduler.recoverOnStartup` | Prozessstart (`ApplicationReadyEvent`, kein `@Scheduled`) | Ruft `IndexingJobService.recoverJobsOrphanedByRestart` auf — siehe Widerspruch unten |

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

| Fundstelle | Zustand |
| --- | --- |
| `DirectorySyncService.run` | Zwei gleichzeitige Synchronisationsläufe derselben Organisation überlappen unkontrolliert — im Javadoc bereits als "Known gap" benannt, mit zwei genannten Lösungsrichtungen (Serialisierung per Advisory-Lock, oder Last-Writer-Wins als dokumentierte Betriebsvoraussetzung) |

**Prozesslokale Task-Executor-Warteschlangen** (Grund, warum eine `RUNNING`-Zeile implizit "läuft auf
mir" statt "läuft auf irgendeiner Instanz" bedeutet - der `@Async`-Task, der sie abarbeitet, ist immer
an den JVM-Prozess gebunden, der ihn eingereiht hat):

| Fundstelle | Zustand |
| --- | --- |
| `IndexingConfiguration.indexingTaskExecutor`, `.embeddingTaskExecutor`, `.uploadTaskExecutor` | Drei `ThreadPoolTaskExecutor`-Bohnen mit eigener, rein prozessinterner Warteschlange - eine Zeile, die auf Instanz A als `RUNNING` eingereiht wurde, hat auf Instanz B keinen wartenden Task, den ein Neustart von B jemals hätte abbrechen können |

**Prozesslokale/knotenlokale Dateiablage:**

| Fundstelle | Zustand |
| --- | --- |
| `LibraryDocumentService` (Upload-Pfad, `opaa.upload.storage-path`, Default `./uploads`) | Speichert hochgeladene Dokumente unter `<storagePath>/<libraryId>/<random-uuid><extension>` auf dem lokalen Dateisystem des Prozesses. Bei zwei Instanzen ohne geteiltes Volume: ein Upload, der auf Instanz A ankommt, ist über Instanz B nicht lesbar - 404/`FileNotFoundException`, sobald eine spätere Anfrage (Download, Re-Indizierung) zufällig auf B landet. Härteste Annahme dieser Liste: kein Cache-Verfall oder Retry hilft hier, die Datei existiert auf B schlicht nicht |
| `FilesystemPathAllowlist` (`FILESYSTEM`-Quellentyp, #484, ADR-0018) | Vom Betreiber gemounteter Nachbarfall, keine eigene Annahme dieser Anwendung: das Backend liest von per `opaa.indexing.filesystem.allowlist` konfigurierten Basisverzeichnissen. Ob mehrere Instanzen dasselbe Verzeichnis sehen, hängt vollständig davon ab, ob der Betreiber es auf jeder Instanz gleich mountet - anders als beim Upload-Pfad gibt es hier keinen anwendungsseitigen Schreibpfad, der bei fehlendem geteiltem Mount silently divergieren könnte |

### Regel für neue Stellen

Wer einen neuen prozesslokalen Cache, einen neuen `@Scheduled`-Job oder eine neue Annahme über "die
eine laufende Instanz" einführt, trägt die Stelle in die obigen Tabellen dieses ADR nach. Das ist keine
zusätzliche Hürde für die Umsetzung selbst — Single-Instance bleibt bis auf Weiteres die geltende
Annahme, prozesslokaler Zustand ist also weiterhin die richtige, einfachste Lösung. Die Eintragung
macht nur sichtbar, was bei einem späteren Multi-Instanz-Umbau geprüft werden muss, statt dass diese
Prüfung erneut durch Code-Archäologie entstehen muss.

### Skizze: Was ein Multi-Instanz-Umbau je Fundstelle bedeuten würde

Diese Skizze ist keine Umsetzungsplanung, nur eine Einordnung der Größenordnung je Kategorie:

- **Prozesslokale Caches** (`GroupMembershipResolver`, `LibraryAccessService`, `SpaceService`,
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
- **`DirectorySyncService`**: Ein Postgres Advisory-Lock, keyed auf `organizationId`, gehalten für die
  Dauer eines Laufs — im Javadoc bereits als eine der beiden möglichen Lösungsrichtungen benannt.
- **Task-Executor-Warteschlangen** (`IndexingConfiguration`): Folgt aus dem `LibraryDocumentService`-
  bzw. `recoverJobsOrphanedByRestart`-Umbau, kein eigenständiges Problem - sobald eine `RUNNING`-Zeile
  eine Instanz-Kennung trägt, kann jede Instanz an ihrer eigenen Warteschlange festhalten und muss nur
  noch die Zeilen der *eigenen* Instanz beim eigenen Neustart als verwaist behandeln.
- **`LibraryDocumentService`s Upload-Ablage**: Härteste Fundstelle dieser Liste - ein geteiltes Volume
  (NFS/EFS o. ä., über alle Instanzen gleich gemountet) oder ein S3-kompatibler Objektspeicher statt
  des lokalen Dateisystems. `FilesystemPathAllowlist`s Nachbarfall braucht keinen Anwendungs-Umbau,
  nur eine Betriebsvoraussetzung: dieselben Basisverzeichnisse müssen auf jeder Instanz identisch
  gemountet sein.

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
