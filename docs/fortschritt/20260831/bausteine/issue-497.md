# Issue #497 — test(backend): Migrationstests dominieren die Suite — Template-DB und geteilter Container
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine (im Chunk-Datensatz nicht verknüpft)

**Laut Issue:** Die Migrationstests (18 Klassen unter `io.opaa.migration`) kosteten 59 % der Suitenzeit durch Schema-Neuaufbau je Testmethode und 19 Einzel-Container. Gefordert: Template-Datenbank statt Vollaufbau je Methode, ein gemeinsamer Singleton-Container, `OpaaApplicationTests` an den geteilten Kontext angleichen, `maxParallelForks = 2`, Ziel `./gradlew test` unter 3:30 min (Referenz 6:44 min), ohne Testsemantik zu schwächen.

**Geliefert:** Im Chunk-Datensatz ist kein PR mit diesem Issue verknüpft — dennoch belegt der heutige Code, dass die Arbeit stattgefunden hat, offenbar über PRs, deren „Closes #497"-Verknüpfung von der Datenextraktion nicht erfasst wurde. Die Commit-Historie zeigt eine mehrteilige Umsetzung: `b67023c2`/`b58e095e` (PR #499, Migrationstests auf Template-DB und geteilten Container), `80bfe782` (OpaaApplicationTests angeglichen), `a9ed523d` (Review-Nachbesserung), `71c69568`/`e3a9f3ec` (PR #648, Spring-Kontexte weiter konsolidiert), `b4c97667` (letzte drei Migrationstests nachgezogen).

**Verifikation:** `backend/src/test/java/io/opaa/migration/AbstractMigrationTest.java` existiert und enthält das Template-DB-Muster; `backend/build.gradle.kts` setzt `maxParallelForks = 2` (Zeile 308). Beide zentralen Umsetzungsbausteine des Issues sind im heutigen Code vorhanden.

**Themen:** ci, backend, testing, performance
