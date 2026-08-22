# Issue #224 — Epic: Suchqualität messbar machen — Eval-Korpus und Retrieval-Regression
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, epic, size:L, evaluation
- PRs: keine

**Laut Issue:** Epic zur messbaren Suchqualität von OPAA — lizenzsauberer, eingefrorener Testkorpus und automatische Retrieval-Regression in CI. Phase 1 (Korpus-Generator, Golden Dataset, Metrik-Harness, CI-Regression, Tickets #225–#228) sollte end-to-end für eine Domäne funktionieren. Die ursprünglich geplante Phase 2 (Demo-Instanz) wurde am 21.08.2026 in ein eigenes Epic (#708) ausgegliedert; dieses Epic behält nur die Messung selbst.

**Geliefert:** Kein PR referenziert „Closes #224“ — das ist beim Epic-Muster dieses Projekts der Normalfall (siehe AGENTS.md: Epics führen Sub-Issues als native Verknüpfung, nicht als direkt schließenden PR). Die eigentliche Lieferung liegt in den Sub-Issues #225–#228, die laut Epic-Body als „Abgeschlossen“ markiert sind. `git log --grep="224"` zeigt zwei branchbezogene Merge-Commits (`feature/224_search-quality-evaluation`, PR #236; `feature/224_spec-korrektur`, PR #253) — beides Dokumentations-/Spezifikationsarbeit direkt am Epic (ADR-0008, Spezifikation `docs/features/search-quality-evaluation.md`), nicht die Implementierung selbst. Die Abnahmekriterien auf Epic-Ebene sind laut Issue-Text als erfüllt abgehakt (Korpus mit SHA-256-Manifest, Golden Dataset mit 121 Fällen, CI-Regression). Das Epic wurde also nicht durch einen eigenen PR, sondern durch den Abschluss seiner Sub-Issues erledigt — konsistent mit dem Prozess für Epics in diesem Repository.

**Verifikation:** `eval/corpus/comic-characters/` und `eval/corpus/city-landmarks/` existieren, `eval/golden/comic-characters.json` existiert, `.github/workflows/retrieval-regression.yml` existiert im heutigen Worktree-Stand — die im Epic behaupteten Artefakte sind tatsächlich vorhanden.

**Themen:** evaluation, retrieval, ci, epic, agenten-organisation
