# ADR-0013: Fehlerkriterium des Retrieval-Regressionsjobs

## Status

Vorgeschlagen — Entwurf aus dem Code-Review zu PR #301 (Issue #228, Epic #224), übernommen und in
`io.opaa.eval.BaselineComparator` umgesetzt im selben PR. Siehe den [Nachtrag](#nachtrag-umsetzung-in-pr-301)
für zwei Korrekturen gegenüber diesem Entwurf und den Umsetzungsstand.

## Kontext

ADR-0011 (Entscheidung 5) legt fest, **dass** der Regressionsjob fehlschlägt, wenn eine Primärmetrik
eine harte Untergrenze unterschreitet oder um mehr als „eine definierte Toleranz" unter die Baseline
fällt. ADR-0012 legt den Messvertrag fest, unter dem gemessen wird. Beides sagt nichts darüber, wie
diese Toleranz zustande kommt — die Feature-Spezifikation nennt lediglich „Vorschlag: 0,03 absolut".

PR #301 setzt stattdessen eine Formel um:

```
toleranz = min( max(0,12 · Baselinewert, 1/n, 0,02), 0,05 )
```

Damit ist implizit und ausschließlich im Code definiert, **was das Projekt künftig als „keine
Regression" bezeichnet**. Das ist keine Implementierungsdetailfrage: Ein zu weiter Schwellenwert
macht den gesamten Aufwand aus #225–#227 wirkungslos, ohne dass es auffällt — der Job bleibt grün
und suggeriert eine Sicherheit, die nicht besteht. Ein zu enger Schwellenwert erzeugt nächtliche
Fehlalarme, mit dem bekannten Ergebnis, dass der Job abgeschaltet oder ignoriert wird.

Die konkrete Formel hat zwei Eigenschaften, die eine bewusste Festlegung verlangen:

1. **Die Obergrenze 0,05 bindet an beiden Enden der Skala.** Für hoch bewertete Gruppen greift sie,
   weil `0,12 · Baselinewert` sie überschreitet; für kleine Gruppen (`n ≤ 20`), weil `1/n` sie
   überschreitet. Für `category:numeric_range` (n=16, nDCG@10 = 0,063) bedeutet das eine erlaubte
   relative Verschlechterung von 79 % (Recall@10: 83 %), für `category:attribute_lookup` von 5 %.
   Ausgerechnet die schwächsten Gruppen — an denen sich eine Verbesserung des Retrievals zuerst
   zeigen müsste — sind damit faktisch ungeschützt.
2. **Die Obergrenze hebelt den Ein-Fall-Schutz dort aus, wofür er gebaut wurde.** Bei n=16 verschiebt
   ein einzelner kippender Fall die Hit Rate@5 um 0,0625 > 0,05. Dieselbe Gruppe ist also für
   niedrige Metriken zu weit und für hohe Metriken zu eng abgesichert. Bei n=20
   (`category:entity_description`) liegt die Toleranz exakt auf der Ein-Fall-Grenze.

Hinzu kommt, dass `n` die Zahl unabhängiger Beobachtungen überschätzt: Der Report weist selbst aus,
dass 121 Fällen deutlich weniger unterschiedliche Erwartungsmengen gegenüberstehen und jeder deutsche
Bereichsfall der konstruierte Zwilling eines englischen ist (`datasetNotes`). `category:crosslingual`
und `language:de` sind zudem dieselbe Menge und liefern denselben Wert doppelt.

Die Begründung für die Enge — drei byte-identische Läufe — stützt sich auf drei Läufe auf zwei
Maschinen mit demselben Modell-Digest. Das belegt Determinismus der Pipeline, nicht die Stabilität
über Runner-Generationen, Docker-Versionen oder pgvector-Updates hinweg.

## Entscheidung

**1. Das Fehlerkriterium wird hier festgeschrieben, nicht nur im Code.** Jede Änderung an Formel,
Konstanten oder Untergrenzen ist eine ADR-Änderung, weil sie die Bedeutung von „grün" verschiebt.

**2. Die Toleranz wird in Fällen ausgedrückt, nicht in absoluten Metrikpunkten.** Grundgröße ist
`k_min / n_eff` mit `k_min = 2` (zwei kippende Fälle sind Rauschen, drei sind ein Befund) und
`n_eff` = Zahl unabhängiger Beobachtungen der Gruppe (Zahl unterschiedlicher Erwartungsmengen unter
den Fällen der Gruppe), nicht die Fallzahl. Die absolute Obergrenze entfällt; sie bindet heute genau
dort, wo sie schadet.

**3. Für Gruppen mit niedrigem Baselinewert gilt zusätzlich eine relative Deckelung.** Kein
Baselinewert darf um mehr als 25 % relativ fallen, unabhängig davon, was die fallbasierte Toleranz
erlaubt. Damit bleibt `numeric_range` (nDCG@10 0,063) bei rund 0,047 statt bei rund 0,013 geschützt.

**4. Die harte Untergrenze der vier Gesamtmetriken wird an die Baseline gekoppelt**: 80 % des jeweils
committeten Baselinewerts. Ihr Zweck bleibt der zweite, baseline-unabhängige Fangnetz gegen
katastrophales Versagen; als feste 0,25 hätte sie eine schrittweise Baseline-Absenkung um 44 %
zugelassen, ohne je auszulösen.

**5. Alle Größen, die den Messvertrag nach ADR-0012 bestimmen, sind Fixpunkte der Baseline** und
führen bei Abweichung zu „Baseline ungültig", nicht zu einem Metrikvergleich. Das umfasst
ausdrücklich auch `searchTopK` (ADR-0012, Entscheidung 3), `pgvectorIndexType` (ADR-0011,
Konsequenzen: der Wechsel auf exakte Suche ist dort als möglicher Weg vorgesehen),
`embeddingDimensions` und die (bewusst nicht angewandte) `productionSimilarityThreshold`.

**6. Eine Baseline-Absenkung ist im Diff sichtbar zu machen.** Der label-ausgelöste Lauf an einem PR
vergleicht zusätzlich die Baseline des PR-Branches gegen die Baseline von `main` und weist jede
Absenkung im PR-Kommentar aus. Das Verfahren in `eval/baseline/README.md` bleibt bestehen, ruht aber
nicht mehr allein auf der Aufmerksamkeit des Reviewers.

## Konsequenzen

**Einfacher:**

- „Keine Regression" ist eine nachlesbare, begründete Aussage statt einer Formel im Code.
- Die schwachen Gruppen — der eigentliche Gegenstand künftiger Retrieval-Arbeit — sind messbar
  geschützt; eine Verbesserung dort wird sichtbar, statt im Toleranzband zu verschwinden.
- Eine schleichende Baseline-Erosion über mehrere PRs hat eine sichtbare Grenze (Entscheidung 6) und
  eine mitwandernde harte Untergrenze (Entscheidung 4) statt einer erodierenden festen Zahl.

**Schwieriger:**

- `n_eff` muss bestimmt und gepflegt werden; der Report führt dafür jetzt eine
  `distinctExpectedDocumentSets`-Zahl je Gruppe (nicht nur gesamt), was `MetricsAggregate` erweitert
  hat.
- Eine relative Deckelung bei niedrigen Werten erhöht die Fehlalarmwahrscheinlichkeit in genau den
  Gruppen, die am stärksten schwanken. Ob 25 % tragfähig sind, ist erst nach mehreren nächtlichen
  Läufen auf CI-Hardware beurteilbar — die bisherigen Läufe stammen von Entwicklerrechnern.
- Eine an die Baseline gekoppelte Untergrenze muss bei jeder Baseline-Aktualisierung mitgezogen
  werden — sie wird jedoch aus dem committeten Baselinewert berechnet, nicht separat gepflegt.

## Offen

- Ob `category:crosslingual` und `language:de` als getrennte Gruppen geführt werden sollen, solange
  sie identisch sind — heute erzeugen sie acht statt vier Prüfungen über dieselben Daten. Eigenes
  Issue (`evaluation`, `size:S`).
- Ob Gruppen mit sehr niedrigem Baselinewert überhaupt über Mittelwerte geprüft werden sollten oder
  besser über die absolute Zahl getroffener Fälle (z. B. „mindestens 2 der 16 `numeric_range`-Fälle
  liefern einen Treffer in den Top 5"). **Konkret bekannt und bewusst nicht gelöst:** Für
  `category:numeric_range` (n_eff=15) liegt die aus Entscheidung 3 resultierende Toleranz von
  `hitRateAt5` bei rund 0,047 — knapp unter der Verschiebung, die ein einzelner kippender Fall in
  dieser Gruppe erzeugt (1/16 ≈ 0,0625). Für dieses eine Metrik/Gruppen-Paar kann ein einzelner
  Fall also weiterhin einen Fehlschlag auslösen, obwohl Entscheidung 2 genau das verhindern soll.
  Der Grund ist der in diesem ADR beschriebene Zielkonflikt selbst: Die relative Deckelung aus
  Entscheidung 3, die `numeric_range`s nDCG@10 wirksam schützt, kann für ein anderes, weniger
  extremes Metrik desselben Golden-Kategoriewerts (`hitRateAt5`, Baseline 0,188) enger ausfallen als
  der Ein-Fall-Schutz aus Entscheidung 2. Eine fallzahlbasierte Prüfung (statt Mittelwert-Toleranz)
  für Gruppen mit `n_eff < 20` wäre der sauberere, aber grundsätzlichere Wechsel — dafür fehlt heute
  die Evidenz aus echten nächtlichen Läufen, um das zu kalibrieren.

## Nachtrag: Umsetzung in PR #301

> **Nachtrag vom 2026-08-03.** Zwei Korrekturen gegenüber der ursprünglichen Entwurfsfassung dieses
> ADR, festgehalten statt still überschrieben.

1. **`n_eff` ist keine Schätzung, sondern eine im Report geführte, gruppenweise Zahl.**
   `MetricsAggregate` trägt jetzt `distinctExpectedDocumentSets` je Gruppe (überall, je Kategorie,
   Schwierigkeit und Sprache) — berechnet exakt wie die bereits vorhandene, gesamt-report-weite
   `datasetNotes.distinctExpectedDocumentSets`, nur granularer. Das schließt die im „Schwieriger"-
   Abschnitt der Entwurfsfassung benannte Lücke („der Report liefert dafür heute nur die Gesamtzahl,
   keine gruppenweise Aufschlüsselung").
2. **Die im Entwurf zitierte Zahl „121 Fälle, 75 unterschiedliche Erwartungsmengen" ist falsch.**
   Nachgerechnet direkt aus dem committeten `eval/golden/comic-characters.json` ergeben sich **94**
   unterschiedliche Erwartungsmengen, nicht 75. Die 75 stammte aus einer ungeprüften früheren Angabe
   und wurde unverändert in den Entwurf übernommen. Alle Zahlenbeispiele in diesem ADR und die
   committete Baseline (`eval/baseline/comic-characters.json`) verwenden die nachgerechnete Zahl 94.

Der Kern der Entscheidung (Formel, harte Untergrenze, Fixpunkt-Liste, Baseline-Diff) ist davon nicht
berührt.
