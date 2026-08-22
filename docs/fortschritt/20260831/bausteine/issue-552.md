# Issue #552 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, evaluation
- PRs: #563 (2026-08-20)

**Laut Issue:** Automatisch von `app/github-actions` erzeugtes Issue: Der nächtliche Retrieval-Regressionslauf schlug fehl, ohne einen Report zu erzeugen — vermutlich vor der Baseline-Prüfung abgebrochen (Manifest- oder Ein-Chunk-Invariante-Verletzung) oder durch Zeitlimit. Kein inhaltlicher Befund im Issue selbst, nur der Workflow-Link.

**Geliefert:** Ursachenanalyse ergab: PR #536 (System-Bibliothek entfernen, #521) hatte den Retrieval-Harness bereits auf eine eigene Eval-Zielbibliothek umgestellt, dabei aber implizit den Quellentyp `UPLOAD` gesetzt. Das kollidierte mit dem später gemergten ADR-0018/#478 („Indizierung je Bibliothek"), das jede Indizierung einer `UPLOAD`-Bibliothek mit 409 ablehnt. Fix: Der Harness legt die Eval-Zielbibliothek jetzt explizit als `FILESYSTEM`-Bibliothek mit `sourcePath=corpusWorkingDir` an und trägt den Pfad in die Filesystem-Allowlist ein. Fachliche Aussagekraft der Messung blieb unverändert (Korpus, Suchpfad, Messvertrag identisch) — die Messwerte weichen von der Baseline nur im Rundungsrauschen ab, keine Baseline-Anpassung nötig. Klassisches Beispiel einer durch parallele, sich überschneidende PRs entstandenen Integrationslücke.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/RetrievalEvaluationHarnessTest.java` enthält im Worktree die `FILESYSTEM`/`corpusWorkingDir`-Logik (Zeilen ~201/306-311).

**Themen:** evaluation, retrieval, ci, library
