# Retrieval-Baseline (Issue #228)

`comic-characters.json` in diesem Verzeichnis ist die committete Baseline, gegen die der
nächtliche Regressionsjob (`.github/workflows/retrieval-regression.yml`, Gradle-Task
`checkRetrievalBaseline`) jeden neuen Lauf von `./gradlew evaluateRetrieval` vergleicht. Geladen und
ausgewertet wird sie von `io.opaa.eval.Baseline`/`io.opaa.eval.BaselineComparator` im
`evalTest`-Source-Set.

## Aufbau

```json
{
  "measurementContractVersion": 1,
  "fixedPoints": { ... },
  "groups": { "overall": { ... }, "category:attribute_lookup": { ... }, ... },
  "measuredAt": "2026-08-03",
  "notes": "..."
}
```

- **`fixedPoints`** legt fest, *unter welcher Messgrundlage* die Zahlen entstanden sind:
  Embedding-Modell samt Digest, Chunk-Größe, Korpus- und Golden-Dataset-Hash,
  Messvertrag-Version. Weicht auch nur eines davon vom aktuellen Lauf ab, ist die Baseline
  **ungültig** für diesen Lauf — der Job meldet das ausdrücklich als "Baseline ungültig,
  Messgrundlage geändert" und vergleicht dann **keine** Metrik, weil ein Vergleich unter
  unterschiedlicher Messgrundlage keine Aussage über Retrieval-Qualität wäre (siehe ADR-0011,
  Konsequenzen; ADR-0012, Entscheidung 6).
- **`groups`** enthält die vier Kernmetriken (`hitRateAt5`, `mrr`, `ndcgAt10`, `recallAt10`) plus
  `recallAt10Ceiling` für `overall` und jede Ausprägung von Kategorie (`category:<name>`),
  Schwierigkeit (`difficulty:<name>`) und Sprache (`language:<name>`) aus dem Golden Dataset.
