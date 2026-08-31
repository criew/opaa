# Retrieval-Benchmark: Konfigurationen vergleichbar machen

> **Status: Entwurf zur Review.** Fachliche Grundlage sind
> [`discussion-rag-evaluation.md`](../discussions/discussion-rag-evaluation.md) (Metriken, Golden
> Dataset, Vergleichsverfahren) und
> [`discussion-retrieval-roadmap-opaa.md`](../discussions/discussion-retrieval-roadmap-opaa.md),
> Phase 0 („Messbarkeit herstellen") — dort ist der Ausbau des Harness ausdrücklich als Vorbedingung
> jeder weiteren Retrieval-Investition benannt. Diese Spezifikation setzt Phase 0 um und trifft dabei
> die Entscheidungen, die die Discussion offengelassen hat. Die strukturellen Festlegungen des
> bestehenden Harness bleiben unverändert in Kraft:
> [ADR-0011](../decisions/0011-search-quality-evaluation-harness.md) (Aufbau, Korpusablage,
> Einbettungsmodell), [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md) (Messvertrag),
> [ADR-0013](../decisions/0013-fehlerkriterium-retrieval-regression.md) (Fehlerkriterium). Was
> heute gebaut ist, beschreibt [`search-quality-evaluation.md`](./search-quality-evaluation.md) und
> [`eval/README.md`](../../eval/README.md); dieses Dokument wiederholt das nicht, sondern baut darauf
> auf.

---

## Teil 0: Begriffe, ohne RAG-Vorwissen lesbar

Dieser Abschnitt richtet sich an Entwicklerinnen und Entwickler, die den Rest des Dokuments beurteilen
sollen, ohne sich vorher in Retrieval-Literatur einzuarbeiten. Wer die Begriffe kennt, springt zur
[Motivation](#motivation-was-heute-nicht-messbar-ist).

**Retrieval.** Wenn ein Nutzer OPAA etwas fragt, sucht das System zuerst in den indizierten Dokumenten
nach passenden Textstellen und gibt erst danach diese Stellen zusammen mit der Frage an ein
Sprachmodell. Der Suchteil heißt Retrieval. Er entscheidet über die Qualität der Antwort mehr als das
Sprachmodell: Was nicht gefunden wird, kann auch nicht beantwortet werden — und ein Modell, das
nichts Passendes im Kontext hat, erfindet gern etwas.

**Golden Dataset.** Eine Liste von Testfragen, bei denen jemand vorher festgelegt hat, welche
Dokumente die richtige Antwort enthalten. Vergleichbar mit den erwarteten Werten in einem
Unit-Test — nur dass die Erwartung nicht „Rückgabewert 42" heißt, sondern „unter den Suchtreffern
muss `satzung-gebuehren.pdf` sein". Ohne so eine Liste ist jede Aussage über Suchqualität eine
Meinung.

**Die vier Metriken.** Für jede Testfrage liefert die Suche eine Rangliste von Dokumenten. Die
Metriken bewerten diese Rangliste; alle vier liegen zwischen 0 und 1, größer ist besser.

| Metrik | Anschaulich |
|---|---|
| **Hit Rate@5** | „Wie oft ist überhaupt etwas Richtiges unter den ersten fünf Treffern?" Ein Ja/Nein je Frage, über alle Fragen gemittelt. 0,8 heißt: bei 4 von 5 Fragen sieht der Nutzer etwas Brauchbares auf der ersten Bildschirmseite. |
| **MRR** | „Wie weit oben steht der erste richtige Treffer?" Steht er auf Platz 1, zählt der Fall 1,0; auf Platz 2 nur noch 0,5; auf Platz 4 noch 0,25. Bestraft also, wenn das Richtige zwar gefunden, aber unter Falschem begraben wird. |
| **nDCG@10** | Wie MRR, aber es zählen *alle* richtigen Treffer im Fenster, jeder mit einem nach Platz abnehmenden Gewicht — und das Ergebnis wird an der bestmöglichen Reihenfolge normiert. Die feinkörnigste der vier Metriken: Sie merkt auch, dass sich etwas verschlechtert hat, wenn kein Treffer verloren ging, sondern nur nach hinten rutschte. |
| **Recall@10** | „Wie viel von dem, was es zu finden gab, wurde gefunden?" Bei einer Frage mit sieben richtigen Dokumenten und vier davon in den Top 10: 0,57. Die Metrik für Fragen mit mehreren richtigen Antworten. |

Warum vier statt einer: Sie messen verschiedene Fehlerbilder. Eine Änderung kann Hit Rate halten
(irgendwas Richtiges ist immer noch dabei) und trotzdem nDCG zerstören (es steht jetzt an Platz 9
statt Platz 1). Genau solche Verschiebungen fallen ohne Messung niemandem auf.

**Eingefrorener Korpus.** Die Dokumente, auf denen gemessen wird, dürfen sich nicht verändern. Sonst
misst man beim nächsten Lauf etwas anderes und schreibt den Unterschied fälschlich der
Code-Änderung zu. „Eingefroren" heißt bei OPAA: Die Dateien liegen im Repository, eine
`MANIFEST.sha256` sichert sie byteweise ab, und der Messlauf bricht ab, sobald auch nur ein Byte
abweicht. Eine Korpus-Änderung ist damit ein bewusster, reviewter Vorgang, kein Nebeneffekt.

**Baseline.** Die zuletzt bewusst akzeptierten Messwerte, als JSON im Repository committet. Jeder
neue Lauf wird dagegen verglichen. Fällt eine Metrik zu weit darunter, schlägt der Job fehl — das ist
Regressionsschutz und funktioniert wie ein Snapshot-Test, nur mit Toleranzen statt exaktem Vergleich
(Details in [ADR-0013](../decisions/0013-fehlerkriterium-retrieval-regression.md)).

**Regression vs. Benchmark — der Unterschied, um den es hier geht.** Beides nutzt dieselbe Maschinerie,
beantwortet aber verschiedene Fragen:

- **Regression** (gebaut, seit #228): *„Hat sich seit gestern etwas verschlechtert?"* Eine
  Konfiguration, ein Lauf, Vergleich gegen die committete Baseline, grün oder rot.
- **Benchmark** (Gegenstand dieses Dokuments): *„Ist Konfiguration A besser als Konfiguration B?"*
  Mehrere Konfigurationen, dieselben Fragen, derselbe Korpus, ein Vergleichsbericht statt eines
  Ampelsignals. Niemand ist hier „rot" — es geht um eine Entscheidungsgrundlage, nicht um einen
  Wächter.

**Variante.** Eine benannte, festgeschriebene Pipeline-Konfiguration, die als Vergleichsgegenstand
dient — etwa „nur Vektorsuche" gegen „Vektorsuche plus Volltextsuche". Ein Name statt einer losen
Sammlung von Umgebungsvariablen, damit ein Messergebnis auch ein Jahr später noch zuordenbar ist.

---

## Motivation: was heute nicht messbar ist

Der bestehende Harness misst `VectorStore.similaritySearch` direkt (siehe
[`eval/README.md`](../../eval/README.md), „Was der Lauf tut", Schritt 4). Er läuft damit an vier
Schritten vorbei, die in der Produktion zwischen Frage und Trefferliste stehen — Teilfragen-Zerlegung,
MMR-Auswahl, Reciprocal Rank Fusion und Dokument-Vervollständigung (Ablauf und Parameter je Schritt:
[`retrieval-algorithm.md`](./retrieval-algorithm.md), Teil 1). Drei Folgen:

1. **Gemessen wird nicht, was Nutzer erleben.** Eine Änderung an RRF, MMR oder der
   Dokument-Vervollständigung ist für den heutigen Harness unsichtbar. #932 und #935 haben genau
   dort eingegriffen — belegt wurden sie über Einzelfallanalysen, nicht über die Baseline.
2. **Vergleiche sind Handarbeit.** Die Messreihen zu #933 (drei Präfix-Varianten über zwei Domänen,
   dokumentiert in PR #940) sind fachlich vorbildlich und mechanisch ein Einzelstück: nacheinander
   umkonfigurieren, laufen lassen, Zahlen von Hand in eine PR-Beschreibung übertragen. Das skaliert
   nicht auf die Vergleichslast, die die Roadmap erzeugt.
3. **Die Roadmap hat keine Eintrittskarte.** Reranking, BM25-Ausbau, `pg_trgm`, Graph — jeder dieser
   Bausteine ist teuer und in seinem Nutzen umstritten. Ohne belastbaren Konfigurationsvergleich
   entscheidet über den Bau die Überzeugungskraft der Vorlage statt eine Messung.

Die dritte Folge ist die wichtigste: Der Benchmark ist nicht Zubehör der Roadmap, er ist ihre
Vorbedingung.

---

## Überblick der Festlegungen

Vom Maintainer entschieden; die Abschnitte darunter führen sie aus.

| # | Festlegung |
|---|---|
| 1 | Der Harness bekommt einen **zweiten Messpfad durch die produktive Query-Pipeline** (Zerlegung → Retrieval → RRF → MMR → Dokument-Vervollständigung, ohne Antwortgenerierung). Der bestehende Rohvektor-Pfad bleibt unverändert bestehen. |
| 2 | Vergleiche laufen als **benannte Pipeline-Varianten** auf denselben eingefrorenen Golden Datasets und ergeben einen Variantenbericht. |
| 3 | **Schlanke Statistik**: Baseline-Diff als Kernmechanismus, Mehrfachläufe nur für Varianten mit LLM-Anteil, kein Bootstrap-/Signifikanzapparat. |
| 4 | Eine **eigene, eingefrorene deutsche Verwaltungs-Evaldomäne** als dritter Korpus — nach Rheinfurt-Muster, aber getrennt von der Demo versioniert. |
| 5 | **Neue Golden-Fall-Klassen** für die bekannten Lücken: #938-Klasse, exakte Kennungen, Komposita, Multi-Hop, Metadaten-Filter. |
| 6 | Der Benchmark ist die **Eintrittsbedingungs-Maschine** der Retrieval-Roadmap. |

---

## 1. Messpfad durch die produktive Pipeline

> **Umsetzungsstand (Issue #1039, 08/2026):** Der Messpfad ist gebaut. `QueryService` bietet mit
> `retrieveRelevantChunksInGivenScope(question, history, searchScope)` die Schritte 2–6 als eigenen Einstieg an;
> `io.opaa.eval.PipelineHarnessSupport` fährt darüber beide Domänen im selben Harness-Lauf und
> schreibt `build/eval-reports/pipeline-metrics-<domäne>.json` mit fenstertragenden Feldnamen
> (`hitRateAt5`, `mrrAt8`, `ndcgAt8`, `recallAt8`). Fortgeschrieben ist der Messvertrag in
> [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md), Nachtrag „Pipeline-Messpfad",
> Entscheidungen 11–16 — mit **eigener** Vertragsversion für den Pipeline-Pfad statt einer Erhöhung
> der bestehenden (Begründung dort unter 16.: der Rohvektor-Vertrag ändert sich nicht, und seine
> Version ist ein Gültigkeitsfeld jeder committeten Baseline).
>
> **Umsetzungsstand (Issue #1040, 08/2026):** Die getrennten Baseline-Dateien je Pfad und Domäne
> sind gebaut — `eval/baseline/pipeline-<domäne>.json`, eigener Typ (`PipelineBaseline`), eigener
> Vergleicher (`PipelineBaselineComparator`), eigenes Urteil im nächtlichen Job neben dem des
> Rohvektor-Pfads. Die Fixpunkte des Pipeline-Pfads werden jetzt geprüft statt nur ausgewiesen; die
> Pipeline-Vertragsversion steht dadurch bei 2 (ADR-0012, Nachtrag „Baselines des Pipeline-Pfads",
> Entscheidungen 17–20). Gezogen ist bisher die Baseline von `comic-characters`; die von
> `city-landmarks` folgt aus dem CPU-Artefakt des nächtlichen Laufs (Issue #1081), bis dahin bleibt
> der Pipeline-Pfad dieser Domäne ungegated statt gegen eine nicht existierende Baseline zu laufen.
> Offen bleibt außerdem die Entscheidung über das Chat-Modell — bis dahin misst der Pipeline-Pfad
> die Variante `decomposition-off`, und dass keines beteiligt war, ist als geprüfter Fixpunkt
> (`chatModel = null`) festgehalten.
>
> **Umsetzungsstand (Issue #1044, 08/2026):** Beide Messpfade liefen mit #1039/#1040 bereits im
> nächtlichen Job je Domäne — für `city-landmarks` misst der Pipeline-Pfad dabei mit, bleibt aber
> bewusst unbeurteilt, bis Issue #1081 seine Baseline zieht (siehe die Matrix-Spalte
> `pipeline_gated` in `retrieval-regression.yml`); offen war nur, ob das gemessene Zeitbudget das
> dauerhaft trägt. Gemessen an fünf realen `checkRetrievalBaseline`/
> `checkCityLandmarksRetrievalBaseline`-Läufen der letzten Feature-PRs (GitHub-Actions-Runs
> 33412876752/33412877826/33429049024/33414091586/33431667972, alle auf CI-Hardware, beide Pfade in
> einem Lauf): `comic-characters` liegt zwischen rund 28 und 43 Minuten (70-Minuten-Budget, der
> 28-Minuten-Ausreißer eines Laufs vermutlich ein besonders warmer Ollama-Modell-Cache, die übrigen
> vier Läufe liegen dicht bei 42–43 Minuten), `city-landmarks` schwankt je nach
> Ollama-Cache-Treffer zwischen rund 77 und 133 Minuten (180-Minuten-Budget, mindestens ~26 %
> Reserve im schlechtesten beobachteten Fall). Beide Matrixeinträge laufen parallel, das
> Gesamtbudget der Nacht ist also durch `city-landmarks` begrenzt, nicht durch die Summe.
> Entscheidung: Beide Regressionspfade bleiben nächtlich, wie bereits umgesetzt; **Variantenvergleiche
> (Issue #1041) einschließlich der Mehrfachlauf-Regel oben bleiben ausschließlich manuell**
> (`-Dopaa.eval.runVariantComparison=true`, lokal oder über `workflow_dispatch`) und werden nicht in
> den nächtlichen Job aufgenommen. Begründung: Ein Vergleich mit mehreren Varianten multipliziert die
> ohnehin knapp bemessene `city-landmarks`-Zeit unmittelbar (jede zusätzliche Variante ein weiterer
> Pipeline-Lauf über das Golden Dataset, jede Zerlegungsvariante zusätzlich verdreifacht durch die
> Mehrfachlauf-Regel), während Variantenvergleiche laut Abschnitt 2 ohnehin Artefakte für punktuelle
> Roadmap-Entscheidungen sind, keine laufende Regressionsprüfung — sie gehören damit fachlich zum
> manuellen, gezielt ausgelösten Werkzeug, nicht zur nächtlichen Routine. Sollte sich das Zeitbudget
> durch künftige Domänen (etwa die Verwaltungsdomäne aus Abschnitt 4) spürbar verengen, ist diese
> Aufteilung neu zu prüfen.

### Was gemessen wird

Ein zweiter Messpfad ruft dieselbe Kette auf, die eine echte Anfrage durchläuft — bis
einschließlich Schritt 6 aus [`retrieval-algorithm.md`](./retrieval-algorithm.md#zusammenfassung-als-ablauf).
Schritt 7 (Antwortgenerierung, Zitatvalidierung, Quellen-Mapping) bleibt außen vor: Er ist kein
Retrieval, kostet ein Vielfaches an Laufzeit und Modellzugriff und wäre mit den vier Ranking-Metriken
ohnehin nicht bewertbar.

```
Golden-Frage
      ↓
[nicht gemessen] Scope-Bestimmung — der Harness setzt einen festen, vollständigen Suchbereich
      ↓
2. Teilfragen-Zerlegung (LLM, abschaltbar je Variante)
      ↓
3. Vektorsuche je Teilfrage (fetch-k, Ähnlichkeitsschwelle)
      ↓
4. MMR-Auswahl je Teilfrage (mmr-lambda)
      ↓
5. Reciprocal Rank Fusion über die Teilfragen
      ↓
6. Dokument-Vervollständigung (max-chunks-per-document)
      ↓
Ergebnisliste → dieselbe Metrikmathematik wie heute
```

Die Rechtedurchsetzung aus Schritt 1 ist ausdrücklich **nicht** Gegenstand dieser Messung. Der
Harness indiziert wie heute in eine einzelne Eval-Bibliothek und misst mit einem Suchbereich, der
diese Bibliothek vollständig umfasst. Rechtefilterung ist über die Integrationstests des Backends
abgedeckt; sie hier mitzumessen würde die Metriken um einen Faktor verschieben, der mit Suchqualität
nichts zu tun hat.

### Warum beide Pfade nebeneinander bestehen bleiben

Der Rohvektor-Pfad wird **nicht** ersetzt. Er misst eine Sache, die der Pipeline-Pfad
konstruktionsbedingt nicht mehr messen kann: die Qualität der Vektorsuche selbst, unvermischt mit
allem, was danach umsortiert. Bei einem Vergleich von Embedding-Modellen oder Chunking-Varianten ist
das der aussagekräftigere Pfad, weil dazwischenliegende Auswahlheuristiken das Signal sonst dämpfen.
Umgekehrt ist der Pipeline-Pfad der einzige, der Aussagen über die Nutzererfahrung erlaubt. Beide
gehören in den Bericht, nebeneinander und getrennt ausgewiesen.

### Folgen für Messvertrag und Baselines

Die beiden Pfade messen **unterschiedliche Dinge und sind nicht ineinander umrechenbar**. Der
Pipeline-Pfad arbeitet mit der Produktionskonfiguration (`fetch-k=25`, `similarity-threshold=0,3`,
`top-k=8`, `max-chunks-per-document=2`), der Rohvektor-Pfad bewusst ohne Ähnlichkeitsschwelle und mit
`documentTopK=10` ([ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md), Entscheidung 3).
Vier Konsequenzen, die vor der ersten Zeile Code feststehen müssen:

1. **Getrennte Baseline-Dateien je Pfad und Domäne.** Eine Pipeline-Baseline überschreibt niemals
   eine Rohvektor-Baseline. Die bestehenden Baselines aus #228/#234 bleiben unangetastet und behalten
   ihre Zahlen — die Erweiterung darf sie nicht ungültig machen.
2. **Der Messvertrag wird fortgeschrieben, nicht umgeschrieben.** Der Pipeline-Pfad braucht eigene
   Fixpunkte (die vier Query-Parameter oben, `query-decomposition-enabled`, `max-sub-queries`, das
   verwendete Chat-Modell bei aktiver Zerlegung). Formal ist das eine Fortschreibung von ADR-0012 in
   Gestalt eines Nachtrags mit erhöhter `measurementContractVersion` — dasselbe Verfahren wie bei
   Issue #721.
3. **Die Ähnlichkeitsschwelle wird im Pipeline-Pfad tatsächlich angewandt**, nicht nur informativ
   ausgewiesen. Damit kann ein Dokument aus der Rangliste ganz verschwinden statt nur zurückzufallen;
   Recall-Werte liegen im Pipeline-Pfad systematisch niedriger. Das ist kein Fehler, sondern die
   gemessene Realität — und ein Grund mehr, die Pfade nie zu vermischen.
4. **`top-k=8` gegen `documentTopK=10`.** Das Fenster des Pipeline-Pfads ist die tatsächliche
   Trefferzahl der Produktion. Die Metriken heißen dort folglich Hit Rate@5, MRR@8, nDCG@8 und
   Recall@8. Ein Nebeneinanderstellen von nDCG@8 und nDCG@10 in einer Tabelle ohne Kennzeichnung ist
   ein Auswertungsfehler; der Bericht führt das Fenster deshalb an jeder Zahl mit.

### Regressionsschutz

Der nächtliche Regressionsjob läuft künftig **beide** Pfade, mit je eigener Baseline und dem
unveränderten Fehlerkriterium aus ADR-0013. Für den Pipeline-Pfad mit aktiver Teilfragen-Zerlegung
gilt zusätzlich die Mehrfachlauf-Regel aus [Abschnitt 3](#3-schlanke-statistik).

---

## 2. Benannte Pipeline-Varianten

> **Umsetzungsstand (Issue #1041, 08/2026):** Die Variantenmechanik ist gebaut. Eine Variante ist
> ein `PipelineVariant`-Datensatz (Name, Beschreibung, `requiresReindex`, ein partielles Override
> von `QueryProperties`) in einer JSON-Datei unter `eval/variants/`
> (`io.opaa.eval.VariantComparisonDataset`) — ein neuer Vergleich ist eine neue Datei, keine neue
> Testklasse. `io.opaa.eval.VariantComparisonRunner` führt jede Variante gegen dasselbe, bereits
> indizierte Korpus und Golden Dataset über den Pipeline-Pfad (#1039) aus, indem es je Variante ein
> eigenes `QueryService` um dieselben Spring-Kollaboratoren herum baut (`QueryServiceDependencies`)
> — kein zweiter Spring-Kontext, kein zweiter Reindex. Der Bericht (`VariantReport`, Artefakt unter
> `build/eval-reports/`, nie committet) weist je Variante entweder das Messergebnis oder einen
> Skip-Grund aus sowie für jede ausgeführte Variante ein Delta gegen die Referenzvariante,
> aggregiert und je Golden-Fall. Eine Variante mit `requiresReindex=true` oder mit aktivierter
> Teilfragen-Zerlegung wird als „nicht ausgeführt" gemeldet (`VariantPrerequisites`), nicht
> stillschweigend gegen den falschen Index bzw. ohne Chat-Modell gemessen. Die
> Referenzvarianten-Selbstprüfung vergleicht die Referenzvariante gegen einen zweiten, unabhängigen
> direkten Aufruf desselben produktiv verdrahteten `QueryService`-Beans im selben Lauf — Metriken
> und Laufkonfiguration, harte Assertion, bitgleich — nicht gegen eine zweite, handgebaute
> `QueryService`-Instanz, damit die Prüfung tatsächlich die produktiv verdrahtete Pipeline trifft
> und nicht nur die Determinismus der Mechanik selbst. Eine committete Pipeline-Baseline zum
> zusätzlichen Abgleich existiert noch nicht (Folgearbeit von Issue #1040). Die Vergleichsdatei wird
> geladen und die Ausführbarkeit der Referenzvariante geprüft, sobald das Opt-in erkannt ist — vor
> der Korpus-Indizierung, nicht erst nach ihr; danach ist der eigentliche Vergleichslauf wie der
> Pipeline-Pfad selbst geschützt (`PipelineHarnessSupport#runAndWriteGuarded`-Muster), sodass ein
> Fehler dort nie das bereits erarbeitete Rohvektor- oder Pipeline-Urteil kostet. Das
> Variantenschema verzichtet bewusst auf `@JsonIgnoreProperties(ignoreUnknown = true)`, damit ein
> Tippfehler in einem Override-Feldnamen den Lauf abbricht statt als stillschweigend wirkungslose,
> legitim aussehende Δ0.000-Variante zu erscheinen. Standardmäßig abgeschaltet
> (`-Dopaa.eval.runVariantComparison=true`), damit ein normaler
> `evaluateRetrieval`/`checkRetrievalBaseline`-Lauf unverändert bleibt. Noch offen: Reindex-fähige
> Varianten (Embedding-Modell, Chunking) — `requiresReindex` ist als Feld bereits vorgesehen, der
> Reindex-Pfad selbst ist Folgearbeit. Details: [`eval/variants/README.md`](../../eval/variants/README.md).

### Prinzip

Eine **Variante** ist eine benannte, versionierte Konfiguration der Pipeline. Sie trägt einen
stabilen Bezeichner, eine kurze fachliche Beschreibung („wofür wurde das gebaut?") und den
vollständigen Satz abweichender Parameter gegenüber der Produktionskonfiguration. Ein Benchmark-Lauf
nimmt eine Liste von Varianten entgegen, führt jede gegen dasselbe eingefrorene Golden Dataset und
denselben eingefrorenen Korpus aus und schreibt einen Variantenbericht.

Die Anforderung dahinter ist ein Grundsatz aus
[`discussion-rag-evaluation.md`](../discussions/discussion-rag-evaluation.md) §7: **gepaart messen.**
Beide Konfigurationen sehen dieselben Fragen, sonst vergleicht man Datensätze statt Konfigurationen.
Der Bericht weist Deltas deshalb nicht nur aggregiert aus, sondern auch je Frage — die interessante
Information ist fast immer „welche fünf Fragen haben sich verschlechtert", nicht „der Mittelwert stieg
um 0,03".

### Vergleiche, für die das gebaut wird

| Vergleich | Varianten | Erwartete Erkenntnis |
|---|---|---|
| Suchverfahren | `vector-only` / `vector+fulltext-rrf` / `vector+fulltext-rrf+rerank` | Trägt die Hybrid-Suche (Roadmap 1a/1b), trägt Reranking obendrauf (1c)? Misst die #938-Klasse direkt. |
| Embedding-Modell | `embed-nomic` / `embed-bge-m3` / `embed-qwen3` | Ist `nomic-embed-text` die gemessene Schwachstelle aus #938 (Roadmap 2d)? Vollständiger Reindex je Variante, deshalb der teuerste Vergleich. |
| Chunking | `chunk-1000` / `chunk-500-parent` / strukturbewusst je Dokumenttyp | Sind kleinere Chunks besser (Roadmap 2b/2c)? Braucht mehrchunkige Domänen und die `answer_span`-Metrikfamilie. |
| Auswahlmechanik | `mmr-1.0` / `mmr-0.7`, `max-chunks-per-document` 1/2/3 | Nur im Pipeline-Pfad überhaupt messbar. Löst die Handarbeit aus #915/#932 ab. |
| Fragezerlegung | `decomposition-off` / `decomposition-on` | Der einzige Vergleich mit nichtdeterministischem Anteil — siehe Abschnitt 3. |

Die Liste ist nicht abschließend und keine Zusage: Sie zeigt, dass jede offene Roadmap-Frage sich als
Variantenvergleich formulieren lässt. Genau das ist der Zweck des Aufbaus.

### Anforderungen an die Umsetzung

- **Deklarativ, nicht als Testklasse je Variante.** Eine Variante ist Daten (eine Konfiguration im
  Repository), kein Code. Andernfalls wächst das `evalTest`-Source-Set mit jedem Vergleich um eine
  Klasse — das Muster, das `EvalDomainConfig` für Domänen bereits vermieden hat.
- **Eine Variante, die keinen Parameter ändert, ist die Referenzvariante** und muss bitgleiche Zahlen
  zur committeten Baseline liefern. Diese Selbstprüfung ist billig und fängt die gesamte Klasse von
  Fehlern ab, bei der ein Benchmark-Lauf unbemerkt anders misst als der Regressionslauf.
- **Jede Variante deklariert, ob sie einen Reindex erfordert.** Embedding- und Chunking-Varianten tun
  das, Query-Parameter-Varianten nicht. Der Lauf indiziert nur so oft wie nötig; ohne diese
  Unterscheidung wird jeder Vergleich unnötig um ein Vielfaches teurer.
- **Varianten mit nicht erfüllten Voraussetzungen werden übersprungen, nicht stillschweigend
  degradiert.** Eine `+rerank`-Variante ohne verfügbares Reranker-Modell meldet „nicht ausgeführt";
  sie darf nicht als „ohne Reranker gemessen" in einen Bericht geraten.
- **Der Bericht ist ein Artefakt, keine Baseline.** Variantenberichte werden nicht committet.
  Committet wird, was aus ihnen folgt: eine Baseline-Änderung, ein Default-Wechsel, eine
  Roadmap-Entscheidung — jeweils mit dem Bericht als Beleg in der PR-Beschreibung.

---

## 3. Schlanke Statistik

> **Umsetzungsstand (Issue #1044, 08/2026):** Die Mehrfachlauf-Regel ist als Mechanismus gebaut.
> `io.opaa.eval.VariantRunner` erkennt eine Variante mit effektiv aktivierter
> Teilfragen-Zerlegung an `QueryProperties#queryDecompositionEnabled` und führt sie
> `MultiRunAggregator.DECOMPOSITION_RUN_COUNT` (3) statt einmal aus; jede andere Variante bleibt bei
> einem Lauf. `io.opaa.eval.MultiRunAggregator` bildet daraus einen `MultiRunSummary`:
> Minimum/Median/Maximum je Metrik (Hit Rate@5, MRR@8, nDCG@8, Recall@8) und die Zahl der Fälle,
> bei denen sich die von der Zerlegung erzeugten Teilfragen zwischen den drei Läufen unterschieden.
> Der Baseline-/Referenzvergleich verwendet dafür den **Median-Lauf** (den Lauf, dessen nDCG@8 der
> Median der drei Werte ist) — `VariantOutcome#report()` liefert für eine Mehrfachlauf-Variante genau
> diesen Lauf, sodass `VariantComparisonRunner#delta` unverändert bleibt und trotzdem den Median
> vergleicht, nicht einen beliebigen Einzellauf. `VariantReportWriter` gibt die drei Zahlen je Metrik
> und die Abweichungszahl zusätzlich zur bestehenden Delta-Ausgabe aus. Um die Teilfragen einer
> Anfrage über Läufe hinweg vergleichen zu können, liefert `QueryService` jetzt zusätzlich
> `retrieveRelevantChunksInGivenScopeWithDecomposition`, additiv neben der bestehenden Methode, die
> neben den Chunks auch die tatsächlich gestellten Suchanfragen zurückgibt; jeder Pipeline-Report
> führt sie seither je Fall mit (`PipelineQueryResult#subQueries`).
>
> **Der Mechanismus ist heute nicht scharf geschaltet**, weil er es nicht sein kann: Jede Variante
> mit `queryDecompositionEnabled=true` wird von `VariantPrerequisites` weiterhin als „nicht
> ausgeführt“ gemeldet, weil der Harness-Kontext noch kein Chat-Modell hat (siehe „Offene Punkte“ 3
> unten). Die Regel selbst — Ausführungszahl, Aggregation, Median-Auswahl, Abweichungszählung — ist
> deshalb über `MultiRunAggregatorTest`/`VariantRunnerTest` mit synthetischen Reports und einem
> injizierten Mess-Supplier abgesichert (issue #1044 review, Befund 1: `VariantRunner` trennt die
> Regel selbst von der Docker-gebundenen `QueryService`-Beschaffung genau dafür), nicht über einen
> echten Zerlegungslauf.
>
> **Sie greift automatisch, sobald eine Variante das Chat-Modell bekommt — mit einer offenen Folge,
> die #1085 mitlösen muss.** Wird dabei die Referenzvariante selbst (keine Parameteränderung)
> zerlegungsfähig — etwa weil `queryDecompositionEnabled` in der Produktionskonfiguration auf
> `true` wechselt —, wird sie bei ihrem nächsten Lauf ebenfalls zur Mehrfachlauf-Variante: drei
> Läufe, Median-Auswahl. Die Referenzvarianten-Selbstprüfung
> (`RetrievalEvaluationHarnessTest`, siehe `eval/variants/README.md`) vergleicht die
> Referenzvariante bisher aber bitgleich gegen genau **einen** unabhängigen Direktaufruf desselben
> `QueryService`-Beans — ein Median aus drei nichtdeterministischen Läufen kann mit einem einzelnen
> Direktaufruf strukturell nicht mehr bitgleich sein. Diese Prüfung muss #1085 deshalb mitlösen
> (z. B. durch drei Direktaufrufe und denselben Median-Vergleich auf beiden Seiten), nicht als
> Nebeneffekt der Chat-Modell-Anbindung entdecken.

### Entscheidung

Kein Bootstrap, keine Konfidenzintervalle, keine p-Werte. Das gilt ausdrücklich **gegen** den
Vorschlag in [`discussion-rag-evaluation.md`](../discussions/discussion-rag-evaluation.md) §7
(Paired Bootstrap, BCa-Intervalle, Sign-Flip-Permutationstest) und ist eine bewusste Vereinfachung,
kein Übersehen.

### Begründung

Der statistische Apparat aus §7 löst ein Problem, das OPAA an dieser Stelle nicht hat. Er ist für
Messungen gedacht, deren Ergebnis von Lauf zu Lauf streut — LLM-Sampling, wechselnde Korpora,
Stichproben aus einer größeren Grundgesamtheit. Der Retrieval-Harness von OPAA hat davon nichts:

- Der Korpus ist eingefroren und über ein SHA-256-Manifest abgesichert.
- Das Golden Dataset ist eingefroren und versioniert.
- Das Einbettungsmodell ist auf Tag **und** Content-Digest gepinnt, mit hartem Abbruch bei Drift.
- Der Lauf erzwingt CPU statt GPU, weil GPU-Kernel nicht bitgleich einbetten.
- Die Belege liegen vor: vier Läufe auf drei Maschinen, alle bit-identisch, Delta ±0,000 über alle
  vierzig Metrik/Gruppen-Zeilen (ADR-0013, Nachtrag „zweite Review-Runde"), bestätigt auf
  GitHub-Actions-Hardware.

Ein Konfidenzintervall über eine deterministische Größe ist keine Vorsicht, sondern ein Kategorienfehler:
Es beziffert eine Streuung, die nicht existiert, und suggeriert Sorgfalt, wo eine schlichte
Gleichheitsprüfung mehr aussagt. Die reale Unsicherheit dieser Messung liegt woanders — im Golden
Dataset (siehe [Abschnitt 4](#4-verwaltungs-evaldomäne), Overfitting-Risiko) und in der
Übertragbarkeit auf echte Nutzerfragen. Beides ist ein Stichprobenproblem, kein Rauschproblem, und
kein Bootstrap der Welt behebt es.

### Was stattdessen gilt

1. **Baseline-Diff bleibt der Kernmechanismus.** Er existiert, ist in ADR-0013 begründet, drückt
   Toleranzen in kippenden Fällen statt in Metrikpunkten aus und hat mit der Kombination aus
   Fallzahl- und Mittelwertprüfung (Issue #306/#694) bereits die Grenzfälle geklärt, an denen ein
   naiver Schwellenvergleich scheitert.
2. **Mehrfachläufe nur bei LLM-Anteil.** Eine Variante mit aktiver Teilfragen-Zerlegung ist
   nichtdeterministisch — das Chat-Modell kann dieselbe Frage unterschiedlich zerlegen, auch bei
   Temperatur 0. Solche Varianten laufen dreimal; der Bericht führt Minimum, Median und Maximum je
   Metrik, der Baseline-Vergleich verwendet den Median. Der Bericht weist zusätzlich aus, bei wie
   vielen Fragen die Zerlegung über die Läufe hinweg abwich — diese Zahl ist die eigentliche
   Kennzahl der Instabilität und aussagekräftiger als jede Streuungsangabe auf der Metrik.
3. **Eine Variante ohne LLM-Anteil läuft einmal.** Ein zweiter Lauf würde dieselben Zahlen liefern;
   das ist geprüft, und wenn es einmal nicht mehr gilt, ist das ein Befund und kein Anlass für
   Statistik.
4. **Der Bericht nennt Deltas, nicht Signifikanz.** Formulierungen wie „A ist signifikant besser als
   B" sind in Berichten und PR-Beschreibungen zu vermeiden, weil kein Test dahintersteht. Zulässig
   ist: „A liegt bei nDCG@8 um 0,07 über B; verschlechtert haben sich dabei drei Fälle, verbessert
   elf" — nachprüfbar, weil der Bericht jeden Einzelfall führt.

Wenn sich diese Einschätzung als falsch erweist — etwa weil ein künftiger Baustein echtes Rauschen
einbringt und Mehrfachläufe sichtbar streuen —, ist der Bootstrap-Apparat aus §7 unverändert
verfügbar und kann nachgezogen werden. Die Entscheidung ist eine Priorisierung, keine
Grundsatzablehnung.

---

## 4. Verwaltungs-Evaldomäne

### Warum eine dritte Domäne

Die beiden bestehenden Domänen messen, wofür sie gebaut wurden, decken Verwaltungssprache aber nicht
ab: `comic-characters` ist englisch, attributlastig und einchunkig; `city-landmarks` ist deutsch und
mehrchunkig, aber enzyklopädische Prosa. OPAAs Produktausrichtung ist die öffentliche Verwaltung
([ADR-0014](../decisions/0014-produktausrichtung-oeffentliche-verwaltung.md)) — Satzungstexte,
Gebührenordnungen, Dienstanweisungen, Formularhinweise. Deren sprachliche Eigenheiten sind genau die,
an denen die bekannten Schwächen hängen: Komposita, Paragraphenverweise, Aktenzeichen, ein Registerbruch
zwischen Amtssprache im Dokument und Bürgersprache in der Frage. Ohne solche Dokumente misst der
Benchmark die Hybrid-Suche und das Reranking an Material, für das sie nicht gebaut werden.

### Getrennt von der Demo

Der Rheinfurt-Korpus unter `demo/corpus/` liefert das **Muster** — synthetische Verwaltungsdokumente
einer fiktiven Stadt, in echten Büroformaten (`.docx`, `.pdf`, `.pptx`), fachlich plausibel
strukturiert. Er liefert **nicht** die Dateien. Die Eval-Domäne bekommt einen eigenen Generator, einen
eigenen eingefrorenen Korpus unter `eval/corpus/` und ein eigenes Manifest.

Die offene Frage 3 der Roadmap-Discussion („eigener Korpus vs. Wiederverwendung des Rheinfurt-Korpus")
ist damit zugunsten der Trennung entschieden. Grund ist das Kopplungsrisiko: Der Demo-Korpus ist ein
**Vorführ**artefakt und wird sich ändern, sobald jemand ein Dokument überzeugender formulieren, ein
Format ergänzen oder ein Szenario nachschärfen will. Jede solche Änderung würde jede committete
Baseline ungültig machen. Beide Interessen an dieselbe Datei zu binden, bedeutet, dass eine kosmetische
Demo-Verbesserung einen Messlauf und eine Baseline-Neuziehung erzwingt — oder, wahrscheinlicher, dass
die Demo-Verbesserung unterbleibt. Der Preis der Trennung ist ein zweiter Generator; er ist niedriger
als der Preis der Kopplung.

### Anforderungen an den Korpus

- **Deutschsprachig, Amtssprache**, mit dem Registerunterschied zur Bürgersprache als bewusstem
  Gestaltungsmerkmal: Was das Dokument „Gebührenbefreiung wegen Bedürftigkeit" nennt, fragt der Bürger
  als „muss ich das bezahlen, wenn ich Bürgergeld bekomme?".
- **Mehrchunkig** und mit sauberer `answer_span`-Ground-Truth je Fall, damit Chunking- und
  Kontextvarianten messbar werden ([ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md),
  Nachtrag Entscheidung 9). Die Chunk-Zahl-Erwartung der Domäne wird wie bei `city-landmarks` als
  `ChunkCountExpectation` deklariert und im Lauf geprüft.
- **Enthält die Fehlerbilder, die gemessen werden sollen**, konstruktiv statt zufällig: Paragraphen
  mit wörtlichen Anfragebegriffen, deren Embedding-Signal schwach ist (#938-Klasse); Kennungen;
  Komposita; Ketten über mehrere Dokumente. Siehe [Abschnitt 5](#5-neue-golden-fall-klassen).
- **Deterministisch generiert, eingefroren, mit `MANIFEST.sha256`** — dieselben Regeln wie für die
  bestehenden Domänen, unverändert aus ADR-0011.
- **Lizenzsauber.** Vollständig synthetisch; wo sich der Generator an Vorbildern orientiert, gelten
  die CC0/CC-BY-Regeln aus ADR-0011 unverändert, einschließlich `SOURCE.md` und, falls einschlägig,
  einer Drittlizenz-Ablage wie im Demo-Korpus.
- **Formatvielfalt bewusst begrenzt.** Der Demo-Korpus nutzt `.docx`/`.pdf`/`.pptx`, um die
  Formatunterstützung vorzuführen. Für die Eval-Domäne ist Formatvielfalt zunächst ein Störfaktor:
  Sie mischt Extraktionsqualität in die Retrieval-Messung. Empfehlung des Product Managers: mit
  Markdown starten und die Formatvielfalt erst aufnehmen, wenn Roadmap-Schritt 2b
  (strukturbewusstes Chunking je Dokumenttyp) sie tatsächlich zum Messgegenstand macht — dann aber
  als eigene, klar getrennte Fallgruppe.

### Ehrliche Einschränkung: Benchmark-Overfitting

**Es gibt für diese Domäne keine echten Nutzerfragen.** Die Golden-Fälle werden von denselben
Personen erdacht, die den Korpus generieren und die Pipeline bauen. Das erzeugt ein bekanntes und
hier ausdrücklich benanntes Risiko: Wir messen die Fragen, die wir uns vorstellen können, und
optimieren die Pipeline auf genau diese Vorstellung. Ein Verfahren, das auf dieser Domäne gewinnt,
gewinnt nachweislich gegen unsere Annahmen über Verwaltungsanfragen — nicht nachweislich gegen
Verwaltungsanfragen.

Drei Milderungen, keine Lösungen:

1. **Die Fälle werden aus den Dokumenten heraus formuliert, nicht aus der Pipeline heraus.** Wer eine
   Frage schreibt, weil er weiß, dass die aktuelle Konfiguration sie trifft, hat den Zweck verfehlt.
   Umgekehrt gilt: Ein Fall, den heute keine Variante löst, ist der wertvollste im Datensatz.
2. **Die Fallklassen sind vorab festgelegt** (Abschnitt 5) und leiten sich aus **belegten**
   Fehlerbildern ab — #938 ist ein real beobachteter Produktionsfall, keine Vermutung.
3. **Der Vorsatz, echte Fragen nachzuziehen, ist Teil dieser Spezifikation.** Sobald ein Pilotbetrieb
   echte Anfragen liefert, wird eine Stichprobe davon zur eigenen Fallgruppe der Domäne — mit
   anonymisierten, gegen den Eval-Korpus umformulierten Fragen. Der billigste Zulieferer dafür wäre
   ein Feedback-Kanal in der Oberfläche („war das hilfreich?"), bereits als offener Punkt in
   [`search-quality-evaluation.md`](./search-quality-evaluation.md#offene-fragen--zukünftige-erweiterungen)
   vermerkt. Bis dahin ist jede Aussage dieses Benchmarks eine Aussage über konstruierte Fragen, und
   Berichte sollten das so benennen.

---

## 5. Neue Golden-Fall-Klassen

> **Umsetzungsstand (Issue #1043, 08/2026):** Die fünf Fallklassen sind gebaut, kuratiert und
> gemessen. `eval/golden/verwaltung.json` führt 46 von Hand kuratierte Fälle (9–10 je Klasse, über
> dem Minimum von acht), jeder mit seiner Klasse als `category` und den drei Zustandsfeldern — ab
> dem ersten Commit im Schema, nicht nachgerüstet. Beide Messpfade werten je Klasse aus
> (`byCategory` in beiden Reports, je Klasse eine eigene Gruppe in beiden Baselines), und beide
> Baselines der Domäne sind aus demselben CPU-Testcontainer-Lauf gezogen
> (`eval/baseline/verwaltung.json`, `eval/baseline/pipeline-verwaltung.json`).
>
> **Was der erste Lauf zeigt** (2026-08-31, Pipeline-Pfad): 33 der 46 Fälle sind als `known_gap`
> geführt. `literal_term_weak_embedding` ist vollständig ungelöst (0 von 9) — der Anfragebegriff
> steht wörtlich im Zieldokument, und dieses liegt in mehreren Fällen nicht einmal im
> Trefferfenster; `compound_word` ebenfalls (0 von 9); `multi_hop` 1 von 9; `metadata_filter` 4 von
> 9; `exact_identifier` dagegen 8 von 10. Die Domäne ist damit nicht pauschal schwer, sondern
> klassenspezifisch — genau die Voraussetzung, die Abschnitt 6 an eine Eintrittsbedingung stellt.
> Die vollständige Liste mit Begründung je Fall: `eval/corpus/verwaltung/MAINTENANCE.md`.
>
> **Zustandsfelder-Audit statt stiller Baseline-Verbesserung:** Jeder Lauf hält in beiden Reports
> die deklarierten Zustände gegen die gemessenen und nennt beide Abweichungsrichtungen namentlich
> (`io.opaa.eval.ExpectedStateAudit`). „Gelöst" ist dabei einheitlich definiert: alle erwarteten
> Dokumente im Fenster **und** ein erwartetes Dokument auf Rang 1, auf beiden Messpfaden — ohne die
> Rang-1-Bedingung gälten alle neun `metadata_filter`-Fälle als gelöst, obwohl in fünf davon die
> falsche Fassung obenauf liegt. Das Audit meldet, es lässt den Lauf nicht fehlschlagen: Ein
> Zustandswechsel bleibt eine bewusste, datierte Entscheidung.
>
> **Nicht mit umgesetzt:** die Aufnahme der Klassen in die bestehenden Domänen (offener Punkt 5) —
> unverändert offen, weil sie dort eine Baseline-Neuziehung kostet.


Fünf Kategorien kommen hinzu. Jede hat ein benanntes Fehlerbild, eine überprüfbare Ground Truth und
einen Adressaten in der Roadmap. Sie werden primär in der Verwaltungsdomäne umgesetzt; wo eine
bestehende Domäne sie trägt, dürfen sie auch dort ergänzt werden — jede Ergänzung an einem
bestehenden Golden Dataset macht dessen Baseline ungültig und erfordert einen bewussten Neuziehungs-Lauf.

### (a) `literal_term_weak_embedding` — die #938-Klasse

Das Dokument enthält den Anfragebegriff **wörtlich**, wird von der Vektorsuche aber trotzdem nicht
gefunden, weil sein Embedding thematisch anders liegt. Der belegte Produktionsfall: § 3 der
Verwaltungsgebührensatzung enthält „Befreiung" und „Bedürftigkeit" im Klartext und rankt für die
passende Frage auf Rang 50 hinter thematisch fremder Konkurrenz (Diagnose in #938, zusammengefasst in
[`retrieval-algorithm.md`](./retrieval-algorithm.md#bekannte-offene-schwächen-aus-den-912-verifikationen)).

*Konstruktion:* Ein kurzer, fachsprachlicher Absatz mit dem Anfragebegriff, eingebettet in ein
Dokument, dessen Gesamtthema woanders liegt — plus mehrere thematisch nahe, aber sachlich falsche
Konkurrenzdokumente im Korpus. Ohne diese Konkurrenz ist der Fall trivial.
*Adressat:* Roadmap 1a/1b (lexikalischer Pfad, Fusion). Ein Fall dieser Klasse ist der direkte Beleg,
ob Hybrid-Suche das Versprechen einlöst.

### (b) `exact_identifier` — Kennungen

Paragraphenreferenzen (`§ 3 Abs. 2 VGS`), Aktenzeichen, Erlass- und Formularnummern, Gebührenpositionen.
Vektorsuche behandelt sie als bedeutungsarme Tokens; eine Ziffer mehr oder weniger ändert am Embedding
fast nichts, an der Bedeutung alles.

*Konstruktion:* Die Frage nennt die Kennung wörtlich, die Ground Truth ist die eine Stelle, die sie
führt. Wichtig sind **Verwechslungspartner** im Korpus — `§ 3` und `§ 13`, `Az. 12/2024` und
`Az. 12/2023` —, sonst gewinnt schon die reine Themenähnlichkeit.
*Adressat:* Roadmap 1a (Schutz unzerlegter Kennungs-Tokens), perspektivisch `pg_trgm` für tolerante
Schreibweisen.

### (c) `compound_word` — Komposita

Der Treffer ist nur über einen **Wortbestandteil** erreichbar: Die Frage sagt „Gebühren", das Dokument
„Verwaltungsgebührensatzung"; die Frage sagt „Ausweis", das Dokument „Personalausweisantragsverfahren".
Diese Klasse misst gezielt die Schwäche der deutschen `tsvector`-Konfiguration ohne
Komposita-Zerlegung: Ein `to_tsvector('german', …)` zerlegt zusammengesetzte Substantive nicht, ein
lexikalischer Pfad ohne Decompounding findet den Teilbegriff also gerade nicht.

*Konstruktion:* Bewusst als Teilmenge geführt, damit sie getrennt auswertbar ist. Sie ist der
Vergleichsgegenstand für die Entscheidung zwischen ispell-Wörterbuch, `german-decompounder` und
„vorerst nichts tun".
*Adressat:* Roadmap 1a, Komposita-Behandlung. Ohne diese Fallgruppe wäre die Wörterbuchfrage nur
argumentativ zu entscheiden.

### (d) `multi_hop` — Ketten über mehrere Dokumente

Die Antwort steht in keinem Dokument vollständig: „Wer entscheidet über die Gebührenbefreiung, wenn
die zuständige Sachbearbeitung im Urlaub ist?" verlangt die Satzung (Befreiung) **und** die
Vertretungsregelung (Zuständigkeit).

*Konstruktion:* Die Ground Truth umfasst zwei bis drei Dokumente; gewertet wird über Recall und
zusätzlich über den strengeren Indikator „alle erwarteten Dokumente im Fenster". Erste Fälle bewusst
zweistufig, keine tiefen Ketten — eine fünfgliedrige Kette misst nichts Zusätzliches und ist
schwerer sauber zu konstruieren.
*Adressat:* Ausschließlich Messgrundlage für Roadmap 3c (Wissensgraph). Diese Fallgruppe **fordert
keinen Graphen** — sie stellt fest, wie groß die Lücke ist, die Phase 1 und 2 offen lassen. Bleibt
die Lücke klein, ist die Graph-Frage damit beantwortet, und das ist ein vollwertiges Ergebnis.

### (e) `metadata_filter` — Filterfragen

„Was gilt nach dem Stand 2024?", „nur Dienstanweisungen, keine Satzungen". Die Antwort ist nur richtig,
wenn ein Metadatum eingehalten wird; ein inhaltlich passendes Dokument der falschen Fassung ist ein
Fehltreffer.

*Konstruktion:* Erfordert Dokumente, die sich **nur** im Metadatum unterscheiden — dieselbe Regelung
in Fassung 2023 und 2024. Die Ground Truth ist die richtige Fassung; die falsche ist der eingebaute
Verwechslungspartner. Der Korpus muss die Metadaten dafür im Frontmatter tragen.
*Adressat:* Roadmap 2f (Metadatenschema je Bibliothek). Zugleich die einzige Fallklasse, die eine
heute **nicht vorhandene** Produktfähigkeit misst: Es gibt keinen Metadatenfilter in der Suche. Die
Fälle werden trotzdem jetzt gebaut — sie sind dann von Anfang an die Abnahmegrundlage, statt
nachträglich passend zur gebauten Lösung zu entstehen.

### Gemeinsame Regeln

Die bestehenden Regeln für Golden-Fälle gelten unverändert und werden hier nicht wiederholt:
Treffermengen zwischen 2 und 15 Dokumenten bei Mengenfragen, Sentinel-Ausschluss vor Bestimmung der
Treffermenge, manuelle Kuratierung vor Aufnahme in die Baseline (siehe
[`search-quality-evaluation.md`](./search-quality-evaluation.md#golden-dataset)). Ergänzend:

- Jeder Fall trägt seine Klasse als `category`, damit der Bericht je Klasse auswertet. Eine
  Verbesserung, die nur im Gesamtmittel sichtbar ist, sagt über den adressierten Fehler nichts.
- Jede Klasse braucht **mindestens acht Fälle**, sonst ist der Gruppenwert vom Kippen eines
  Einzelfalls dominiert und das Fehlerkriterium aus ADR-0013 greift dort ins Leere.
- Fälle, die heute keine Variante löst, bleiben im Datensatz. Sie sind der Zweck der Übung.

### Zustandsfelder: ungelöste Fälle bleiben ungelöst benannt

Ein Datensatz, in dem gelöste und bewusst ungelöste Fälle ununterscheidbar nebeneinanderliegen,
verliert nach wenigen Monaten seine wichtigste Eigenschaft: Niemand weiß mehr, ob ein roter Fall eine
Regression oder der seit einem Jahr bekannte offene Punkt ist. Der naheliegende Ausweg — den störenden
Fall stillschweigend entfernen — ist genau der Verlust, den der vorige Punkt verhindern soll.

Deshalb trägt **jeder Fall** zwei zusätzliche Pflichtfelder:

| Feld | Inhalt |
|---|---|
| `expected_state` | `solved` oder `known_gap` — der zuletzt bewusst akzeptierte Zustand des Falls |
| `expected_state_since` | Datum, an dem dieser Zustand zuletzt bewusst gesetzt wurde |
| `expected_state_reason` | Eine Zeile: warum. Bei `known_gap` der fehlende Baustein, bei `solved` die Änderung, die ihn gelöst hat |

Ein Fall, der von `known_gap` auf `solved` wechselt, ist damit ein sichtbarer, reviewbarer Vorgang mit
Datum — und der Beleg dafür, dass ein Baustein geliefert hat, was er versprochen hat. Ein Fall, der in
die Gegenrichtung wechselt, ist ein begründungspflichtiger Rückschritt und kein Datenpflegevorgang.

Daneben liegt eine **`MAINTENANCE.md` beim Korpus**, die drei Fragen beantwortet, die sonst nur in
Köpfen stehen: wer welchen Teil pflegt (Generator, Korpus, Golden Dataset, Baselines), wie eine
Baseline-Neuziehung abläuft und woran sie erkennbar bewusst war, und welche Fälle derzeit als
`known_gap` geführt werden und warum.

**Beides ist Abnahmekriterium der betroffenen Umsetzungsschritte**, nicht ein Vorsatz für später: Die
Felder gehören ab dem ersten committeten Fall ins Schema, die `MAINTENANCE.md` entsteht mit dem
Korpus. Nachträglich sind beide nicht mehr wahrheitsgemäß auszufüllen — die Begründungen sind dann
rekonstruiert statt festgehalten.

---

## 6. Der Benchmark als Eintrittsbedingungs-Maschine

**Grundsatz: Kein Retrieval-Baustein wird gebaut, bevor der Benchmark die Lücke gemessen hat, die er
schließen soll — und keiner bleibt, wenn er sie nicht messbar schließt.**

Das ist die Konkretisierung der Messpflicht aus
[`discussion-retrieval-roadmap-opaa.md`](../discussions/discussion-retrieval-roadmap-opaa.md)
(Rahmenbedingung 4) und gilt für jeden Baustein der Phasen 1 bis 3: Reranking, BM25-Ausbau über
`pgroonga`/`pg_search`, `pg_trgm`, Wissensgraph, Parent-Document, Embedding-Modellwechsel.

Der Ablauf ist für jeden Baustein derselbe:

1. **Lücke benennen.** Welches Fehlerbild? Welche Fallklasse misst es?
2. **Lücke messen.** Zeigt der Benchmark diese Lücke auf dem aktuellen Stand? Zahl statt Vermutung.
   Zeigt er sie nicht, ist der Baustein nicht beauftragt — dann fehlen entweder die Fälle (dann
   werden die gebaut) oder das Problem existiert im OPAA-Kontext nicht.
3. **Bauen.** Als Variante, nicht als Default-Wechsel.
4. **Wirkung messen.** Variantenvergleich gegen die Referenz, mit Einzelfall-Deltas, auf allen
   Domänen. Ein Baustein, der eine Domäne verbessert und eine andere regressiert, ist nicht fertig —
   siehe die #933-Messreihen, bei denen genau das zweimal passierte.
5. **Erst dann Default-Wechsel**, mit neuer Baseline und dem Bericht als Beleg in der
   PR-Beschreibung.

### Die Reihenfolge muss nachweisbar sein

Ein Verfahren, das die Lücke erst misst, während der Baustein schon gebaut wird, kann nicht mehr Nein
sagen — es dokumentiert dann eine Entscheidung, statt sie herbeizuführen. Damit die Maschine tatsächlich
ablehnen kann, gilt:

- **Die Fallklasse zu einem Baustein MUSS committet und kuratiert sein, bevor das Bau-Issue eröffnet
  wird.** Nicht „parallel", nicht „im selben PR" — vorher.
- **Die Reihenfolge wird im PR über den Commit-Zeitpunkt des Golden Datasets nachgewiesen.** Das ist
  eine mechanisch prüfbare Angabe und kein Vertrauensakt; sie kostet eine Zeile in der
  PR-Beschreibung.
- **Die Schwelle steht vor dem ersten Vergleich fest.** Für die Eskalationsstufen des lexikalischen
  Pfads ist das der Wert X aus
  [Hybrides Retrieval](./hybrid-retrieval.md#eskalationsstufen-mit-eintrittsbedingung); für jeden
  weiteren Baustein gilt dasselbe Muster. Eine Schwelle, die nach Sichtung der Zahlen gewählt wird, ist
  keine Eintrittsbedingung, sondern eine Beschreibung des Ergebnisses.

Der Preis dieser Regel ist bewusst in Kauf genommen: Sie verzögert den Baubeginn um die Zeit, die das
Kuratieren der Fälle braucht. Genau diese Zeit ist der Punkt, an dem ein Baustein noch abgelehnt werden
kann.

Zwei Klarstellungen, damit der Grundsatz nicht ins Gegenteil kippt:

- **Er gilt für Retrieval-Bausteine, nicht für Fehlerbehebungen.** Ein Bug im RRF wird behoben, nicht
  benchmarkt.
- **Er ist keine Ausrede für Stillstand.** Fehlt für eine plausible Lücke die Fallklasse, ist das
  Ergebnis „Fälle bauen", nicht „Idee verwerfen". Der Benchmark bestimmt die Reihenfolge, nicht das
  Ambitionsniveau.

Der erwartete Nebeneffekt ist der eigentliche Gewinn: Die Diskussion verschiebt sich von „welches
Verfahren ist state of the art?" zu „welche unserer Fragen scheitern und woran?".

---

## Umsetzungsschnitt

Vorschlag des Product Managers für den Zuschnitt in Issues; Reihenfolge ist bindend, weil jeder
Schritt die Grundlage des nächsten legt.

| Schritt | Inhalt | Ergebnis |
|---|---|---|
| A | Pipeline-Messpfad, eigene Baseline-Dateien, ADR-0012-Nachtrag mit neuer Messvertrag-Version | Der Harness misst, was Nutzer erleben |
| B | Variantenmechanik (deklarative Varianten, Variantenbericht, Referenzvarianten-Selbstprüfung) | Konfigurationsvergleiche ohne Handarbeit |
| C | Verwaltungs-Evaldomäne: Generator, Korpus, Manifest, Chunk-Zahl-Erwartung, `MAINTENANCE.md` | Deutschsprachige Amtssprache im Messmaterial, mit benannter Pflegezuständigkeit |
| D | Golden-Fälle der fünf neuen Klassen samt Zustandsfeldern, Kuratierung, erste Baseline der Domäne | **Geliefert (Issue #1043).** Die bekannten Lücken sind beziffert und als `known_gap` benannt |
| E | Mehrfachlauf-Regel für LLM-behaftete Varianten, Aufnahme beider Pfade in den nächtlichen Job | Der Benchmark ist Routine statt Sonderveranstaltung |

Schritt A und B sind unabhängig von C und D und können parallel laufen. Erst nach D ist die
Eintrittsbedingung aus Abschnitt 6 für die Roadmap-Phase 1 tatsächlich prüfbar.

---

## Integrationspunkte

- [`search-quality-evaluation.md`](./search-quality-evaluation.md) — der bestehende Harness; diese
  Spezifikation erweitert ihn, ersetzt ihn nicht.
- [`retrieval-algorithm.md`](./retrieval-algorithm.md) — die Pipeline, durch die der neue Messpfad
  läuft; Parameter und Schrittreihenfolge sind dort maßgeblich.
- [`data-indexing-rag.md`](./data-indexing-rag.md) — Zielbild und Stellschrauben-Tabelle; jede
  Variante bezieht sich auf dort dokumentierte Parameter.
- [`hybrid-retrieval.md`](./hybrid-retrieval.md) — der erste Abnehmer dieses Benchmarks: Dort stehen
  die Eintrittsbedingungen der Eskalationsstufen mitsamt Schwellenwert und das Arbeitspaket
  „Latenz-/Hardwareprofil", das die hier ausgeklammerte Laufzeitmessung trägt.
- [`demo-instance.md`](./demo-instance.md) und `demo/generator/` — Vorbild für den
  Verwaltungskorpus-Generator, ausdrücklich ohne Kopplung der erzeugten Dateien.
- [`eval/README.md`](../../eval/README.md), [`eval/golden/README.md`](../../eval/golden/README.md),
  [`eval/baseline/README.md`](../../eval/baseline/README.md) — Betriebswissen zu Läufen, Datasets und
  Baseline-Aktualisierung; bei Umsetzung fortzuschreiben.
- [`docs/AGENT-ORGANIZATION.md`](../AGENT-ORGANIZATION.md) — der QA Engineer ist Eigentümer der
  RAG-Evaluierung; dieser Ausbau erweitert sein Werkzeug um den Vergleichsfall.

---

## Abgrenzung

Bewusst **nicht** Gegenstand dieses Vorhabens:

- **Statistischer Apparat** (Paired Bootstrap, BCa-Konfidenzintervalle, Sign-Flip-Permutationstest,
  Wilcoxon) aus `discussion-rag-evaluation.md` §7. Begründung vollständig in
  [Abschnitt 3](#3-schlanke-statistik): Die Messung ist bis auf die LLM-Zerlegung deterministisch,
  und für diese genügen drei Läufe mit Median und einer Abweichungszählung.
- **LLM-as-Judge im CI-Pfad.** Ein bewertendes Sprachmodell im nächtlichen Regressionslauf würde die
  Baseline von einem externen, jederzeit still änderbaren Modell abhängig machen — genau die Drift,
  gegen die ADR-0011 (Entscheidung 4) das Einbettungsmodell auf einen Content-Digest pinnt. Als
  Werkzeug für gelegentliche, manuell ausgelöste Analysen außerhalb des Regressionspfads bleibt es
  denkbar.
- **RAGAS-Sidecar** und Generationsmetriken. Unverändert die Position aus ADR-0011 (Entscheidung 3):
  kein Python-Sidecar, solange nur Retrieval gemessen wird. Dieses Vorhaben misst ausschließlich
  Retrieval — es endet vor der Antwortgenerierung — und ändert an dieser Voraussetzung nichts.
- **Antwortqualität.** Faithfulness, Answer Relevancy und die Spring-AI-Evaluatoren bleiben Phase 4
  aus `search-quality-evaluation.md`.
- **Rechtefilterung als Messgegenstand.** Abgedeckt durch Backend-Integrationstests, siehe
  [Abschnitt 1](#1-messpfad-durch-die-produktive-pipeline).
- **Latenz- und Kostenmessung.** Reranking und Hybrid-Suche haben ein Laufzeitbudget, das über ihren
  Einsatz mitentscheidet. Der Benchmark misst Qualität; eine Laufzeitmessung in einem
  Testcontainers-Lauf auf wechselnder CI-Hardware wäre nicht belastbar und würde als Zusage gelesen.
  Sie ist deshalb kein Verzicht, sondern anderswo verortet: als eigenes benanntes Arbeitspaket
  **[Latenz-/Hardwareprofil](./hybrid-retrieval.md#arbeitspaket-latenz-hardwareprofil)** in
  `hybrid-retrieval.md`, gemessen auf definierter Referenzhardware außerhalb der CI und Voraussetzung
  jeder Aktivierungsempfehlung für Reranking.
- **Vergleichbarkeit mit externen Benchmarks** (BEIR, MTEB, GermanQuAD). Reizvoll, aber ein anderer
  Zweck: Diese Benchmarks vergleichen Verfahren über Projekte hinweg, hier geht es um Entscheidungen
  innerhalb von OPAA auf OPAA-typischem Material.

---

## Offene Punkte

1. **Umfang der Verwaltungs-Evaldomäne — beantwortet mit den Issues #1042/#1043 (08/2026).** 70
   Dokumente, 46 Golden-Fälle. Die Größe ist damit um mehr als eine Größenordnung kleiner als bei
   den beiden anderen Domänen, und das ist die belegte Antwort auf die ursprüngliche Frage: Für
   diese Klassen zählt nicht die Menge, sondern die Zahl glaubwürdiger Verwechslungspartner je
   Fall — 62 thematisch nahe, begriffsfreie Konkurrenzdokumente genügen, um
   `literal_term_weak_embedding` vollständig scheitern zu lassen. Offen bleibt nur der
   Nachschärfungsvorbehalt in `eval/corpus/verwaltung/SOURCE.md`: Sollte ein künftiger Baustein
   diese Klasse lösen und der Verdacht aufkommen, die Schlagwort-Verstärkung in
   `verwaltung-0038` habe dabei geholfen, wird der Korpus dort nachgeschärft — mit Neuziehung
   beider Baselines.
2. **Laufzeitbudget des nächtlichen Jobs — entschieden mit Issue #1044 (08/2026).** Gemessen an
   realen CI-Läufen (siehe die Umsetzungsstand-Notiz zu Issue #1044 in Abschnitt 1): Beide
   Regressionspfade bleiben nächtlich für beide Domänen, wie bereits durch #1039/#1040 umgesetzt —
   für `city-landmarks` läuft der Pipeline-Pfad dabei bis zur Baseline-Ziehung in Issue #1081
   bewusst unbeurteilt mit (misst und schreibt seinen Report, ohne dass ein Urteil gefällt wird).
   Variantenvergleiche (Abschnitt 2), einschließlich der Mehrfachlauf-Regel aus Abschnitt 3, bleiben
   ausschließlich manuell ausgelöst (`-Dopaa.eval.runVariantComparison=true`) und werden nicht Teil
   des nächtlichen Jobs — das gemessene Zeitbudget des `city-landmarks`-Regressionslaufs (bis zu ~133
   von 180 Minuten in der langsamsten beobachteten Messung) verträgt die Vervielfachung durch mehrere
   Varianten und dreifache Zerlegungsläufe nicht risikofrei, und Variantenvergleiche sind laut
   Abschnitt 2 ohnehin punktuelle Belege für Roadmap-Entscheidungen, keine laufende Prüfung.
3. **Chat-Modell für den Pipeline-Pfad mit aktiver Zerlegung — Empfehlung mit Issue #1044 (08/2026),
   Umsetzung als Folge-Issue #1085.** Empfehlung: ein lokales, über den bestehenden
   Ollama-Mechanismus bereitgestelltes Instruct-Modell, wie das Einbettungsmodell per Tag **und**
   Content-Digest gepinnt, Temperatur 0 — konsistent mit dem übrigen Determinismus-/Pinning-Ansatz
   dieses Harness (ADR-0011, Entscheidung 4) und ohne neues Secret. Kandidat: ein kleines Modell in
   der Größenordnung von `qwen2.5:1.5b-instruct` oder `llama3.2:3b-instruct`, endgültige Auswahl
   anhand tatsächlicher Zerlegungsqualität an Golden-Fällen. Akzeptierte Nachteile: zusätzliche
   Laufzeit (durch die Mehrfachlauf-Regel aus Abschnitt 3 je Zerlegungsvariante verdreifacht) und eine
   Zerlegungsqualität, die nicht repräsentativ für das tatsächliche Produktionsmodell ist — der
   Benchmark misst dann die Zerlegungs**mechanik**, nicht die Zerlegungs**güte** des
   Produktionsmodells. Ein gehostetes Modell (Secret, Kosten, Driftrisiko) und ein eingefrorener
   Zerlegungs-Cache (vollständig deterministisch, misst Zerlegung aber gar nicht mehr) wurden
   erwogen und zugunsten des lokalen Modells zurückgestellt. Die formale Freigabe dieser Empfehlung
   und ihre Umsetzung (Chat-Modell-Bean im Eval-Kontext, Modell-Cache in der Workflow-Datei,
   Prerequisite-Lockerung, erste Baseline mit aktiver Zerlegung) sind Gegenstand von Issue #1085.
4. **Umgang mit `answer_span` bei Fallklassen mit mehreren Zieldokumenten — entschieden mit Issue
   #1043 (08/2026).** Die Chunkebenen-Metrik wird **je Fall** gebildet, und ein `answer_span` ist
   nur bei Fällen mit **genau einem** erwarteten Dokument zulässig; mehrdokumentige Fälle
   (`multi_hop` und die mehrdokumentigen `compound_word`-Fälle) tragen keinen. Begründung: Ein
   einzelner Span auf einem Fall, dessen Antwort über zwei Dokumente verteilt ist, misst eine
   Hälfte und meldet sie als Ergebnis des ganzen Falls — ein Fall, den die Dokumentebene zu Recht
   als Fehlschlag führt, sähe auf Chunkebene erfolgreich aus. Die Alternative (eine Span-Liste je
   Dokument) wäre eine neue Metrik mit eigener Aggregation über Dokumente innerhalb eines Falls,
   eigener Trefferdefinition und einer Erhöhung beider Vertragsversionen; kein heutiger
   Messgegenstand braucht sie, weil die Chunkebene für Chunking-Vergleiche existiert und
   Einzeldokument-Fälle das bereits bedienen. Die Regel entspricht dem, was `city-landmarks`
   bereits praktiziert, ist aber jetzt geprüft (`GoldenCaseCuration`) statt Gewohnheit;
   festgeschrieben im Nachtrag zu [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md).
   Da sich an keiner gemessenen Größe etwas ändert, bleiben beide Vertragsversionen unverändert.
5. **Aufnahme der neuen Fallklassen in bestehende Domänen.** Fachlich naheliegend für Kennungen und
   Komposita in `city-landmarks`; kostet aber eine Baseline-Neuziehung dort. Offen, ob dieser Preis
   sich lohnt oder die Klassen der Verwaltungsdomäne vorbehalten bleiben.
