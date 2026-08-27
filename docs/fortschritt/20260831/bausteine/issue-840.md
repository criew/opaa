# Issue #840 — fix(chat): Archivierungsprüfung vor dem LLM-Aufruf statt erst beim Persistieren
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: #855 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund A4). Die Archivierungsprüfung eines Chats lief erst in `ChatService.appendTurn`, nach dem LLM-Aufruf — wird ein Space zwischenzeitlich archiviert, wird eine bereits bezahlte LLM-Antwort verworfen.

**Geliefert:** `QueryService.query` prüft jetzt zusätzlich vor Retrieval/LLM-Aufruf, ob der Space eines persistierten Chats archiviert ist (`ChatService#requireSpaceNotArchived` von `private` auf `public` erweitert und wiederverwendet, kein neuer Text/Status). Die späte Prüfung in `appendTurn` bleibt als Race-Absicherung bestehen. Bewusster Trade-off: ein zusätzlicher SELECT pro Anfrage mit persistiertem Chat, um den teureren LLM-Aufruf im Normalfall zu vermeiden.

**Verifikation:** `backend/src/main/java/io/opaa/chat/ChatService.java` und `QueryService.java` im Worktree vorhanden. Reproduktionsnachweis (roter Test mit NullPointerException, weil der Modellaufruf tatsächlich ausgelöst wurde) in PR-Beschreibung dokumentiert.

**Themen:** chat, bugfix, kosten, archivierung
