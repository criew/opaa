# Issue #997 — chore(ci): Renovate den Gradle-Wrapper-Befehl erlauben (allowedUnsafeExecutions)

- Geschlossen: 2026-08-28 (completed)
- Labels: ci
- PRs: #998 (2026-08-28)

**Laut Issue:** Beim Gradle-Update aktualisierte Renovate nur `gradle-wrapper.properties`,
nicht die Wrapper-Skripte/JAR, weil `gradleWrapper` nicht in den `allowedUnsafeExecutions`
freigegeben war — der Wrapper blieb inkonsistent zur deklarierten Version.

**Geliefert:** PR #998 setzt `RENOVATE_ALLOWED_UNSAFE_EXECUTIONS` mit `gradleWrapper` im
Workflow und zieht `docs/renovate.md` nach.

**Verifikation:** Commit `6ab92f59` auf `main`; das anschließende Gradle-Update (#973)
aktualisierte den Wrapper vollständig.

**Themen:** Renovate, Gradle, CI
