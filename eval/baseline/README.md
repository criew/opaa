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
  Embedding-Modell samt Digest und Dimensionen, Chunk-Größe, `searchTopK`,
  `productionSimilarityThreshold`, `pgvectorIndexType`, Korpus- und Golden-Dataset-Hash,
  Messvertrag-Version (ADR-0013, Entscheidung 5 — die letzten drei kamen im Review zu PR #301
  hinzu, weil sie ebenfalls Teil des Messvertrags nach ADR-0012 sind, nicht nur Metadaten). Weicht
  auch nur eines davon vom aktuellen Lauf ab, ist die Baseline **ungültig** für diesen Lauf — der
  Job meldet das ausdrücklich als "Baseline ungültig, Messgrundlage geändert" und vergleicht dann
  **keine** Metrik, weil ein Vergleich unter unterschiedlicher Messgrundlage keine Aussage über
  Retrieval-Qualität wäre (siehe ADR-0011, Konsequenzen; ADR-0012, Entscheidung 6).
- **`groups`** enthält die vier Kernmetriken (`hitRateAt5`, `mrr`, `ndcgAt10`, `recallAt10`) plus
  `recallAt10Ceiling` und `distinctExpectedDocumentSets` (die Grundgröße der Toleranzformel unten,
  `n_eff`) für `overall` und jede Ausprägung von Kategorie (`category:<name>`), Schwierigkeit
  (`difficulty:<name>`) und Sprache (`language:<name>`) aus dem Golden Dataset.
- **`measuredAt`**/**`provenance`**/**`notes`** sind rein dokumentarisch (nie Teil des Vergleichs):
  wann, mit welchem PR und welchem Report gemessen wurde, inklusive Verweis auf frühere, hinfällige
  Zahlen (siehe unten).

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

## Toleranzen je Gruppe (ADR-0013)

> **Überarbeitet nach dem Review zu PR #301.** Die ursprüngliche Formel dieses Abschnitts
> (`min(max(0,12·Baselinewert, 1/n, 0,02), 0,05)`) hatte eine absolute Obergrenze, die an **beiden**
> Enden der Skala band: zu locker für schwache, kleine Gruppen (bis zu 79 % erlaubte relative
> Verschlechterung bei `numeric_range`), gleichzeitig zu eng genau an der Ein-Fall-Grenze anderer
> Gruppen (`entity_description`, n=20: ein einzelner kippender Fall scheiterte an
> Fließkomma-Rundung, `12/20 − 0,65 == −0,05000000000000004` gegen eine Toleranz von exakt `0,05`).
> Das jetzt umgesetzte Fehlerkriterium ist in [ADR-0013](../../docs/decisions/0013-fehlerkriterium-retrieval-regression.md)
> festgeschrieben, nicht mehr nur hier beschrieben — Änderungen daran sind eine ADR-Änderung.

Der Vergleich verwendet **keine einheitliche Toleranz**. Eine feste absolute Toleranz von z. B. 0,05
wäre bei `attribute_lookup` (Baseline-nDCG@10 0,942) reines Rauschen, bei `numeric_range`
(Baseline-nDCG@10 0,063) dagegen fast der gesamte Messwert — dieselbe Zahl bedeutet an den beiden
Enden der Skala etwas völlig anderes. Eine einheitliche *relative* Toleranz löst das ebenfalls nicht:
Bei einem Wert nahe null kollabiert sie gegen null, sodass die kleinste Schwankung schon als
Regression zählt.

`BaselineComparator.toleranceFor(baselineValue, n_eff)` kombiniert deshalb zwei Terme:

```
toleranz = min( k_min / n_eff, 0,25 · baselineValue )
```

