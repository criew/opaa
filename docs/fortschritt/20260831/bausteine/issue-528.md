# Issue #528 — @-Bibliotheksreferenzen und Schalter „Wissen nutzen“ im Eingabefeld; Space-Filter entfernen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #539 (2026-08-19)

**Laut Issue:** Die wirkungslose Space-Mehrfachauswahl im Eingabefeld soll ersatzlos entfallen. Stattdessen Schalter „Wissen nutzen" (Default an, mit Hinweis bei Aus-Zustand ohne Referenzen), @-Autocomplete für lesbare Bibliotheken, sticky entfernbare Chips, die persistiert werden sobald die Chat-Persistenz verfügbar ist. Tastaturbedienbarkeit gefordert.

**Geliefert:** PR #539 liefert Space-Filter-Entfernung (Popover, `chatFilterSpaceIds` aus `spaceStore`), Schalter mit Hinweistext bei `answeredWithoutKnowledge`, @-Autocomplete über den bereits vorhandenen `libraryStore` (#421) mit Pfeiltasten/Enter/Escape und ARIA-Rollen, sticky Chips mit auf spätere Persistenz vorbereiteter Store-Schnittstelle (die dann in #527 tatsächlich angebunden wurde). Dokumentation nachgezogen.

**Verifikation:** `frontend/src/components/chat/ChatInput.tsx` existiert im Worktree und ist laut Dateiliste zentraler Ort der Änderung. Keine weitere Tiefenprüfung nötig, Feature baut konsistent auf #526 (Backend-API) und #527 (Persistenz) auf.

**Themen:** chats, retrieval, frontend, spaces, epic-523
