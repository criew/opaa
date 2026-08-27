# Issue #912 — Mehrthemen-Fragen: Retrieval verdrängt das schwächere Thema vollständig (topK-Monokultur)
- Geschlossen: 2026-08-27 (completed)
- Labels: enhancement, backend, evaluation
- PRs: keine (Sammel-/Ursprungs-Issue ohne eigenen PR — Umsetzung in Sub-Issues, siehe unten)

**Laut Issue:** Detaillierter Live-Befund auf der Demo (25.08.2026): Bei Mehrthemen-Fragen (z. B. „was kosten führerschein und personalausweis“) füllt das dominantere Thema alle `topK`-Plätze, das schwächere Thema bekommt keinen Chunk. Drei Ursachen identifiziert: ein einziger Suchvektor für zwei Teilfragen, Tippfehlerverschiebung der Ähnlichkeit, und eine starre Erste-Nachricht-Konkatenation bei Folgefragen. Fünf unabhängig umsetzbare Lösungsrichtungen A–E vorgeschlagen (MMR-Diversität, Teilfragen-Zerlegung, Query-Reformulierung, topK-Anhebung, Eval-Absicherung) mit empfohlener Reihenfolge E → A+D → B/C.

**Geliefert:** Dieses Issue ist kein eigener Lieferbaustein, sondern die Ursachenanalyse und Wurzel eines kleinen Epics. Die tatsächliche Arbeit steckt in den Sub-Issues, die die vorgeschlagene Reihenfolge fast exakt umsetzten: #913 (Maßnahme E, Eval-Fälle), #914 (Maßnahmen A+D, MMR/topK), #923 (Maßnahmen B+C, Multi-Query-RAG), sowie den daraus entstandenen Folgebefunden #932 (Chunk-Vervollständigung), #933 (Contextual Chunking), #937 (Zitat-Faktenprüfung) und #938 (verbleibende Rankinggrenze für Satzungs-PDF). Alle sind in dieser Inventur als eigene Bausteine erfasst.

**Verifikation:** Entfällt (kein eigener Code-Beitrag dieses Issues); siehe die Verifikationen der genannten Sub-Issues.

**Themen:** retrieval, epic, evaluation, mehrthemen, query