- **`k_min / n_eff`** ("fallbasiert", `k_min = 2`): drückt die Toleranz als „wie viele unabhängige
  Fälle müssten kippen, damit das als Regression zählt" aus, statt als festen Metrikpunkt. `n_eff`
  ist `distinctExpectedDocumentSets` der Gruppe — die Zahl *unterschiedlicher* Erwartungsmengen
  unter ihren Fällen, nicht die rohe Fallzahl `n` (siehe `MetricsAggregate`-Javadoc): Mehrere
  Golden-Dataset-Fälle teilen dieselbe Erwartungsmenge (z. B. ist jeder `crosslingual`-Fall der
  deutsche Zwilling eines englischen mit identischer Erwartungsmenge), sodass `n` die Zahl
  unabhängiger Beobachtungen überschätzt.
- **`0,25 · baselineValue`** ("relative Deckelung"): begrenzt den fallbasierten Term zusätzlich für
  niedrig bewertete Gruppen. Ohne diese Deckelung würde eine kleine `n_eff` bei einem
  Baseline-Wert nahe null eine beliebig große *relative* Verschlechterung zulassen, allein weil die
  Gruppe klein ist — genau das Problem, das ADR-0013 behebt. Die Deckelung kann die Toleranz nur
  verengen, nie erweitern.

Es gibt bewusst **keine** separate absolute Unter- oder Obergrenze mehr: Eine einzelne absolute
Grenze band an beiden Enden der Skala zugleich. Fallbasiert plus relative (nicht absolute) Deckelung
vermeidet diese Kollision.

Resultierende Toleranzen für die aktuelle Baseline, **alle elf Gruppen und alle vier Metriken**:

| Gruppe | n | n_eff | Metrik | Baseline | Toleranz | dominanter Term |
|---|---|---|---|---|---|---|
| overall | 121 | 94 | hitRateAt5 | 0,521 | 0,0213 | fallbasiert |
| overall | 121 | 94 | mrr | 0,461 | 0,0213 | fallbasiert |
| overall | 121 | 94 | ndcgAt10 | 0,445 | 0,0213 | fallbasiert |
| overall | 121 | 94 | recallAt10 | 0,490 | 0,0213 | fallbasiert |
| category:attribute_lookup | 30 | 30 | hitRateAt5 | 0,967 | 0,0667 | fallbasiert |
| category:attribute_lookup | 30 | 30 | mrr | 0,933 | 0,0667 | fallbasiert |
| category:attribute_lookup | 30 | 30 | ndcgAt10 | 0,942 | 0,0667 | fallbasiert |
| category:attribute_lookup | 30 | 30 | recallAt10 | 0,967 | 0,0667 | fallbasiert |
| category:entity_description | 20 | 20 | hitRateAt5 | 0,650 | 0,1000 | fallbasiert |
| category:entity_description | 20 | 20 | mrr | 0,518 | 0,1000 | fallbasiert |
| category:entity_description | 20 | 20 | ndcgAt10 | 0,575 | 0,1000 | fallbasiert |
| category:entity_description | 20 | 20 | recallAt10 | 0,750 | 0,1000 | fallbasiert |
| category:crosslingual | 34 | 33 | hitRateAt5 | 0,382 | 0,0606 | fallbasiert |
| category:crosslingual | 34 | 33 | mrr | 0,337 | 0,0606 | fallbasiert |
| category:crosslingual | 34 | 33 | ndcgAt10 | 0,302 | 0,0606 | fallbasiert |
| category:crosslingual | 34 | 33 | recallAt10 | 0,322 | 0,0606 | fallbasiert |
| category:multi_attribute_filter | 21 | 21 | hitRateAt5 | 0,238 | 0,0595 | relative Deckelung |
| category:multi_attribute_filter | 21 | 21 | mrr | 0,206 | 0,0515 | relative Deckelung |
| category:multi_attribute_filter | 21 | 21 | ndcgAt10 | 0,137 | 0,0343 | relative Deckelung |
| category:multi_attribute_filter | 21 | 21 | recallAt10 | 0,159 | 0,0398 | relative Deckelung |
| category:numeric_range | 16 | 15 | hitRateAt5 | 0,188 | 0,0470 | relative Deckelung |
| category:numeric_range | 16 | 15 | mrr | 0,101 | 0,0253 | relative Deckelung |
| category:numeric_range | 16 | 15 | ndcgAt10 | 0,063 | 0,0158 | relative Deckelung |
| category:numeric_range | 16 | 15 | recallAt10 | 0,060 | 0,0150 | relative Deckelung |
| difficulty:easy | 40 | 30 | hitRateAt5 | 0,950 | 0,0667 | fallbasiert |
| difficulty:easy | 40 | 30 | mrr | 0,912 | 0,0667 | fallbasiert |
| difficulty:easy | 40 | 30 | ndcgAt10 | 0,922 | 0,0667 | fallbasiert |
| difficulty:easy | 40 | 30 | recallAt10 | 0,950 | 0,0667 | fallbasiert |
| difficulty:medium | 48 | 35 | hitRateAt5 | 0,333 | 0,0571 | fallbasiert |
| difficulty:medium | 48 | 35 | mrr | 0,253 | 0,0571 | fallbasiert |
| difficulty:medium | 48 | 35 | ndcgAt10 | 0,262 | 0,0571 | fallbasiert |
| difficulty:medium | 48 | 35 | recallAt10 | 0,335 | 0,0571 | fallbasiert |
| difficulty:hard | 33 | 30 | hitRateAt5 | 0,273 | 0,0667 | fallbasiert |
| difficulty:hard | 33 | 30 | mrr | 0,216 | 0,0540 | relative Deckelung |
| difficulty:hard | 33 | 30 | ndcgAt10 | 0,134 | 0,0335 | relative Deckelung |
| difficulty:hard | 33 | 30 | recallAt10 | 0,157 | 0,0393 | relative Deckelung |
| language:en | 87 | 85 | hitRateAt5 | 0,575 | 0,0235 | fallbasiert |
| language:en | 87 | 85 | mrr | 0,509 | 0,0235 | fallbasiert |
| language:en | 87 | 85 | ndcgAt10 | 0,502 | 0,0235 | fallbasiert |
| language:en | 87 | 85 | recallAt10 | 0,555 | 0,0235 | fallbasiert |
| language:de | 34 | 33 | hitRateAt5 | 0,382 | 0,0606 | fallbasiert |
| language:de | 34 | 33 | mrr | 0,337 | 0,0606 | fallbasiert |
| language:de | 34 | 33 | ndcgAt10 | 0,302 | 0,0606 | fallbasiert |
| language:de | 34 | 33 | recallAt10 | 0,322 | 0,0606 | fallbasiert |

