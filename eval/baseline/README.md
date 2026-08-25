# Retrieval-Baseline (Issue #228)

`comic-characters.json` in diesem Verzeichnis ist die committete Baseline, gegen die der
nächtliche Regressionsjob (`.github/workflows/retrieval-regression.yml`, Gradle-Task
`checkRetrievalBaseline`) jeden neuen Lauf von `./gradlew evaluateRetrieval` vergleicht. Geladen und
ausgewertet wird sie von `io.opaa.eval.Baseline`/`io.opaa.eval.BaselineComparator` im
`evalTest`-Source-Set.

## Domäne `city-landmarks` (Issue #234)

`city-landmarks.json` ist die separate Baseline für die zweite Domäne — eigene Gruppen, eigene
Toleranzen, kein gemeinsames `overall` mit `comic-characters` (Issue #234 Abnahmekriterium). Geladen
über denselben Mechanismus (`io.opaa.eval.Baseline`/`BaselineComparator`), verglichen über
`./gradlew checkCityLandmarksRetrievalBaseline` gegen den Report aus
`./gradlew evaluateCityLandmarksRetrieval`. Alles Weitere in diesem README (Toleranzformel,
Rundungsregel, Aktualisierungsverfahren) gilt unverändert auch für diese Domäne — nur die
Gruppennamen, Fallzahlen und Zahlenwerte unterscheiden sich zwischen den beiden Baseline-Dateien.

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
  hinzu, weil sie ebenfalls Teil des Messvertrags nach ADR-0012 sind, nicht nur Metadaten). Seit
  Issue #721 (Messvertrag-Version 2, ADR-0012 Nachtrag) zusätzlich `chunkOverlap` (vorher nur
  Report-Metadatum — für eine einchunkige Domäne folgenlos, für eine mehrchunkige aber
  messgrundlagenbestimmend) sowie `documentTopK`/`chunkTopK` (das jetzt ausdrücklich
  dokumentbezogene k-Fenster, siehe `io.opaa.eval.DocumentRanking`). Weicht auch nur eines davon
  vom aktuellen Lauf ab, ist die Baseline **ungültig** für diesen Lauf — der Job meldet das
  ausdrücklich als "Baseline ungültig, Messgrundlage geändert" und vergleicht dann **keine** Metrik,
  weil ein Vergleich unter unterschiedlicher Messgrundlage keine Aussage über Retrieval-Qualität
  wäre (siehe ADR-0011, Konsequenzen; ADR-0012, Entscheidung 6).
- **`groups`** enthält die vier Kernmetriken (`hitRateAt5`, `mrr`, `ndcgAt10`, `recallAt10`) plus
  `recallAt10Ceiling`, `distinctExpectedDocumentSets` (die Grundgröße der Toleranzformel unten,
  `n_eff`), `hitCountAt5`/`hitCountAt10` (Issue #306 — Zahl der Fälle mit einem Treffer in den
  Top 5 bzw. Top 10, Grundgröße der fallzahlbasierten Prüfung weiter unten) und
  `allExpectedDocumentsHitAt10` (Issue #913 — „Recall pro Teilthema": Anteil der Fälle, in denen
  *jedes* erwartete Dokument im Top-10-Fenster steht, nicht nur `recallAt10`s Teilkredit; wird von
  `BaselineComparator` wie die vier Kernmetriken toleranzgeprüft, aber ohne harte Untergrenze) für
  `overall` und jede Ausprägung von Kategorie (`category:<name>`), Schwierigkeit
  (`difficulty:<name>`) und Sprache (`language:<name>`) aus dem Golden Dataset — mit einer
  Ausnahme, siehe [Konsolidierung von `language:de`](#konsolidierung-von-languagede-issue-304)
  unten.
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

### Neuziehung unter Messvertrag-Version 2 (Issue #721)

Issue #721 macht den Harness mehrchunkfähig (dokumentbezogenes k-Fenster, je Domäne konfigurierbare
Chunk-Zahl-Invariante, zweite Metrikfamilie auf Chunkebene — siehe ADR-0010/ADR-0012, jeweils
Nachtrag). Das erhöht `measurementContractVersion` auf 2, was diese Baseline formal ungültig macht —
sie wurde im selben PR mit einem frischen `evaluateRetrieval`-Lauf neu gezogen.

