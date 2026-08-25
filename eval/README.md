# Evaluierungskorpus

Enthält die Testkorpora für die Suchqualitäts-Evaluierung (siehe
[`docs/features/search-quality-evaluation.md`](../docs/features/search-quality-evaluation.md) und
[ADR-0011](../docs/decisions/0011-search-quality-evaluation-harness.md)). Dieses Verzeichnis liegt
bewusst außerhalb des Gradle-Builds und der CI — die Generatoren laufen nur bei bewussten
Korpus-Änderungen, nie automatisch.

```
eval/
├── generator/                       Python-Werkzeuge, ein Skript je Domäne
│   ├── generate_corpus.py                    Domäne Comichelden (Issue #225)
│   ├── generate_golden_dataset.py            Golden Dataset für dieselbe Domäne (Issue #226)
│   ├── generate_city_landmarks_corpus.py     Domäne Sehenswürdigkeiten (Issue #234)
│   ├── frozen/                                eingefrorene Wikidata-SPARQL-Rohdaten, committet (Issue #234)
│   ├── README.md                    Reproduktionsanleitung
│   └── raw-source/                  gecachte Rohdaten, gitignored
├── corpus/                          generierte Markdown-Dokumente, committet
│   ├── comic-characters/
│   │   ├── *.md                     ein Dokument je Entität
│   │   ├── MANIFEST.sha256          SHA-256 über alle Dokumente dieser Domäne
│   │   └── SOURCE.md                Quelle, Lizenz, Abrufdatum
│   └── city-landmarks/
│       ├── *.md                     ein Dokument je Stadt (mehrchunkig, deutsch)
│       ├── MANIFEST.sha256
│       └── SOURCE.md
└── golden/                          Golden-Query-Datasets, committet (siehe eval/golden/README.md)
    ├── comic-characters.json
    └── city-landmarks.json
```

