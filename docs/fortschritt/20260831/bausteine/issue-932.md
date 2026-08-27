# Issue #932 — Query: Gebühren-Chunk verliert gegen Einleitungs-Chunk desselben Dokuments — Chunk-Auswahl nach der Fusion vervollständigen
- Geschlossen: 2026-08-26 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: #934 (2026-08-26), #935 (2026-08-26)

**Laut Issue:** Folgebefund der Live-Verifikation von #923 auf der Demo: Die Teilfragen-Zerlegung wirkt, beide Themen werden abgerufen — aber von `001_personalausweis.md` überlebt nur der Einleitungs-Chunk die Fusion, nicht der Gebühren-Chunk desselben Dokuments. Drei Lösungsrichtungen zur Bewertung vorgeschlagen: Dokument-Vervollständigung im Retrieval, Fusions-Budget entkoppeln, oder Chunking mit Dokumentkontext anreichern.

**Geliefert:** Lösungsrichtung 1 (Dokument-Vervollständigung) umgesetzt, in zwei Runden. PR #934 („Zuschnitt v1“) verdrängte nur den auswahlrang-schwächsten Chunk eines bereits ≥2-Chunk-vertretenen Dokuments — die Live-Verifikation nach dem Merge scheiterte, weil bei 8 Ein-Chunk-Dokumenten keine Verdrängungsquelle existierte. Das Issue wurde daraufhin **wieder geöffnet** und mit PR #935 um eine zweite Verdrängungsstufe ergänzt (Stufe 2: auswahlrang-letzter Chunk der Gesamtauswahl, gedeckelt auf `max(1, topK/4)` Verdrängungen je Aufruf, nach einem Review-Fund zur unbegrenzten Verdrängung). Lösungsrichtung 2 wurde nicht separat umgesetzt (bereits über bestehende Konfiguration abgedeckt), Lösungsrichtung 3 in #933 ausgekoppelt.

**Verifikation:** `backend/src/main/java/io/opaa/query/DocumentCompletion.java` existiert im Worktree.

**Themen:** retrieval, query, chunk-auswahl, evaluation, epic-912
