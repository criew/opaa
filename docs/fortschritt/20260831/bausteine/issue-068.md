# Issue #68 — Docker Build Skips Tests
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, size:S, ci
- PRs: keine

**Laut Issue:** Das Backend-`Dockerfile` baut mit `./gradlew bootJar --no-daemon -x test`, überspringt also Tests. Gefordert war ein Build, der Tests ausführt und bei Fehlschlag abbricht, notfalls per Multi-Stage-Aufbau.

**Geliefert:** Kein Code-Fix am Dockerfile. Laut Schließungskommentar ist das Anliegen durch das inzwischen etablierte CI-Gating obsolet geworden: Jeder PR muss Build und Tests als Required Checks bestehen, gemerged wird nur auf grünem Stand, und das Image wird aus genau diesem geprüften `main`-Stand gebaut (`Publish Images`). Zusätzlich sei das Ausführen der Integrationstests im Docker-Build selbst inzwischen praktisch unmöglich, weil sie Testcontainers (Docker-in-Docker im Image-Build) benötigen. Das im Issue beschriebene Risiko — ungeprüfte Images gelangen in Produktion — bestehe im heutigen Setup nicht mehr, weil die Prüfung vor dem Merge stattfindet statt beim Image-Bau.

**Verifikation:** `backend/Dockerfile` enthält weiterhin `RUN ./gradlew bootJar --no-daemon -x test` — die Zeile aus dem Issue ist unverändert im Code vorhanden. Die Schließung war eine bewusste Risikobewertung (CI-Gating vor dem Merge ersetzt Tests im Image-Build), kein technischer Fix am benannten Dockerfile.

**Themen:** ci, deployment, docker
