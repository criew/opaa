# ADR-0012: Messvertrag des Retrieval-Harness

## Status

Akzeptiert — ergänzt um den [Nachtrag vom 2026-08-21](#nachtrag-dokumentbezogenes-k-fenster-und-chunkebene-issue-721)
(Maintainer-Entscheidung zu Issue #721/#234: Messvertrag-Version 2, dokumentbezogenes k-Fenster,
zweite Metrikfamilie auf Chunkebene; die Entscheidungen 1–7 unten bleiben unverändert in Kraft)
sowie um den [Nachtrag vom 2026-08-31](#nachtrag-pipeline-messpfad-issue-1039) (Issue #1039: ein
zweiter Messpfad durch die produktive Query-Pipeline mit **eigenem**, getrennt gezähltem
Messvertrag; der hier festgehaltene Vertrag beschreibt weiterhin ausschließlich den
Rohvektor-Pfad und bleibt bei Version 2) und den
[Nachtrag zu den Pipeline-Baselines](#nachtrag-baselines-des-pipeline-pfads-issue-1040)
(Issue #1040: getrennte Baseline-Dateien je Pfad und Domäne, durchgesetzte Fixpunkte des
Pipeline-Pfads, Pipeline-Messvertrag-Version 2 — die Rohvektor-Version bleibt auch dadurch
unberührt bei 2) und den [Nachtrag zum Volltextpfad](#nachtrag-volltextpfad-in-der-fusion-issue-1049)
(Issue #1049: der lexikalische Suchpfad wird Eingangsliste der Fusion und bewegt damit erstmals die
Endauswahl — zwei neue Fixpunkte des Pipeline-Pfads, Pipeline-Messvertrag-Version 3, neu gezogene
Pipeline-Baselines aller drei Domänen; die Rohvektor-Version bleibt bei 2, weil dieser Pfad den
Volltextpfad konstruktionsbedingt nicht sieht) und den
[Nachtrag zum strukturbewussten Markdown-Chunking](#nachtrag-strukturbewusstes-markdown-chunking-issue-1103)
(Issue #1103: Markdown wird über `MarkdownDocumentPipeline` statt `TikaFallbackPipeline`
gechunkt — kein Messvertragspunkt 1–10 ändert sich, aber alle drei Eval-Domänen brauchen neue
Baselines auf beiden Pfaden, weil sich Chunkinhalt und -zahl der ausschließlich-Markdown-Korpora
ändern) und den
[Nachtrag zum Ingestion-Pipeline-Fixpunkt](#nachtrag-ingestion-pipeline-fixpunkt-issue-1144)
(Issue #1144: `ingestionPipelineFingerprint` — ein Sammelabdruck über alle registrierten
Ingestion-Pipelines — wird Fixpunkt auf **beiden** Pfaden, weil beide Chunks derselben Pipelines
messen; Rohvektor-Messvertrag-Version 3, Pipeline-Messvertrag-Version 4, reine
Fixpunkt-Ergänzung ohne neuen Messlauf für alle drei Baselines).
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

> **Erledigt mit Issue #1040** — beide hier offen gelassenen Punkte sind im
> [Nachtrag zu den Pipeline-Baselines](#nachtrag-baselines-des-pipeline-pfads-issue-1040)
> entschieden. Der folgende Abschnitt bleibt als Zustandsbeschreibung von #1039 stehen.

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

---

## Nachtrag: Baselines des Pipeline-Pfads (Issue #1040)

> **Nachtrag vom 2026-08-31, Umsetzung von `docs/features/retrieval-benchmark.md`, Abschnitt 1
> („Folgen für Messvertrag und Baselines"), Issue #1040.** Fortschreibung, keine Umschreibung: Die
> Entscheidungen 1–10 (Rohvektor-Pfad) und 11–16 (Pipeline-Messpfad) gelten unverändert. Was hier
> hinzukommt, betrifft ausschließlich den Pipeline-Pfad — **die committeten Rohvektor-Baselines aus
> #228/#234 bleiben unangetastet und gültig**, ihre `measurementContractVersion` bleibt bei 2.
>
> **Herkunft der Abweichungen:** Dieser Nachtrag weicht in zwei Punkten von
> `retrieval-benchmark.md` §1 ab. Erstens erhöht er — wie schon der Nachtrag zu #1039, siehe dort
> Entscheidung 16 — die **eigene** Vertragsversion des Pipeline-Pfads statt der
> `measurementContractVersion`, die die Spezifikation nennt. Zweitens sagt die Spezifikation das
> „unveränderte Fehlerkriterium aus ADR-0013" zu, während Entscheidung 19 unten für den
> Pipeline-Pfad **eigene feste Anker der harten Untergrenze** festlegt (Formel, Toleranz und
> fallzahlbasierte Prüfung bleiben wörtlich unverändert; nur die absoluten Anker sind
> pfadspezifisch). Beides freigegeben vom Koordinator unter delegierter Maintainer-Autorität am
> 2026-08-31; die Freigabe wird dem Maintainer im Abschlussbericht des Epics gemeldet. ADR-0013
> trägt die entsprechende Verweiszeile in seinem Kopf.

### 17. Eine Baseline-Datei je Pfad und Domäne

Die beiden Pfade messen unterschiedliche Dinge und sind nicht ineinander umrechenbar
(Entscheidung 12). Eine gemeinsame Baseline-Datei wäre deshalb nicht nur unhandlich, sondern
gefährlich: Sie machte es möglich, eine Pipeline-Neuziehung über die Zahlen des Rohvektor-Pfads zu
schreiben.

- Ablage flach unter `eval/baseline/`, Rohvektor-Baseline `<domäne>.json`, Pipeline-Baseline
  **`pipeline-<domäne>.json`**. Der Name wird in `EvalDomainConfig` aus dem Rohvektor-Namen mit
  festem Präfix abgeleitet, nicht je Domäne eigenständig deklariert — eine Namenskollision ist damit
  nicht per Tippfehler erreichbar (`PipelinePathIsolationTest`).
- Eigener Typ (`PipelineBaseline`) mit eigenen Fixpunkten und den Gruppenwerten am @8-Fenster
  (`PipelineMetricsAggregate`), eigener Vergleicher (`PipelineBaselineComparator`), eigene
  Delta-Tabelle. Kein gemeinsames Schema, in das eine @10-Zahl unter einem @8-Feldnamen geraten
  könnte.
- Flach statt in einem Unterverzeichnis, damit der Absenkungsvergleich gegenüber `main`
  (`eval/baseline/diff_baseline.py`, `.github/workflows/baseline-diff.yml`, Entscheidung 6 in
  ADR-0013) die Pipeline-Baselines ohne jede Pfad-Fallunterscheidung mitnimmt — er iteriert über
  `eval/baseline/*.json`.

### 18. Die Fixpunkte aus Entscheidung 13 werden durchgesetzt; Pipeline-Messvertrag-Version 2

Der Nachtrag zu #1039 ließ `fetch-k`, `similarity-threshold`, `max-chunks-per-document`,
`mmr-lambda` und `max-sub-queries` ausgewiesen, aber ungeprüft — ausdrücklich nur deshalb, weil es
nichts gab, wogegen verglichen wurde. Mit der ersten committeten Pipeline-Baseline endet dieser
Grund: Eine unbemerkte `mmr-lambda`-Änderung würde sonst still verändern, was die committeten Zahlen
beschreiben, und der Vergleich meldete „Regression", wo die Messgrundlage gewechselt hat.

Alle Fixpunkte aus Entscheidung 13 sind deshalb Gültigkeitsfelder von `PipelineBaseline.FixedPoints`
und werden gegen die Laufkonfiguration geprüft: `fetch-k`, `top-k`, `similarity-threshold`,
`max-chunks-per-document`, `mmr-lambda`, `query-decomposition-enabled`, `max-sub-queries`, das
Chat-Modell (heute `null`, siehe 20.) sowie die beiden Fenster `hitRateK`/`rankingK` — dazu die
gemeinsamen Fixpunkte (Einbettungsmodell samt Digest und Dimensionen, `chunk-size`, `chunk-overlap`,
`pgvectorIndexType`, Korpus-Manifest, Golden Dataset). Weicht eines ab, ist die **Pipeline**-Baseline
ungültig und es wird auf diesem Pfad keine Metrik verglichen; die Rohvektor-Baseline ist davon nicht
berührt.

Das erweitert, was „dieselbe Messung" heißt, und ist damit nach Entscheidung 6 eine
Vertragsänderung: `PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION` steigt von 1 auf
**2**. Die Erhöhung kostet nichts, weil unter Version 1 keine Pipeline-Baseline existierte — genau
deshalb ist sie jetzt und nicht später fällig. Die Rohvektor-Zählung bleibt unberührt; das ist der
Zweck der getrennten Zählung aus Entscheidung 16.

### 19. Getrenntes Urteil je Pfad, unverändertes Fehlerkriterium

Der nächtliche Job prüft beide Pfade, jeden gegen seine eigene Baseline, mit dem **unveränderten**
Fehlerkriterium aus ADR-0013: dieselbe Toleranzformel, dieselbe fallzahlbasierte Konjunktion aus
#306, dieselbe Kombination aus baseline-relativer und absoluter harter Untergrenze. Das ist
strukturell abgesichert und nicht nur behauptet: Beide Vergleicher erzeugen ihre Prüfungen über
dieselbe Methode (`BaselineComparator.metricCheck`), sodass die beiden Implementierungen nicht
auseinanderlaufen können.

**Zwei Urteile, nicht eines.** Rohvektor- und Pipeline-Vergleich sind zwei JUnit-Testklassen im
selben Gradle-Task. JUnit führt beide unabhängig vom Ausgang der jeweils anderen aus, sodass jeder
Pfad seine eigene Delta-Tabelle und sein eigenes Ja/Nein liefert. Das löst die in #1039 offene Frage,
wie ein Pipeline-Fehler künftig gatet, ohne das Rohvektor-Urteil zu verhindern:

- `PipelineHarnessSupport.runAndWriteGuarded` fängt einen Fehler des Pipeline-Pfads weiterhin ab,
  damit der Messlauf selbst grün bleibt und der Baseline-Vergleich des Rohvektor-Pfads überhaupt
  stattfindet. Der Guard verbirgt den Fehler vor dem *Messlauf*, nicht vor dem *Urteil*.
- Ein fehlender Pipeline-Report lässt `PipelineBaselineRegressionTest` fehlschlagen. Ein
  Pipeline-Fehler ist damit genau einmal rot — unter dem Urteil des Pipeline-Pfads.
- Das Alarm-Issue des nächtlichen Laufs trägt beide Delta-Tabellen mit eigener Überschrift und sagt
  ausdrücklich, dass der jeweils andere Pfad im selben Lauf grün sein kann. Die frühere,
  pauschale „Retrieval-Regression"-Begründung wäre mit zwei Urteilen irreführend gewesen.

**Eine Domäne ohne gezogene Pipeline-Baseline wird nicht gegated, sondern sichtbar ausgelassen.**
`city-landmarks` hatte seine Pipeline-Baseline zunächst nicht; ihr Pipeline-Vergleich war deshalb im
Gradle-Task ausdrücklich nicht eingehängt (`pipelineBaselineTestClass = null` mit Begründung), statt
gegen eine nicht existierende Datei zu laufen. Das ist die Alternative zu einem stillen Sonderfall im
Vergleichscode („keine Baseline → bestanden"), der genau die Lücke erzeugte, die dieser Nachtrag
schließen soll: eine Prüfung, die aussieht, als fände sie statt. Issue #1081 zog diese Baseline aus
dem CPU-Artefakt eines erfolgreichen, label-ausgelösten Regressionslaufs (Run 33437536393, Branch
von PR #1084) und hängte `CityLandmarksPipelineBaselineRegressionTest` ein — der beschriebene
ausgelassene Zustand war damit ein Übergangszustand, kein Dauerzustand, und betrifft heute keine der
drei Domänen mehr.

**Eigene absolute Anker der harten Untergrenze.** Die *Formel* ist unverändert
(`max(0,8 · Baselinewert, feste Untergrenze)`); die festen Untergrenzen des Pipeline-Pfads sind
eigene Werte: Hit Rate@5 ≥ 0,15, MRR@8/nDCG@8/Recall@8 ≥ 0,125 — die Hälfte der ADR-0013-Werte. Die
ADR-0013-Anker sind an @10-Messungen ohne Ähnlichkeitsschwelle kalibriert; der Pipeline-Pfad misst
am engeren Fenster und mit angewandter Schwelle und liegt aus Gründen niedriger, die mit
Retrieval-Qualität nichts zu tun haben (Entscheidung 12). Die Werte sind **vor** der ersten Messung
festgelegt worden, damit die Untergrenze nicht nachträglich am Ergebnis entlang gewählt wird, und
sie behalten ihre Rolle: ein zweites, baselineunabhängiges Netz gegen katastrophales Versagen (leerer
oder falsch konfigurierter Vektor-Store), kein Qualitätsziel. Sobald mehrere Pipeline-Baselines über
mehrere Domänen vorliegen, sind sie erneut zu bewerten.

### 20. Das Chat-Modell bleibt ein Fixpunkt mit dem Wert „keines"

Entscheidung 15 gilt unverändert: Der Harness misst die Variante `decomposition-off`. Die Baseline
führt `queryDecompositionEnabled = false`, `maxSubQueries` und `chatModel = null` als geprüfte
Fixpunkte — ein Modellname auf einer der beiden Seiten allein bedeutet, dass die beiden Läufe nicht
dasselbe gemessen haben, und macht die Baseline ungültig. Welches Chat-Modell der Pipeline-Pfad mit
aktiver Zerlegung verwenden soll, bleibt offen
(`docs/features/retrieval-benchmark.md`, „Offene Punkte" 3); die Entscheidung wird die
Pipeline-Vertragsversion erneut erhöhen und eine Neuziehung der Pipeline-Baselines erfordern — dann
zusammen mit der Mehrfachlauf-Regel aus Abschnitt 3 derselben Spezifikation.

---

## Nachtrag: `answer_span` bei mehreren Zieldokumenten (Issue #1043)

`docs/features/retrieval-benchmark.md` führte als offenen Punkt 4, ob die mit Entscheidung 9
eingeführte Chunkebenen-Metrik bei Fällen mit mehreren Zieldokumenten **je Dokument** oder **je
Fall** gebildet wird. Mit der ersten Domäne, die solche Fälle in Serie führt (`verwaltung`,
Fallklassen `multi_hop` und `compound_word`), ist die Frage zu entscheiden.

### 21. Ein `answer_span` je Fall, und nur bei genau einem erwarteten Dokument

`answer_span` bleibt genau ein Feld je Golden-Fall. Ein Fall mit mehr als einem erwarteten Dokument
trägt **keinen** — nicht einen für eines der Dokumente, und keine Liste.

Begründung: Ein einzelner Span auf einem Fall, dessen Antwort über zwei Dokumente verteilt ist,
misst nachweislich eine Hälfte der Antwort und meldet das Ergebnis als das des ganzen Falls. Ein
`multi_hop`-Fall, den die Dokumentebene zu Recht als Fehlschlag führt (das zweite Belegdokument
fehlt), sähe auf Chunkebene erfolgreich aus, sobald der Chunk des ersten Dokuments zurückkam — die
beiden Metrikfamilien widersprächen sich, ohne dass eine von beiden falsch rechnet.

Die naheliegende Alternative — eine Span-Liste je Dokument mit einer Aggregation über die Dokumente
eines Falls — ist keine Erweiterung dieses Vertrags, sondern eine **neue Metrik**: Sie braucht eine
eigene Trefferdefinition („alle Spans gefunden" oder „mindestens einer"), eine eigene
Mittelungsebene und damit eine Erhöhung beider Vertragsversionen samt Neuziehung aller Baselines.
Sie wird zurückgestellt, weil kein heutiger Messgegenstand sie braucht: Die Chunkebene existiert
für Chunking-Vergleiche (Spezifikation, Abschnitt 2), und die tragen Einzeldokument-Fälle bereits
vollständig.

**Keine Vertragsversion wird erhöht.** Die Regel schreibt fest, was die bestehende Implementierung
ohnehin tut (ein Feld, ein Span, geprüft gegen die erwarteten Dokumente) und was
`city-landmarks` bereits praktiziert — `multi_city` und `multi_topic` tragen dort keinen
`answer_span`. Es ändert sich keine gemessene Größe, also auch keine Vergleichbarkeit
bestehender Baselines. Neu ist allein, dass die Regel **geprüft** wird statt Gewohnheit zu sein:
`io.opaa.eval.GoldenCaseCuration` lehnt einen `answer_span` auf einem mehrdokumentigen Fall ab,
`GoldenCaseCurationTest` wendet das Docker-frei auf jeden committeten Datensatz der betroffenen
Domäne an.

---

## Nachtrag: Volltextpfad in der Fusion (Issue #1049)

**Datum:** 2026-09-01 · **Betrifft:** ausschließlich den Pipeline-Messpfad · **Rohvektor-Vertrag:**
unverändert Version 2.

Mit [#1049](https://github.com/criew/opaa/issues/1049) geht der lexikalische Suchpfad als weitere
Eingangsliste in die Reciprocal Rank Fusion ein (docs/features/hybrid-retrieval.md, Arbeitspaket 3).
Das ist die erste Änderung dieses Epics, die die Endauswahl tatsächlich bewegt — und damit die erste,
die den Messvertrag berührt.

### 22. Zwei neue Fixpunkte des Pipeline-Pfads

`PipelineEvaluationReport.PipelineRunConfiguration` und `PipelineBaseline.FixedPoints` führen zwei
weitere Felder, beide als Gültigkeitsfelder nach Entscheidung 18:

- **`fullTextSearchEnabled`** — ob der lexikalische Pfad in diesem Lauf seine Listen in die Fusion
  eingebracht hat (`opaa.query.full-text-search-enabled`). Ohne dieses Feld trüge ein
  `vector-only`-Lauf denselben `runConfiguration`-Abdruck wie ein hybrider, und die Differenz
  zwischen beiden würde gegen die committete Baseline als Codeänderung verbucht — genau die
  Verwechslung, die Entscheidung 18 für die Query-Parameter ausschließt. Der Wert war bis #1049
  bewusst **kein** Fixpunkt (die Stufe lief protokollarisch, die Endauswahl war bit-identisch); die
  Auflage, ihn mit der Aufnahme in die Fusion nachzuziehen, stand seit dem Review zu #1048 in
  docs/features/hybrid-retrieval.md, Arbeitspaket 2.
- **`fullTextBackfillComplete`** — ob der Volltext-Backfill der gemessenen Bibliothek abgeschlossen
  war. Das Backfill-Tor (`FullTextBackfillGate`) hält eine unvollständig indizierte Bibliothek
  vollständig aus dem lexikalischen Pfad heraus; ein Lauf mit `fullTextSearchEnabled = true` über
  einem halb gefüllten Index misst deshalb die vector-only-Konfiguration, ohne es zu sagen. Erst
  beide Felder zusammen beantworten die Frage „hat der lexikalische Pfad in diesem Lauf beigetragen?".

Der Harness-Guard (`PipelineHarnessSupport#requireMeasurableConfiguration`) weist zusätzlich einen
Lauf mit `fullTextSearchEnabled = false` ab — nicht weil er nicht messbar wäre (das ist er, seit der
Fixpunkt existiert), sondern weil der Pfad, der die committete Baseline schreibt, die ausgelieferte
Konfiguration messen muss. Die vector-only-Messung ist ein benannter Variantenvergleich
(`eval/variants/*-lexical-path.json`), und ein Variantenbericht ist ein Artefakt, keine Baseline.
Ein Variantenlauf, der den lexikalischen Pfad über einem unvollständigen Backfill anfordert, wird
als „nicht ausgeführt" gemeldet statt stillschweigend degradiert (`VariantPrerequisites`) — dieselbe
Regel wie für die Teilfragen-Zerlegung ohne Chat-Modell (Entscheidung 15).

### 23. Pipeline-Messvertrag-Version 3, Rohvektor-Version unverändert 2

Zwei neue Gültigkeitsfelder erweitern, was „dieselbe Messung" heißt; nach Entscheidung 6 ist das eine
Vertragsänderung. `PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION` steigt von 2 auf
**3**, und die Pipeline-Baselines aller drei Domänen werden neu gezogen — nicht nur wegen der neuen
Felder, sondern weil die gemessenen Zahlen sich tatsächlich bewegen.

Die Rohvektor-Zählung bleibt bei 2, und das ist keine Nachlässigkeit, sondern die Aussage: Dieser Pfad
misst `similaritySearch` direkt und kennt den lexikalischen Pfad nicht. Weder seine Definitionen noch
seine Werte ändern sich durch #1049 — nachgewiesen im selben Lauf, in dem die Pipeline-Zahlen sich
verschoben haben, mit unveränderten Rohvektor-Werten in jeder Gruppe. Genau dafür existiert die
getrennte Zählung aus Entscheidung 16.

**Eine Folge für die Zustandsfelder** (Spezifikation, Abschnitt 5): Ein Fall gilt als `solved`, wenn
ihn **beide** Messpfade lösen. Von den zwölf Fällen, die der Pipeline-Pfad mit #1049 zusätzlich löst,
erfüllt genau einer diese Bedingung (`verw-comp-006`, den der Rohvektor-Pfad schon vorher löste) und
wechselt auf `solved`; die übrigen elf kann der Rohvektor-Pfad strukturell nicht lösen und bleiben
deshalb `known_gap` mit committeter `expected_state_exception`. Ob diese Definition mit einem
produktiven zweiten Suchpfad noch die richtige ist, ist eine offene Frage an die Spezifikation und
wird hier nicht entschieden.

---

## Nachtrag: Strukturbewusstes Markdown-Chunking (Issue #1103)

**Datum:** 2026-09-01 · **Betrifft:** beide Messpfade, alle drei Eval-Domänen · **Messvertragspunkte
1–10 (Rohvektor) und 11–23 (Pipeline-Pfad):** inhaltlich unverändert.

#1061 liefert strukturbewusstes, überschriftenbasiertes Chunking für PDF/DOCX/PPTX
(`HeadingSectionSplitter`) und bereitet `MarkdownDocumentPipeline` für Markdown vor, ohne sie zu
registrieren — der Eval-Korpus besteht ausschließlich aus Markdown, sodass die Registrierung eine
Messvertragsänderung ist, nicht eine beiläufige Formaterweiterung (siehe Kontext von #1103). Dieser
Nachtrag hält die Entscheidung fest, die die Registrierung nachträglich trifft.

### 24. Markdown wird ab sofort strukturbewusst gechunkt

`.md`-Dokumente durchlaufen ab #1103 `MarkdownDocumentPipeline` (Schnitt entlang ATX-Überschriften
Ebene 1–3, siehe deren Javadoc) statt `TikaFallbackPipeline`
(`DocumentService`/`ChunkingService`, größenbasierter Schnitt ohne Rücksicht auf Struktur). Das ändert
für jede der drei Eval-Domänen — die vollständig aus Markdown bestehen — sowohl die Chunkzahl je
Dokument als auch den Wortlaut jedes einzelnen Chunks, selbst dort, wo die Chunkzahl gleich bleibt
(`comic-characters`, siehe 25.).

### 25. Frontmatter wird nicht als Inhalt gechunkt

Alle drei Korpora beginnen jedes Dokument mit einem YAML-Frontmatter-Block (`---` … `---`) vor der
ersten Überschrift. `HeadingSectionSplitter` kennt kein Frontmatter von sich aus — ohne besondere
Behandlung würde der Block zu einem eigenen, überschriftslosen ersten Chunk, was bei
`comic-characters` deterministisch **jedes** der 1448 Dokumente auf zwei Chunks gebracht und damit die
Ein-Chunk-Invariante (ADR-0010) gebrochen hätte.

Entscheidung: Ein `---`-begrenzter Block **am Dateianfang**, vor jeder Überschrift, wird verworfen
statt zu einem Chunk zu werden — er ist strukturierte Metadaten, kein Fließtext, und liefert keinen
beantwortbaren Inhalt. Ein `---` an jeder anderen Stelle (horizontale Linie mitten im Dokument, oder
ein Block ohne schließenden Delimiter am Anfang) bleibt gewöhnlicher Inhalt; die Unterscheidung ist
kein Sonderfall der Eval-Korpora, sondern folgt der verbreiteten Konvention aus Jekyll/Hugo/Obsidian.
Die Frontmatter-**Felder** selbst werden hier nicht als Metadaten ausgewertet — das berührt die
Metadaten-Durchreichung, an der #1107 parallel arbeitet.

### 26. Gemessene Verschiebung: Chunkzahl je Domäne

Chunkzahl je Dokument, gemessen mit `CityLandmarksChunkSizeDryRunTest`/`VerwaltungChunkSizeDryRunTest`
gegen die real registrierte `MarkdownDocumentPipeline` (chunk-size=1000/chunk-overlap=100, wie
`application.yml`):

| Domäne | vorher (TikaFallbackPipeline) | nachher (MarkdownDocumentPipeline, Frontmatter verworfen) | `maxChunksPerDocument` |
|---|---|---|---|
| `comic-characters` | exakt 1 (Ein-Chunk-Invariante) | exakt 1 (Ein-Chunk-Invariante hält, siehe 25.) | unverändert 1 |
| `city-landmarks` | min 3, median 8, max 11 | min 5, median 15, max 17 | 13 → 20 |
| `verwaltung` | min 3, median 3, max 4 | min 10, median 15, max 16 | 6 → 19 |

`comic-characters` bleibt bei Chunkzahl 1, aber **nicht** bei identischem Chunk-Text: Der neue
Pipeline-Pfad formatiert den Chunk neu (Überschriften-Breadcrumb statt `#`-Präfix, neu zusammengesetzte
Absätze) und verwirft das Frontmatter — die Einbettung dieses einen Chunks ändert sich also trotz
gleichbleibender Chunkzahl. Alle drei Domänen brauchen deshalb neue Baselines auf beiden Pfaden, nicht
nur `city-landmarks`/`verwaltung`.

### 27. Keine Erhöhung von `measurementContractVersion`/`PIPELINE_MEASUREMENT_CONTRACT_VERSION`, aber eine neue Baseline-Pflicht für alle drei Domänen

Diese Änderung berührt keine der Metrikdefinitionen (Entscheidungen 1–10) und keine der
Pipeline-Pfad-Festlegungen (Entscheidungen 11–23) — nDCG-Gain, IDCG-Basis, Fenstergrößen,
Schwellenbehandlung, Mittelungsart und die geprüften Fixpunkte selbst bleiben wörtlich unverändert.
`maxChunksPerDocument`/`max-chunks-per-document` (Entscheidung 13/18), der Produktions-Query-Parameter
in `PipelineBaseline.FixedPoints`, bleibt in allen drei Pipeline-Baselines unverändert bei 2 — er ist
kein Fixpunkt, der sich hier bewegt. Geändert haben sich stattdessen **`chunkTopK`/`searchTopK`**
(city-landmarks 130 → 200, verwaltung 60 → 190), abgeleitet aus dem gleichnamigen, aber eigenen
Eval-Feld `EvalDomainConfig.maxChunksPerDocument` über `DocumentRanking.documentTopKWindowSize(...)` —
`searchTopK` ist nach Entscheidung 3 selbst Teil des Messvertrags. Ungetrackt von jedem heutigen
Fixpunkt bleibt zusätzlich der tatsächliche Chunk-Inhalt der drei Korpora. Nach demselben Muster wie
ein Embedding-Modell- oder Chunk-Size-Wechsel (Entscheidung 6, Klammerbemerkung: „denselben Charakter
wie eine Korpus- oder Modelländerung") gilt: **keine Vertragsversion steigt**, aber **jede der drei
Domänen-Baselines auf beiden Pfaden muss neu gezogen werden**. Die Voraussetzung „sobald ein stabiler
Stand nach #1049 vorliegt" wurde nicht abgewartet — der Maintainer hat auf den nächtlichen
Nachlauf verzichtet und die Baselines aus dem lokalen Lauf vom 2026-09-01 gemergt (siehe
PR-Beschreibung zu #1103 sowie die `notes`-Felder der sechs Baseline-Dateien).

**Offene Lücke, nicht durch #1103 geschlossen:** Weder `Baseline.FixedPoints` noch
`PipelineBaseline.FixedPoints` führen heute einen Fixpunkt für „welche `DocumentPipeline`
(Id+Version) hat dieses Format zuletzt gechunkt". Ein künftiger, unbeabsichtigter Pipeline-Tausch für
ein bereits registriertes Format (etwa ein Downgrade oder ein Bugfix, der die Chunk-Grenzen
verschiebt) würde vom `BaselineComparator` nicht als Ungültigkeit der Baseline erkannt — anders als
ein Chunk-Size- oder Embedding-Modell-Wechsel, die beide eigene Fixpunkte sind. Für #1103 wird das
bewusst nicht geschlossen (Umfang des Issues ist die Registrierung samt Baseline-Neuziehung, nicht der
Fixpunkt-Katalog); ein Folge-Issue für einen `chunkingPipeline`-Fixpunkt ist sinnvoll, sobald ein
zweites Format nach seiner eigenen Registrierung denselben Effekt zeigt.

---

## Nachtrag: Ingestion-Pipeline-Fixpunkt (Issue #1144)

**Datum:** 2026-09-03 · **Betrifft:** beide Messpfade, alle drei Eval-Domänen.

Schließt die im Nachtrag zu #1103 offen benannte Lücke: Zwei weitere reale Vorfälle aus derselben
Arbeit (Eval-Harness leitete ihre Chunk-Map über den alten Tika-Zerleger statt über die produktive
Pipeline ab; ein `answer_span`-Fehler im Golden Dataset, den erst der reparierte Wächter fand) sind
Belege derselben Fehlerklasse: die messende Seite sah eine andere Zerlegung als die produktive, ohne
dass ein Fixpunkt es bemerkt hätte.

### 28. `ingestionPipelineFingerprint` als neuer Fixpunkt auf beiden Pfaden

`Baseline.FixedPoints` und `PipelineBaseline.FixedPoints` führen je ein neues Feld
`ingestionPipelineFingerprint`: ein sortierter Sammelabdruck `id:version` über **alle** von
`DocumentPipelineRegistry` gemeldeten Pipelines (einschließlich der Fallback-Pipeline), Komma-getrennt
und nach Id sortiert — deterministisch unabhängig von der Bean-Registrierungsreihenfolge
(`IngestionPipelineFingerprint`). Ein kanonischer String statt eines Hashes, nach dem Vorbild von
`embeddingModel` (neben seinem Digest geführt) statt `corpusManifestSha256` (ein Hash, weil eine
Dokumentliste unlesbar groß wäre): zehn Pipelines passen in einen Diff, den ein Reviewer auf einen
Blick liest, und ein Diff, der **welche** Pipeline sich bewegt hat benennt, ist hier strikt
nützlicher als einer, der nur "etwas hat sich geändert" sagt.

Der Abdruck erfasst bewusst **alle** registrierten Pipelines, nicht nur die vom jeweiligen Korpus
genutzten — ein Routing-Fehler (ein Dokument landet unerwartet bei der falschen Pipeline) wäre sonst
selbst dann unsichtbar, wenn beide beteiligten Pipelines für sich genommen unverändert sind.

### 29. Beide Messverträge steigen: Rohvektor 2 → 3, Pipeline 3 → 4

Ein neuer Fixpunkt erweitert, was „dieselbe Messung" heißt (Entscheidung 6) — auf **beiden** Pfaden
gleichzeitig, weil beide Chunks derselben Ingestion-Pipelines messen (anders als der Nachtrag zu
#1049, der ausschließlich den Pipeline-Pfad betraf, weil nur dieser den lexikalischen Pfad kennt).
`EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION` steigt von 2 auf **3**,
`PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION` von 3 auf **4**; beide Zählungen
bleiben unabhängig voneinander (Entscheidung 16), sie bewegen sich hier nur zufällig im selben Schritt.

### 30. Reine Fixpunkt-Ergänzung, kein neuer Messlauf

Die sechs committeten Baselines (drei Domänen × zwei Pfade) wurden **ohne** neuen `evaluateRetrieval`-
Lauf nachgezogen — nur die Fixpunkte, nicht die Messzahlen, ändern sich: der Eval-Korpus besteht (Stand
#1145) ausschließlich aus Markdown, sodass `MarkdownDocumentPipeline` die einzige tatsächlich
beteiligte Pipeline ist und ihre Version durch diesen Nachtrag unverändert bleibt. Der Abdruck selbst
(`docx:3,email:2,html:1,markdown:1,odp:2,odt:2,pdf:1,pptx:1,tabular:1,tika-fallback:1`, Stand
`DocumentPipelineRegistry` zum Zeitpunkt dieses Nachtrags) ist identisch in allen sechs Dateien.
Kein bereits committeter Chunk, keine bereits committete Metrik ändert sich durch diesen Nachtrag —
nur die Beschreibung der Messbedingungen wird vollständiger.
