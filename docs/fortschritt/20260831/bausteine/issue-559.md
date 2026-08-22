# Issue #559 — Chat-Seite bleibt im Lade-Spinner hängen, wenn „Neuer Chat“ einen laufenden loadChat unterbricht
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #562 (2026-08-20)

**Laut Issue:** In einem im E2E-Lauf beobachteten Fehlerbild bleibt `<main>` dauerhaft im `CircularProgress` hängen, wenn `startNewChat()` einen laufenden `loadChat()` per Sequenz-Token überholt — der überholte Handler kehrt vor seinem `set()` zurück, sodass niemand `isLoadingChat` zurücksetzt. Erwartet wird ein Fix mit Rot/Grün-Reproduktionsnachweis, sowie eine Prüfung, ob derselbe Pfad auch bei schnellem Wechsel zwischen zwei Chats auftritt.

**Geliefert:** `startNewChat()` setzt `isLoadingChat: false` jetzt direkt im eigenen synchronen `set()`, statt darauf zu warten, dass der überholte `loadChat`-Handler es irgendwann tut. Den zweiten im Issue genannten Fall (loadChat überholt loadChat) deckte laut PR bereits ein bestehender Test ab, da dort die zuletzt aufgelöste Anfrage `isLoadingChat` selbst korrekt zurücksetzt. Rot/Grün-Nachweis mit konkreter Fehlermeldung in der PR-Beschreibung enthalten.

**Verifikation:** `frontend/src/stores/chatStore.ts` existiert im Worktree und enthält weiterhin die Sequenz-/Guard-Logik (siehe auch #565/#573, die auf demselben Muster aufbauen).

**Themen:** chat, frontend, bugfix, race-condition
