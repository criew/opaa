# Issue #560 — Suchbereich als Chip-Leiste: @Alles-Wissen statt Schalter „Wissen nutzen“
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, enhancement, frontend, size:M
- PRs: #564 (2026-08-20)

**Laut Issue:** Der Suchbereich eines Chats soll ausschließlich über die Chip-Leiste am Eingabefeld gesteuert werden; der Schalter „Wissen nutzen“ entfällt. Drei Zustände: Standard-Chip @Alles-Wissen (alle lesbaren Bibliotheken), konkrete Bibliotheks-Chips (nur referenzierte ∩ lesbare) oder leere Leiste (kein Retrieval, mit Hinweis). Der erste konkrete Chip ersetzt @Alles-Wissen und umgekehrt. Spezifikation und E2E-Suite sollen im selben PR nachgezogen werden.

**Geliefert:** `chatStore` wurde auf `scope: 'all' | 'libraries' | 'none'` umgestellt, mit der beschriebenen Ersetzungslogik und atomarer PATCH-Persistierung. `ChatInput` entfernt den Schalter, bietet @Alles-Wissen immer als ersten Autocomplete-Eintrag, jeder Chip ist entfernbar, leere Leiste zeigt Hinweistext mit Ein-Klick-Rückweg. Backend blieb unverändert, da das bestehende `useKnowledge`/`referencedLibraryIds`-Schema alle drei Zustände bereits abdeckt. Spezifikation (`spaces-and-assets.md`, `user-frontends.md`, `agents-and-tools.md`, `CONCEPTS.md`, `STATUS.md`) wurde nachgezogen. E2E-Anpassung (`space-chats.spec.ts`) war zum PR-Zeitpunkt noch nicht möglich, da die betroffene Datei erst mit dem parallel laufenden PR #554 entstand — laut PR-Beschreibung sollte ein Rebase nach dessen Merge folgen; das im Chunk vorliegende Datei-Diff zeigt `e2e/tests/space-chats.spec.ts` und `e2e/fixtures/chat.ts` bereits als geänderte Dateien, das Ergebnis dieses nachträglichen Rebase-Schritts ist also mit im PR enthalten.

**Verifikation:** `frontend/src/components/chat/ChatInput.tsx` enthält die Scope-/@Alles-Wissen-Logik unverändert (Kommentare referenzieren #560 explizit); Schalter „Wissen nutzen“ ist nicht mehr vorhanden.

**Themen:** chat, retrieval, suchbereich, frontend, doku
