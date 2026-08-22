# Issue #308 — test(backend): GroupServiceIntegrationTest auf echtes Liquibase-Schema umstellen
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend, size:S
- PRs: #691 (2026-08-21)

**Laut Issue:** `GroupServiceIntegrationTest` lief noch mit `spring.liquibase.enabled=false`/`ddl-auto=create-drop` (in #288 nur die Space-Suiten umgestellt). Dadurch konnte der Test `cannotDeleteAGroupThatStillOwnsALibrary` den Guard strukturell nicht scharf prüfen, da Hibernate den entsprechenden Fremdschlüssel gar nicht erzeugt. Gefordert: Umstellung auf echtes Liquibase-Schema, Nachweis dass der Test ohne Guard mit echter FK-Verletzung fehlschlägt, gezielte statt pauschale Datenbereinigung.

**Geliefert:** PR #691 stellt genau darauf um (`@SpringBootTest`, `spring.liquibase.enabled=true`, `ddl-auto=none`, `TestcontainersConfiguration`). Reproduktionsnachweis erbracht (Guard temporär entfernt → `DataIntegrityViolationException` statt fehlender Exception). Zusätzlicher, im Issue nicht vorgesehener Befund während der Umstellung: ein zwischenzeitlich (Migration 047) hinzugekommener Fremdschlüssel machte ein bestehendes Testszenario obsolet — dieses wurde durch einen neuen Test ersetzt, der die jetzt datenbankseitige Garantie direkt prüft.

**Verifikation:** `GroupServiceIntegrationTest.java` existiert im Worktree und enthält `spring.liquibase.enabled`.

**Themen:** backend, testinfrastruktur, groups, liquibase
