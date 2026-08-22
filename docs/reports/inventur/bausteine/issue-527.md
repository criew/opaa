# Issue #527 — Chats unterhalb von Spaces führen (Routen, Chatliste, persistenter Verlauf)
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L, workspace
- PRs: #548 (2026-08-20)

**Laut Issue:** Route `/spaces/:spaceId/chats/:chatId` statt globaler `/chat`-Route (kein toter Link), Chatliste in Space-Seite/Sidebar mit Umbenennen/Löschen, `chatStore`-Umbau auf API-gestützten Verlauf mit Wiederherstellung nach Neuladen, impliziter Chat-Einstieg ohne vorhandenen Chat.

**Geliefert:** PR #548 liefert die Route inklusive `ChatRedirect`-Komponente (Redirect auf Default-Space + letzten Chat, oder auf `.../chats/new` als virtuellen, noch nicht persistierten Chat), eine wiederverwendete `ChatList`-Komponente in `SpacePage` und `Sidebar`, Umbenennen/Löschen mit `window.confirm`, `chatStore`-Umbau inkl. Persistierung von Schalter/Chips aus #528 sobald ein Chat existiert. Neue Store `chatListStore`, neue API-Funktionen. Dokumentation (`user-frontends.md`) nachgezogen.

**Verifikation:** `frontend/src/components/chat/ChatList.tsx` und `frontend/src/pages/ChatRedirect.tsx` (referenziert im PR) existieren im Worktree; `frontend/src/components/chat/` enthält im aktuellen Stand auch `MessageList.tsx`, `SourceEvidenceDrawer.tsx`, `SourceFootnotes.tsx`, `citations.ts` — spätere Erweiterungen über diesen PR hinaus, aber konsistent mit dem hier gelegten Fundament.

**Themen:** chats, spaces, frontend, epic-523