Aktuell umgesetzt: die Domänen **Comichelden** (Issue #225/#226, einchunkig) und **Sehenswürdigkeiten
in europäischen Großstädten** (Issue #234, bewusst mehrchunkig, deutschsprachig). Die ursprünglich für
Phase 2 vorgesehenen weiteren Domänen (Filme, Reiseziele, Tiere) sind gestrichen (Maintainer-
Entscheidung vom 21.08.2026, Issue #234) — Begründung siehe
`docs/features/search-quality-evaluation.md`, Abschnitt „Domänen und was sie prüfen sollen".

## Retrieval-Evaluation ausführen (Issue #227)

Der eigentliche Metrik-Harness liegt **im Backend**, nicht hier — er ist ein JUnit-Integrationstest
unter `backend/src/evalTest/java/io/opaa/eval/`, weil er die produktive Indizierungs-Pipeline
(`io.opaa.indexing`) direkt gegen Testcontainers laufen lässt (siehe ADR-0011, Entscheidung 3).
Dieses Verzeichnis liefert ihm nur die Eingaben: Korpus, Manifest, Golden Dataset.

```bash
cd backend
./gradlew evaluateRetrieval               # comic-characters
./gradlew evaluateCityLandmarksRetrieval  # city-landmarks (Issue #234)
```

Beide sind eigenständige Tasks mit eigenem Report, eigener Baseline-Datei
(`eval/baseline/comic-characters.json` bzw. `eval/baseline/city-landmarks.json`) und eigener
Testklasse (`RetrievalEvaluationHarnessTest` bzw. `CityLandmarksRetrievalEvaluationHarnessTest`) —
bewusst kein parametrisierter, gemeinsamer Lauf (siehe Javadoc von
`CityLandmarksRetrievalEvaluationHarnessTest`): Ein Fehlschlag ist damit immer eindeutig einer
Domäne zugeordnet, und die comic-characters-Baseline bleibt von der zweiten Domäne unberührt.
Analog dazu `checkRetrievalBaseline`/`checkCityLandmarksRetrievalBaseline`.

Beide sind ein **eigener Gradle-Task, nicht Teil von `./gradlew build`/`test`**. Er läuft in
einem eigenen Source-Set (`src/evalTest/`), das an keiner Stelle in `build`/`test` verdrahtet ist
— ein normaler Entwicklerlauf wird dadurch nicht langsamer. Grund für den eigenen Task: Der Lauf
braucht Docker, zieht zwei Testcontainer (`pgvector/pgvector:pg18`, `ollama/ollama:0.6.5`), lädt
das Embedding-Modell `nomic-embed-text:v1.5` (~275 MB, fest auf diesen Versions-Tag gepinnt, siehe
unten) und indiziert danach rund 1.450 Dokumente über die echte Chunking-/Embedding-Pipeline —
mehrere Minuten, auch mit warmem Modell-Cache.

Voraussetzungen: Docker (für Testcontainers) und eine Internetverbindung.

### Unit-Tests für die Metrikmathematik

`RetrievalMetrics`, `MetricsAggregate` und `CorpusManifest` (Hit Rate/MRR/nDCG/Recall-Berechnung,
Aggregation, Manifest-Prüfung) sind reine, Docker-freie Java-Klassen — bewusst weiterhin im
`evalTest`-Source-Set statt in `main`, damit reiner Evaluierungscode nicht im Produktions-Jar
landet. Ihre Unit-Tests laufen über einen eigenen, schnellen Gradle-Task, der **Teil von
`./gradlew check` ist** und ohne Docker auskommt:

```bash
./gradlew evalUnitTest
```

`evalUnitTest` führt gezielt alle Testklassen im `evalTest`-Source-Set **außer**
`RetrievalEvaluationHarnessTest` aus (die braucht Testcontainers und bleibt exklusiv
`evaluateRetrieval` vorbehalten). `./gradlew check` hängt `evalUnitTest` als Abhängigkeit an — die
Metrikmathematik ist damit sowohl unit-getestet als auch bei jedem CI-Lauf kompiliert und
ausgeführt, ohne dass `check`/`build` Docker braucht oder langsamer wird (~1 s zusätzlich).

### Modell-Cache

Der Ollama-Testcontainer bindet ein benanntes Docker-Volume (`opaa-eval-ollama-models`) unter
`/root/.ollama` ein. Auf einer Entwickler-Maschine überlebt das gepullte Modell dadurch mehrere
`./gradlew evaluateRetrieval`-Läufe — nur der allererste Lauf zieht die ~275 MB. Auf einem
**ephemeren CI-Runner** (der bei jedem Job neu startet) gibt es diese Persistenz nicht: Dort wird
bei jedem Lauf neu gepullt, sofern der Runner selbst kein Docker-Volume-Caching zwischen Jobs
anbietet. Das war in einer früheren Fassung dieses Dokuments unzutreffend als "danach reicht der
lokale Cache" beschrieben, ohne dass ein Volume oder `commitToImage` existierte — korrigiert mit
Issue #227-Review.

### Modell-Pinning

`nomic-embed-text` ohne Tag löst auf `:latest` auf, das sich mit der Zeit ändern kann (ADR-0011,
Entscheidung 4 verlangt eine feste Modellversion). Der Harness pinnt deshalb zweistufig:

1. **Tag-Pin**: `nomic-embed-text:v1.5` statt `nomic-embed-text`.
2. **Digest-Assertion**: Nach dem Pull liest der Harness den Content-Digest über
   `GET /api/tags` vom Ollama-Container aus und vergleicht ihn gegen einen im Code hinterlegten
   erwarteten Wert (`EXPECTED_EMBEDDING_MODEL_DIGEST`). Weicht er ab — z. B. weil der Tag `v1.5`
   selbst nachträglich neu veröffentlicht wurde —, bricht der Lauf mit einer klaren
   Drift-Fehlermeldung ab, statt still eine andere Baseline zu messen. `RunConfiguration` im Report
   führt sowohl Tag als auch Digest, damit sich zwei Reports auch nachträglich auf Modellgleichheit
   prüfen lassen.

### CPU statt GPU

Testcontainers' `OllamaContainer` fordert automatisch eine GPU an, sobald der Docker-Daemon
irgendeine `nvidia`-Runtime *auflistet* — unabhängig davon, ob sie funktioniert. Der Harness
entfernt diese Anforderung bewusst und bleibt auf CPU. Das ist nicht nur ein Workaround für kaputte
GPU-Passthroughs, sondern für die Baseline sogar **vorteilhaft**: CPU- und GPU-Kernel liefern nicht
notwendigerweise bitgleiche Embeddings, ein GPU-Lauf wäre also nicht direkt mit CI oder mit Läufen
auf anderen Maschinen vergleichbar. Für lokale Experimente mit GPU-Embeddings gibt es ein Opt-out
über `-Dopaa.eval.allowGpu=true`; das Verhalten ist zusätzlich über eine Assertion auf eine leere
`DeviceRequests`-Liste abgesichert, damit ein künftiges Testcontainers-Upgrade eine stillschweigend
wieder aktivierte GPU-Anforderung nicht unbemerkt durchlässt.

### Was der Lauf tut

1. Prüft `eval/corpus/comic-characters/MANIFEST.sha256` gegen die tatsächlichen Korpusdateien und
   bricht mit einer benannten Fehlermeldung ab, falls auch nur ein Byte abweicht (ADR-0011,
   Entscheidung 1 und 6).
2. Indiziert die Korpusdateien über die reguläre Pipeline (`FileProcessingService`/
   `ChunkingService`, Ollama-Embedding). `chunkSize` wird **nicht** vom Harness vorgegeben, sondern
   aus der laufenden Anwendungskonfiguration gelesen (`IndexingProperties.chunkSize()`) — siehe
   ADR-0010. Der Harness assertiert diesen Wert zusätzlich gegen den zum Zeitpunkt der letzten
   Baseline bekannten Anwendungsdefault (1000); weicht er ab, bricht der Lauf ab, statt still mit
   einer möglicherweise nicht mehr gültigen Ein-Chunk-Invariante weiterzumessen.
3. Prüft die **Chunk-Zahl-Invariante** der jeweiligen Domäne (ADR-0010, Nachtrag Issue #721):
   `comic-characters` verlangt weiterhin genau einen Chunk je Dokument (Ein-Chunk-Invariante,
   unverändert). Das ist die beweiskräftige Prüfung, die die Byte-Vorabprüfung im Generator
   (`MAX_DOCUMENT_BYTES`) nicht liefern kann — siehe ADR-0010.
4. Führt jeden Fall aus `eval/golden/comic-characters.json` direkt gegen `VectorStore.similaritySearch`
   aus — kein LLM, keine `QueryService`-Anbindung. Das Suchfenster ist ausdrücklich
   **dokumentbezogen** (ADR-0012 Nachtrag, Issue #721): `io.opaa.eval.DocumentRanking` dedupliziert
   die Chunk-Treffer zu Dokumenten (Rang eines Dokuments = Rang seines bestplatzierten Chunks —
   vormals die private `dedupeByFileName`, jetzt explizit gemacht) und stellt sicher, dass
   `documentTopK=10` unterschiedliche Dokumente im Fenster stehen, nicht nur zehn Chunks. Für
   `comic-characters` (`maxChunksPerDocument=1`) ist das bitgleich zum bisherigen Verhalten.
5. Berechnet Hit Rate@5, MRR, nDCG@10 und Recall@10 auf Dokumentebene, gesamt sowie aufgeschlüsselt
   nach Kategorie, Schwierigkeit und Sprache, und schreibt einen Report. Zusätzlich (nur für Domänen
   mit `answer_span`-Fällen — `comic-characters` hat keine): eine zweite Metrikfamilie auf
   Chunkebene, `io.opaa.eval.ChunkAnswerSpanMetrics` (`answerSpanHitRate@5`, Rang des ersten
   Treffer-Chunks). Der Lauf schreibt außerdem eine Chunk-Map
   (`build/eval-reports/chunk-map-<domäne>.json`, nicht committet): welches Dokument in wie viele
   Chunks zerfiel und an welchen Zeichenpositionen die Grenzen lagen.

### Report lesen

Der Lauf schreibt zwei Dinge:

- **Konsolen-Zusammenfassung** (auf `System.out`/im Test-Log): Konfiguration des Laufs, Ergebnis der
  Ein-Chunk-Prüfung, die vier Metriken gesamt und je Gruppe, sowie die zehn schlechtesten Anfragen
  nach nDCG@10 mit erwarteter und tatsächlich gefundener Dokumentmenge — für Debugging, nicht nur
  eine Note.
- **Maschinenlesbarer JSON-Report** unter `backend/build/eval-reports/retrieval-metrics.json`
  (nicht committet, wird bei jedem Lauf neu geschrieben). Enthält dieselben Daten strukturiert, plus
  die SHA-256-Summen von Korpus-Manifest und Golden Dataset, den Embedding-Modell-Digest, die
  Messvertrag-Version (siehe [ADR-0012](../docs/decisions/0012-messvertrag-retrieval-harness.md))
  und `allQueryResults` — die Ergebnisse **aller** 121 Anfragen, nicht nur der zehn schlechtesten,
  damit sich auch nicht-triviale Fälle aus dem Report nachrechnen lassen.

Jede `MetricsAggregate`-Gruppe führt neben den vier Kernmetriken auch `recallAt10Ceiling`: die
höchste bei dieser Gruppe erreichbare Recall@10, gegeben wie viele Fälle mehr als zehn erwartete
Dokumente haben (dort ist Recall@10=1,0 selbst bei perfektem Ranking unerreichbar). Ein rohes
Recall@10 ohne diese Obergrenze verzerrt jede Schwellensetzung in #228.

Die `similarityThreshold` aus der Produktivkonfiguration (`opaa.query.similarity-threshold`) wird im
Report nur informativ ausgewiesen — die Suchen im Lauf selbst verwenden `threshold=0.0`, weil die
Ranking-Metriken die vollständige, ungefilterte Top-k-Reihenfolge brauchen.

### Was dieser Korpus nicht messen kann

Die Ein-Chunk-Invariante hat eine Kehrseite, die bei jeder Auswertung mitgedacht werden muss: **Alles,
was erst zwischen zwei Chunks eines Dokuments wirkt, ist hier unsichtbar.** Das betrifft insbesondere
die Chunk-Überlappung (`opaa.indexing.chunk-overlap`, Issue #374). Läufe mit 0, 100 und 200 Token
Überlappung liefern über alle 121 Fälle bitgleiche Ergebnisse — nicht weil die Überlappung nichts
brächte, sondern weil es in diesem Korpus keine einzige Chunk-Grenze gibt, an der sie greifen könnte.

Der Report führt den verwendeten Wert deshalb als `chunkOverlap` mit: Zwei Reports lassen sich damit
auseinanderhalten, aber ein Vergleich beantwortet die Frage nicht. Eine belastbare Aussage über die
Überlappung braucht Referenzfälle an mehrchunkigen Dokumenten, also einen eigenen Teilkorpus.

**Update (Issue #721): Der Harness selbst kann das inzwischen messen — dieser Korpus weiterhin
nicht.** `RetrievalEvaluationHarnessTest` unterstützt seit #721 mehrchunkige Domänen: ein
dokumentbezogenes k-Fenster (`io.opaa.eval.DocumentRanking`), eine je Domäne konfigurierbare
Chunk-Zahl-Erwartung (`io.opaa.eval.ChunkCountExpectation`, ADR-0010 Nachtrag) und eine zweite
Metrikfamilie auf Chunkebene über eingefrorene Antwort-Textausschnitte
(`io.opaa.eval.ChunkAnswerSpanMetrics`, ADR-0012 Nachtrag). `comic-characters` bleibt bewusst
einchunkig (`ChunkCountExpectation.exactlyOneChunk()`) — die oben beschriebene Lücke schließt erst die
mehrchunkige Domäne aus #234, sobald deren Korpus und Golden Dataset vorliegen. Details zum
Messvertrag: [ADR-0012, Nachtrag](../docs/decisions/0012-messvertrag-retrieval-harness.md#nachtrag-dokumentbezogenes-k-fenster-und-chunkebene-issue-721).

### Kalibrierungshinweis

Der Korpus ist absichtlich uniform (siehe `eval/golden/README.md`, Abschnitt „Kalibrierungshinweis
für #227/#228"): Ein Jaccard-Median von 0,51 über ganze Dokumente staucht die Score-Verteilung und
macht Hit Rate/MRR unempfindlicher gegenüber echten Regressionen als bei einem heterogenen Korpus.
Schwellenwerte für eine künftige CI-Regression (#228) sind deshalb gegen die hier tatsächlich
gemessene Verteilung zu kalibrieren, nicht gegen Erfahrungswerte aus anderen Korpora. Ebenso gilt:
Die Fallzahl im Golden Dataset überschätzt die Zahl unabhängiger Beobachtungen — mehrere Fälle teilen
sich eine Erwartungsmenge, und jeder `crosslingual`-Fall ist konstruktionsbedingt der deutsche
Zwilling eines englischen Falls. Der Report weist das über `datasetNotes` aus.

**Der Sprachvergleich (de vs. en) ist mit Vorsicht zu lesen.** Ein niedrigerer nDCG@10 für Deutsch
ist überwiegend ein Artefakt der Fragetypverteilung, nicht (nur) ein Aussage über Mehrsprachigkeit:
Der deutsche Teilkorpus (ausschließlich `crosslingual`-Fälle) enthält einen höheren Anteil
`hard`-Fälle als der englische Teilkorpus. Innerhalb derselben Schwierigkeitsstufe (`easy`) treffen
die deutschen Zwillingsfragen mit Hit Rate 1,000 genauso gut wie die englischen. Vor einer Aussage
über Sprachqualität in #228 eine Kreuztabelle Sprache × Schwierigkeit bilden (die `allQueryResults`
im JSON-Report liefern dafür alle nötigen Rohdaten) statt die aggregierten Je-Sprache-Zahlen direkt
zu interpretieren.

### Baseline und CI

Issue #227 lieferte den Harness und die erstmals gemessenen Zahlen. Issue #228 hat darauf die feste
Baseline, die Schwellenwerte und die CI-Anbindung aufgesetzt:

- Baseline und Update-Verfahren: [`eval/baseline/README.md`](baseline/README.md).
- CI-Workflow: [`.github/workflows/retrieval-regression.yml`](../.github/workflows/retrieval-regression.yml)
  — nächtlich auf `main`, manuell über `workflow_dispatch` und per Label `evaluation` an einem Pull
  Request; niemals bei jedem Pull Request automatisch.
- Vergleichslogik: `io.opaa.eval.BaselineComparator`, ausgeführt über den Gradle-Task
  `checkRetrievalBaseline` (führt `evaluateRetrieval` aus und vergleicht das Ergebnis anschließend
  gegen `eval/baseline/comic-characters.json`).

### Messvertrag

Was genau gemessen wird — Gain-Funktion, IDCG-Basis, die ungleichen Fenster von Hit Rate@5 und
nDCG@10, dass ohne Ähnlichkeitsschwelle gemessen wird statt mit der Produktionskonfiguration
(`top-k=8`/`threshold=0,3`, seit #914; zuvor `top-k=5`), wie die Recall-Obergrenze bei `|E|>k` gehandhabt wird, dass Mikro- statt
Makro-Mittel gebildet wird, und (seit Messvertrag-Version 2, Issue #721) dass das k-Fenster
ausdrücklich dokumentbezogen ist und eine zweite Metrikfamilie auf Chunkebene existiert — ist in
[ADR-0012](../docs/decisions/0012-messvertrag-retrieval-harness.md) festgehalten, nicht nur im
Code. Jeder Report führt die Version dieses Messvertrags (`measurementContractVersion`); eine
künftige Änderung an einer dieser Festlegungen muss die Version erhöhen, damit historische Reports
nicht stillschweigend unvergleichbar werden.
