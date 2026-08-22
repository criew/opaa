# Issue #288 — test(backend): FK-abhängige Integrationstests auf echtes Liquibase-Schema umstellen
- Geschlossen: 2026-08-03 (completed)
- Labels: bug, backend, size:M
- PRs: #298 (2026-08-03)

**Laut Issue:** Viele Integrationstests liefen mit `spring.liquibase.enabled=false`/`ddl-auto=create-drop`, wodurch Hibernate das Schema erzeugte statt Liquibase — und Hibernate legt für schlichte `UUID`-Spalten ohne `@ManyToOne` (z. B. `Space.ownerId`, `SpaceMembership.userId`, `organizationId`-Felder) keine Fremdschlüssel an. Zwei Regressionen (PR #254, #280) rutschten deshalb durch grünes CI. Gefordert: Entscheidung, welche Suiten umgestellt werden, Umstellung, Nachweis der Wirksamkeit, ein einheitliches Teardown-Muster für Migrationstests, Messung der Laufzeit.

**Geliefert:** `SpaceServiceIntegrationTest` und `SpaceRepositoryTest` auf `spring.liquibase.enabled=true`/`ddl-auto=none` umgestellt, `TestcontainersConfiguration` als gemeinsame `public`-Konfiguration; `SpaceServiceTest` (reine Mocks) bewusst nicht umgestellt. Zwei neue Regressionswächter in `SpaceRepositoryTest` (Space mit nicht existentem Owner/Organisation muss scheitern) als PR-eigener Wirksamkeitsnachweis, nachdem der ursprüngliche Nachweis im Review als eigentlich zu #287 gehörig entlarvt wurde. Teardown-Muster für Migrationstests über `io.opaa.migration.package-info` vereinheitlicht (`setAutoCommit(true)` nach jedem `Liquibase.update`). Laufzeit: kein spürbarer Nachteil, geteilter Testcontainer schon durch `@ServiceConnection` gegeben.

**Verifikation:** `backend/src/test/java/io/opaa/space/SpaceRepositoryTest.java` existiert im Worktree. Migration-Package-Info und `TestcontainersConfiguration` wurden laut PR-Dateiliste mitgeliefert; nicht einzeln erneut gegengelesen (kein tiefes Review nötig, Dateien sind vorhanden).

**Themen:** backend, testing, ci, spaces, datenbank
