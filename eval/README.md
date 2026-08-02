# Evaluierungskorpus

Enthält die Testkorpora für die Suchqualitäts-Evaluierung (siehe
[`docs/features/search-quality-evaluation.md`](../docs/features/search-quality-evaluation.md) und
[ADR-0011](../docs/decisions/0011-search-quality-evaluation-harness.md)). Dieses Verzeichnis liegt
bewusst außerhalb des Gradle-Builds und der CI — die Generatoren laufen nur bei bewussten
Korpus-Änderungen, nie automatisch.

```
eval/
├── generator/                       Python-Werkzeuge, ein Skript je Domäne
│   ├── generate_corpus.py           Domäne Comichelden (Issue #225)
│   ├── generate_golden_dataset.py   Golden Dataset für dieselbe Domäne (Issue #226)
│   ├── README.md                    Reproduktionsanleitung
│   └── raw-source/                  gecachte Rohdaten, gitignored
├── corpus/                          generierte Markdown-Dokumente, committet
│   └── comic-characters/
│       ├── *.md                     ein Dokument je Entität
│       ├── MANIFEST.sha256          SHA-256 über alle Dokumente dieser Domäne
│       └── SOURCE.md                Quelle, Lizenz, Abrufdatum
└── golden/                          Golden-Query-Datasets, committet (siehe eval/golden/README.md)
    └── comic-characters.json
```

Aktuell umgesetzt: die Domäne **Comichelden** (Issue #225/#226). Die weiteren drei Domänen (Filme,
Reiseziele, Tiere) folgen über denselben Aufbau (Issue #234).

## Retrieval-Evaluation ausführen (Issue #227)

Der eigentliche Metrik-Harness liegt **im Backend**, nicht hier — er ist ein JUnit-Integrationstest
unter `backend/src/evalTest/java/io/opaa/eval/`, weil er die produktive Indizierungs-Pipeline
(`io.opaa.indexing`) direkt gegen Testcontainers laufen lässt (siehe ADR-0011, Entscheidung 3).
Dieses Verzeichnis liefert ihm nur die Eingaben: Korpus, Manifest, Golden Dataset.

```bash
cd backend
./gradlew evaluateRetrieval
```

Das ist ein **eigener Gradle-Task, nicht Teil von `./gradlew build`/`test`/`check`**. Er läuft in
einem eigenen Source-Set (`src/evalTest/`), das an keiner Stelle in `build`/`check` verdrahtet ist
— ein normaler Entwicklerlauf wird dadurch nicht langsamer. Grund: Der Lauf braucht Docker, zieht
zwei Testcontainer (`pgvector/pgvector:pg18`, `ollama/ollama:0.6.5`), lädt beim ersten Mal das
Embedding-Modell `nomic-embed-text` (~275 MB) und indiziert danach rund 1.450 Dokumente über die
echte Chunking-/Embedding-Pipeline — mehrere Minuten, auch mit warmem Modell-Cache.

Voraussetzungen: Docker (für Testcontainers) und eine Internetverbindung beim allerersten Lauf
(Image- und Modell-Pull; danach reicht der lokale Docker-/Ollama-Cache).

### Was der Lauf tut

1. Prüft `eval/corpus/comic-characters/MANIFEST.sha256` gegen die tatsächlichen Korpusdateien und
   bricht mit einer benannten Fehlermeldung ab, falls auch nur ein Byte abweicht (ADR-0011,
   Entscheidung 1 und 6).
2. Indiziert die Korpusdateien über die reguläre Pipeline
   (`FileProcessingService`/`ChunkingService`, `chunkSize=1000`, Ollama-Embedding).
3. Prüft die **Ein-Chunk-Invariante** (ADR-0010): Jedes Korpusdokument muss nach dem echten
   `TokenTextSplitter`-Lauf genau einen Chunk ergeben. Das ist die beweiskräftige Prüfung, die die
   Byte-Vorabprüfung im Generator (`MAX_DOCUMENT_BYTES`) nicht liefern kann — siehe ADR-0010.
4. Führt jeden Fall aus `eval/golden/comic-characters.json` direkt gegen
   `VectorStore.similaritySearch(topK=10)` aus — kein LLM, keine `QueryService`-Anbindung.
5. Berechnet Hit Rate@5, MRR, nDCG@10 und Recall@10, gesamt sowie aufgeschlüsselt nach Kategorie,
   Schwierigkeit und Sprache, und schreibt einen Report.

### Report lesen

Der Lauf schreibt zwei Dinge:

- **Konsolen-Zusammenfassung** (auf `System.out`/im Test-Log): Konfiguration des Laufs, Ergebnis der
  Ein-Chunk-Prüfung, die vier Metriken gesamt und je Gruppe, sowie die zehn schlechtesten Anfragen
  nach nDCG@10 mit erwarteter und tatsächlich gefundener Dokumentmenge — für Debugging, nicht nur
  eine Note.
- **Maschinenlesbarer JSON-Report** unter `backend/build/eval-reports/retrieval-metrics.json`
  (nicht committet, wird bei jedem Lauf neu geschrieben). Enthält dieselben Daten strukturiert, plus
  die SHA-256-Summen von Korpus-Manifest und Golden Dataset, mit denen sich ein Report eindeutig auf
  den Stand zurückführen lässt, der ihn erzeugt hat.

Die `similarityThreshold` aus der Produktivkonfiguration (`opaa.query.similarity-threshold`) wird im
Report nur informativ ausgewiesen — die Suchen im Lauf selbst verwenden `threshold=0.0`, weil die
Ranking-Metriken die vollständige, ungefilterte Top-k-Reihenfolge brauchen.

### Kalibrierungshinweis

Der Korpus ist absichtlich uniform (siehe `eval/golden/README.md`, Abschnitt „Kalibrierungshinweis
für #227/#228"): Ein Jaccard-Median von 0,51 über ganze Dokumente staucht die Score-Verteilung und
macht Hit Rate/MRR unempfindlicher gegenüber echten Regressionen als bei einem heterogenen Korpus.
Schwellenwerte für eine künftige CI-Regression (#228) sind deshalb gegen die hier tatsächlich
gemessene Verteilung zu kalibrieren, nicht gegen Erfahrungswerte aus anderen Korpora. Ebenso gilt:
Die Fallzahl im Golden Dataset überschätzt die Zahl unabhängiger Beobachtungen — mehrere Fälle teilen
sich eine Erwartungsmenge, und jeder `crosslingual`-Fall ist konstruktionsbedingt der deutsche
Zwilling eines englischen Falls. Der Report weist das über `datasetNotes` aus.

### Baseline und CI

Dieses Issue (#227) liefert den Harness und die erstmals gemessenen Zahlen. Eine feste Baseline,
Schwellenwerte und die CI-Anbindung (nächtlich auf `main`, per Label an einem PR) folgen in #228.