- **`measuredAt`**/**`notes`** sind rein dokumentarisch: wann und mit welchem Kontext gemessen
  wurde, inklusive Verweis auf frühere, hinfällige Zahlen (siehe unten).

## Warum diese Zahlen und nicht andere

Gemessen am 2026-08-03 mit `nomic-embed-text:v1.5` (Digest siehe `fixedPoints`), Laufzeit 1004 s.
Drei unabhängige Läufe — zwei vom Autor, einer vom Reviewer auf fremder Hardware — lieferten
byte-identische Metriken. Die in ADR-0011 genannte HNSW-Approximationsschwankung tritt bei dieser
Korpusgröße also nicht auf; das ist die Grundlage für die vergleichsweise engen Toleranzen unten.

Ein früherer Bericht nannte einen Gesamt-nDCG@10 von 0,463. Der stammte vom ungepinnten
`nomic-embed-text:latest`. Er ist **hinfällig** — nicht trotz, sondern *wegen* des Pinnings: Die
Verschiebung von 0,463 auf 0,445 bei identischem Korpus und identischer Golden-Dataset-Version ist
der empirische Beleg dafür, dass `:latest` ohne Digest-Pin keine stabile Messgrundlage ist (siehe
ADR-0011, Entscheidung 4, und den Digest-Assertion-Mechanismus in
`RetrievalEvaluationHarnessTest`).

## Toleranzen je Gruppe

Der Vergleich verwendet **keine einheitliche Toleranz**. Eine feste absolute Toleranz von z. B. 0,05
wäre bei `attribute_lookup` (Baseline-nDCG@10 0,942) reines Rauschen, bei `numeric_range`
(Baseline-nDCG@10 0,063) dagegen fast der gesamte Messwert — dieselbe Zahl bedeutet an den beiden
Enden der Skala etwas völlig anderes. Eine einheitliche *relative* Toleranz löst das nicht: Bei einem
Wert nahe null kollabiert eine relative Toleranz ebenfalls gegen null, sodass die kleinste
Schwankung schon als Regression zählt.

`BaselineComparator.toleranceFor(baselineValue, n)` kombiniert deshalb drei Terme:

```
toleranz = min(
    max(0.12 · baselineValue, 1 / n, 0.02),
    0.05)
```

- **`0.12 · baselineValue`** (relativer Anteil): skaliert die Toleranz mit dem eigenen Score-Niveau
  der Gruppe — der dominante Term bei mittel/hoch bewerteten Gruppen (`attribute_lookup`,
  `entity_description`, `easy`).
- **`1 / n`** (Ein-Fall-Schutz): skaliert **umgekehrt** mit der Gruppengröße. Kleine Gruppen wie
  `numeric_range` (n=16) schwanken pro Einzelfall stärker als große — ein einzelner kippender Fall
  verschiebt den Mittelwert einer 16er-Gruppe um bis zu 0,0625, bei einer 121er-Gruppe nur um 0,008.
  Dieser Term wird für genau die kleinen, niedrig bewerteten Gruppen dominant, bei denen der
  relative Term allein gegen null ginge (`numeric_range`, `multi_attribute_filter`).
- **`0.02`** (absolute Untergrenze): verhindert, dass eine große, hoch bewertete Gruppe allein durch
  die anderen beiden Terme eine unrealistisch enge Toleranz bekommt.
- **`0.05`** (absolute Obergrenze): verhindert, dass ein hoher Baseline-Wert einen beliebig großen
  Rückgang deckt, bevor der Job reagiert.

Resultierende Toleranzen für die aktuelle Baseline (nDCG@10, exemplarisch — jede Metrik jeder Gruppe
bekommt ihre eigene, nach derselben Formel berechnete Toleranz):

| Gruppe | n | Baseline nDCG@10 | Toleranz | dominanter Term |
|---|---|---|---|---|
| overall | 121 | 0,445 | 0,050 | relativ (gedeckelt) |
| attribute_lookup | 30 | 0,942 | 0,050 | relativ (gedeckelt) |
| entity_description | 20 | 0,575 | 0,050 | relativ (gedeckelt) |
| crosslingual | 34 | 0,302 | 0,036 | relativ |
| multi_attribute_filter | 21 | 0,137 | 0,048 | Ein-Fall-Schutz |
| numeric_range | 16 | 0,063 | 0,050 | Ein-Fall-Schutz (gedeckelt) |

Diese engen Toleranzen sind bewusst gewählt, **weil** die Reproduzierbarkeit oben belegt ist: Eine
weite Toleranz würde bei nachgewiesener Stabilität keinen zusätzlichen Schutz vor falschem
Alarm kaufen, sondern nur echte Regressionen durchlassen.

### Harte Untergrenze (zweite Stufe)

Zusätzlich zur baseline-relativen Toleranz gilt eine **von der Baseline unabhängige** Untergrenze
für die vier Gesamt-Metriken (`overall`, nicht für einzelne Gruppen): Hit Rate@5 ≥ 0,30, MRR ≥ 0,25,
nDCG@10 ≥ 0,25, Recall@10 ≥ 0,25 (`BaselineComparator.HARD_FLOOR_*`). Diese Werte liegen deutlich
unter der aktuellen Baseline — die baseline-relative Toleranz greift im Regelfall lange vorher. Ihr
Zweck ist ein zweiter, von der Baseline-Datei selbst unabhängiger Fangnetz gegen katastrophales
Versagen (z. B. ein leerer oder falsch konfigurierter Vektor-Store), nicht die primäre
Regressionserkennung.

## Baseline ungültig vs. Regression — wie der Job das unterscheidet

`BaselineComparator.compare(...)` beantwortet zwei getrennte Fragen, absichtlich nicht in ein
einziges Ja/Nein zusammengefasst:

1. **Ist die Baseline noch gültig?** Stimmen `measurementContractVersion`,
   `corpusManifestSha256`, `goldenDatasetSha256`, `embeddingModelDigest`, `chunkSize` und
   `chunkSizeMatchesApplicationDefault` mit dem aktuellen Lauf überein? Wenn nicht, bricht der Job
   mit einer Meldung ab, die ausdrücklich **"Baseline ungültig, Messgrundlage geändert"** sagt, samt
   Tabelle der abweichenden Felder — nicht "Retrieval ist schlechter geworden". In diesem Fall wird
   **keine** Metrik verglichen.
2. **Nur wenn die Baseline gültig ist:** Liegt jede Gruppe innerhalb ihrer Toleranz, und halten die
   vier Gesamt-Metriken die harte Untergrenze? Nur hier ist die Meldung "Regression erkannt".

Diese Trennung ist der Kern der Anforderung aus Issue #228: Eine Korpus-, Modell- oder
Golden-Dataset-Änderung ist kein Bug im Retrieval, sondern eine bewusste, reviewte Änderung der
Messgrundlage — sie erfordert eine neue Baseline, keinen Fix am Suchcode.

## Ein-Chunk-Invariante

Unabhängig von Toleranzen: Verletzt der Lauf die Ein-Chunk-Invariante (ADR-0010,
`oneChunkInvariant.violations` im Report nicht leer), schlägt der Job **immer** fehl — das ist kein
Toleranzfall. Eine verletzte Invariante bedeutet, dass "ein Treffer = eine Entität" nicht mehr gilt,
wodurch jede Metrik bedeutungslos wird.

## Baseline aktualisieren

Eine Baseline-Aktualisierung ist ein bewusster, reviewter Schritt — kein Nebeneffekt eines anderen
PRs. Sie ist nötig, wenn sich eine der `fixedPoints` legitim ändert (Korpus-Regenerierung,
Golden-Dataset-Erweiterung, Embedding-Modell-Wechsel, `chunk-size`-Änderung, neuer
Messvertrag/ADR-0012-Version) **oder** wenn ein grüner Lauf durchgängig bessere Werte zeigt als die
Baseline (der Job schlägt dafür nie fehl, meldet aber einen Hinweis — siehe
`BaselineMarkdownWriter`).

**Wer:** Wer auch immer die zugrunde liegende Änderung durchführt (Entwickler, QA Engineer) —
dieselbe Person, die den PR mit der Korpus-/Modell-/Messvertrag-Änderung einreicht. Der PR braucht
kein separates Baseline-only-PR-Muster; die Baseline-Datei wird als Teil desselben PRs aktualisiert,
das die Änderung verursacht, damit Ursache und neue Zahl im selben Review sichtbar sind.

**Womit begründet:**

1. `./gradlew evaluateRetrieval` lokal (oder über `workflow_dispatch`) mindestens einmal mit der
   neuen Konfiguration laufen lassen und den resultierenden
   `backend/build/eval-reports/retrieval-metrics.json` im PR verlinken oder als Artefakt anhängen.
2. Bei einer Verschlechterung: nachvollziehbar begründen, warum sie hingenommen wird (z. B. ein
   bewusster Tradeoff aus einer anderen Änderung) — eine Baseline-Aktualisierung ist kein Freibrief,
   um eine Regression verschwinden zu lassen.
3. Bei einer Verbesserung: kurz benennen, was sie verursacht hat (Chunking, Modell, Korpus), damit
   die Baseline-Historie nachvollziehbar bleibt.
4. Die neuen Werte in `comic-characters.json` eintragen, `fixedPoints` entsprechend aktualisieren,
   `measuredAt` und `notes` fortschreiben (frühere, hinfällige Zahlen wie oben nicht löschen,
   sondern als überholt kennzeichnen — siehe die bestehende `notes`-Historie in dieser Datei als
   Vorbild).
5. Wie jede andere Code-Änderung: Code Reviewer prüft den PR, ein Maintainer merged.

**Nicht** ausreichend: Die Baseline-Datei ohne einen tatsächlichen `evaluateRetrieval`-Lauf von Hand
anpassen, oder eine Verschlechterung stillschweigend als neue Baseline übernehmen, ohne den Grund im
PR zu nennen.
