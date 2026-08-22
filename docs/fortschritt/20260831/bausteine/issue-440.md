# Issue #440 — fix(frontend): Space-, Gruppen- und Bibliotheks-Store beim Logout zurücksetzen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #574 (2026-08-20)

**Laut Issue:** `authStore.ts` reset beim Logout nur `spaceStore`. `groupStore` und `libraryStore` haben zwar `reset()`, es wird aber nie aufgerufen — bei Nutzerwechsel im selben Tab bleiben fremde Daten sichtbar. Vorschlag: gemeinsame Registrierung statt Einzelimporte.

**Geliefert:** PR #574 geht über den Issue-Umfang hinaus: Neben `spaceStore`/`groupStore`/`libraryStore` wurden zusätzlich `chatStore`, `chatListStore`, `documentStore`, `indexingStore` und `grantStore` geprüft und einbezogen — `chatStore` und `indexingStore` bekamen dabei überhaupt erst eine `reset()`-Aktion. Die im Issue vorgeschlagene gemeinsame Registrierung wurde als `frontend/src/stores/resettableStores.ts` umgesetzt. `uiStore` bewusst ausgenommen (Geräteeinstellungen, keine Sitzungsdaten). Reproduktionsnachweis mit rotem/grünem Testlauf erbracht.

**Verifikation:** `frontend/src/stores/resettableStores.ts` existiert im heutigen Code.

**Themen:** frontend, auth, logout, spaces, workspace, statemanagement
