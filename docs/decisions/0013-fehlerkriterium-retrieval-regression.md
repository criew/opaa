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

**4. Die harte Untergrenze der vier Gesamtmetriken kombiniert eine baseline-relative und eine feste
absolute Komponente** über `max(...)`: `max(0,8 · committeter Baselinewert, feste Untergrenze)` —
Hit Rate@5 ≥ 0,30, MRR ≥ 0,25, nDCG@10 ≥ 0,25, Recall@10 ≥ 0,25 als feste Komponente (siehe
[Nachtrag, zweite Review-Runde](#nachtrag-zweite-review-runde-2026-08-03), Punkt 2, für die
Korrektur gegenüber der ursprünglichen Entwurfsfassung dieser Entscheidung, die die feste Komponente
noch fallen ließ). Beide Komponenten allein sind unzureichend: rein relativ (0,8·b) liegt sie bei der
aktuellen Baseline oberhalb der Primär-Toleranz und kann bei gültiger Baseline nie auslösen — und sie
wandert unverändert mit, wenn die Baseline selbst über mehrere PRs erodiert, verliert also genau den
Erosionsschutz, den sie haben soll. Rein absolut (feste 0,25/0,30) erodiert sie umgekehrt zur
Bedeutungslosigkeit, sobald die tatsächlichen Werte weit darüber liegen. Erst `max(...)` beider
Komponenten liefert sowohl Katastrophenschutz als auch einen Anker gegen schleichende
Baseline-Absenkung.

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
  besser über die absolute Zahl getroffener Fälle (z. B. „die Zahl der Fälle mit nDCG@10 > 0 darf um
  höchstens 1 sinken"). **Konkret bekannt und bewusst nicht gelöst — korrigiert in der zweiten
  Review-Runde vom 2026-08-03:** Es ist **kein** isolierter Einzelfall, sondern betrifft **sechs**
  Metrik/Gruppen-Paare, deren Toleranz enger ist als die Verschiebung, die ein einzelner kippender
  Fall in dieser Gruppe erzeugt (`1/n`):

  | Paar | Toleranz | 1/n | Verhältnis |
  |---|---|---|---|
  | `numeric_range` / `recallAt10` | 0,0150 | 0,0625 | 0,24 |
  | `numeric_range` / `ndcgAt10` | 0,0158 | 0,0625 | 0,25 |
  | `numeric_range` / `mrr` | 0,0253 | 0,0625 | 0,40 |
  | `multi_attribute_filter` / `ndcgAt10` | 0,0343 | 0,0476 | 0,72 |
  | `numeric_range` / `hitRateAt5` | 0,0470 | 0,0625 | 0,75 |
  | `multi_attribute_filter` / `recallAt10` | 0,0398 | 0,0476 | 0,83 |

  Für `numeric_range`s nDCG@10 genügt es bereits, dass eine einzige der 16 Anfragen von Rang 1 auf
  Rang 3 rutscht — **kein** Treffer muss verloren gehen —, um die Toleranz mehr als doppelt zu
  reißen. Für alle sechs Paare kann ein einzelner Fall also weiterhin einen Fehlschlag auslösen,
  obwohl Entscheidung 2 genau das verhindern soll. Der Grund ist der in diesem ADR beschriebene
  Zielkonflikt selbst: Die relative Deckelung aus Entscheidung 3, die die schwächsten Werte wirksam
  schützt, fällt für andere, etwas weniger extreme Metriken derselben (kleinen) Gruppe enger aus als
  der Ein-Fall-Schutz aus Entscheidung 2. Eine fallzahlbasierte Prüfung (statt Mittelwert-Toleranz)
  für diese Paare wäre der sauberere, aber grundsätzlichere Wechsel und ist billiger als zunächst
  angenommen — der Report führt in `allQueryResults` bereits jeden Einzelfall mit seinen vier
  Metriken, eine „Zahl erfolgreicher Fälle darf um höchstens 1 sinken"-Prüfung bräuchte keine neue
  Kalibrierungsevidenz und wäre strenger als die 25-%-Deckelung. Nachverfolgt in Issue #306
  (`evaluation`, `ci`, `size:M`).

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

## Nachtrag: zweite Review-Runde (2026-08-03)

> Der Maintainer hat `checkRetrievalBaseline` mit echtem Docker laufen lassen — vier Läufe auf drei
> Maschinen, alle bit-identisch, Exit 0. Für `multi_attribute_filter` und `numeric_range` exakt die
> committeten Baseline-Werte (Delta 0,000). Das ist die erste Verifikation des vollständigen Wegs,
> nicht nur der Vergleichslogik gegen synthetische Reports — und die Grundlage für die folgenden drei
> Korrekturen.

1. **Der Sechsfach-Befund aus dem „Offen"-Abschnitt war als Einzelfall beschrieben.** Korrigiert:
   siehe die Tabelle mit allen sechs betroffenen Metrik/Gruppen-Paaren oben. Der Javadoc von
   `BaselineComparator` listet dieselben sechs Paare.
2. **Die harte Untergrenze (Entscheidung 4) war ausschließlich baseline-relativ und dadurch
   wirkungslos**, nicht wie ursprünglich in diesem ADR vorgeschlagen. Zur Laufzeit unerreichbar (die
   Primär-Toleranz löst bei einer gültigen Baseline immer zuerst aus) **und** ohne Erosionsschutz
   (sie wandert unverändert mit einer abgesenkten Baseline mit, statt dagegen zu verankern). Korrekt
   ist die Kombination aus relativer und fester absoluter Komponente über `max(...)` — Entscheidung 4
   oben ist entsprechend präzisiert.
3. **Die Herabstufung des Sechsfach-Befunds auf einen Folge-Eintrag beruht auf vier bit-identischen
   Läufen über drei Maschinen — CI-Hardware (GitHub-Actions-Runner) steht dabei noch aus.** Alle
   bisherigen Läufe stammen von Entwickler-/Reviewer-Rechnern. Der erste nächtliche Lauf über
   `.github/workflows/retrieval-regression.yml` ist insofern der eigentliche Test, ob die
   Reproduzierbarkeit auch auf Runner-Hardware hält — nicht nur eine Formalität nach dem Merge.
4. **Der Baseline-Diff gegenüber `main` (Entscheidung 6) hing zunächst am Label `evaluation`.** Ein
   PR, der die Baseline unbemerkt absenkt, aber nie dieses Label bekommt, hätte dadurch überhaupt
   keinen Hinweis erzeugt — der CODEOWNERS-Gegenvorschlag des Autors trägt zwar (Merge-Rechte sind
   ohnehin auf zwei Maintainer beschränkt), macht die Sichtbarkeit aber zur einzigen verbliebenen,
   und damals noch freiwilligen, Kontrolle. Korrigiert: `eval/baseline/diff_baseline.py` läuft jetzt
   über einen eigenen, Docker-freien Workflow (`.github/workflows/baseline-diff.yml`) für **jeden**
   PR, der `eval/baseline/**` ändert, unabhängig vom Label.
