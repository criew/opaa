# ADR-0012: Messvertrag des Retrieval-Harness

## Status

Akzeptiert — ergänzt um den [Nachtrag vom 2026-08-21](#nachtrag-dokumentbezogenes-k-fenster-und-chunkebene-issue-721)
(Maintainer-Entscheidung zu Issue #721/#234: Messvertrag-Version 2, dokumentbezogenes k-Fenster,
zweite Metrikfamilie auf Chunkebene; die Entscheidungen 1–7 unten bleiben unverändert in Kraft).
Ursprünglich Entwurf des Code Reviewers zu PR #292 (Issue #227), übernommen und in der
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

---

## Nachtrag: Dokumentbezogenes k-Fenster und Chunkebene (Issue #721)

> **Nachtrag vom 2026-08-21, Maintainer-Entscheidung zu Issue #721/#234 (akzeptiert).** Dieser ADR
> ist bereits akzeptiert; dieser Abschnitt schreibt den Messvertrag fort, statt ihn stillschweigend
> umzuschreiben — die Entscheidungen 1–7 oben gelten unverändert für die Ranking-Metrikfamilie auf
> Dokumentebene.

### Warum eine Fortschreibung nötig ist

Entscheidung 3 oben legt `topK=10` fest, ohne zwischen „Chunk" und „Dokument" zu unterscheiden — für
`comic-characters` (Ein-Chunk-Invariante, ADR-0010) ist das dieselbe Zahl, weil dort jeder Chunk ein
eigenes Dokument ist. Diese Vieldeutigkeit war folgenlos, solange nur einchunkige Domänen existierten.
Issue #721 macht sie explizit, weil sie es für eine mehrchunkige Domäne (#234) nicht mehr sein darf:
Zehn Chunks können nach Deduplizierung nur drei oder vier unterschiedliche Dokumente sein, und ohne
diese Fortschreibung würde nDCG@10/Recall@10 dann faktisch über ein Fenster von drei bis vier
Dokumenten gemessen — unbemerkt, weil `dedupeByFileName` (die schon vor #721 bestehende
Chunk-zu-Dokument-Aggregation im Harness) das Fenster nie korrigierte.

### 8. Das k-Fenster ist ausdrücklich dokumentbezogen

Entscheidung 3 wird präzisiert, nicht ersetzt: „`topK=10`" heißt ab Messvertrag-Version 2
„`documentTopK=10` unterschiedliche Dokumente", nicht „zehn Treffer der Rohsuche". Der Harness sucht
mit einem separat geführten `chunkTopK`, das deterministisch groß genug gewählt wird, damit die
Deduplizierung `documentTopK` unterschiedliche Dokumente erreichen kann:
`chunkTopK = documentTopK · maxChunksPerDocument`, wobei `maxChunksPerDocument` eine je Domäne
deklarierte Obergrenze ist (`io.opaa.eval.EvalDomainConfig`), keine zur Laufzeit ermittelte Schätzung
— siehe `io.opaa.eval.DocumentRanking`. Für `comic-characters` ist `maxChunksPerDocument = 1`, also
`chunkTopK == documentTopK == 10` — bitgleich zum Verhalten vor diesem Nachtrag.

Ein Lauf, der `documentTopK` nicht erreicht (weil der Korpus selbst weniger Dokumente als
`documentTopK` enthält oder `chunkTopK` nicht ausreicht), meldet das über
`DocumentRanking.DocumentWindowResult#reachedDocumentTopK()` ausdrücklich, statt still ein kleineres
Fenster zu messen.

### 9. Zweite Metrikfamilie: Chunkebene über `answer_span`

Zusätzlich zur (weiterhin primären, für Baseline und CI maßgeblichen) Ranking-Metrikfamilie auf
Dokumentebene gibt es eine zweite, unabhängige Familie auf Chunkebene
(`io.opaa.eval.ChunkAnswerSpanMetrics`): `answerSpanHitRate@5` und der Rang des ersten Chunks, der
einen eingefrorenen, wörtlichen Textausschnitt (`GoldenCase#answerSpan()`, optionales
Golden-Dataset-Feld `answer_span`) enthält. Bewusst ein Textausschnitt, keine Chunk-Index-Ground-Truth
— ein Chunk-Index wird bei jeder Änderung von `chunk-size`/`chunk-overlap` lautlos falsch, ein
wörtlicher Ausschnitt bleibt unter beiden Parametern stabil und macht dadurch den Vergleich
verschiedener Chunking-Konfigurationen (#374) erst möglich. Für eine Domäne, deren Golden Cases kein
`answer_span` führen (`comic-characters`: die Ein-Chunk-Invariante macht eine Chunk-Ebene dort
bedeutungslos), liefert die Aggregation `ChunkAnswerSpanMetrics.Aggregate.NOT_APPLICABLE`
(`applicableCases=0`) statt eines gemessenen, aber bedeutungslosen Werts.

### 10. Messvertrag-Version 2

`EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION` wird von 1 auf 2 erhöht (Entscheidung 6 oben:
jede Änderung an den Messvertrag-Festlegungen macht bestehende Baselines ungültig und erfordert einen
bewussten neuen Baseline-Lauf). `chunkOverlap`, `documentTopK` und `chunkTopK` werden zusätzliche
Gültigkeitsfelder (`Baseline.FixedPoints`) — `chunkOverlap` existierte vor #721 nur als
Report-Metadatum (informativ, weil es bei einer Ein-Chunk-Domäne nichts verändern kann), ist aber für
eine mehrchunkige Domäne ein Wert, der das Messergebnis unmittelbar bestimmt.

### Erwartung an die comic-characters-Baseline

Für eine Domäne mit `maxChunksPerDocument = 1` fallen `chunkTopK` und `documentTopK` zusammen, und die
Chunkebene liefert `NOT_APPLICABLE` — die neue Fassung des Harnesses berechnet für `comic-characters`
dieselbe Rangliste wie die alte. **Erwartung: bitgleiche Zahlen** gegenüber der unter
Messvertrag-Version 1 gemessenen Baseline; die PR-Beschreibung zu Issue #721 enthält den
Vorher/Nachher-Vergleich als Beleg.
