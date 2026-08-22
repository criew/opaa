# Issue #43 — feat: In-Memory Chat-Gedächtnis für Folgefragen (MVP)
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend, frontend
- PRs: #56 (2026-02-27)

**Laut Issue:** Der Chat verarbeitete jede Anfrage isoliert ohne Konversationskontext. Gefordert war ein flüchtiges In-Memory-Gedächtnis per `conversationId`, Nutzung von Spring AIs `InMemoryChatMemory`/`MessageChatMemoryAdvisor`, `conversationId` als API-Parameter, Frontend-Verwaltung des States inkl. Neuer-Chat-Button. DB-Persistenz war explizit als Follow-up (#54) ausgeklammert.

**Geliefert:** PR #56 setzt das Kernziel um (Folgefragen behalten Kontext, `conversationId` wird generiert/zurückgegeben, Frontend sendet/verwaltet sie, "Neuer Chat"-Button), weicht aber bewusst von der vorgeschlagenen Technik ab: **kein** `MessageChatMemoryAdvisor`, weil dieser die History vor die System-Message setzt und die Antwortqualität verschlechtert — stattdessen manuelle Memory-Verwaltung mit fester Reihenfolge `[SYSTEM, History…, aktuelle Frage]`. Zusätzlich wird bei Folgefragen die erste User-Frage der Vektorsuche vorangestellt, damit die Quellen thematisch relevant bleiben (im Issue nicht vorgesehen, aber sachlich naheliegende Ergänzung).

**Verifikation:** `backend/src/main/java/io/opaa/query/AnswerGenerationService.java` und `frontend/src/stores/chatStore.ts` existieren weiterhin. Chat-Memory ist im heutigen Code über `CaffeineChatMemoryRepository` (`backend/src/main/java/io/opaa/query/`) realisiert — ein Nachfolgeschritt gegenüber der ursprünglichen reinen In-Memory-Lösung, aber weiterhin nicht dauerhaft persistent (siehe #54).

**Themen:** backend, frontend, chat-memory, retrieval, mvp
