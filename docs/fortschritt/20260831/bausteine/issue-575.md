# Issue #575 — Frontend-Stores: In-flight-Antworten schreiben nach Logout/Reset wieder in geleerte Stores
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #626 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #574 (#440): `reset()` leert die Stores beim Logout, aber laufende Anfragen schreiben ihr Ergebnis ungeschützt zurück — nur `loadChat` prüft ein Sequenz-Token. Konkret genannt: `chatStore.sendMessage` (insbesondere bei 401-ausgelöstem Logout), der `indexingStore`-Poll-Callback, `spaceStore.loadSpaces` und `libraryStore.loadLibraries`.

**Geliefert:** Statt eines Tokens pro Store wurde ein gemeinsamer Sitzungs-Epoch-Zähler (`frontend/src/stores/sessionEpoch.ts`) eingeführt, der bei jedem Reset inkrementiert wird; alle acht registrierten Stores (`chatStore`, `chatListStore`, `spaceStore`, `libraryStore`, `documentStore`, `groupStore`, `grantStore`, `indexingStore`) prüfen ihn vor dem abschließenden `set()`. Deckt die vier explizit genannten Pfade ab und geht bei der systematischen Durchsicht der `resettableStores`-Registrierung darüber hinaus (u. a. `chatListStore`, `documentStore`, `groupStore`, `grantStore`). Für alle sechs zentralen Pfade liegt ein einzeln dokumentierter Rot/Grün-Nachweis vor.

**Verifikation:** `frontend/src/stores/sessionEpoch.ts` existiert im Worktree.

**Themen:** frontend, auth, session, bugfix, race-condition
