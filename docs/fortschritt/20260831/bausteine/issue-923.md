# Issue #923 — Query: Teilfragen-Zerlegung und kontextbewusste Reformulierung vor dem Retrieval (Multi-Query-RAG)
- Geschlossen: 2026-08-26 (completed)
- Labels: enhancement, backend, size:L, evaluation
- PRs: #926 (2026-08-26)

**Laut Issue:** Maßnahmen B+C aus #912, nachdem D/A (#914) das Originalbeispiel nachweislich nicht heilten (`001_personalausweis.md` lag strukturell unter den Führerschein-Scores der Kombifrage). Gefordert: ein LLM-Vorverarbeitungsschritt, der Mehrthemen-Fragen in 1–N eigenständige Teilfragen zerlegt, je Teilfrage eine eigene Vektorsuche mit Berechtigungsfilter, rangbasierte Zusammenführung (Reciprocal Rank Fusion statt Score-Vergleich), robuster Fallback bei Zerlegungsfehlern, sowie Ablösung der starren Erste-Nachricht-Konkatenation.

**Geliefert:** Neue `QueryDecompositionService` und `ReciprocalRankFusion`. Konfigurierbar über `queryDecompositionEnabled` (Default true) und `maxSubQueries` (Default 3). Fallback auf die alte `buildSearchQuery`-Logik bei LLM-Fehler/unparsebarer Antwort. Berechtigungsfilter nachweislich in jeder Teilsuche (dedizierter Integrationstest). MMR läuft je Teilfrage in ihrer eigenen Kandidatenmenge, nicht auf der Gesamtmenge — im PR begründet. Messung: 19/20 vorher wie nachher auf den `multi_topic`-Fällen (keine Verbesserung im Eval-Korpus, da dessen Score-Lücke nicht so scharf ist wie im echten Personalausweis-Fall), gemessene Zusatzlatenz ~157 ms. **Live-Verifikation auf der Demo folgte erst nach Deploy** (nicht Teil dieses PRs) und deckte den in #932 dokumentierten Folgedefekt auf.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryDecompositionService.java` existiert im Worktree.

**Themen:** retrieval, query, multi-query-rag, llm-integration, evaluation, epic-912
