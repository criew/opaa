# Issue #913 — Eval: Mehrthemen-Golden-Fälle und Recall pro Teilthema
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: #915 (2026-08-25)

**Laut Issue:** Maßnahme E aus #912, absichtlich zuerst umzusetzen: Das Golden Dataset und der Eval-Harness konnten das Mehrthemen-Fehlerbild bisher nicht messen. Gefordert: 10–20 Mehrthemen-Golden-Fälle (idealerweise `city-landmarks.json`), Tippfehler-Varianten, eine neue Metrik „alle erwarteten Dokumente getroffen“ statt eines verwässernden Teilkredits, sowie eine aktualisierte Baseline mit erwartbar schlechten `multi_topic`-Werten als Vorher-Messung.

**Geliefert:** 20 neue `multi_topic`-Fälle in `eval/golden/city-landmarks.json` (15 medium, 5 Tippfehler-Varianten hard). Neue Metrik `allExpectedDocumentsHitAt10`, binär statt Teilkredit — bestätigt als notwendig, da die bestehende `recallAt10` bei einem von zwei Dokumenten bereits 0,5 vergeben hätte. **Wesentliche Abweichung vom Issue:** Die erwartete „erwartbar schlechte“ Vorher-Messung trat **nicht** ein — `allExpectedDocumentsHitAt10=1,000` für alle 20 Fälle, weil der Harness mit einem dokumentbezogenen Fenster von `documentTopK=10` misst (ADR-0012), nicht mit dem Produktions-`topK=5`, das die Verdrängung auf der Demo tatsächlich auslöste. Diese Baseline belegt das #912-Fehlerbild damit noch nicht; eine engere Messung wäre eigener Aufwand gewesen und wurde nicht umgesetzt.

**Verifikation:** `eval/golden/city-landmarks.json` enthält `multi_topic`-Einträge (Grep bestätigt Treffer).

**Themen:** evaluation, retrieval, golden-dataset, metriken
