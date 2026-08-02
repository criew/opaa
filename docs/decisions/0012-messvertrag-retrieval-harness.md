# ADR-0012: Messvertrag des Retrieval-Harness

## Status

Vorgeschlagen — Entwurf des Code Reviewers zu PR #292 (Issue #227), übernommen und in der
Review-Nacharbeit desselben PRs umgesetzt (`measurementContractVersion` im Report, `allQueryResults`
im JSON-Report, `recallAt10Ceiling` je Gruppe).

## Kontext

ADR-0011 entscheidet, **dass** der Retrieval-Harness java-nativ ist, welches Einbettungsmodell er
verwendet und wo Korpus und Golden Dataset liegen. Es entscheidet **nicht**, wie die vier Metriken
genau definiert sind und mit welchen Suchparametern gemessen wird. PR #292 legt beides im Code
fest, ohne dass es an einer Stelle verbindlich niedergeschrieben wäre.

Das wird spätestens mit #228 folgenreich: Dort wird eine Baseline eingefroren, gegen die künftig
jede Pipeline-Änderung gemessen wird. Eine Baseline ist aber nur so lange vergleichbar, wie der
Messvertrag unverändert bleibt. Wird später etwa die Gain-Funktion des nDCG, die Basis des idealen
DCG, das Suchfenster `topK` oder die angewandte Ähnlichkeitsschwelle geändert, verschieben sich alle
Zahlen — ohne dass sich die Retrieval-Qualität geändert hätte. Der Fehler wäre nicht offensichtlich:
Er erzeugt keine Ausnahme, sondern eine stabil reproduzierte, falsche Wahrheit.

Konkret sind heute nur im Code festgelegt (`backend/src/main/java/io/opaa/eval/`,
`backend/src/evalTest/java/io/opaa/eval/`):

- Binäre Relevanz, Gain 1, Diskont `1/log2(rang+1)`, Rangzählung 1-basiert.
- Ideales DCG über `min(|erwartete Dokumente|, k)` — nicht über `k`.
- Unterschiedliche Fenster je Metrik: Hit Rate@5, nDCG@10, Recall@10, MRR über das volle Fenster
  (`topK=10`, also faktisch MRR@10).
- Gemessen wird mit `topK=10` und `similarityThreshold=0.0`, während die Produktion `top-k=5` und
  `similarity-threshold=0.3` verwendet.
- Fälle mit mehr als `k` erwarteten Dokumenten begrenzen Recall@k nach oben; das Golden Dataset
  lässt Treffermengen bis 15 zu.
- Aggregiert wird als Mikro-Mittel über Anfragen (jede Anfrage zählt gleich), nicht als
  Makro-Mittel über Kategorien.

## Entscheidung

**1. Die Metrikdefinitionen sind Teil des Messvertrags und werden hier festgehalten**, nicht nur im
Code. Binäre Relevanz; nDCG mit Gain 1 und Diskont `1/log2(rang+1)`; ideales DCG über
`min(|erwartete Dokumente|, k)`; Rangzählung 1-basiert; MRR als Kehrwert des Rangs des ersten
relevanten Treffers innerhalb des Suchfensters, sonst 0.

**2. Die Fenstergrößen sind bewusst ungleich und bleiben es:** Hit Rate@5 bildet ab, was ein Nutzer
bei produktivem `top-k=5` sieht; nDCG@10 und Recall@10 brauchen ein weiteres Fenster, um
Rangunterschiede und Mengenabdeckung überhaupt sichtbar zu machen. MRR wird als MRR@10 geführt.

**3. Gemessen wird ohne Ähnlichkeitsschwelle und mit `topK=10`.** Ranking-Metriken brauchen die
vollständige, ungefilterte Reihenfolge. Die produktive Schwelle wird im Report nur informativ
ausgewiesen. Daraus folgt ausdrücklich: Der Harness misst die Rangfolge des Retrievals, nicht die
Trefferliste, die ein Nutzer erhält.

**4. Recall@k wird nach der Standarddefinition berechnet** (`Treffer in top-k / |erwartete
Dokumente|`) und **nicht** auf `min(k, |erwartete Dokumente|)` normiert. Fälle mit mehr als `k`
erwarteten Dokumenten sind damit nach oben begrenzt. Weil das den Mittelwert still verzerrt, weist
der Report je Gruppe zusätzlich die **erreichbare Obergrenze** aus (`recallAt10Ceiling` in
`MetricsAggregate`, siehe #292-Review).

**5. Aggregiert wird als Mikro-Mittel über Anfragen.** Kategoriegrößen sind ungleich (16 bis 34);
der Gesamtwert ist deshalb nicht das Mittel der Kategoriewerte.

**6. Jede Änderung an den Punkten 1 bis 5 macht bestehende Baselines ungültig** und erfordert einen
bewussten neuen Baseline-Lauf — denselben Charakter wie eine Korpus- oder Modelländerung
(ADR-0011, Konsequenzen). Der Report führt dafür eine Versionsnummer des Messvertrags mit
(`measurementContractVersion`, `EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION`); ein
`git blame`/`git log` auf diese Konstante zeigt, welcher Commit den Messvertrag zuletzt geändert
hat.

**7. Der Report führt die Ergebnisse jeder einzelnen Anfrage**, nicht nur der zehn schlechtesten
(`allQueryResults` in `EvaluationReport`). Damit sind Kreuzauswertungen (etwa Sprache ×
Schwierigkeit) aus dem Report selbst möglich, ohne den Harness erneut laufen zu lassen — siehe die
in "Offen" der Entwurfsfassung dieses ADRs aufgeworfene Frage, unten als entschieden übernommen.

## Konsequenzen

**Einfacher:**

- Eine Baseline aus #228 lässt sich jederzeit darauf prüfen, unter welchem Messvertrag sie entstand.
- Die Obergrenze von Recall@k wird sichtbar, statt als vermeintlich schlechter Wert gelesen zu werden.
- Die Metrikdefinitionen sind reviewbar, ohne den Harness-Code zu lesen.
- Kreuzauswertungen wie der Sprachvergleich lassen sich direkt aus dem JSON-Report bilden, ohne
  Docker/Ollama erneut anzuwerfen.

**Schwieriger:**

- Eine weitere Stelle, die bei Änderungen mitgepflegt werden muss.
- Die Festlegung auf ungleiche Fenster (5 bzw. 10) muss bei jeder Ergebnisdarstellung mitgedacht
  werden; eine Tabelle mit vier Spalten suggeriert sonst Vergleichbarkeit, die nicht besteht.
- Der JSON-Report wächst um die Ergebnisse aller Anfragen (bei 121 Fällen niedrige zweistellige
  KB-Größenordnung) — für dieses Korpus vernachlässigbar, bei künftigen, deutlich größeren
  Golden-Datasets (#234) erneut zu bewerten.