**Erwartung: bitgleiche Zahlen.** `comic-characters` erfüllt weiterhin die Ein-Chunk-Invariante
(`maxChunksPerDocument=1`), wodurch `chunkTopK == documentTopK == 10` gilt — die neue,
dokumentbezogene Deduplizierung (`io.opaa.eval.DocumentRanking`) entfernt bei dieser Domäne nichts,
das die alte, chunkbezogene Suche nicht auch schon als eindeutiges Dokument gesehen hätte. Die
Chunkebenen-Metrik liefert `NOT_APPLICABLE` (kein `answer_span` in `comic-characters.json`). Der
Vorher/Nachher-Vergleich der vier Kernmetriken (alte Baseline unter Vertrag Version 1 gegen den neuen
Lauf unter Version 2) steht in der PR-Beschreibung zu Issue #721 — Abweichungen wären dort einzeln zu
benennen und zu begründen; erwartet wird keine.

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

Resultierende Toleranzen für die aktuelle Baseline, **alle zehn Gruppen und alle vier Metriken**
(`language:de` ist keine eigene Gruppe mehr — siehe
[Konsolidierung von `language:de`](#konsolidierung-von-languagede-issue-304) unten):

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

Diese Tabelle ist reproduzierbar aus der committeten Baseline nachrechenbar
(`BaselineComparator.toleranceFor`, unit-getestet in `BaselineComparatorTest`) und keine separate,
von Hand gepflegte Angabe.

**Gelöster Grenzfall — betraf sechs Paare, nicht eines (Issue #306).** Für die folgenden
Metrik/Gruppen-Paare ist die *Mittelwert*-Toleranz enger als die Verschiebung, die ein einzelner
kippender Fall erzeugt (`1/n`) — dort konnte ein einzelner Fall bislang einen Fehlschlag auslösen,
ohne dass sich sonst etwas geändert hatte:

| Paar | Toleranz | 1/n | Verhältnis |
|---|---|---|---|
| `numeric_range` / `recallAt10` | 0,0150 | 0,0625 | 0,24 |
| `numeric_range` / `ndcgAt10` | 0,0158 | 0,0625 | 0,25 |
| `numeric_range` / `mrr` | 0,0253 | 0,0625 | 0,40 |
| `multi_attribute_filter` / `ndcgAt10` | 0,0343 | 0,0476 | 0,72 |
| `numeric_range` / `hitRateAt5` | 0,0470 | 0,0625 | 0,75 |
| `multi_attribute_filter` / `recallAt10` | 0,0398 | 0,0476 | 0,83 |

Für `numeric_range`s nDCG@10 genügte es bereits, dass eine einzige der 16 Anfragen von Rang 1 auf
Rang 3 rutscht — kein verlorener Treffer nötig —, um die Toleranz mehr als doppelt zu reißen. Die
relative Deckelung, die die niedrigsten Werte wirksam schützt, fällt für andere, etwas weniger
extreme Metriken derselben kleinen Gruppe enger aus als der Ein-Fall-Schutz.

**Fix (Issue #306): fallzahlbasierte *und* Mittelwert-Prüfung (Konjunktion), ausschließlich für
Paare mit Toleranz < 1/n.** `BaselineComparator` bestimmt das dynamisch (`usesCaseBasedCheck`, nicht
anhand einer festen Sechs-Paare-Liste) — ein künftiges Baseline-Update, das die Toleranz oder `n`
eines Paares verschiebt, nimmt oder verliert die fallzahlbasierte Prüfung dafür automatisch. Für die
betroffenen Paare müssen **beide** Bedingungen gelten: die Zahl der Fälle mit einem Treffer
(`hitCountAt5` für `hitRateAt5`; `hitCountAt10` für `mrr`/`ndcgAt10`/`recallAt10` — dasselbe Ereignis
pro Fall, da die Rangliste dieses Harness nie mehr als `searchTopK=10` Einträge hat) darf gegenüber
der Baseline um höchstens `MAX_CASE_COUNT_DROP=1` sinken (exakt die in ADR-0013s Abschnitt „Offen"
vorgeschlagene Prüfung), **und** der Mittelwert muss innerhalb der auf mindestens `1/n` geweiteten
Toleranz bleiben (`max(toleranceFor(...), 1/n)`, nie enger als bisher). Eine reine Ersetzung der
Mittelwert-Prüfung — die erste, im Review zu PR #694 korrigierte Fassung dieses Fixes — hätte für
diese sechs Paare jeden Schutz gegen eine schwere Rang- oder Teiltreffer-Verschlechterung ohne
verlorenen Treffer aufgegeben (Beispiel: `numeric_range`/`mrr` kann so um 75 % fallen); Details zur
Korrektur: ADR-0013, Nachtrag zu Issue #306. `hitCountAt5`/`hitCountAt10` sind dafür neue, je Gruppe
geführte Felder der Baseline-Datei — siehe [Aufbau](#aufbau) oben und `BaselineComparator`s Javadoc
für die Begründung, warum sie nicht aus den bereits committeten Mittelwerten zurückgerechnet werden
können (das wäre für `hitRateAt5` exakt, für die drei stetigen Metriken aber nicht).

Diese engen Toleranzen sind bewusst gewählt, **weil** die Reproduzierbarkeit oben belegt ist: Eine
weite Toleranz würde bei nachgewiesener Stabilität keinen zusätzlichen Schutz vor falschem
Alarm kaufen, sondern nur echte Regressionen durchlassen.

### Harte Untergrenze (zweite Stufe)

> **Korrigiert in der zweiten Review-Runde zu PR #301.** Die erste Fassung dieses Abschnitts
> beschrieb eine rein baseline-relative Untergrenze (80 % des Baselinewerts, sonst nichts). Das war
> in zweierlei Hinsicht wirkungslos: Bei einer gültigen Baseline liegt `0,8·Baselinewert` für jede
> Gesamtmetrik oberhalb der viel engeren Primär-Toleranz (rund 0,021 für `overall`) — die Toleranz
> schlägt also immer zuerst zu, die Untergrenze konnte praktisch nie auslösen. Und weil sie
> ausschließlich relativ war, wandert sie bei einer schrittweise abgesenkten Baseline unverändert
> mit, statt dagegen zu verankern — das Gegenteil dessen, wofür ein „harter Boden" da ist.

Zusätzlich zur baseline-relativen Toleranz gilt für die vier Gesamt-Metriken (`overall`, nicht für
einzelne Gruppen) eine Untergrenze, die **beide** Komponenten kombiniert
(`BaselineComparator.HARD_FLOOR_FRACTION_OF_BASELINE` und `HARD_FLOOR_ABSOLUTE_*`, ADR-0013
Entscheidung 4):

```
harteUntergrenze = max( 0,8 · committeter Baselinewert, feste Untergrenze )
```

Feste Untergrenzen: Hit Rate@5 ≥ 0,30, MRR ≥ 0,25, nDCG@10 ≥ 0,25, Recall@10 ≥ 0,25. Für die aktuelle
Baseline dominiert die relative Komponente (Hit Rate@5 ≥ 0,417, MRR ≥ 0,369, nDCG@10 ≥ 0,356,
Recall@10 ≥ 0,392) — bei einer künftig, legitim oder nicht, stark abgesenkten Baseline übernimmt die
feste Komponente die Ankerfunktion. Die baseline-relative Toleranz greift im Regelfall trotzdem lange
vor der harten Untergrenze; deren Zweck bleibt ein zweiter, von der primären Toleranzformel
unabhängiger Fangnetz gegen katastrophales Versagen (z. B. ein leerer oder falsch konfigurierter
Vektor-Store) — jetzt tatsächlich mit dieser Wirkung, nicht nur der Absicht danach.

## Konsolidierung von `language:de` (Issue #304)

Im Golden Dataset (`eval/golden/comic-characters.json`) ist jeder `crosslingual`-Fall
konstruktionsbedingt eine deutsche Frage gegen den englischen Korpus — und es gibt keine andere
Quelle für deutsche Fälle. `category:crosslingual` und `language:de` waren damit exakt dieselbe
Fallmenge (34 Fälle, 33 unterschiedliche Erwartungsmengen), nicht nur ähnlich: derselbe Baseline-
Eintrag, dieselbe Toleranz, dieselben Werte. Der Regressionsjob prüfte diese eine Fallmenge doppelt
(acht statt vier Prüfungen) und suggerierte damit eine Abdeckung, die nicht bestand — ADR-0013,
Abschnitt „Offen", benannte das als offenen Punkt.

**Entscheidung: Konsolidierung, nicht getrennte Gruppen und keine Generator-Erweiterung.** Die
Baseline-Gruppe `language:de` wurde entfernt, `category:crosslingual` bleibt bestehen — sie benennt
die fachliche Eigenschaft, um die es hier tatsächlich geht (deutsche Fragen gegen einen englischen
Korpus), während `language:de` nur die zufällige sprachliche Ausprägung dieser einen Kategorie war.
`BaselineComparator.compare` überspringt die vom Report weiterhin gelieferte `language:de`-Gruppe
gezielt (`REDUNDANT_LANGUAGE_GROUP`), statt sie gegen einen entfernten Baseline-Eintrag zu prüfen.
Das Golden Dataset selbst bleibt unverändert — kein Umbau des Generators
(`eval/generator/generate_golden_dataset.py`) in diesem Schritt.

**Erwogene, nicht gewählte Alternative: Generator-Erweiterung.** Der Generator könnte
`crosslingual`-Fälle künftig auch für mindestens eine weitere Sprache oder Domäne erzeugen, sodass
`category:crosslingual` und `language:de` sich tatsächlich unterscheiden und beide Gruppen
eigenständige Aussagekraft behalten. Das würde die Redundanz an der Wurzel auflösen, verlangt aber
einen neuen Corpus-/Golden-Dataset-Lauf und eine neu gezogene Baseline (`eval/baseline/comic-
characters.json`) — ein größerer, eigenständiger Schritt, der über die reine Konsolidierung der
Baseline-Gruppen hinausgeht und hier bewusst nicht gegangen wird. Sollte künftig echter Bedarf an
mehrsprachiger `crosslingual`-Abdeckung entstehen, ist das ein eigenes Vorhaben mit eigenem Issue,
kein Nachtrag zu #304.

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
   Vorbild). `hitCountAt5`/`hitCountAt10` (Issue #306) gehören für jede Gruppe dazu — sie stehen
   direkt im JSON-Report unter `allQueryResults` (Zahl der Fälle mit `hitRateAt5 > 0` bzw.
   `ndcgAt10 > 0`) und müssen aus demselben Lauf stammen wie die vier Mittelwerte, nicht separat
   nachgerechnet werden. `allExpectedDocumentsHitAt10` (Issue #913, „Recall pro Teilthema") gehört
   für jede Gruppe ebenso dazu — fehlt es, wird es beim Laden stillschweigend als `0.0` normalisiert
   (siehe `MetricsAggregate`s Javadoc) und `BaselineComparator` schützt dann nichts mehr gegen einen
   Rückgang bei dieser Metrik; `Baseline.validate` prüft beim Laden zwei nachrechenbare
   Invarianten (`allExpectedDocumentsHitAt10 <= recallAt10` und
   `allExpectedDocumentsHitAt10 * n <= hitCountAt10`) gegen genau dieses stille Verschwinden, ersetzt
   aber nicht das Eintragen des tatsächlich gemessenen Werts.

   **Verbindliche Rundungsregel (Issue #721 code review, Nit 5):** Die vier gerundeten Mittelwerte
   je Gruppe werden **wörtlich aus der `%.3f`-Textausgabe von `ReportWriter.renderSummary`**
   übernommen (Konsolen-Log des Laufs bzw. `backend/build/eval-reports/`-Textausgabe), nie separat
   von Hand oder mit einem anderen Werkzeug (Python, Taschenrechner, `BigDecimal`) nachgerundet.
   Java rundet `String.format(Locale.ROOT, "%.3f", d)` über die kürzeste round-trip-fähige
   Dezimaldarstellung von `d` (dieselbe wie `Double.toString`), **nicht** über den exakten
   Binärwert — für einen Mittelwert, der exakt auf einer Rundungsgrenze liegt (z. B. `36.5/40 =
   0.9125`), kann das von einer naiven Nachrundung des rohen `double`-Werts abweichen (siehe die
   Begründung in `git log` zu dieser Zeile: `difficulty:easy`/`mrr` war in einer früheren,
   von Hand eingetragenen Baseline-Fassung als `0.912` notiert, während der tatsächliche
   `ReportWriter`-Output für denselben Wert `0.913` liefert — verifiziert direkt gegen die JVM,
   nicht nur behauptet). Die `%.3f`-Ausgabe ist die einzige Quelle der Wahrheit für die
   gerundete Baseline-Zahl.
5. Wie jede andere Code-Änderung: Code Reviewer prüft den PR, ein Maintainer merged.

**Nicht** ausreichend: Die Baseline-Datei ohne einen tatsächlichen `evaluateRetrieval`-Lauf von Hand
anpassen, oder eine Verschlechterung stillschweigend als neue Baseline übernehmen, ohne den Grund im
PR zu nennen.

**`provenance`** (`sourceReportRunStartedAt`, `sourcePullRequest`) hält fest, aus welchem Lauf und
PR die aktuellen Zahlen stammen — rein dokumentarisch, nie Teil des Vergleichs. Bei jeder
Aktualisierung mitschreiben, damit eine spätere Frage „woher kommt dieser Wert" ohne Git-Archäologie
beantwortbar bleibt.

## Baseline-Absenkung gegenüber `main` (ADR-0013, Entscheidung 6)

> **Korrigiert in der zweiten Review-Runde zu PR #301.** Dieser Vergleich lief zunächst nur im
> label-ausgelösten Retrieval-Regressionslauf. Das hätte einen PR, der die Baseline unbemerkt
> absenkt, aber nie das Label `evaluation` bekommt, ohne jeden Hinweis durchgelassen — die einzige
> verbliebene Kontrolle wäre dann die Aufmerksamkeit eines Reviewers gewesen, nicht mehr etwas
> Automatisiertes.

**`.github/workflows/baseline-diff.yml`** vergleicht deshalb für **jeden** Pull Request, der
`eval/baseline/**` ändert — unabhängig vom Label `evaluation` — die Baseline **des PR-Branches**
gegen die Baseline **von `main`** (`eval/baseline/diff_baseline.py`) und postet jede Metrik, die im
PR-Branch niedriger ist als auf `main`, als eigene Tabelle im PR-Kommentar sowie in der
Job-Zusammenfassung. Der Grund für einen eigenen Workflow statt eines Schritts im
Retrieval-Regressionsjob: Das Skript ist Standardbibliothek-only Python, braucht kein Docker und
läuft in unter einer Minute — es gibt keinen Grund, es an das teure, gelabelte Verfahren zu koppeln.

Das Skript ist rein informativ und schlägt nie fehl (Exit-Code immer 0) — es macht eine Absenkung im
PR-Kommentar sichtbar, ersetzt aber nicht die Review-Pflicht aus dem Abschnitt oben. Es ist bewusst
ein eigenständiges, Standardbibliothek-only Python-Skript (kein Java, kein Gradle-Task), aus
demselben Grund wie die Korpus-/Golden-Dataset-Generatoren unter `eval/generator/` (ADR-0011,
Entscheidung 2): Ein einfacher Zwei-Dateien-JSON-Vergleich braucht keine Backend-Infrastruktur.
