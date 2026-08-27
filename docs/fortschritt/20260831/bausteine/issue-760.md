# Issue #760 — test(e2e): Modellverwaltung — Anlegen, Aktivieren, Verbindungstest und Löschschutz
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #770 (2026-08-23)

**Laut Issue:** End-to-End-Szenarien für die Modellverwaltung: Anlegen/Testen/Aktivieren, Chat-Antwort nach Aktivierung ohne Neustart, Berechtigungsausschluss für Nicht-Admins, Löschschutz für das aktive Modell, Fehlermeldung bei nicht erreichbarer Test-Adresse, „Schlüssel kommt nie zurück“ nach erneutem Öffnen. Mehrfacher Lauf als Flaky-Nachweis, kein hinterlassener Zustand für Folgetests.

**Geliefert:** `e2e/tests/llm-model-management.spec.ts` mit allen sechs Szenarien in `test.describe.serial`, `afterAll` stellt das ursprüngliche aktive Modell wieder her. Abweichung von der Ausgangsannahme des Issues: Es gibt keinen echten Ollama-Dienst im Compose-Stack der Suite (weder `docker-compose.yml` noch `e2e/docker-compose.e2e.yml`) — der positive Verbindungstest läuft stattdessen gegen `ai-stub`, denselben OpenAI-kompatiblen Ersatz, den auch das Seed-Modell nutzt. Zusätzlich ein `data-testid` auf `LlmModelCard` ergänzt, um eine reale Mehrdeutigkeit im DOM (mehrfach gemountete `AccordionDetails`) zu beheben. Laut PR zweimal hintereinander grüner Volllauf (34/34 Tests) als Flaky-Nachweis.

**Verifikation:** `e2e/tests/llm-model-management.spec.ts` existiert im Worktree; `e2e/README.md` referenziert `llm-model-management` (Szenarien-Abschnitt).

**Themen:** modellverwaltung, e2e, qa
