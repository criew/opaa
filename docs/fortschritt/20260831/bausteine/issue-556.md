# Issue #556 — Sidebar-Chatliste folgt nicht dem ausgewählten Space
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #558 (2026-08-20)

**Laut Issue:** Auf der Testinstallation beobachtet: Wählt man in der Space-Übersicht einen anderen Space aus, wechselt die Sidebar-Chatliste nicht mit, sondern zeigt weiter die Chats des vorherigen Space. Erst das Öffnen eines Chats in der Space-Detailansicht bringt die Liste in Sync. Vermutung im Ticket: `Sidebar`/`ChatList` binden die Liste an den Space des aktiven Chats (`chatStore.spaceId`) statt an den ausgewählten Space (`spaceStore.selectedSpaceId`), oder der `chatListStore` lädt beim Space-Wechsel nicht neu.

**Geliefert:** Die Vermutung aus dem Issue traf im Kern zu — genauer: `Sidebar` band an `chatStore.spaceId` (Space des zuletzt geöffneten Chats). Fix: `Sidebar` liest `:spaceId` jetzt direkt aus dem Route-Match (`useParams`), das bei allen space-bezogenen Routen konsistent gesetzt ist und sich sofort mit der Navigation ändert; Fallback auf den Default-Space bleibt auf Routen ohne `:spaceId`. Reproduktionsnachweis mit rotem/grünem Testlauf in `Sidebar.test.tsx` erbracht.

**Verifikation:** `frontend/src/layouts/Sidebar.tsx` importiert und nutzt `useParams` (Zeilen 25/55 im Worktree).

**Themen:** frontend, spaces, chats, workspace, routing
