# Variantenvergleiche

Deklarative Konfigurationsvergleiche für den Pipeline-Messpfad (issue #1041,
[`docs/features/retrieval-benchmark.md`](../../docs/features/retrieval-benchmark.md), Abschnitt 2
„Benannte Pipeline-Varianten“). Eine Datei hier ist ein **Variantenvergleich**: ein Name, eine
Domäne (siehe `eval/corpus/`) und eine Liste benannter Varianten, von denen eine als
Referenzvariante markiert ist. Ein neuer Vergleich ist eine neue Datei — kein neuer Java-Code.

## Schema

```json
{
  "name": "comic-characters-selection-mechanics",
  "description": "...",
  "domain": "comic-characters",
  "referenceVariant": "production",
  "variants": [
    {
      "name": "production",
      "description": "...",
      "requiresReindex": false,
      "queryOverrides": {}
    },
    {
      "name": "mmr-0.7",
      "description": "...",
      "requiresReindex": false,
      "queryOverrides": { "mmrLambda": 0.7 }
    }
  ]
}
```

- `referenceVariant` muss der Name einer der `variants` sein und selbst ausführbar sein (siehe
  unten) — jedes Delta im Bericht ist gegen sie gepaart.
- `queryOverrides` ist ein partielles Override von `io.opaa.query.QueryProperties`: ein
  weggelassenes oder `null`-Feld übernimmt den Produktionswert unverändert. Unterstützte Felder:
  `fetchK`, `mmrLambda`, `similarityThreshold`, `queryDecompositionEnabled`, `maxSubQueries`,
  `maxChunksPerDocument`, `fullTextSearchEnabled` (seit Issue #1049). `topK` ist bewusst kein
  Feld — die Metriknamen des Pipeline-Pfads (`hitRateAt5`, `ndcgAt8`, …) sind an das
  Produktionsfenster gebunden (siehe `retrieval-benchmark.md`, Abschnitt 1).
- Eine Variante mit `fullTextSearchEnabled: true` wird als „nicht ausgeführt" gemeldet, solange der
  Volltext-Backfill der gemessenen Bibliothek unvollständig ist: Das Backfill-Tor hielte die
  Bibliothek dann vollständig aus dem lexikalischen Pfad heraus, und die Variante hätte die
  vector-only-Konfiguration unter dem Namen der hybriden gemessen.
- `requiresReindex: true` markiert eine Variante, die ein anderes Embedding-Modell oder eine
  andere Chunking-Konfiguration bräuchte. Diese Variantenmechanik führt noch keinen Reindex je
  Variante aus — eine solche Variante wird als „nicht ausgeführt“ gemeldet, nicht stillschweigend
  gegen den falschen Index gemessen (Abnahmekriterium des Issues).
- Eine Variante, deren effektive Konfiguration `queryDecompositionEnabled=true` ergibt, wird
  ebenfalls als „nicht ausgeführt“ gemeldet: Der Harness-Kontext hat kein Chat-Modell (siehe
  `PipelineHarnessSupport#requireMeasurableConfiguration`). Sobald ein Chat-Modell verfügbar ist
  (Issue #1085), greift für eine solche Variante automatisch die Mehrfachlauf-Regel unten.

## Mehrfachlauf-Regel für Varianten mit LLM-Anteil

Issue #1044, [`docs/features/retrieval-benchmark.md`](../../docs/features/retrieval-benchmark.md),
Abschnitt 3 „Schlanke Statistik": Eine Variante mit effektiv aktivierter Teilfragen-Zerlegung ist
nichtdeterministisch und läuft deshalb dreimal statt einmal (`io.opaa.eval.VariantRunner`,
`io.opaa.eval.MultiRunAggregator`). Der Bericht führt je Metrik Minimum, Median und Maximum sowie
die Zahl der Golden-Fälle, bei denen sich die von der Zerlegung erzeugten Teilfragen zwischen den
drei Läufen unterschieden haben — die eigentliche Kennzahl der Instabilität. Für den Delta-Vergleich
gegen die Referenzvariante zählt der **Median-Lauf**, keine Mittelwertbildung über die drei Läufe.
Jede Variante ohne LLM-Anteil bleibt bei einem Lauf; ein abweichender zweiter Lauf wäre dort ein
Befund, kein Anlass für Statistik. Solange keine Zerlegungsvariante ausführbar ist (siehe oben), ist
diese Regel über `MultiRunAggregatorTest` mit synthetischen Reports abgesichert statt über einen
echten Lauf.

## Nächtlich vs. manuell

Variantenvergleiche laufen **nicht** im nächtlichen Regressionsjob
(`.github/workflows/retrieval-regression.yml`) — nur die beiden Regressionspfade (Rohvektor,
Pipeline) tun das, mit fester, committeter Baseline. Diese Aufteilung ist mit Issue #1044 anhand
gemessener Laufzeiten des nächtlichen Jobs bewusst getroffen worden (Details:
`docs/features/retrieval-benchmark.md`, „Offene Punkte" 2): Der `city-landmarks`-Regressionslauf
nutzt im ungünstigsten beobachteten Fall bereits rund drei Viertel seines Zeitbudgets, und ein
Variantenvergleich mit mehreren Varianten — dazu die Verdreifachung jeder Zerlegungsvariante durch
die Mehrfachlauf-Regel — würde dieses Budget beliebig vervielfachen. Ein Variantenvergleich läuft
deshalb ausschließlich manuell aus, wie im Abschnitt „Ausführen" oben beschrieben.

## Ausführen

Ein Variantenvergleich läuft als zusätzlicher, standardmäßig **abgeschalteter** Schritt am Ende des
Harness-Laufs **jeder** der drei Domänen (`io.opaa.eval.VariantComparisonStep`, seit Issue #1049 in
allen drei Harnessen statt nur in `RetrievalEvaluationHarnessTest`), nachdem der Korpus bereits
indiziert und der Rohvektor- sowie der Pipeline-Pfad bereits gemessen wurden — kein zweiter
Indizierungslauf, keine zusätzliche Belastung der nächtlichen Baseline-Prüfung:

```bash
cd backend
./gradlew evaluateRetrieval -Dopaa.eval.runVariantComparison=true
./gradlew evaluateVerwaltungRetrieval -Dopaa.eval.runVariantComparison=true
./gradlew evaluateCityLandmarksRetrieval -Dopaa.eval.runVariantComparison=true

# Anderer Vergleich derselben Domäne: Datei explizit angeben
./gradlew evaluateRetrieval -Dopaa.eval.runVariantComparison=true \
  -Dopaa.eval.variantComparisonFile=eval/variants/comic-characters-lexical-path.json
```

`opaa.eval.variantComparisonFile` ist relativ zum Repository-Wurzelverzeichnis und optional: Jede
Domäne hat eine Voreinstellung (`comic-characters-selection-mechanics.json` für comic-characters,
`<domäne>-lexical-path.json` für die beiden anderen). Ein neuer Vergleich ist damit ohne
Codeänderung auslösbar: eine neue JSON-Datei hier, dieselbe Kommandozeile mit einem anderen Pfad.

Der Bericht wird nach `build/eval-reports/variant-report-<comparisonName>.json` geschrieben und
zusätzlich als lesbare Zusammenfassung auf der Konsole ausgegeben — ein Artefakt, kein
committetes Ergebnis (siehe `docs/features/retrieval-benchmark.md`, Abschnitt 2, „Der Bericht ist
ein Artefakt, keine Baseline").

### Wirkungsnachweis des lexikalischen Pfads (Issue #1049)

`comic-characters-lexical-path.json`, `verwaltung-lexical-path.json` und
`city-landmarks-lexical-path.json` stellen `vector+fulltext-rrf` (die Produktion seit #1049) gegen
`vector-only` (den Stand davor). Referenzvariante ist die **Produktions**variante, weil die
Selbstprüfung unten das verlangt; die ausgewiesenen Deltas sind deshalb `vector-only` minus hybrid —
ein negatives Delta ist ein Gewinn des lexikalischen Pfads. Dass die `vector-only`-Variante exakt die
Zahlen der bis #1049 committeten Pipeline-Baseline reproduziert, ist der eigentliche Beleg: Die
Verschiebung kommt vom Volltextpfad und nicht von einem Nebeneffekt derselben Änderung.

## Referenzvarianten-Selbstprüfung

Die Referenzvariante (keine Parameteränderung) wird im selben Testlauf sowohl über die
Variantenmechanik als auch direkt über das produktiv verdrahtete, `@Autowired` `QueryService`-Bean
gemessen (dasselbe Bean, mit dem der Pipeline-Pfad in Schritt 6 bereits misst) — nicht über eine
zweite, handgebaute `QueryService`-Instanz, denn das würde nur Determinismus belegen, nicht dass
die Variantenmechanik dieselbe Pipeline trifft wie die Produktion. Der Harness assertiert, dass
beide Messungen bitgleiche Zahlen liefern — Metriken (`overall()`, `allQueryResults()`) **und**
die Laufkonfiguration (`runConfiguration()`, ohne die naturgemäß unterschiedlichen Zeitstempel-
und Laufzeitfelder) —, damit auch eine rangfolge-neutrale, versehentlich angewandte Override
auffällt (Abnahmekriterium des Issues). Das ist die stärkste heute mögliche Ausprägung dieser
Prüfung: Für den Pipeline-Pfad existiert noch keine committete Baseline-Datei (das ist Gegenstand
von Issue #1040); sobald sie vorliegt, kann derselbe Referenzvarianten-Bericht zusätzlich gegen sie
geprüft werden.

## Fail-Fast und Fehlerbehandlung

Eine kaputte Vergleichsdatei (fehlende Datei, unbekanntes Feld, ungültige Parameterkombination wie
`fetchK < topK`, eine nicht ausführbare Referenzvariante) lässt den Lauf **vor** der Indizierung
abbrechen, nicht erst nach der teuren Korpus-Indizierung und den beiden vorangehenden Messpfaden.
Ist die Datei einmal geladen und die Referenzvariante ausführbar, ist ein Fehler beim eigentlichen
Vergleichslauf (Laden, Ausführen, Schreiben des Berichts) dagegen wie der Pipeline-Pfad selbst
geschützt (`PipelineHarnessSupport#runAndWriteGuarded`): Er kostet nur den Variantenbericht, nie
das bereits erarbeitete Urteil des Rohvektor- oder Pipeline-Pfads. Einzige Ausnahme ist die
Referenzvarianten-Selbstprüfung selbst — ihr Fehlschlagen bleibt ein harter Testfehler, weil er
einen Fehler in der Variantenmechanik selbst anzeigt, keinen kaputten Input.
