# ADR-0021: OPAA ist bis auf Weiteres Single-Instance

## Status

Akzeptiert · zuletzt geändert 2026-10-19 · gilt bis zum Abschluss von #1292; wird durch
[ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md) abgelöst

## Kontext

Das Backend trifft an vielen voneinander unabhängigen Stellen die Annahme, dass zur Laufzeit genau
ein Backend-Prozess läuft: prozesslokale Caches ohne verteilte Invalidierung, `@Scheduled`-Methoden
ohne Leader-Election, Task-Executor-Warteschlangen im Prozess, Semaphoren als Parallelitätsbudget und
Recovery-Routinen, die beim Start jede laufend markierte Zeile als verwaist behandeln. Keine dieser
Stellen ist falsch für den heutigen Betrieb; jede bekannte Zielumgebung läuft mit einem
Backend-Prozess. Problematisch war, dass die Annahme nirgends als Entscheidung festgehalten war:
Neue Stellen wiederholten sie als Nebenwirkung der einfachsten Implementierung, und Javadoc
widersprach sich darin, ob Multi-Instanz-Betrieb bereits teilweise berücksichtigt sei.

## Entscheidung

### 1. Genau ein Backend-Prozess je Installation

OPAA läuft bis auf Weiteres als genau ein Backend-Prozess je Installation. Prozesslokaler Zustand
(In-Memory-Caches, `@Scheduled`-Zustand, Warteschlangen, Semaphoren, Zähler, der Monotonie-Zeitgeber
der Rechtehistorie, die Annahme „die eine laufende Instanz“ in Recovery-Routinen, die knotenlokale
Upload-Ablage) ist unter dieser Annahme korrekt und braucht keine verteilte Koordination. Das ist
keine Entscheidung gegen den Multi-Instanz-Betrieb; dessen Bauart legt ADR-0053 fest, und bis
#1292 abgeschlossen ist, gilt diese Annahme weiter.

### 2. Register in `backend/docs/`, Verweis in der Javadoc

Jede Stelle, die diese Annahme trägt, steht im Register
[backend/docs/prozesslokaler-zustand.md](../../backend/docs/prozesslokaler-zustand.md), zusammen mit
dem Sub-Issue von #1292, das sie umbaut. Wer eine neue einführt, trägt sie dort ein und nennt
„(ADR-0021)“ in der Javadoc. Das ist keine Hürde für die Umsetzung:
Prozesslokal bleibt die richtige, einfachste Lösung; die Eintragung macht nur sichtbar, was ein
späterer Umbau prüfen muss, statt dass die Liste erneut durch Code-Archäologie entsteht.

### 3. Serialisierung gegen Doppelstarts geht über die Datenbank

Wo zwei Läufe einander ausschließen müssen, liegt die Sperre in der Datenbank, nicht im Prozess:
Advisory-Locks (`DirectorySyncRunLock`, `UserRepository#lockRoleChanges`), Unique-Constraints
(`uk_indexing_jobs_library_running`, `uk_chat_messages_chat_sequence`), `SELECT … FOR UPDATE` in
Aufbewahrungs- und Löschläufen, bedingte `UPDATE`s und Seed-Marker, die eine
`DataIntegrityViolationException` als „eine andere Instanz war schneller“ behandeln. Zustand, der
einen Neustart überleben muss, liegt ebenfalls in der Datenbank, etwa die Fehlversuch-Sperre der
lokalen Anmeldung in `local_credentials`. Diese Stellen tragen schon heute über Instanzen und sind
das Vorbild für einen Umbau; Postgres reicht dafür. Audit-Partitionen entstehen nicht zur Laufzeit,
ein DDL-Rennen gibt es dort nicht.

### 4. Keine Sitzungsaffinität, kein Instanzabgleich

Alle Sicherheitskonfigurationen sind `STATELESS`, der CSRF-Schutz der lokalen Anmeldung liegt in einem
Cookie, es gibt keinen SSE-, Streaming- oder WebSocket-Endpunkt; Sticky Sessions wären nie nötig.
Nicht gebaut sind eine Erkennung widersprüchlicher Konfiguration zwischen Instanzen (jeder Start-Guard
prüft nur sich gegen Umgebung und Datenbank), ein Schutz gegen den gleichzeitigen Kaltstart zweier
Prozesse (`initialize-schema` von pgvector setzt DDL ab, der Liquibase-Lock verschiebt nur die
Zeitachse; ein Multi-Instanz-Betrieb bräuchte einen gestaffelten Start oder einen eigenen
Migrationsschritt) und eine geteilte Upload-Ablage über das Dateisystem (der S3-Adapter nach
ADR-0030 wäre geteilt). Sanftes Herunterfahren und eine Readiness ohne Modellabhängigkeit sind
gebaut (#1710): Laufende HTTP-Anfragen laufen aus, die Task-Executor stoppen sofort, ein
Indexierungslauf verzögert den Stopp nicht und läuft beim nächsten Start wieder an.

## Konsequenzen

**Einfacher:** Keine vorzeitige Komplexität durch verteilte Caches oder Leader-Election; ein späterer
Umbau hat eine vollständige Startliste.

**Schwieriger:** Horizontale Skalierung oder Hochverfügbarkeit über mehrere Backend-Prozesse ist
ohne Umbau der registrierten Stellen nicht möglich. Die Pflege des Registers ist manuell, durch
Review und Entscheidung 2.

**Restrisiken:** Werden dennoch zwei Instanzen gestartet, setzt der Start der einen die Läufe der
anderen auf `FAILED`, die vor ihm begonnen haben; sie arbeiten als Zombies weiter, während ein
zweiter Lauf derselben Bibliothek starten kann. Rechteänderungen wirken auf der anderen Instanz erst
nach TTL oder Neustart, Konfigurationsänderungen erst nach Neustart, und mit der Ablage im
Dateisystem sind Uploads nur auf dem Knoten lesbar, der sie annahm.

## Verworfene Alternativen

- Leader-Election oder verteilter Scheduler-Lock jetzt — doppelte Arbeit vermeiden, die heute nicht
  anfällt.
- Verteilter Cache jetzt — ersetzt einen korrekten prozesslokalen Zustand ohne Bedarf.
- Register im ADR selbst — wuchs auf über 300 Zeilen und lag weit vom Code.

## Offen

- Der Umbau zum Mehrinstanzbetrieb nach ADR-0053 (#1292).
