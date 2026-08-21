# ADR-0013: Fehlerkriterium des Retrieval-Regressionsjobs

## Status

Vorgeschlagen — Entwurf aus dem Code-Review zu PR #301 (Issue #228, Epic #224), übernommen und in
`io.opaa.eval.BaselineComparator` umgesetzt im selben PR. Siehe den [Nachtrag](#nachtrag-umsetzung-in-pr-301)
für zwei Korrekturen gegenüber diesem Entwurf und den Umsetzungsstand. Der ursprünglich offene Punkt
des Abschnitts „Offen" (sechs zu enge Metrik/Gruppen-Paare) ist mit dem
[Nachtrag zu Issue #306](#nachtrag-fallzahlbasierte-prüfung-für-zu-enge-paare-issue-306) gelöst.

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

> **Gelöst — siehe [Nachtrag zu Issue #306](#nachtrag-fallzahlbasierte-prüfung-für-zu-enge-paare-issue-306).**
> Der folgende Abschnitt bleibt als historischer Befund stehen (die Zahlen entstammen dem
> damaligen Stand); die Lösung selbst steht im Nachtrag.

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

## Nachtrag: Konsolidierung von `category:crosslingual` und `language:de` (Issue #304)

> Löst den ersten Punkt des ursprünglichen „Offen"-Abschnitts auf, statt ihn dort unverändert stehen
> zu lassen.

`category:crosslingual` und `language:de` waren, wie im Abschnitt „Kontext" oben bereits notiert,
konstruktionsbedingt exakt dieselbe Fallmenge (34 Fälle, 33 unterschiedliche Erwartungsmengen) —
jeder `crosslingual`-Fall im Golden Dataset ist eine deutsche Frage gegen den englischen Korpus, und
es gibt keine andere Quelle für deutsche Fälle. Der Regressionsjob prüfte diese eine Fallmenge acht
statt vier Mal.

**Entscheidung: Konsolidierung.** Die Baseline-Gruppe `language:de` entfällt,
`category:crosslingual` bleibt — sie benennt die fachliche Eigenschaft, um die es geht (deutsche
Fragen gegen einen englischen Korpus), während `language:de` nur deren zufällige sprachliche
Ausprägung war. `BaselineComparator.compare` überspringt die vom Report weiterhin gelieferte
`language:de`-Gruppe gezielt (`REDUNDANT_LANGUAGE_GROUP`). Das Golden Dataset und der Generator
(`eval/generator/generate_golden_dataset.py`) bleiben unverändert.

**Erwogene, nicht gewählte Alternative:** den Generator um `crosslingual`-Fälle in mindestens einer
weiteren Sprache oder Domäne zu erweitern, sodass beide Gruppen sich tatsächlich unterscheiden. Das
wäre der grundsätzlichere Fix, verlangt aber einen neuen Corpus-/Golden-Dataset-Lauf und eine neu
gezogene Baseline — ein eigenständiges, hier bewusst nicht gegangenes Vorhaben. Details und
Begründung: `eval/baseline/README.md`, Abschnitt „Konsolidierung von `language:de`".

## Nachtrag: Fallzahlbasierte Prüfung für zu enge Paare (Issue #306)

> Löst den zweiten (verbliebenen) Punkt des „Offen"-Abschnitts auf.

**Ausgangslage.** Sechs Metrik/Gruppen-Paare — alle in `numeric_range` und `multi_attribute_filter`,
den beiden schwächsten Kategorien — haben eine Mittelwert-Toleranz, die enger ist als die
Verschiebung, die ein einzelner kippender Fall erzeugt (`1/n`, siehe Tabelle im „Offen"-Abschnitt
oben). Für `numeric_range`s nDCG@10 genügt es, dass eine einzige der 16 Anfragen von Rang 1 auf Rang
3 rutscht — kein verlorener Treffer nötig —, um die Toleranz mehr als doppelt zu reißen.

**Entscheidung: fallzahlbasierte statt Mittelwert-Prüfung, ausschließlich für Paare mit Toleranz
< 1/n, ermittelt dynamisch statt über eine feste Paarliste.** `BaselineComparator.usesCaseBasedCheck`
prüft für jedes Metrik/Gruppen-Paar, ob `toleranceFor(baselineValue, n_eff) < 1/n` gilt (`n` ist die
rohe Fallzahl, dieselbe, durch die der Mittelwert tatsächlich geteilt wurde — nicht `n_eff`, das die
Formel selbst verengt). Nur dort ersetzt eine fallzahlbasierte Prüfung die Mittelwert-Toleranz: die
Zahl der Fälle mit einem Treffer darf gegenüber der Baseline um höchstens `MAX_CASE_COUNT_DROP = 1`
sinken — exakt die Prüfung, die der „Offen"-Abschnitt oben vorschlug. Für `hitRateAt5` ist das die
Zahl der Fälle mit einem Treffer in den Top 5 (`hitCountAt5`); für `mrr`, `ndcgAt10` und `recallAt10`
dieselbe Zahl für die Top 10 (`hitCountAt10`) — diese drei sind für dieses Harness dasselbe
Fall-Ereignis, nicht nur korreliert: `searchTopK=10` bedeutet, die Rangliste hat nie mehr als 10
Einträge, also ist „ein Treffer irgendwo in der (bis zu 10 langen) Rangliste" (was `mrr` positiv
macht) identisch mit „ein Treffer in den Top 10" (was `ndcgAt10`/`recallAt10` positiv macht).

**Warum dynamisch statt einer festen Sechs-Paare-Liste.** Eine hartkodierte Liste würde nach dem
nächsten Baseline-Update lautlos falsch: verschiebt sich ein Paar über oder unter die `1/n`-Grenze,
folgt die Prüfung dem nicht automatisch. `usesCaseBasedCheck` rechnet stattdessen bei jedem Vergleich
aus der *aktuell geladenen* Baseline neu nach — im selben selbstheilenden Geist wie der
`language:de`-Skip aus dem vorherigen Nachtrag.

**Warum `hitCountAt5`/`hitCountAt10` als neue Baseline-Felder, nicht aus den bereits committeten
Mittelwerten zurückgerechnet.** Naheliegend wäre gewesen, die historische Fallzahl aus Mittelwert
und `n` zu schätzen (z. B. `round(baselineValue * n)`) statt das Baseline-Schema zu erweitern. Das
ist für `hitRateAt5` exakt (die Metrik ist pro Fall binär, ihr Mittelwert mal `n` ist genau die
Trefferzahl), für `mrr`/`ndcgAt10`/`recallAt10` aber nicht: Diese sind pro Fall stetig (ein Treffer
auf einem niedrigeren Rang zählt weniger als 1,0), sodass ihr Mittelwert die tatsächliche Trefferzahl
unterschätzt. Am realen Fallmaterial hinter der aktuellen Baseline nachgerechnet: `numeric_range`s
`ndcgAt10`-Mittelwert von 0,063 summiert sich über 16 Fälle zu 1,008 — die tatsächliche Trefferzahl
ist aber 4, nicht 1. Eine Rückrechnung hätte also nicht „die Zahl der Fälle mit `ndcgAt10 > 0`"
geliefert, sondern die Prüfung für genau die Paare stillschweigend zu locker gemacht, die sie
schützen soll — das Gegenteil des Ziels. `hitCountAt5`/`hitCountAt10` sind deshalb zusätzliche,
aus denselben Rohdaten wie die vier Mittelwerte geführte Felder je Gruppe (siehe
`eval/baseline/README.md`, Abschnitt „Aufbau").

**Woher die Zahlen für die aktuell committete Baseline stammen.** Keine neue Messung: Die Baseline
selbst hat sich nicht geändert (Fixpunkte, Mittelwerte und `distinctExpectedDocumentSets` sind
unverändert). Die Zähler wurden aus dem bit-identischen, artefaktverifizierten
`checkRetrievalBaseline`-Lauf auf einem GitHub-Actions-eigenen Runner gelesen (planmäßiger
nächtlicher Lauf auf `main`, Workflow-Run `32442551477`, Commit `45faad2`, 2026-08-21T03:12Z — vgl.
den Nachtrag „zweite Review-Runde" oben, der genau diesen Runner-Lauf als offenen Punkt benannte).
Dessen Delta-Tabelle zeigt für jede der vierzig Metrik/Gruppen-Zeilen ein Delta von `±0.000`
gegenüber der committeten Baseline — die exakt gleiche Messgrundlage, aus der auch die vier
Mittelwerte stammen, nicht eine andere oder neuere.

**Warum `MAX_CASE_COUNT_DROP = 1` statt `K_MIN = 2` wie bei der Mittelwert-Toleranz.** Die
fallbasierte Mittelwert-Toleranz braucht die Marge von zwei Fällen, um Fließkomma-Grenzfälle wie den
aus dem PR-#301-Review (`category:entity_description`, `12/20 − 0,65 == −0,05000000000000004`
gegen eine Toleranz von exakt `0,05`) abzufangen. Die fallzahlbasierte Prüfung vergleicht dagegen
zwei Ganzzahlen exakt — es gibt keine Rundungsgrenze, an der ein Fall fälschlich kippen könnte —,
sodass ein einzelner tolerierter Fall genügt, um genau das ursprüngliche Ziel aus Entscheidung 2
(„zwei unabhängige Fälle sind Rauschen, drei sind ein Befund" — hier: ein Fall ist Rauschen, zwei
sind ein Befund) ohne die zusätzliche Marge zu erreichen.

**Auswirkung auf die anderen Prüfungen.** Keine — außerhalb der sechs betroffenen Paare bleibt die
Mittelwert-Toleranzformel aus Entscheidung 2/3 unverändert in Kraft, unverändert von `evalUnitTest`
abgedeckt (`BaselineComparatorTest`). `eval/baseline/diff_baseline.py` vergleicht zusätzlich
`hitCountAt5`/`hitCountAt10` zwischen PR-Branch und `main` (rein informativ, wie die vier
Mittelwerte auch), damit eine stille Absenkung der neuen Felder ebenfalls im PR sichtbar wird.
