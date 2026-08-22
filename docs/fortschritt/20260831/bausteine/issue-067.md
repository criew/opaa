# Issue #67 — ⚠️ [HIGH] Spotless Config Missing (ADR-0002 Violation)
- Geschlossen: 2026-02-28 (completed)
- Labels: bug, backend, setup, size:S
- PRs: keine

**Laut Issue:** `spotless { }`-Block in `backend/build.gradle.kts` war leer, `./gradlew spotlessCheck` prüfte nichts — Verstoß gegen ADR-0002/ADR-0003. Gefordert: `googleJavaFormat()`-Konfiguration, CI-Anbindung, Contributing-Guide-Update.

**Geliefert:** Kein PR verknüpft. Laut Autorenkommentar (bigpuritz, beim Schließen) war der Befund zum Zeitpunkt der Prüfung bereits gegenstandslos — die Spotless-Konfiguration existierte inzwischen (vermutlich durch eine andere, nicht direkt verlinkte Änderung) bereits vollständig mit `googleJavaFormat()`, `removeUnusedImports()`, `trimTrailingWhitespace()`, `endWithNewline()` sowie einer `kotlinGradle`-Sektion. Das Issue wurde als „already resolved" geschlossen, ohne dass ein eigener PR dafür nötig war.

**Verifikation:** `backend/build.gradle.kts` enthält im heutigen Worktree eine vollständige `spotless { }`-Konfiguration mit `java { googleJavaFormat() ... }` und `kotlinGradle { ... }` — deckt sich mit dem im Schließkommentar zitierten Stand.

**Themen:** ci, code-style, backend, projektsetup
