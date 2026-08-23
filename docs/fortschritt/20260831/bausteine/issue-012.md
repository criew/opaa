# Issue #12 — feat(query): implement LLM answer generation with source references
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, size:L
- PRs: #36 (2026-02-26)

**Laut Issue:** Generierungs-Komponente der RAG-Pipeline: `ChatModel`-Bean (OpenAI/Ollama, unabhängig vom Embedding-Provider konfigurierbar), `AnswerGenerationService` mit System-/User-Prompt-Konstruktion, Wiring in `QueryService` (Retrieval → Generation), Fehlerbehandlung für LLM-Fehler (Timeout, Rate Limit).

**Geliefert:** PR #36 liefert die Anforderung vollständig: `AnswerGenerationService`, `QueryService`, `QueryController`, `QueryConfiguration`, Fehlerbehandlung für `TransientAiException` (503) und `NonTransientAiException` (502), konfigurierbare Temperature/MaxTokens, Unit- und Integrationstests. Deckt zugleich #11 (Retrieval) implizit mit ab, siehe Baustein zu Issue #11.

**Auffälligkeit — Fehlzuordnung in den Daten:** Die Chunk-Daten weisen zusätzlich #286 und #291 als verknüpfte PRs aus. Beide betreffen tatsächlich ein völlig anderes Thema — das Tagesreport-CI-Skript (`.github/scripts/daily_report.py`, `docs/tagesreport.md`), nicht die LLM-Antwortgenerierung. Der PR-Body von #291 erklärt die Ursache selbst: In #286 wurden Test-Beispieltexte wie „`fixes #12 und Closes #13`" in der eigenen PR-Checkliste fälschlich als echte `Closes #N`-Referenzen ausgewertet, wodurch #286 (und in der Folge #291, dessen Body #286 zitiert) automatisiert mit #12 (und #13, #99, #221) verknüpft wurde, obwohl inhaltlich kein Bezug besteht. #291 behebt genau diesen Fehler in der PR-Zuordnungslogik des Report-Skripts. Für die Leistungsinventur zählt daher **nur #36** als tatsächlicher Liefer-PR von Issue #12.

**Verifikation:** `backend/src/main/java/io/opaa/query/AnswerGenerationService.java` und `QueryConfiguration.java` existieren weiterhin im Worktree.

**Themen:** backend, generation, rag, llm, dokumentationslücke, ci