Diese Tabelle ist reproduzierbar aus der committeten Baseline nachrechenbar
(`BaselineComparator.toleranceFor`, unit-getestet in `BaselineComparatorTest`) und keine separate,
von Hand gepflegte Angabe.

**Bekannter, bewusst nicht gelöster Grenzfall** (siehe ADR-0013, Abschnitt „Offen"): Für
`category:numeric_range` liegt die Toleranz von `hitRateAt5` (0,047) knapp unter der Verschiebung,
die ein einzelner kippender Fall in dieser 16-Fälle-Gruppe erzeugt (1/16 ≈ 0,0625) — dort kann ein
einzelner Fall weiterhin einen Fehlschlag auslösen. Die relative Deckelung, die `numeric_range`s
nDCG@10 wirksam schützt, fällt für `hitRateAt5` (höherer Baseline-Wert derselben Gruppe) enger aus
als der Ein-Fall-Schutz. Eine fallzahlbasierte statt Mittelwert-Prüfung für sehr kleine Gruppen wäre
der sauberere Fix, ist aber ohne Erfahrung aus echten nächtlichen Läufen nicht kalibrierbar — offenes
Folge-Thema, kein PR-#301-Bug.

Diese engen Toleranzen sind bewusst gewählt, **weil** die Reproduzierbarkeit oben belegt ist: Eine
weite Toleranz würde bei nachgewiesener Stabilität keinen zusätzlichen Schutz vor falschem
Alarm kaufen, sondern nur echte Regressionen durchlassen.

### Harte Untergrenze (zweite Stufe)

Zusätzlich zur baseline-relativen Toleranz gilt eine für die vier Gesamt-Metriken (`overall`, nicht
für einzelne Gruppen) **an die Baseline gekoppelte** Untergrenze: 80 % des jeweils committeten
Baselinewerts (`BaselineComparator.HARD_FLOOR_FRACTION_OF_BASELINE`, ADR-0013 Entscheidung 4). Für
die aktuelle Baseline also Hit Rate@5 ≥ 0,417, MRR ≥ 0,369, nDCG@10 ≥ 0,356, Recall@10 ≥ 0,392. Eine
feste Zahl (zuvor 0,25 für alle vier) hätte eine schrittweise Baseline-Absenkung um 44 % erlaubt,
ohne je auszulösen — an die Baseline gekoppelt wandert die Untergrenze bei jeder Baseline-
Aktualisierung mit. Die baseline-relative Toleranz greift im Regelfall lange vorher; der Zweck der
harten Untergrenze bleibt ein zweiter, von der primären Toleranzformel unabhängiger Fangnetz gegen
katastrophales Versagen (z. B. ein leerer oder falsch konfigurierter Vektor-Store).

## Baseline ungültig vs. Regression — wie der Job das unterscheidet

`BaselineComparator.compare(...)` beantwortet zwei getrennte Fragen, absichtlich nicht in ein
einziges Ja/Nein zusammengefasst:

1. **Ist die Baseline noch gültig?** Stimmen `measurementContractVersion`,
   `corpusManifestSha256`, `goldenDatasetSha256`, `embeddingModelDigest`, `embeddingDimensions`,
   `chunkSize`, `chunkSizeMatchesApplicationDefault`, `searchTopK`, `productionSimilarityThreshold`
   und `pgvectorIndexType` mit dem aktuellen Lauf überein? Wenn nicht, bricht der Job mit einer
   Meldung ab, die ausdrücklich **"Baseline ungültig, Messgrundlage geändert"** sagt, samt Tabelle
   der abweichenden Felder — nicht "Retrieval ist schlechter geworden". In diesem Fall wird **keine**
   Metrik verglichen.
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

**`provenance`** (`sourceReportRunStartedAt`, `sourcePullRequest`) hält fest, aus welchem Lauf und
PR die aktuellen Zahlen stammen — rein dokumentarisch, nie Teil des Vergleichs. Bei jeder
Aktualisierung mitschreiben, damit eine spätere Frage „woher kommt dieser Wert" ohne Git-Archäologie
beantwortbar bleibt.

## Baseline-Absenkung gegenüber `main` (ADR-0013, Entscheidung 6)

Der label-ausgelöste Lauf an einem Pull Request vergleicht zusätzlich die Baseline **des
PR-Branches** gegen die Baseline **von `main`** (`eval/baseline/diff_baseline.py`, aufgerufen aus
`.github/workflows/retrieval-regression.yml`) und postet jede Metrik, die im PR-Branch niedriger ist
als auf `main`, als eigene Tabelle im PR-Kommentar. Der Grund: Ohne diesen Vergleich prüft der
label-ausgelöste Lauf nur, ob der aktuelle Retrieval-Lauf zur **eigenen, im selben PR mitgelieferten**
Baseline passt — ein PR, der die Baseline im selben Zug unbemerkt absenkt, bekommt dadurch einen
grünen Regressionsjob als scheinbaren Beleg für "keine Regression", obwohl der Maßstab selbst
gesunken ist.

Das Skript ist rein informativ und schlägt nie fehl (Exit-Code immer 0) — es macht eine Absenkung im
PR-Kommentar sichtbar, ersetzt aber nicht die Review-Pflicht aus dem Abschnitt oben. Es ist bewusst
ein eigenständiges, Standardbibliothek-only Python-Skript (kein Java, kein Gradle-Task), aus
demselben Grund wie die Korpus-/Golden-Dataset-Generatoren unter `eval/generator/` (ADR-0011,
Entscheidung 2): Ein einfacher Zwei-Dateien-JSON-Vergleich braucht keine Backend-Infrastruktur.
