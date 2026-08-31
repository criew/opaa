# Pflege der Domäne `verwaltung`

Beantwortet die drei Fragen, die `docs/features/retrieval-benchmark.md` (Abschnitt 5,
„Zustandsfelder: ungelöste Fälle bleiben ungelöst benannt") für jede Domäne mit neuen
Golden-Fall-Klassen verbindlich verlangt: wer welchen Teil pflegt, wie eine Baseline-Neuziehung
abläuft und woran sie erkennbar bewusst war, und welche Fälle derzeit als `known_gap` geführt
werden.

## Stand dieser Domäne (Issue #1042)

Dieses Issue liefert **ausschließlich Generator, Korpus und Manifest** — Schritt C des
Umsetzungsschnitts in `docs/features/retrieval-benchmark.md`. Golden Dataset, Baseline und die
`EvalDomainConfig`-Registrierung im Retrieval-Harness (`backend/src/evalTest/java/io/opaa/eval/`)
sind **Schritt D**, ein eigenes, späteres Issue. Diese Domäne ist damit heute noch nicht Teil von
`./gradlew evaluateRetrieval`/`checkRetrievalBaseline` oder eines nächtlichen Regressionslaufs —
sie existiert ausschließlich als eingefrorener, reproduzierbarer Korpus mit einem Docker-freien
Chunk-Zahl-Nachweis (`io.opaa.eval.VerwaltungChunkSizeDryRunTest`). Die folgenden Abschnitte
beschreiben deshalb teils den heutigen Stand, teils das Verfahren, das ab Schritt D gilt — jeweils
gekennzeichnet.

## Wer pflegt was

| Teil | Pflegeverantwortung | Heutiger Stand |
|---|---|---|
| Generator (`generate_verwaltung_corpus.py`) | Wer eine Korpusänderung vornimmt — reguläre Entwickler-Issue-Arbeit, kein dedizierter Owner. Review durch den Code Reviewer wie bei jedem PR. | vorhanden (#1042) |
| Korpus (`eval/corpus/verwaltung/*.md`, `MANIFEST.sha256`) | Wird nie von Hand editiert — jede Änderung läuft ausschließlich über einen Generator-Lauf, committet als Teil desselben PRs, der den Generator ändert. | vorhanden (#1042) |
| Golden Dataset (`eval/golden/verwaltung.json`) | QA Engineer (`docs/AGENT-ORGANIZATION.md`, „der QA Engineer ist Eigentümer der RAG-Evaluierung im laufenden Betrieb"), umgesetzt im Schritt-D-Issue. | **noch nicht vorhanden** |
| Baseline (`eval/baseline/verwaltung.json`) | QA Engineer, analog zu `eval/baseline/README.md` für die bestehenden zwei Domänen. | **noch nicht vorhanden** |

## Wie eine Baseline-Neuziehung abläuft — und woran sie erkennbar bewusst war

Gilt ab Schritt D, hier bereits festgehalten, damit das Verfahren nicht erst beim ersten
tatsächlichen Bedarf entschieden werden muss (Spezifikation, Abschnitt 5: „Nachträglich sind
beide nicht mehr wahrheitsgemäß auszufüllen — die Begründungen sind dann rekonstruiert statt
festgehalten").

1. **Auslöser benennen.** Eine Korpus- oder Golden-Dataset-Änderung hat immer einen konkreten
   Grund: einen Fehler im Generator, eine neue Fallklasse, eine Erweiterung der Ämterliste, eine
   Anpassung an einen geänderten Anwendungsdefault (`chunk-size`, `chunk-overlap`). Der Grund
   steht in der PR-Beschreibung, nicht nur im Commit-Betreff.
2. **Generator ändern, laufen lassen, Determinismus erneut belegen.** Zwei aufeinanderfolgende
   Läufe müssen weiterhin byte-identische Ausgabe erzeugen (Vergleich der SHA-256-Summen aller
   generierten Dateien, wie in `SOURCE.md` für #1042 dokumentiert) — sonst ist der Korpus nicht
   mehr eingefroren im Sinne von ADR-0011.
3. **`MANIFEST.sha256` wird vom Generator-Lauf selbst neu geschrieben**, nie von Hand editiert.
   Ein PR, der den Korpus ändert, ändert damit immer auch das Manifest — beide gehören in denselben
   Commit.
4. **Chunk-Zahl-Invariante erneut prüfen.** `./gradlew evalUnitTest --tests
   "io.opaa.eval.VerwaltungChunkSizeDryRunTest"` muss grün bleiben (Docker-frei, siehe oben). Ab
   Schritt D zusätzlich: `./gradlew evaluateVerwaltungRetrieval`/`checkVerwaltungRetrievalBaseline`
   (Namen vorbehaltlich der Schritt-D-Umsetzung, analog zu den bestehenden zwei Domänen).
5. **Golden Dataset gegen den neuen Korpus neu kuratieren, sofern der Korpus sich geändert hat**
   (ab Schritt D). Ein Golden-Case, dessen `expected_documents` sich durch die Korpusänderung
   verschiebt, muss vor dem Merge erkannt werden — nicht erst durch einen roten Regressionslauf.
6. **Baseline-Update ist ein eigener, erkennbar benannter Commit** (ab Schritt D), analog zu
   `eval/baseline/README.md`: „Baseline-Aktualisierungen sind bewusste, reviewte Commits."
   Erkennbarkeit heißt konkret: Commit-Typ `chore(eval)` oder `feat(eval)`, PR-Beschreibung nennt
   den Auslöser aus Schritt 1 und die alten sowie neuen Metrikwerte (sobald eine Baseline
   existiert). Eine Baseline-Datei wird **nie** im selben Commit wie eine unabhängige
   Code-Änderung mitgeändert — sonst lässt sich aus dem `git log` allein nicht mehr entscheiden,
   ob eine Verschiebung eine bewusste Neuziehung oder ein Nebeneffekt war.
7. **Was *keine* Neuziehung auslöst:** eine reine Dokumentationsänderung an diesem Verzeichnis
   (`SOURCE.md`, `MAINTENANCE.md`) oder eine Änderung am Generator, die nachweislich keine Ausgabe
   verändert (z. B. ein Kommentar oder eine Umbenennung einer internen Variable) — solange Punkt 2
   das belegt.

## `known_gap`-Fälle

**Derzeit keine** — diese Domäne hat noch kein Golden Dataset (siehe „Stand dieser Domäne" oben),
also existiert noch kein einziger Fall, der als `solved` oder `known_gap` klassifiziert sein
könnte. Diese Zeile ist absichtlich nicht gelöscht, sondern wird beim Schritt-D-Issue durch die
tatsächliche Liste ersetzt — eine leere `known_gap`-Liste ist ein geprüfter Zustand, kein
vergessener Abschnitt (Spezifikation, Abschnitt 5: „auch das Ergebnis „keine" gehört
festgehalten, damit es später nicht als ungeprüft gilt", dort zwar für Sentinel-Werte formuliert,
gilt hier sinngemäß für `known_gap`).

Erwartbar bei der ersten Kuratierung (nicht bindend, nur eine Einschätzung für Schritt D): Die
`metadata_filter`-Fallklasse (Abschnitt 5e der Spezifikation) misst eine heute nicht vorhandene
Produktfähigkeit — es gibt keinen Metadatenfilter in der Suche (Roadmap 2f) —, ihre Fälle würden
also zunächst als `known_gap` erwartet, bis Roadmap 2f geliefert ist. Das ist keine Aussage über
diesen Korpus, sondern eine Vorwegnahme dessen, was Schritt D vorfinden wird.

## Overfitting-Risiko

Siehe [`SOURCE.md`](SOURCE.md), Abschnitt „Overfitting-Risiko", und
`docs/features/retrieval-benchmark.md`, Abschnitt 4, „Ehrliche Einschränkung:
Benchmark-Overfitting". Kurzfassung: Diese Domäne hat keine echten Nutzerfragen; jedes Ergebnis
auf ihr ist eine Aussage über konstruierte Annahmen, nicht über echte Verwaltungsanfragen, bis
eine Stichprobe echter (anonymisierter) Anfragen aus einem Pilotbetrieb nachgezogen wird.
