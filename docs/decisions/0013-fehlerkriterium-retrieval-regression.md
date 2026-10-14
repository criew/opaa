# ADR-0013: Fehlerkriterium des Retrieval-Regressionsjobs

## Status

Akzeptiert · zuletzt geändert 2026-08-31 · ergänzt ADR-0012 · ergänzt durch ADR-0012 Entscheidung 19
(Anker des Pipeline- und Mehrrunden-Pfads)

## Kontext

ADR-0011 (Entscheidung 5) legt fest, **dass** der Regressionsjob fehlschlägt, wenn eine
Primärmetrik eine harte Untergrenze unterschreitet oder um mehr als eine Toleranz unter die Baseline
fällt; ADR-0012 legt den Messvertrag fest. Wie die Toleranz zustande kommt, stand zunächst nur im
Code — und damit die Bedeutung von „grün“. Ein zu weiter Schwellenwert macht den Harness wirkungslos,
ohne dass es auffällt; ein zu enger erzeugt nächtliche Fehlalarme, bis der Job ignoriert wird. Eine
absolute Toleranz in Metrikpunkten bindet an beiden Enden falsch: Für die schwächsten Gruppen
erlaubt sie relative Einbrüche von über 70 %, für kleine Gruppen liegt sie unter der Verschiebung,
die ein einzelner kippender Fall erzeugt. Zudem überschätzt die Fallzahl `n` die Zahl unabhängiger
Beobachtungen, weil mehrere Fälle eine Erwartungsmenge teilen.

## Entscheidung

### 1. Das Fehlerkriterium steht hier, nicht nur im Code

Jede Änderung an Formel, Konstanten oder den Ankern des Rohvektor-Pfads ist eine Änderung dieses
ADRs, weil sie die Bedeutung von „grün“ verschiebt. Die Umsetzung ist `BaselineComparator`; der
Pipeline- und der Mehrrunden-Pfad erzeugen ihre Prüfungen über dieselbe Methode
`BaselineComparator.metricCheck` und verwenden nur eigene Anker der harten Untergrenze (ADR-0012,
Entscheidung 19), für die ADR-0012 zuständig ist.

### 2. Die Toleranz wird in Fällen ausgedrückt, nicht in Metrikpunkten

`toleranz = min(K_MIN / n_eff, 0,25 · Baselinewert)` mit `K_MIN = 2` (zwei kippende Fälle sind
Rauschen, drei ein Befund — die Marge von zwei fängt Fließkomma-Grenzfälle wie
`12/20 − 0,65 = −0,05000000000000004` gegen eine Toleranz von exakt 0,05 ab) und
`n_eff = distinctExpectedDocumentSets` der Gruppe aus der **Baseline**, nicht die Fallzahl. Keine
absolute Obergrenze. Geprüft wird je Gruppe (`overall`, `category:*`, `difficulty:*`,
`language:*`) für Hit Rate@5, MRR, nDCG@10, Recall@10 und `allExpectedDocumentsHitAt10`:
`delta ≥ −toleranz − ε` mit `ε = 1e-9`. `language:de` wird übersprungen (`REDUNDANT_LANGUAGE_GROUP`):
Sie ist dieselbe Fallmenge wie `category:crosslingual` (deutsche Fragen gegen den englischen Korpus)
und lieferte denselben Wert doppelt; seit #304 führt die Baseline die Gruppe nicht mehr.

### 3. Relative Deckelung bei niedrigem Baselinewert

Kein Baselinewert darf um mehr als 25 % relativ fallen, unabhängig von der fallbasierten Toleranz
(`RELATIVE_CAP_FRACTION`). Damit bleiben die schwächsten Gruppen — der eigentliche Gegenstand
künftiger Retrieval-Arbeit — messbar geschützt, statt im Toleranzband zu verschwinden.

### Konjunktionsregel für zu enge Paare (#306)

