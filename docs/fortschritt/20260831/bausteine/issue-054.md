# Issue #54 — feat: Erweitertes Chat-Memory mit Persistenz und Session-Verwaltung
- Geschlossen: 2026-08-15 (not planned)
- Labels: enhancement, backend, frontend
- PRs: keine

**Laut Issue:** Aufbauend auf dem In-Memory Chat-Gedächtnis (#43) sollte das Chat-Memory persistent werden: DB-Tabellen `conversations`/`messages`, DB-backed `ChatMemory`-Implementierung statt `InMemoryChatMemory`, Token-Limit-Management (Kürzen/Zusammenfassen alter Nachrichten), Conversation-CRUD-API (Liste, Details, Löschen, Umbenennen) sowie eine Frontend-Sidebar mit Liste vergangener Konversationen, Fortsetzen, Umbenennen, Löschen und Auto-Titeln.

**Geliefert:** Nicht umgesetzt. Laut Schließungskommentar wurde das Issue als "abgelöst durch #205" geschlossen, im Rahmen der Backlog-Neuausrichtung (`docs/discussions/discussion-backlog-neuausrichtung.md`). Grund: #54 beschreibt dauerhafte Chats nach dem alten MVP-Modell (Konversation gehört einem Nutzer, frei sichtbar, löschbar), während das inzwischen eingeführte Space-/Asset-Modell ein anderes Rechte- und Sichtbarkeitsmodell vorsieht (Chat gehört einem Space, Status PRIVATE → SHARED → WITHDRAWN, kein Löschen sondern protokolliertes Zurückziehen durch Space-Admin). #205 baut die Persistenz auf dieser neuen Grundlage neu auf. Drei Teilaspekte aus #54 sind laut Kommentar in #205 **nicht** enthalten und explizit als offen vermerkt: Kontextfenster-Verwaltung (Kürzen/Zusammenfassen), automatisch generierte Konversationstitel, und die Bedienoberfläche (Liste/Fortsetzen/Umbenennen). Der Kommentar merkt zudem an, dass der Chatverlauf aktuell keinen Page-Reload übersteht.

**Verifikation:** Heute existiert `backend/src/main/java/io/opaa/query/CaffeineChatMemoryRepository.java` — ein Caffeine-Cache-basiertes Chat-Memory, also weiterhin kein DB-persistentes Modell im Sinne von #54. Das bestätigt die im Schließungskommentar beschriebene Ausgangslage.

**Themen:** backend, frontend, chat-memory, persistenz, spaces, backlog-neuausrichtung, not-planned
