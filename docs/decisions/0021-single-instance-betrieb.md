# ADR-0021: OPAA ist bis auf Weiteres Single-Instance

## Status

Vorgeschlagen

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
  teilweise berücksichtigt ist oder nicht (siehe `IndexingJobService.recoverJobsOrphanedByRestart`
  unten).

Dieses ADR macht die Annahme explizit, listet die bekannten Fundstellen und legt fest, wie mit neuen
Stellen dieser Art umzugehen ist. Es trifft **keine** Entscheidung, Multi-Instanz-Betrieb zu bauen —
das bleibt eine spätere, eigene Entscheidung, sobald ein tatsächlicher Bedarf (Hochverfügbarkeit,
horizontale Skalierung) entsteht.

## Entscheidung

OPAA läuft bis auf Weiteres als **genau ein Backend-Prozess** pro Installation. Prozesslokaler
Zustand (In-Memory-Caches, `@Scheduled`-Zustand, Recovery-Annahmen über "die eine laufende Instanz")
ist unter dieser Annahme korrekt und braucht keine verteilte Koordination.

### Bekannte Fundstellen

**Prozesslokale Caches ohne verteilte Invalidierung** (alle Caffeine-basiert, invalidiert nur im
eigenen Prozess — bei mehreren Instanzen sieht jede Instanz ihre eigene, potenziell veraltete Kopie):

| Fundstelle | Zustand | Invalidierung |
| --- | --- | --- |
| `GroupMembershipResolver.groupIdsByUser` | Gruppenmitgliedschaften pro Nutzer (max. 50.000 Einträge) | 10 Minuten TTL, gezielt bei Mitgliedschaftsänderung (`invalidate`/`invalidateAll`) |
| `LibraryAccessService.grantsByLibrary` | Zugriffsrechte pro Bibliothek (max. 50.000 Einträge) | 10 Minuten TTL, gezielt nach Commit bei Grant-Änderung — mirrort `GroupMembershipResolver`s Muster bewusst |
| `SpaceService.personalSpaceProvisioned` | Flag "persönlicher Space bereits angelegt" pro Nutzer (max. 50.000 Einträge) | kein TTL, nur additiv gesetzt (Flag kann nie fälschlich `true` werden, nur fälschlich fehlen) |
| `RateLimitService.requestLog` | Zeitfenster-Anfragehistorie pro Client-IP | `expireAfterAccess`, kein aktives Invalidieren |
| `ActiveChatModelResolver.cache` | Der eine `ChatClient` des systemweit aktiven LLM-Modells (Single-Slot, kein Map-Cache) | Ereignisgesteuert via `ActiveChatModelChangedEvent` nach Commit (`TransactionalEventListener`) |
| `CaffeineChatMemoryRepository` | Chatverlauf, LRU auf 50 gleichzeitige Konversationen begrenzt | TTL nach letztem Zugriff |

Bei mehreren Instanzen sieht jede ihre eigene Kopie: eine Rechteänderung, die auf Instanz A verarbeitet
wird, invalidiert nicht den Cache auf Instanz B — eine Anfrage, die zufällig auf B landet, sieht bis
zum TTL-Ablauf den alten Stand. Für `personalSpaceProvisioned` ist das harmlos (das Flag kann nur
fälschlich fehlen, nie fälschlich vorhanden sein), für die anderen wäre es eine echte
Rechte-/Konsistenzlücke.

**`@Scheduled` ohne Leader-Election** (bei mehreren Instanzen feuert jede ihre eigene Kopie des
Schedulers, unkoordiniert):

| Fundstelle | Intervall | Bemerkung |
| --- | --- | --- |
| `AuditRetentionScheduler.deleteExpiredAuditLogPartitions` | monatlich | Löschung ist idempotent — eine doppelte Ausführung löscht nichts, was nicht schon weg ist |
| `LibraryIndexingScheduler.triggerDueLibraries` | jede Minute | Doppelte Trigger derselben fälligen Bibliothek werden durch `uk_indexing_jobs_library_running` (Migration 028) auf Datenbankebene abgefangen — die Instanz, die den Unique-Constraint verletzt, bucht das als Skip. `lastTickAt` (Rückschaufenster gegen Jitter zwischen zwei Ticks) ist zusätzlich rein prozesslokaler Zustand, der bei mehreren Instanzen pro Prozess getrennt geführt wird |
| `IndexingJobRecoveryScheduler.recoverStaleRunningJobs` | alle 15 Minuten | Fails jeden Job, dessen `lastProgressAt`-Heartbeat zu alt ist — siehe Widerspruch unten |

**Widerspruch zwischen Scheduler-Javadoc und Recovery-Verhalten:**
`LibraryIndexingScheduler`s Javadoc beschreibt explizit ein Szenario mit mehreren Instanzen ("Multiple
backend instances ticking the same due library at the same minute...") und erklärt, warum der
Unique-Index diesen einen Fall absichert. Das erweckt den Eindruck, Multi-Instanz-Betrieb sei für die
Indizierung bereits teilweise tragfähig. `IndexingJobService.recoverJobsOrphanedByRestart` — aufgerufen
von `IndexingJobRecoveryScheduler.recoverOnStartup` bei jedem Prozessstart — widerlegt das: die Methode
failt **jede** noch `RUNNING` markierte Job-Zeile, mit der Begründung, "a fresh JVM cannot possibly
still be running the task any such row refers to". Diese Prämisse gilt nur, wenn genau eine Instanz
existiert. Bei mehreren Instanzen würde der Neustart einer Instanz A die noch laufenden, legitimen Jobs
einer Instanz B als verwaist abbrechen — der Unique-Index schützt vor doppelten Läufen, nicht vor
diesem Fall. Die beiden Javadoc-Stellen sind im Zuge dieses ADR korrigiert (siehe unten): Der
Unique-Index macht ausschließlich das gleichzeitige Anstoßen desselben fälligen Laufs sicher, nicht
Multi-Instanz-Betrieb im Allgemeinen.

**Fehlende Serialisierung konkurrierender Läufe:**

| Fundstelle | Zustand |
| --- | --- |
| `DirectorySyncService.run` | Zwei gleichzeitige Synchronisationsläufe derselben Organisation überlappen unkontrolliert — im Javadoc bereits als "Known gap" benannt, mit zwei genannten Lösungsrichtungen (Serialisierung per Advisory-Lock, oder Last-Writer-Wins als dokumentierte Betriebsvoraussetzung) |

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
  ein verteilter Scheduler-Lock (z. B. ShedLock), sodass nur eine Instanz pro Tick tatsächlich feuert.
  Für `LibraryIndexingScheduler` ist das die sauberere Alternative zum heutigen Unique-Index-Workaround,
  der Doppelläufe erst nach dem Versuch abfängt statt sie von vornherein zu vermeiden.
- **`recoverJobsOrphanedByRestart`**: Darf bei Multi-Instanz-Betrieb nicht mehr pauschal jede
  `RUNNING`-Zeile failen. Braucht entweder eine Instanz-Kennung pro Job (nur Zeilen der eigenen Instanz
  beim eigenen Neustart failen) oder eine Umstellung auf ausschließlich heartbeat-basierte Erkennung
  (`recoverStaleJobs`s Ansatz), sodass ein Neustart einer Instanz die Jobs anderer, weiterhin laufender
  Instanzen nicht mehr anfasst.
- **`DirectorySyncService`**: Ein Postgres Advisory-Lock, keyed auf `organizationId`, gehalten für die
  Dauer eines Laufs — im Javadoc bereits als eine der beiden möglichen Lösungsrichtungen benannt.

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