Wo die Mittelwert-Toleranz enger ist als die Verschiebung eines einzelnen kippenden Falls —
`toleranceFor(baselineValue, n_eff) < 1/n`, mit `n` der rohen Fallzahl der Baseline, dynamisch je
Metrik/Gruppen-Paar bei jedem Vergleich bestimmt (`usesCaseBasedCheck`), nicht als feste Paarliste —
muss ein Paar **beide** Prüfungen bestehen:

1. **Fallzahlprüfung:** Die Zahl der Fälle mit Treffer sinkt gegenüber der Baseline um höchstens
   `MAX_CASE_COUNT_DROP = 1` — `hitCountAt5` für Hit Rate@5, `hitCountAt10` für MRR, nDCG@10,
   Recall@10 und `allExpectedDocumentsHitAt10` (am Pipeline-Fenster `hitCountAt20`). Ein Fall genügt
   statt zwei, weil zwei Ganzzahlen ohne Rundungsgrenze verglichen werden. Die Zähler sind eigene
   Baseline-Felder, nicht aus den Mittelwerten zurückgerechnet: Für MRR, nDCG und Recall ist der
   Mittelwert pro Fall stetig und unterschätzt die Trefferzahl.
2. **Mittelwertprüfung mit geweiteter Toleranz:** `max(toleranceFor(…), 1/n)` — nie enger, höchstens
   auf eine Fallbreite geweitet. Die Weitung ist bewusst: Ohne sie ließe ein Fall, der von Rang 1 auf
   Rang 3 rutscht, die Toleranz reißen, obwohl kein Treffer verloren geht; mit Ersetzung statt
   Konjunktion bliebe ein Absturz aller Trefferränge auf Rang 10 oder ein Rückgang auf je ein
   gefundenes Dokument unentdeckt.

Außerhalb dieser Paare gilt die Toleranz aus Entscheidung 2 und 3 unverändert.

### 4. Harte Untergrenze: baseline-relativ und absolut kombiniert

Für die vier Gesamtmetriken der Gruppe `overall`: `currentValue ≥ max(0,8 · Baselinewert, Anker)`
mit den Ankern Hit Rate@5 ≥ 0,30, MRR ≥ 0,25, nDCG@10 ≥ 0,25, Recall@10 ≥ 0,25
(`HARD_FLOOR_FRACTION_OF_BASELINE`, `HARD_FLOOR_ABSOLUTE_*`). Rein relativ kann sie bei gültiger
Baseline nie vor der Toleranz auslösen und wandert mit einer erodierenden Baseline mit; rein absolut
wird sie bedeutungslos, sobald die Werte weit darüber liegen. Erst `max(…)` liefert
Katastrophenschutz und einen Anker gegen schleichende Absenkung. Die Anker des Pipeline- und
Mehrrunden-Pfads (0,15 / 0,125) stehen in ADR-0012, Entscheidung 19.

### 5. Festpunkte führen zu „Baseline ungültig“, nicht zu einem Metrikvergleich

Weicht ein Festpunkt des Messvertrags ab — der vollständige Katalog je Pfad steht in ADR-0012,
darunter `searchTopK`, `pgvectorIndexType`, `embeddingDimensions`, die bewusst nicht angewandte
`productionSimilarityThreshold` und die Vertragsversion —, meldet der Job die Baseline als
unvergleichbar und prüft keine Metrik. Beim Laden prüft `Baseline.validate` zusätzlich die
Invarianten der Zähler: `distinctExpectedDocumentSets > 0`, `hitCountAt5 == round(hitRateAt5 · n)`,
`0 ≤ hitCountAt5 ≤ hitCountAt10 ≤ n`, `hitCountAt10 ≥ max(mrr, ndcgAt10, recallAt10) · n`
(am Pipeline-Fenster `PipelineGroupInvariants`); eine handbearbeitete Gruppe kann die Toleranz so
nicht still weiten.

### 6. Eine Baseline-Absenkung ist im Diff sichtbar

