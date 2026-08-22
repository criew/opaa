# Issue #529 — E2E-Abdeckung: Chat im Space, @-Referenzen und Wissens-Schalter
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, size:M, ci
- PRs: #554 (2026-08-20)

**Laut Issue:** Fünf E2E-Szenarien über den Docker-Compose-Stack: Chat im Space mit Neuladen-Persistenz, @-Referenz schränkt die Suche ein, Antwort ohne Wissensbasis bei leerem Referenz-Set, Rechte-Negativfall (nicht lesbare Bibliothek erscheint nicht in Vorschlägen), mehrere Chats mit getrenntem Verlauf/Referenzen. Kein Szenario darf von echter LLM-Ausgabe abhängen; CI soll grün bleiben.

**Geliefert:** PR #554 liefert alle fünf Szenarien in `e2e/tests/space-chats.spec.ts`, mit wiederverwendeten Hilfsfunktionen aus `e2e/fixtures/chat.ts` (aus `knowledge-libraries.spec.ts` extrahiert). Der PR-Body dokumentiert mehrere Nachbesserungsrunden nach echten CI-Läufen: Fixture-Namenskollisionen, Sortierposition/Korpusverschmutzung bei ungescopter Suche, eine Race-Bedingung in `startFreshChat` gegen bereits geladene Chats, und einen Dev-Auth-Identitätsverlust nach `?devUser=`-Navigation. Zwei der gefundenen Produktverhaltensweisen (Race in `chatStore.ts` um `isLoadingChat`, möglicher `devUser`-Verlust) wurden explizit **nicht** in diesem PR behoben, sondern als eigene Befunde an Koordination/Maintainer gemeldet — laut PR-Body nicht als eigenständige Issues, sondern zur weiteren Untersuchung.

**Verifikation:** `e2e/tests/space-chats.spec.ts` existiert im Worktree. Keine Anhaltspunkte im Chunk, ob die zwei gemeldeten Frontend-Befunde (Race-Bedingung, devUser-Verlust) später als eigene Issues aufgegriffen wurden — das liegt außerhalb dieses Chunks.

**Themen:** ci, e2e, chats, retrieval, epic-523
