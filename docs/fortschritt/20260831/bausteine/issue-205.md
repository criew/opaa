# Issue #205 — Persistent chats inside spaces
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:L
- PRs: keine

**Laut Issue:** Neubau (keine Umbenennung) eines persistenten Chats als Space-eigenes Objekt: privat beim Autor, sichtbar für alle Mitglieder erst nach bewusstem Platzieren (`PRIVATE`→`SHARED`→`WITHDRAWN`), inklusive Provenienz-Verfolgung, Widerruf durch Autor/Space-Admin, Export auch privater Chats und Benachrichtigung bei wesentlicher Erweiterung des Leserkreises.

**Geliefert:** Teilweise, aber unter anderem Zuschnitt. Am 19.08.2026 wurde das Issue neu geschnitten: Die Persistenz-Grundlage (Chat/Nachrichten als persistente Objekte in genau einem Space, zunächst privat, CRUD-API, Query-Anbindung) entstand im separaten Epic #523 (konkret #525) — nicht in diesem Issue. Die Suchbereichsfrage wandert zum Schalter "Wissen nutzen" und @-Bibliotheksreferenzen (#526/#528). Beim Schließen (im Zuge von Epic #198) wird bestätigt: Der Kern — persistente Chats innerhalb eines Spaces, Neustart-Überleben, ein Chat pro Space, `ChatStatus.PRIVATE` — ist seit #525 umgesetzt. Der eigentliche Kollaborationsteil dieses Issues (Platzieren, Leserkreis, Provenienz-Hinweis im Freigabedialog, Widerruf, Export, `ChatParticipant`) ist **bewusst nicht umgesetzt** und bekommt erst bei Bedarf ein neues Ticket.

**Verifikation:** `backend/src/main/java/io/opaa/chat/Chat.java` existiert; keine `ChatStatus`-Datei mit `SHARED`/`WITHDRAWN`-Werten gefunden — bestätigt, dass nur der Persistenz-Kern (über #523) geliefert wurde, der Teilen-Lebenszyklus dieses Issues jedoch fehlt.

**Themen:** spaces, chats, retrieval, agenten
