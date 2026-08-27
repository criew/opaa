# Issue #933 — Indexing: Contextual Chunking — Dokumentkontext in Chunk-Embeddings
- Geschlossen: 2026-08-27 (completed)
- Labels: enhancement, backend, size:L, evaluation
- PRs: #940 (2026-08-27)

**Laut Issue:** Aus #932 (Lösungsrichtung 3) ausgekoppelt: Chunks tragen ohne Dokumentkontext kaum Signal, wovon sie handeln (z. B. eine reine Gebührentabelle) — das ist die Wurzel mehrerer Rankingprobleme. Gefordert: ein Kontext-Präfix vor dem Embedding (nicht in Metadaten, wegen der bestehenden Whitelist-Invariante der Embedding-Pipeline), Entscheidung Embedding-only vs. gespeicherter Text, eine Migrationsstrategie (Voll-Reindex), neu gezogene Eval-Baselines beider Domänen ohne globale Verschlechterung, und ein Live-Nachweis auf der Demo.

**Geliefert:** `ChunkContextTitle` leitet aus dem Dateinamen einen bereinigten Titel ab; Präfix greift nur embedding-seitig (nicht im gespeicherten Text/Zitat), ausschließlich für Dokumente, die in **2 oder mehr Chunks** zerfielen — ein einzelner Chunk bekommt bewusst keinen Präfix. Diese „Split-Gate“-Form wurde erst nach zwei verworfenen Varianten gefunden (roher Dateiname auf allen Chunks regressierte city-landmarks, humanisierter Titel auf allen Chunks regressierte comic-characters); beide Eval-Baselines wurden entsprechend neu gezogen/geprüft. Voll-Reindex als Migrationsstrategie dokumentiert. **Offener Punkt, im PR selbst benannt:** Für den `maria.weber`-Fall aus #938 lagen nach dem Reindex live beide einschlägigen Quellen außerhalb `topK=8` — Live-Verifikation beider Demo-Konten war explizite Merge-Nachbedingung, nicht Teil dieses PR-Diffs.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChunkContextTitle.java` existiert im Worktree.

**Themen:** indexing, chunking, retrieval, evaluation, epic-912
