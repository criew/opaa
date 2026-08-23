# Issue #256 — test(e2e): Lokale Modellbereitstellung für den E2E-Stack
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:M, ci
- PRs: #690 (2026-08-21)

**Laut Issue:** Der E2E-Stack zeigte auf `OPAA_AI_CHAT_PROVIDER=openai` mit einem Platzhalter-API-Key gegen die echte OpenAI-Base-URL — tragfähig nur für den reinen Rauchtest ohne KI-Aufruf, nicht für Szenarien mit echter Indizierung/Suche (#232, #233), die Determinismus und Unabhängigkeit von einem kostenpflichtigen externen Dienst verlangen. Gefordert: Bewertung Ollama vs. leichtgewichtiger OpenAI-kompatibler Stub, Integration in den Compose-Stack, Anpassung von `e2e/e2e.env`, Dokumentation der Entscheidung als Ergänzung zu ADR-0009.

**Geliefert:** Nur der Dokumentationsteil — die eigentliche technische Umsetzung (Stub-Server `e2e/ai-stub/server.mjs`, Einbindung in `e2e/docker-compose.e2e.yml`, `OPAA_OPENAI_BASE_URL=http://ai-stub:8089` in `e2e/e2e.env`) war laut PR-Beschreibung zum Zeitpunkt dieses PRs bereits andernorts umgesetzt und in `e2e/README.md` beschrieben. PR #690 ergänzt lediglich ADR-0009 um einen Nachtrag zu Punkt 4 ("Modelle lokal im Stack statt externer Anbieter"), der die Entscheidung "eigener minimaler OpenAI-kompatibler Stub statt Ollama" nachträglich mit Begründung festhält. Einzige geänderte Datei ist die ADR selbst.

**Verifikation:** `e2e/ai-stub/server.mjs` existiert im heutigen Code. `docs/decisions/0009-e2e-teststrategie.md` enthält den Nachtrag. Die eigentliche Implementierung des Stubs lässt sich diesem Issue/PR nicht zuordnen — sie kam über einen anderen, hier nicht referenzierten PR.

**Themen:** e2e, ci, doku
