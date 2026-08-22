# Issue #525 — Persistente Chats in genau einem Space (Grundlage, ausschließlich privat)
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:L, workspace
- PRs: #541 (2026-08-19)

**Laut Issue:** Ein Chat soll ein persistentes Objekt in genau einem Space werden (Tabellen `chats`/`chat_messages`), mit Endpunkten zum Erstellen, Auflisten, Lesen, Patchen und Löschen. Anbindung an `POST /api/v1/query` über `chatId` statt `conversationId`, Persistierung von Frage/Antwort/Quellen als `ChatMessage`. Zugriff nur für den Autor, auch nicht für Space-/System-Admins. Migrationstest gefordert.

**Geliefert:** PR #541 liefert die Tabellen (Migration 032, wegen Kollisionen mit parallelen PRs zweimal umnummeriert), die fünf Endpunkte, die Query-Anbindung inklusive Rehydrierung des Gesprächsverlaufs aus persistierten Nachrichten bei kaltem Cache. Der PR-Body dokumentiert zwei Review-Runden mit gravierenden Funden, die vor dem Merge behoben wurden: (1) Persistenz griff wegen `@Transactional(readOnly = true)` zunächst gar nicht — Reproduktionsnachweis mit rotem/grünem Test vorhanden; (2) Cache-Schlüssel-Leck zwischen Nutzern über den Caffeine-Cache — ebenfalls mit Reproduktionsnachweis behoben; (3) Verbindungspool-Deadlock-Risiko durch verschachtelte Transaktionen — behoben nach dem `SpaceService#ensureDefaultSpace`-Muster (`NOT_SUPPORTED` + `REQUIRES_NEW` via `TransactionTemplate`). Space-Löschen mit vorhandenen Chats liefert jetzt 409 statt Constraint-Verletzung (Vorgriff auf das später separat behobene #543-Problem: Space mit fremden Chats blieb dadurch dauerhaft unlöschbar). Koordination mit parallel gemergten #526/#528 wird im PR-Body ausführlich dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/chat/` enthält im Worktree `Chat.java`, `ChatMessage.java`, `ChatMessageRepository.java`, `ChatRepository.java`, `ChatRole.java`, `ChatService.java`, `ChatStatus.java` sowie zusätzlich `ChatConfiguration.java`, `ChatTitleGenerationService.java`, `TitleSource.java` (spätere Erweiterungen). Deckt sich mit dem PR-Anspruch.

**Themen:** chats, spaces, retrieval, backend, migration, epic-523
