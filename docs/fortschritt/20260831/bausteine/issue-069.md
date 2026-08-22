# Issue #69 — 🟡 [MEDIUM] ChatMemory Lifecycle Management Unclear
- Geschlossen: 2026-02-28 (completed)
- Labels: enhancement, backend, size:M
- PRs: #83 (2026-02-28)

**Laut Issue:** `ChatMemory` hatte keine erkennbare Eviction-Policy — Risiko für unbegrenztes Speicherwachstum bzw. OOM bei Langzeitbetrieb. Gefordert: TTL- oder LRU-basierte Begrenzung, Metriken, Persistenzstrategie klären.

**Geliefert:** PR #83 ersetzt das unbegrenzte `InMemoryChatMemoryRepository` durch `CaffeineChatMemoryRepository` mit LRU-Eviction (max. 50 Sessions), TTL (60 min Inaktivität) und begrenztem Nachrichtenfenster (max. 20 Nachrichten je Session), inkl. 8 Unit-Tests. Deckt die Kernanforderung ab; explizite Speicher-Metriken (separat von den in #65 gelieferten Query-/Indexing-Metriken) wurden nicht ergänzt.

**Verifikation:** `backend/src/main/java/io/opaa/query/CaffeineChatMemoryRepository.java` existiert im heutigen Worktree weiterhin.

**Themen:** backend, chat, memory-leak, caching
