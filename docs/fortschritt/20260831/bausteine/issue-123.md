# Issue #123 — feat(query): Gesprächsgedächtnis je Person trennen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** Das flüchtige Gesprächsgedächtnis (`CaffeineChatMemoryRepository`) war allein über die Gesprächskennung adressiert — eine fremde, erratene oder bekannte Konversations-ID lieferte fremden Gesprächsverlauf als Kontext. Gefordert: Schlüssel um `userId` erweitern (`{userId}:{conversationId}`), durchgängig in `QueryService`, mit Test, der zwei Konten dieselbe Kennung verwenden lässt.

**Geliefert:** Laut Schließungskommentaren (21.08.2026) war die Lücke bereits geschlossen — der Gedächtnisschlüssel folgt bereits dem Muster `userId:chatId` und wird auf allen Pfaden durchgesetzt. Es gibt daher keinen PR, der dieses Issue schließt; die Behebung ist an anderer Stelle (vermutlich im Rahmen der Chat-/Space-Arbeiten) bereits mitgeliefert worden, wurde hier nur nachgeprüft und bestätigt. Ausdrücklich als Zwischenlösung bis #205 (persistente Chats im Space) markiert.

**Verifikation:** `CaffeineChatMemoryRepository.java` existiert im Worktree unter `backend/src/main/java/io/opaa/query/`. Eine detaillierte Codeprüfung des Schlüsselformats wurde im Rahmen dieser Recherche nicht vertieft (Primärquelle: Schließungskommentar mit expliziter Bestätigung „Lücke ist zu — doppelt").

**Themen:** auth, chat, gedächtnis, sicherheit, personenbezug
