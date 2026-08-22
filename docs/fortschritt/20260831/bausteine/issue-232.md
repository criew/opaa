# Issue #232 — test(e2e): Smoke-Test für das Demo-Profil
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:S, demo
- PRs: #729 (2026-08-21)

**Laut Issue:** Genau ein Smoke-Test gegen das Compose-Profil `demo`: Stack startet, Seed läuft fehlerfrei durch (Nutzer, Spaces, fünf Bibliotheken, Rechte), alle Indizierungsläufe enden `COMPLETED`, eine Demo-Nutzerin stellt eine Drehbuchfrage und erhält eine belegte Antwort mit Quellenangabe. Ursprünglich war das Ticket als fünfteilige E2E-Prüfung gegen den (inzwischen ersetzten) Superhelden-Korpus formuliert; das Demo-Konzept schneidet die Testarbeit neu: nur ein grobkörniger Smoke-Test gegen `demo`, Feature-Tests laufen gegen das separate `e2e`-Profil (#233).

**Geliefert:** Ein Playwright-Test `e2e/demo-smoke/tests/demo-smoke.spec.ts`, bewusst außerhalb von `e2e/tests/`, läuft nie in `npm test` mit. Eigenes Compose-Overlay `e2e/docker-compose.demo-smoke.yml` (ai-stub statt Ollama), eigene Env `e2e/demo-smoke.env`, `e2e/scripts/run-e2e.mjs` um `--target demo` erweitert (Stack-Logik geteilt statt dupliziert). Eigener, nicht required CI-Workflow `.github/workflows/demo-smoke.yml`, läuft nächtlich/`workflow_dispatch`, nicht bei jedem PR — begründet mit ~80s zusätzlicher Seed-Laufzeit. Während der Verifikation wurde eine Konfigurationslücke gefunden und in `e2e/demo-smoke.env` behoben (`OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY=30`, da die Standard-Warteschlange bei 26 sequentiellen Uploads überlief) — reine Konfiguration, kein Produktivcode-Eingriff. Nachweis im PR: voller grüner Lauf, 3 Minuten 6 Sekunden Gesamtlaufzeit, 129 Dokumente über vier Bibliotheken indiziert, reguläre Suite unverändert grün (28/28).

**Verifikation:** `.github/workflows/demo-smoke.yml`, `e2e/demo-smoke/tests/demo-smoke.spec.ts` und `e2e/docker-compose.demo-smoke.yml` existieren im heutigen Code.

**Themen:** e2e, demo, ci, testinfrastruktur, seed
