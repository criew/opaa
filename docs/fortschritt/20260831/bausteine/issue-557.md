# Issue #557 — Chat-Titel nach der ersten Antwort per LLM ermitteln
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #561 (2026-08-20)

**Laut Issue:** Nach der ersten Antwort in einem neuen Chat soll das System per LLM einen kurzen, deutschen Titel ermitteln (statt des mechanischen Präfix-Titels), außer der Nutzer hat bereits selbst einen Titel gesetzt. Die Generierung darf die Antwortzeit nicht verzögern; LLM-Fehler dürfen die Antwort nicht beeinträchtigen (Fallback Präfix-Titel).

**Geliefert:** `ChatTitleGenerationService` generiert den Titel asynchron (`@Async` auf eigenem `chatTitleTaskExecutor`), ausgelöst nachdem `ChatService#appendTurn` die Antwort samt Präfix-Fallback committed hat. Titelherkunft wird als `chats.title_source` (`GENERATED`/`CUSTOM`) persistiert (Migration 034); ein nutzergesetzter Titel wird nie überschrieben, auch nicht im Race-Fenster während laufender Generierung (per Test belegt, mit Rot/Grün-Reproduktionsnachweis). `QueryResponse` liefert den aktuellen Titel synchron im neuen Feld `chatTitle` mit. Frontend übernimmt den Titel sofort und lädt nach 2,5 s einmalig nach, um den fertig generierten LLM-Titel zu übernehmen. Deckt die Abnahmekriterien vollständig ab.

**Verifikation:** `backend/src/main/java/io/opaa/chat/ChatTitleGenerationService.java` und `TitleSource.java` existieren im Worktree.

**Themen:** chat, retrieval, llm, frontend, backend
