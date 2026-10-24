# ADR-0056: Flyway statt Liquibase für die Schemamigration

## Status

Akzeptiert · zuletzt geändert 2026-10-24

## Kontext

OPAA steht unter der AGPL-3.0 und migrierte sein Datenbankschema mit Liquibase (`liquibase-core`,
über `spring-boot-starter-liquibase`; Spring Boot 4.1.1 bringt 5.0.3 mit). Ab Version 5.0 steht
`liquibase-core` unter der
[Functional Source License 1.1 mit Apache-2.0 als künftiger Lizenz](https://github.com/liquibase/liquibase/blob/master/LICENSE.txt)
(FSL-1.1-ALv2). Nach dem Lizenztext ist jede Nutzung erlaubt außer einer „Competing Use“: die
Software anderen in einem kommerziellen Produkt oder Dienst bereitzustellen, das sie ersetzt oder
im Wesentlichen dieselbe Funktion bietet. Am zweiten Jahrestag ihrer Veröffentlichung wird jede
Version zusätzlich unter Apache-2.0 lizenziert. Die FSL steht weder auf der
[Liste der OSI](https://opensource.org/licenses) noch unter den freien Lizenzen der
[FSF](https://www.gnu.org/licenses/license-list.html). Die AGPL-3.0 verbietet in § 10, Empfängern
weitere Beschränkungen aufzuerlegen; ob die Nutzungsbeschränkung der FSL bei der Weitergabe einer
Installation eine solche ist, beantwortet dieser ADR nicht, er ist keine Rechtsberatung. Die
Lizenzprüfung (`docs/sbom.md`, Abschnitt „Lizenzprüfung“) gab FSL-1.1-ALv2 nicht frei; Liquibase
stand bis zu dieser Entscheidung als Ausnahme in `.github/licenses/exceptions.txt`. Betroffen sind
vor allem Behörden, die OPAA weitergeben.

Geprüft wurde der Rückgriff auf Liquibase 4.33.0 (09.07.2025), die letzte Version unter
Apache-2.0. Build und Spring-Boot-Autokonfiguration liefen, aber `VectorStoreColdStartTest` (zwei
Migrationen gleichzeitig in einer JVM) blieb in 2 bis 4 von 5 Wiederholungen an einer gesetzten
Changelog-Sperre hängen, unter 5.0.3 nie. Seit 4.33.0 erschien keine 4.x-Version mehr; die
Sicherheitskorrekturen von 5.0.3 und 5.0.4 gibt es für 4.x nicht.

Flyway Community (`flyway-core`, `flyway-database-postgresql`) steht unter Apache-2.0, wird
gepflegt und von Spring Boot autokonfiguriert (`spring-boot-starter-flyway`, Version aus der BOM).
Die Community-Ausgabe enthält nur einen leeren Telemetrie-Dienst. Die Changesets von OPAA bestanden
fast nur aus PostgreSQL-SQL in `sql:`-Blöcken (ADR-0034). Produktiv lief OPAA noch nicht; die einzige
Installation ist eine Demo, die neu aufgesetzt werden kann.

## Entscheidung

### 1. Flyway migriert das Schema

Die Schemamigration läuft mit Flyway Community aus SQL-Skripten unter
`backend/src/main/resources/db/migration/<modul>/`. Liquibase entfällt aus Abhängigkeiten,
Laufzeit-Klassenpfad und Lizenz-Ausnahmeliste. Die Startmodi `migrate` und `await-migration`
(ADR-0053) bleiben, `SPRING_FLYWAY_ENABLED=false` ersetzt `SPRING_LIQUIBASE_ENABLED=false`.

Begründung: Flyway ist frei, OSI-anerkannt und gepflegt, und es deckt alles ab, was OPAA nutzt:
versionierte SQL-Skripte, Platzhalter, Ausführung außerhalb der Reihenfolge und eine Sperre gegen
gleichzeitige Migrationen. Undo-Migrationen und Dry-Run der kostenpflichtigen Ausgaben braucht OPAA
nicht.

### 2. Eine Baseline je Modul, keine Übernahme der Liquibase-Historie

Die gesamte Liquibase-Historie ist zu einer Baseline je Modul zusammengefasst:
`V1.<n>__<modul>_baseline.sql`, `<n>` die Stelle des Moduls in der Modulreihenfolge. Eine frische
Datenbank hat danach dasselbe Schema, dieselben Eigentümer und Rechte, dieselben Partitionen und
dieselben gelieferten Zeilen wie zuvor. Eine Installation mit Liquibase-Historie wird nicht
übernommen, sondern neu aufgesetzt. Die Baselines liegen vor der Versionsgrenze `v0.1.0` ohne
Rolling Update.

Begründung: Eine Übernahme bräuchte eine Brücke von `databasechangelog` zu `flyway_schema_history`
für eine einzige Demo-Installation; Neuaufsetzen ist dort billiger und sicherer. Die Gleichheit des
Endstands ist mit Schema- und Datenabzug gegen eine frisch mit Liquibase migrierte Datenbank
belegt.

### 3. Spätere Migrationen nach Datum versioniert, Contract im Namen

Jede spätere Migration heißt `V<JJJJ>.<MM>.<TT>.<n>__<thema>.sql`. Flyway ordnet nach Version über
alle Modulverzeichnisse; `out-of-order` ist an, damit ein später gemergtes Skript mit älterem Datum
auf einer bestehenden Installation noch läuft. Die Beschreibung eines Contract-Schritts beginnt mit
`contract_`, weil Flyway keine Labels kennt.

Begründung: Das Datum hält die bisherige Reihenfolge je Modul, eine eindeutige Version verlangt
Flyway ohnehin. Die Kennzeichnung im Namen steht in `flyway_schema_history` und wirkt nicht auf die
Ausführung.

## Konsequenzen

- **Einfacher:** Keine Lizenzausnahme und keine offene Lizenzfrage bei der Weitergabe. Ein
  abgebrochener Start hinterlässt keine Sperre in der Datenbank: Flyway sperrt über eine
  Advisory-Sperre der Sitzung, die mit der Verbindung endet.
- **Schwieriger:** Die Kommentare der einzelnen Changesets sind mit dem Zusammenfassen entfallen;
  der Endstand steht im Format von `pg_dump`. Delta-Tests, die nur das Verhalten einzelner
  Changesets auf vorhandenen Zeilen prüften, sind entfallen; Tests auf Constraints, Trigger, Wächter
  und Rechte laufen auf dem Endstand weiter.
- **Restrisiko:** Flyway Community beschränkt ältere Datenbankversionen auf die kostenpflichtigen
  Ausgaben. OPAA verlangt PostgreSQL 18, das Flyway 12 unterstützt; ein künftiger Wechsel des
  Herstellers am Zuschnitt der Community-Ausgabe ist zu beobachten.

## Verworfene Alternativen

- Liquibase 5 als Ausnahme behalten — lässt die Frage der Weitergabe unter AGPL-3.0 offen.
- Pin auf Liquibase 4.33.0 — keine Korrekturen mehr, `VectorStoreColdStartTest` scheitert, Bruch bei künftigen Spring-Boot-Updates.
- FSL-Umwandlung abwarten — 5.0.0 wird erst am 30.09.2027 Apache-2.0, danach liefe OPAA stets zwei Jahre hinter den Korrekturen.
- Liquibase-Historie in Flyway übernehmen (Baseline-on-migrate) — Brückenlogik für eine einzige Demo-Installation.
