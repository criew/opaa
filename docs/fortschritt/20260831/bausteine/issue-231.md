# Issue #231 — test(e2e): Grundgerüst für browserbasierte End-to-End-Tests
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, frontend, size:M, ci
- PRs: #251 (2026-08-02)

**Laut Issue:** Grundgerüst für eine browserbasierte E2E-Suite, da das Repo keinerlei E2E-Infrastruktur besitzt. Umfang: Werkzeugwahl, Verzeichnisstruktur, reproduzierbares Hochfahren des vollständigen Stacks, wiederverwendbare Testnutzer-Anmeldung, ein Rauchtest, CI-Job mit Trace/Screenshot-Artefakten bei Fehlschlag, unter 10 Minuten Laufzeit. Der Auth-Modus für die Suite war als offene Entscheidung markiert (Vorschlag `mock`).

**Geliefert:** Playwright-Suite unter `e2e/` (Begründung in `e2e/README.md`), Stack-Start via Docker Compose (`e2e/docker-compose.e2e.yml`, `e2e/scripts/run-e2e.mjs`), wiederverwendbare Login-Fixture (`e2e/fixtures/auth.ts`), ein Rauchtest (`e2e/tests/smoke.spec.ts`), CI-Workflow `.github/workflows/e2e.yml` mit `timeout-minutes: 10` und Trace/Screenshot-Upload bei Fehlschlag, ADR `docs/decisions/0009-e2e-teststrategie.md`, Aktualisierung von `AGENTS.md`/`CONTRIBUTING.md`. Abweichung vom Issue: Der vorgeschlagene `mock`-Auth-Modus war technisch nicht nutzbar (`opaa.auth.mode: mock` ist im Backend nur ein Frontend-Signal ohne aktives Spring-Security-Profil; ohne `basic`/`oidc` existieren die Fach-Controller gar nicht). Stattdessen läuft die Suite im `basic`-Modus mit fest hinterlegten Wegwerf-Zugangsdaten — dokumentierte, begründete Abweichung.

**Verifikation:** `e2e/README.md`, `e2e/playwright.config.ts`, `.github/workflows/e2e.yml` und `docs/decisions/0009-e2e-teststrategie.md` existieren im heutigen Code. Die Suite wurde seither von #233 (Seed-Umstellung) und #232 (Demo-Smoke) erweitert, das Grundgerüst blieb tragfähig.

**Themen:** e2e, ci, testinfrastruktur, playwright, auth
