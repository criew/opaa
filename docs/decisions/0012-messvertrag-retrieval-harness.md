# ADR-0012: Messvertrag des Retrieval-Harness

## Status

Akzeptiert — ergänzt um den [Nachtrag vom 2026-08-21](#nachtrag-dokumentbezogenes-k-fenster-und-chunkebene-issue-721)
(Maintainer-Entscheidung zu Issue #721/#234: Messvertrag-Version 2, dokumentbezogenes k-Fenster,
zweite Metrikfamilie auf Chunkebene; die Entscheidungen 1–7 unten bleiben unverändert in Kraft)
sowie um den [Nachtrag vom 2026-08-31](#nachtrag-pipeline-messpfad-issue-1039) (Issue #1039: ein
zweiter Messpfad durch die produktive Query-Pipeline mit **eigenem**, getrennt gezähltem
Messvertrag; der hier festgehaltene Vertrag beschreibt weiterhin ausschließlich den
Rohvektor-Pfad und bleibt bei Version 2).
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

**Präzisierung (Issue #721 code review, Wichtig 3): Stabil ist der Text, nicht seine
Ein-Chunk-Auffindbarkeit.** Der Absatz oben stellt fest, dass der *Text* eines `answer_span`
unter `chunk-size`/`chunk-overlap` stabil bleibt — das bleibt richtig. Nicht garantiert ist,
dass dieser Text nach einer Chunking-Parameteränderung noch **in irgendeinem** zurückgegebenen
Chunk vollständig enthalten ist: Eine Verkleinerung von `chunk-size` kann einen Span, der vorher
mittig in einem Chunk lag, über eine neue Chunk-Grenze schieben, sodass er in keinem einzelnen
Chunk mehr vollständig auftaucht. Das ist numerisch **identisch** zu einem echten
Retrieval-Fehlschlag (`spanChunkRank=-1` in beiden Fällen) — für `boundary_span`-Fälle (#234, bewusst
nah an einer Chunk-Grenze kuratiert) ist genau das der Fall, den ein Vergleich zweier
Chunking-Konfigurationen messen soll, nicht ein Messfehler.

Deshalb prüft der Harness nach dem Bau der Chunk-Map (`io.opaa.eval.ChunkMap`) zusätzlich, ob
jeder anwendbare `answer_span` in **mindestens einem** Chunk **mindestens eines** seiner
`expected_documents` auflösbar ist (`EvaluationReport.AnswerSpanResolutionResult`,
`RetrievalEvaluationHarnessTest`). Ein nicht auflösbarer Span bei einer Domäne, die
`answer_span`-Fälle führt, ist ein harter Abbruch — analog zur Chunk-Zahl-Invariante ein
Messvoraussetzungsfehler, kein Toleranzfall: Andernfalls wäre nicht unterscheidbar, ob eine
gemessene Verschlechterung eine echte Retrieval-Regression ist oder nur eine kaputte
Golden-Dataset-Fixtur (Tippfehler, ein von `SpanMatcher` nicht abgefangener
Whitespace-Unterschied) bzw. eine Chunking-Änderung, die den Span über eine Grenze geschoben hat.
`SpanMatcher` mildert das Whitespace-Problem (Zeilenumbrüche/mehrfache Leerzeichen werden vor dem
Vergleich kollabiert), löst aber nicht das Grenzproblem — das ist beabsichtigt: Der Abbruch macht
eine echte Grenzverschiebung sichtbar, statt sie still falsch zu messen.

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

---

## Nachtrag: Pipeline-Messpfad (Issue #1039)

> **Nachtrag vom 2026-08-31, Umsetzung von `docs/features/retrieval-benchmark.md`, Abschnitt 1
> („Messpfad durch die produktive Pipeline"), Issue #1039.** Dieser Abschnitt schreibt den
> Messvertrag fort, statt ihn umzuschreiben: Die Entscheidungen 1–10 oben gelten unverändert und
> beschreiben ab hier ausdrücklich den **Rohvektor-Pfad**.
>
> **Herkunft der Abweichung (Entscheidung 16):** Die eigene, getrennt gezählte
> `PIPELINE_MEASUREMENT_CONTRACT_VERSION` weicht von `retrieval-benchmark.md` §1, Konsequenz 2 ab,
> die eine Erhöhung der bestehenden `measurementContractVersion` vorsieht. Freigegeben vom
> Koordinator unter delegierter Maintainer-Autorität am 2026-08-31; Begründung in Entscheidung 16.
> Die Freigabe wird dem Maintainer im Abschlussbericht des Epics gemeldet.

### 11. Zwei Messpfade, getrennt ausgewiesen

Der Harness misst ab #1039 zweimal auf demselben, einmal indizierten und manifest-geprüften Korpus:

| | Rohvektor-Pfad | Pipeline-Pfad |
|---|---|---|
| Gemessen wird | `VectorStore#similaritySearch` direkt | `QueryService#retrieveRelevantChunksInGivenScope`, also Schritte 2–6 aus `retrieval-algorithm.md` |
| Ähnlichkeitsschwelle | ausgewiesen, **nicht** angewandt (Entscheidung 3) | **angewandt**, nicht nur ausgewiesen |
| Fenster | `documentTopK = 10` | `top-k = 8` (die tatsächliche Trefferzahl der Produktion) |
| Metriken | Hit Rate@5, MRR@10, nDCG@10, Recall@10 | Hit Rate@5, MRR@8, nDCG@8, Recall@8 |
| Report | `retrieval-metrics[-<domäne>].json` | `pipeline-metrics-<domäne>.json` |
| Vertragsversion | `EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION` | `PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION` |

Der Rohvektor-Pfad wird **nicht ersetzt**. Er misst die Qualität der Vektorsuche selbst,
unvermischt mit allem, was danach umsortiert — der aussagekräftigere Pfad für Vergleiche von
Einbettungsmodellen und Chunking-Varianten. Der Pipeline-Pfad ist der einzige, der Aussagen über
die Nutzererfahrung erlaubt. Beide gehören in den Bericht, nebeneinander und getrennt.

### 12. Die Metriken tragen ihr Fenster an jeder Zahl

Die beiden Pfade sind **nicht ineinander umrechenbar**. Weil die Schwelle im Pipeline-Pfad
tatsächlich greift, kann ein Dokument dort ganz aus der Rangliste verschwinden statt nur
zurückzufallen; Recall-Werte liegen systematisch niedriger. Das ist kein Fehler, sondern die
gemessene Realität — und ein nDCG@8 neben einem nDCG@10 in einer Tabelle ohne Kennzeichnung ist
ein Auswertungsfehler.

Deshalb ist die Fensterangabe im Pipeline-Pfad nicht Konvention, sondern Schema: Die
Report-Felder heißen `hitRateAt5`, `mrrAt8`, `ndcgAt8`, `recallAt8`, `recallAt8Ceiling`,
`hitCountAt8`, `allExpectedDocumentsHitAt8` (`io.opaa.eval.PipelineMetricsAggregate`), jede
Textausgabe beschriftet dieselben Zahlen genauso, und jeder Report führt zusätzlich einen
`metricWindowNote`. `PipelineMetricsAggregate.of` weist Ergebnisse zurück, die an einem anderen
Fenster gemessen wurden, statt sie unter einem Feldnamen abzulegen, der sie falsch beschreibt.

### 13. Fixpunkte des Pipeline-Pfads

Zusätzlich zu den gemeinsamen Fixpunkten (Einbettungsmodell samt Digest, Dimensionen, `chunk-size`,
`chunk-overlap`, `pgvectorIndexType`, Korpus-Manifest, Golden Dataset) sind für den Pipeline-Pfad
festgeschrieben: `fetch-k`, `top-k`, `similarity-threshold`, `max-chunks-per-document`,
`mmr-lambda`, `query-decomposition-enabled`, `max-sub-queries` und — bei aktiver Zerlegung — das
verwendete Chat-Modell. Alle werden aus der laufenden Anwendungskonfiguration gelesen, nicht im
Harness gesetzt; Ausnahme ist `query-decomposition-enabled` (siehe 15.).

### 14. Der Suchbereich ist fest und vollständig; Rechtefilterung ist nicht Messgegenstand

Schritt 1 der Pipeline (Scope-Bestimmung) wird nicht mitgemessen. Der Harness übergibt einen festen
Suchbereich, der genau die eine Eval-Bibliothek mit dem gesamten Korpus umfasst.
`QueryService#retrieveRelevantChunksInGivenScope` wendet ihn als denselben `library_id`-Filter innerhalb des
`similaritySearch`-Aufrufs an, den eine echte Anfrage verwendet — er löst nur keine Berechtigungen
selbst auf. Rechtedurchsetzung ist über die Backend-Integrationstests abgedeckt; sie hier
mitzumessen würde die Metriken um einen Faktor verschieben, der mit Suchqualität nichts zu tun hat.

### 15. Teilfragen-Zerlegung: vorerst abgeschaltet, nie stillschweigend degradiert

Der Harness-Kontext hat kein aktives Chat-Modell. Bliebe die Zerlegung eingeschaltet, würde
`QueryDecompositionService#decompose` je Anfrage fehlschlagen und auf Einzelanfragen-Retrieval
zurückfallen — ein Lauf, der „mit Zerlegung" ausweist, was ohne Zerlegung gemessen wurde. Der
Harness misst deshalb die Variante `decomposition-off` (`query-decomposition-enabled = false`,
im Report als Fixpunkt geführt, `chatModel = null`) und **bricht ab**, wenn er mit eingeschalteter
Zerlegung ohne Modell laufen soll (`PipelineHarnessSupport`). Welches Chat-Modell der Pipeline-Pfad
mit aktiver Zerlegung verwenden soll, ist eine offene Entscheidung
(`docs/features/retrieval-benchmark.md`, „Offene Punkte" 3).

### 16. Eigene Vertragsversion statt Erhöhung der bestehenden

`docs/features/retrieval-benchmark.md` schlägt vor, die Fortschreibung „mit erhöhter
`measurementContractVersion`" vorzunehmen. Umgesetzt ist stattdessen eine **eigene**, bei 1
beginnende Zählung für den Pipeline-Pfad. Begründung:

- Der Rohvektor-Vertrag (Entscheidungen 1–10) ändert sich durch diese Erweiterung an keiner Stelle
  — weder Gain-Funktion noch IDCG-Basis, Fenster, Schwellenbehandlung oder Mittelungsart.
- `measurementContractVersion` ist ein Gültigkeitsfeld von `BaselineComparator`. Eine Erhöhung
  würde jede committete Rohvektor-Baseline ungültig machen und einen mehrstündigen
  Neuziehungs-Lauf erzwingen — für eine Messung, deren Definitionen und Zahlen sich nicht bewegt
  haben. Genau das verbietet Abnahmekriterium 4 von #1039 dem Sinn nach.
- Zwei Verträge, die verschiedene Dinge beschreiben, unter einer Nummer zu führen, hieße, jede
  künftige Änderung an einem Pfad als Änderung des anderen auszuweisen.

Entscheidung 6 gilt für beide Zählungen unverändert: Jede Änderung an den Festlegungen eines Pfads
erhöht dessen Versionsnummer und macht dessen Baselines ungültig.

### Was dieser Nachtrag noch nicht festlegt

Baselines und Toleranzen des Pipeline-Pfads. #1039 liefert den Messpfad und seinen Report; die
getrennten Baseline-Dateien je Pfad und Domäne, ihre Aufnahme in `BaselineComparator` und in den
nächtlichen Job sind Gegenstand der Folgearbeit desselben Epics (Umsetzungsschnitt A/E in
`docs/features/retrieval-benchmark.md`). Bis dahin ist der Pipeline-Report ein Beobachtungs-,
kein Wächterartefakt — er läuft entsprechend abgesichert und lässt den Harness-Lauf grün, wenn er
selbst scheitert, damit ein Fehler in der Beobachtung nie das Urteil des Rohvektor-Pfads verhindert.

**Ebenfalls offen: die Durchsetzung der Fixpunkte aus Entscheidung 13.** Der Harness prüft heute
nur zwei davon aktiv (`top-k`, weil die Metriknamen dieses Fenster wörtlich führen, und
`query-decomposition-enabled`, weil eine stille Degradierung sonst als „mit Zerlegung" gemessen
würde). Die übrigen — `fetch-k`, `similarity-threshold`, `max-chunks-per-document`, `mmr-lambda`,
`max-sub-queries` — werden ausgewiesen, aber nicht geprüft: Sie stehen im Report, und eine Änderung
ist dort nachlesbar, führt aber zu keinem Abbruch. Eine `mmr-lambda`-Änderung könnte damit heute
unbemerkt in einen Pipeline-Report einfließen. Das ist bis zur Baseline folgenlos, weil es nichts
gibt, wogegen verglichen würde; **mit der ersten Pipeline-Baseline muss die Prüfung dieser Werte
Teil der Gültigkeitsprüfung werden** (dieselbe Rolle, die `Baseline.FixedPoints` für den
Rohvektor-Pfad spielt). Gehört zur Baseline-Folgearbeit, nicht zu #1039.
