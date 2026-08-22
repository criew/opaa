# Issue #19 — docs: update ADR-0002 with finalized technology decisions
- Geschlossen: 2026-02-18 (completed)
- Labels: documentation, mvp
- PRs: #20 (2026-02-18)

**Laut Issue:** ADR-0002 von „Proposed" auf „Accepted" setzen und finalisierte Technologieentscheidungen dokumentieren: Gradle 9.3.1 statt Maven, Package `io.opaa` statt `com.opaa`, Spring AI 1.1.2, MUI 7.3.8, Liquibase, Vitest+RTL, MSW, Testcontainers, GitHub Actions.

**Geliefert:** PR #20 setzt genau diese Änderungen um — Status auf „Accepted", alle genannten Technologieentscheidungen ergänzt, Maven-/`com.opaa`-Referenzen entfernt. Keine Abweichung vom Issue.

**Verifikation:** `docs/decisions/0002-mvp-technology-stack.md` existiert weiterhin im Repo. Die dort dokumentierten Versionsstände (Gradle 9.3.1, Spring AI 1.1.2) sind laut AGENTS.md/Build-Doku inzwischen weiterentwickelt (aktuell Gradle 9.6.1, Spring Boot 4.1.0, Spring AI 2.0.0) — das ADR selbst wurde nicht mehr aktualisiert, dokumentiert also den Stand zum MVP-Zeitpunkt, nicht den heutigen.

**Themen:** dokumentation, adr, projektsetup, mvp
