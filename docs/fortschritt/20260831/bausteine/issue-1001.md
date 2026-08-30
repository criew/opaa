# Issue #1001 — fix(build): Backend-Dockerfile zurück auf Temurin 21 — Renovate-Major #988 bricht Image-Build

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, ci
- PRs: #1003 (2026-08-28, gemeinsam mit #996)

**Laut Issue:** Der Automerge von #988 (`eclipse-temurin` 21→25 im Backend-Dockerfile) brach
E2E und Publish Images auf `main`: Die Gradle-Toolchain pinnt Java 21, im
`eclipse-temurin:25-jdk`-Image ist nur JDK 25 vorhanden, Auto-Provisioning ist aus. `e2e` ist
kein Required Check, daher hielt nichts den Merge auf.

**Geliefert:** PR #1003 setzt das Backend-Dockerfile zurück auf `eclipse-temurin:21` (zusammen
mit der Lockfile-Reparatur aus #996). Die strukturelle Konsequenz — Majors nicht mehr
auto-mergen — lieferte #1002.

**Verifikation:** Commit `93ab40f3` auf `main`; `backend/Dockerfile` referenziert Temurin 21,
mit Begründungskommentar in `renovate.json5`.

**Themen:** Renovate, Docker, Java-Toolchain, CI-Ausfall
