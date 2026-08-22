# Issue #306 — eval(baseline): Fallzahlbasierte Regressionsprüfung für Paare mit Toleranz < 1/n
- Geschlossen: 2026-08-21 (completed)
- Labels: size:M, ci, evaluation
- PRs: #694 (2026-08-21)

**Laut Issue:** Sechs Metrik/Gruppen-Paare (v. a. `numeric_range`, `multi_attribute_filter`) haben eine Mittelwert-Toleranz, die enger ist als die Verschiebung eines einzelnen kippenden Falls (`1/n`) — ein einzelner Fall konnte die Baseline-Prüfung fälschlich reißen lassen. Gefordert: fallzahlbasierte Prüfung für genau diese Paare, ohne die Mittelwert-Toleranz für andere Paare zu ändern, mit Unit-Test für das Kipp-Szenario.

**Geliefert:** PR #694 ersetzt für Paare mit `toleranceFor(...) < 1/n` die Mittelwert-Toleranz durch eine fallzahlbasierte Prüfung (`MAX_CASE_COUNT_DROP = 1`), betroffene Paare werden **dynamisch** ermittelt statt über eine feste Liste. Dafür führt die Baseline neu `hitCountAt5`/`hitCountAt10` je Gruppe. Werte stammen aus einem realen, artefaktverifizierten `checkRetrievalBaseline`-Lauf auf CI, keine neue lokale Messung. Test `oneCaseFlipInNumericRangeNoLongerFalselyFailsTheCaseBasedPairs` reproduziert das Issue-Szenario, ein zweiter Test bestätigt, dass echte Regressionen weiter erkannt werden. Entscheidung in ADR-0013 nachgetragen.

**Verifikation:** `BaselineComparator.java` im Worktree enthält `usesCaseBasedCheck`.

**Themen:** evaluation, retrieval, ci, baseline
