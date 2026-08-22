# Issue #6 — chore: scaffold Spring Boot backend with Gradle
- Geschlossen: 2026-02-18 (completed)
- Labels: mvp, backend, setup, size:M
- PRs: #21 (2026-02-18)

**Laut Issue:** Grundgerüst für Spring Boot 3.x mit Java 21 und Gradle 9.3.1 (Kotlin DSL) aufsetzen: Basis-Package `io.opaa` mit Unterpaketen `api`, `indexing`, `query`; Spring-AI-Abhängigkeiten (OpenAI, Ollama, pgvector, Tika); `application.yml` mit Profilen `local`, `docker`, `mock`; Health-Check-Endpunkt.

**Geliefert:** PR #21 scaffoldet exakt wie gefordert — Spring Boot 3.5.10, Gradle 9.3.1, Java 21, Paketstruktur `io.opaa.api`/`io.opaa.indexing`/`io.opaa.query`, Health-Endpoint, `application.yml` mit den drei Profilen. Zusätzlich liefert der PR bereits `docs/decisions/0003-code-formatting.md` und Testcontainers-Setup, was über den reinen Scaffold-Umfang hinausgeht, aber sinnvoll ergänzt.

**Verifikation:** `backend/build.gradle.kts` und `backend/src/main/java/io/opaa/api/HealthController.java` existieren weiterhin im Worktree. Grundstruktur ist erwartungsgemäß seither weit ausgebaut worden (viele weitere Packages/Klassen), das Fundament aus PR #21 blieb bestehen.

**Themen:** backend, projektsetup, gradle, spring-boot
