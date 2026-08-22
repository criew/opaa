# Issue #188 — chore(backend): migrate to Spring Boot 4.1 and bump all backend dependencies
- Geschlossen: 2026-08-01 (completed)
- Labels: enhancement, backend, size:L
- PRs: #190 (2026-08-01)

**Laut Issue:** Koordinierter Sprung auf Spring Boot 4.1 und Spring AI 2.0 plus Bump des gesamten Backend-Dependency-Baums (Spotless, OpenAPI Generator, JJWT, Caffeine, Liquibase, Testcontainers, Gradle-Wrapper auf 9.6). Detaillierter Migrationsplan mit Breaking-Change-Liste (Jackson 3, Property-Umbenennungen, Package-Verschiebungen, Actuator-Nullability) und Akzeptanzkriterien inkl. grünem `spotlessCheck build test`, funktionierendem `bootRun` und aktualisierten Versionsreferenzen in README/AGENTS.md/ADR-0002.

**Geliefert:** PR #190 setzt praktisch alle Zielversionen um (Spring Boot 4.1.0, Spring AI 2.0.0, Spotless 8.9.0, OpenAPI Generator 7.24.0, JJWT 0.13.0, Caffeine 3.2.4, Liquibase 5.0.3, Testcontainers 2.0.5, Gradle 9.6.1) und dokumentiert die konkret angetroffenen Breaking Changes (Package-Umzüge bei Actuator-Health und RestClientCustomizer, separate Test-Slice-Starter, Liquibase-Autoconfig-Umzug, Jackson-3-`JsonMapper`-Bean statt `ObjectMapper`, Spring-AI-Property-Renames, Testcontainers-Artefakt-Präfixe). Bewusste Abweichung vom Issue: der `spring-boot-jackson2`-Kompatibilitäts-Shim wurde **nicht** genutzt, stattdessen ADR-0007 als Entscheidungsdokument angelegt. Dieses ADR-0007 wurde laut Commit-Historie später wieder entfernt (`chore(backend): evalTest-Sourceset auf Jackson 3 umstellen, ADR-0007 entfernen`, gefolgt von `docs: ADR-Nummernkollision 0008 auflösen` und einer ADR-Bestandsbereinigung) — die damalige Jackson-3-Entscheidung ist im heutigen ADR-Bestand nicht mehr als eigenes Dokument sichtbar.

**Verifikation:** `backend/gradle/libs.versions.toml` im heutigen Worktree bestätigt `spring-boot = "4.1.0"` und `spring-ai = "2.0.0"` weiterhin aktiv, inklusive der im PR eingeführten Bundles (`spring-boot-starter-liquibase`, `spring-boot-starter-security-test`, `spring-ai-vector-store-advisor`). `docs/decisions/0007-jackson-3-adoption.md` existiert nicht mehr (siehe oben) — Verzeichnis springt heute von 0006 direkt auf 0009.

**Themen:** backend, dependencies, spring-boot, spring-ai, migration