`eval/baseline/diff_baseline.py` vergleicht über `.github/workflows/baseline-diff.yml` für **jeden**
PR, der `eval/baseline/**` ändert — unabhängig vom Label `evaluation`, Docker-frei —, jede
Baseline-Datei gegen `main` und weist jede Absenkung der Mittelwerte und der Zähler
`hitCountAt5`/`hitCountAt10` im PR aus. Das Verfahren in `eval/baseline/README.md` ruht damit nicht
allein auf der Aufmerksamkeit des Reviewers.

## Konsequenzen

**Einfacher:**

- „Keine Regression“ ist eine nachlesbare, begründete Aussage; schwache Gruppen sind geschützt, eine
  Verbesserung dort wird sichtbar.
- Baseline-Erosion über mehrere PRs hat eine sichtbare Grenze (Entscheidung 6) und eine mitwandernde
  harte Untergrenze (Entscheidung 4).
- Die Konjunktionsregel folgt jeder Neuziehung automatisch, weil sie aus der geladenen Baseline
  berechnet wird.

**Schwieriger:**

- `n_eff` und die Zähler müssen je Gruppe in der Baseline geführt werden; `Baseline.validate` erzwingt
  ihre Konsistenz.
- Die 25-%-Deckelung erhöht die Fehlalarmwahrscheinlichkeit in den am stärksten schwankenden Gruppen;
  für die Paare der Konjunktionsregel wirkt sie nur bis zu einer Fallbreite.

**Restrisiken:**

- Die Enge der Toleranz stützt sich auf bit-identische Läufe über mehrere Maschinen und den
  nächtlichen Runner; eine Laufzeitänderung wie die ggml-CPU-Variante (#1652) schlägt direkt in die
  Metriken durch und wird über Festpunkte (ADR-0012), nicht über Toleranz abgefangen.

## Verworfene Alternativen

- Absolute Toleranz `min(max(0,12 · b, 1/n, 0,02), 0,05)` — bindet an beiden Enden der Skala falsch.
- Rein relative oder rein absolute harte Untergrenze — wirkungslos bzw. erodierend (Entscheidung 4).
- Feste Liste der sechs zu engen Paare — würde nach der nächsten Neuziehung lautlos falsch.
- Fallzahlprüfung als Ersatz der Mittelwertprüfung — erkannte Rangabstürze und Teilverluste ohne
  verlorenen Treffer nicht.
- Trefferzahlen aus Mittelwert und `n` zurückrechnen — für stetige Metriken zu niedrig, lockerte
  genau die zu schützenden Paare.
- `language:de` durch weitere Sprachen oder Domänen von `crosslingual` unterscheidbar machen — ein
  eigenes Vorhaben mit neuem Korpus und neuer Baseline.
- Baseline-Diff nur beim Label `evaluation` — ein PR ohne Label hätte keinen Hinweis erzeugt.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-03 | #228, PR #301 | Erste Fassung aus dem Review: fallbasierte Toleranz, relative Deckelung, Festpunkte, Baseline-Diff | 1–6 |
| 2026-08-03 | PR #301 | `n_eff` je Gruppe im Report; harte Untergrenze als `max(relativ, absolut)`; Baseline-Diff für jeden Baseline-PR statt nur beim Label | 4, 6 |
| 2026-08-20 | #304 | `language:de` aus der Baseline entfernt, im Vergleich übersprungen | 2 |
| 2026-08-21 | #306, PR #694 | Konjunktion aus Fallzahl- und geweiteter Mittelwertprüfung für zu enge Paare; `hitCountAt5`/`hitCountAt10`; Ladeinvarianten | 2, 3, 5 |
| 2026-08-31 | #1040 | Geltung für den Pipeline-Pfad mit eigenen Ankern (ADR-0012, Entscheidung 19) | 1, 4 |
| 2026-10-14 | #2698 | Neufassung als Endstand ohne Nachträge | alle |
