# Issue #407 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-16 (completed)
- Labels: bug, evaluation
- PRs: keine

**Laut Issue:** Automatisch von `github-actions` erzeugtes Alarm-Issue: der nächtliche Retrieval-Regressionslauf ist fehlgeschlagen, ohne Report (vermutlich Manifest- oder Ein-Chunk-Invariante-Verletzung oder Zeitlimit-Abbruch). Verweis auf den Workflow-Lauf zur Diagnose.

**Geliefert:** Kein PR, kein Code-Fix — passt zum Charakter des Issues als automatischer Alarm statt Arbeitsauftrag. Laut Kommentar von `github-actions` vom 2026-08-16 ist der nächtliche Lauf beim nächsten Durchlauf „wieder grün" (Link auf Folge-Workflow-Lauf) — das Issue wurde also durch Selbstheilung bzw. einen transienten Fehler geschlossen, nicht durch eine gezielte Behebung. Keine Aussage im Datensatz, was den einmaligen Fehlschlag verursacht hat.

**Verifikation:** Nicht code-relevant — reines CI-Signal-Issue ohne bleibende Codeänderung.

**Themen:** ci, evaluation, retrieval, automatischer-alarm, transient
