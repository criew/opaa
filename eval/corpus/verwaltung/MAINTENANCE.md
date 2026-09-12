# Pflege der Domäne `verwaltung`

Beantwortet die drei Fragen, die `docs/features/retrieval-benchmark.md` (Abschnitt 5,
„Zustandsfelder: ungelöste Fälle bleiben ungelöst benannt") für jede Domäne mit neuen
Golden-Fall-Klassen verbindlich verlangt: wer welchen Teil pflegt, wie eine Baseline-Neuziehung
abläuft und woran sie erkennbar bewusst war, und welche Fälle derzeit als `known_gap` geführt
werden.

## Stand dieser Domäne (Issues #1042/#1043)

Vollständig: Generator, Korpus und Manifest (Schritt C, Issue #1042) sowie Golden Dataset,
Zustandsfelder, beide Baselines und die Registrierung im Retrieval-Harness (Schritt D, Issue
#1043 — `EvalDomainConfig.VERWALTUNG`, `VerwaltungRetrievalEvaluationHarnessTest`). Die Domäne
läuft seither als eigener Task und im nächtlichen Regressionsjob:

```bash
cd backend
./gradlew evaluateVerwaltungRetrieval       # misst beide Pfade, schreibt beide Reports
./gradlew checkVerwaltungRetrievalBaseline  # misst und vergleicht gegen beide Baselines
```

Beide Messpfade sind von Anfang an beurteilt: `eval/baseline/verwaltung.json` (Rohvektor) und
`eval/baseline/pipeline-verwaltung.json` (Pipeline) wurden im selben CPU-Testcontainer-Lauf am
2026-09-01 gezogen. Der Docker-freie Chunk-Zahl-Nachweis
(`io.opaa.eval.VerwaltungChunkSizeDryRunTest`) bleibt daneben bestehen, ergänzt um
`io.opaa.eval.GoldenCaseCurationTest`, das die Kuratierungsregeln und jeden `answer_span` ohne
Docker prüft.

## Füllstand der Kernfelder auf diesem Korpus (Issue #1070, Teil 2)

Die Eintrittsbedingung des Kernfeld-Filters verlangt einen ausgewiesenen Füllstand
(`docs/features/metadata-schema.md`, „Eintrittsbedingung für den Kernfeld-Filter"). Für den
Eval-Korpus misst ihn `io.opaa.eval.VerwaltungCorpusMetadataFillLevelTest` Docker-frei: Jede
Korpusdatei läuft durch das produktive `MarkdownDocumentFormat` und den produktiven
`CoreMetadataExtractor` mit dem ausgelieferten Vokabular — dieselben zwei Schritte wie im
Indexlauf, nur ohne Datenbank. Die Zahlen sind im Test **festgenagelt**, damit eine Generator- oder
Extraktoränderung, die sie bewegt, eine bewusste Entscheidung ist:

| Kernfeld | Füllstand | Fehlende |
|---|---|---|
| Titel | 72 von 72 (100 %) | — |
| Dokumentart | 49 von 72 (68 %) | 22 Dokumente mit `formularhinweis`/`vertretungsregelung`/`geschaeftsverteilungsplan` — Werte, die das ausgelieferte Vokabular nicht kennt — plus das Leerwert-Dokument `verwaltung-leitfaden-barrierefreiheit.md` |
| Datum/Stand | 71 von 72 (99 %) | `verwaltung-dienstanweisung-aktenaufbewahrung.md` (das Leerwert-Dokument) |

**Der Korpus erreicht die committete Schwelle für die Dokumentart bewusst nicht** (0,90; Datum/Stand
0,75 dagegen deutlich). Das ist eine Aussage über diesen Korpus, nicht über die Extraktion: Er führt
absichtlich 22 Dokumente einer Art, die das ausgelieferte Vokabular nicht kennt, und ein Dokument
ganz ohne Art. Die Eintrittsbedingung selbst wird am echten Bestand geprüft, nicht hier — der
Nachweis auf der Demo-Instanz steht an [#1070](https://github.com/criew/opaa/issues/1070) und in
[#1065](https://github.com/criew/opaa/issues/1065). Der Benchmark misst unabhängig davon, was der
Filter tut, wenn er gesetzt ist.

## Wer pflegt was

| Teil | Pflegeverantwortung | Heutiger Stand |
|---|---|---|
| Generator (`generate_verwaltung_corpus.py`) | Wer eine Korpusänderung vornimmt — reguläre Entwickler-Issue-Arbeit, kein dedizierter Owner. Review durch den Code Reviewer wie bei jedem PR. | vorhanden (#1042) |
| Korpus (`eval/corpus/verwaltung/*.md`, `MANIFEST.sha256`) | Wird nie von Hand editiert — jede Änderung läuft ausschließlich über einen Generator-Lauf, committet als Teil desselben PRs, der den Generator ändert. | vorhanden (#1042) |
| Golden Dataset (`eval/golden/verwaltung.json`) | QA Engineer (`docs/AGENT-ORGANIZATION.md`, „der QA Engineer ist Eigentümer der RAG-Evaluierung im laufenden Betrieb"). Von Hand kuratiert, kein Generator — die Regeln stehen in `io.opaa.eval.GoldenCaseCuration` und werden von `GoldenCaseCurationTest` auf die committete Datei angewandt. | vorhanden (#1043), 49 Fälle (drei Fälle der Leerwert-Regel kamen mit #1070, Teil 2 hinzu) |
| Zustandsfelder je Fall (`expected_state`) | Wer eine Zustandsänderung auslöst (ein neuer Retrieval-Baustein), zieht sie im selben PR nach — begründet und datiert; Regel siehe unten. | vorhanden (#1043) |
| Baselines (`eval/baseline/verwaltung.json`, `eval/baseline/pipeline-verwaltung.json`) | QA Engineer, analog zu `eval/baseline/README.md` für die bestehenden zwei Domänen. | vorhanden (#1043) |

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
4. **Chunk-Zahl-Invariante und Kuratierungsregeln erneut prüfen.** `./gradlew evalUnitTest` muss
   grün bleiben (Docker-frei: `VerwaltungChunkSizeDryRunTest` für die Chunk-Zahl,
   `GoldenCaseCurationTest` für Fallklassen-Mindestzahlen, Zustandsfelder und die
   `answer_span`-Auflösung). Danach `./gradlew checkVerwaltungRetrievalBaseline` (braucht Docker).
5. **Golden Dataset gegen den neuen Korpus neu kuratieren, sofern der Korpus sich geändert hat.**
   Ein Golden-Case, dessen `expected_documents` sich durch die Korpusänderung verschiebt, muss vor
   dem Merge erkannt werden — nicht erst durch einen roten Regressionslauf. Ein `answer_span`, der
   durch die Änderung über eine Chunk-Grenze rutscht, fällt in `GoldenCaseCurationTest` auf.
6. **Baseline-Update ist ein eigener, erkennbar benannter Commit**, analog zu
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

## Zustandsfelder: wann ein Fall als gelöst gilt

`expected_state` ist der zuletzt **bewusst akzeptierte** Zustand eines Falls, nicht das Ergebnis
des letzten Laufs. Gesetzt wird er nach einer für alle Klassen gleichen Regel
(`io.opaa.eval.ExpectedStateAudit#isSolved`):

> Ein Fall gilt als `solved`, wenn alle seine erwarteten Dokumente im Fenster des Messpfads liegen
> **und** ein erwartetes Dokument auf Rang 1 steht — und zwar auf **beiden** Messpfaden
> (Rohvektor und Pipeline). Sonst `known_gap`.

Die Rang-1-Bedingung ist nicht Strenge um ihrer selbst willen: Die beiden Fassungen einer Satzung
unterscheiden sich nur im Frontmatter und ranken deshalb unmittelbar nebeneinander. Ohne sie wäre
jeder `metadata_filter`-Fall „gelöst", sobald die richtige Fassung irgendwo im Fenster liegt —
auch dann, wenn die falsche darüber steht. Genau das ist die Fähigkeit, die diese Klasse messen
soll.

**`metadata_filter` war bis #1070 ausnahmslos `known_gap`** (Entscheidung nach Spezifikation,
Abschnitt 5e: die Klasse maß „eine heute **nicht vorhandene** Produktfähigkeit"). Vier ihrer neun
Fälle löste die Rangfolge schon damals richtig — aber ohne Mechanismus, rein zufällig, weil die
richtige Fassung eben oben landete; sie als `solved` zu führen hätte ein Zufallsergebnis unter
Regressionsschutz gestellt.

**Seit #1070 (Teil 2) gibt es den Mechanismus**, und neun der zwölf Fälle sind mit ihm auf beiden
Messpfaden gelöst (Stand 2026-09-05): `verw-meta-002/004/006/007/008/009` über Datumsfenster bzw.
Dokumentart, `verw-meta-010/011/012` über die Leerwert-Regel. Sie stehen deshalb auf `solved` — was
sie jetzt schützt, ist eine geprüfte Fähigkeit, kein Zufall. `known_gap` bleiben drei:
`verw-meta-003`/`-005` (die Frage „derzeit gültig"/„gilt heute" ist eine Gültigkeitsaussage, die
kein Kernfeld ausdrückt — sie tragen deshalb `filter: null` mit `filter_note` und warten auf das
Bibliotheksfeld aus #1071) und `verw-meta-001`, bei dem der Filter greift, aber die Rangfolge des
Rohvektor-Pfads zwei gleich datierte Dienstanweisungen vor das erwartete Dokument stellt.

**Einordnung gegen Abschnitt 6 der Spezifikation** („Die Fallklasse zu einem Baustein MUSS
committet und kuratiert sein, bevor das Bau-Issue eröffnet wird"): Die Klasse `metadata_filter` war
das — seit #1043, vor dem Bau des Filters, und sie hat die Lücke vorher beziffert. Die drei Fälle
der Leerwert-Regel (`verw-meta-010/011/012`) sind dagegen **nach** dem Bau entstanden, weil erst der
gebaute Filter eine Leerwert-Regel hat, die man verfehlen kann; sie messen nicht die Lücke, sondern
eine Zusicherung des Ergebnisses. Diese Ausnahme ist bewusst und vom Koordinator am 04.09.2026 so
beauftragt (Kommentar an #1070) — sie taugt nicht als Vorbild für einen Fall, der eine noch
ungebaute Fähigkeit misst.

### Erwartete Abweichungen (`expected_state_exception`)

Ein Fall darf einen vierten, optionalen Text tragen: die committete Begründung, **warum** seine
gemessene Lage dauerhaft von der deklarierten abweicht. Das Audit führt solche Fälle getrennt von
den Befunden; nur unerklärte Abweichungen gelten als Befund. Ohne diese Trennung stünde in jedem
Lauf dieselbe erwartete Meldung in der Fundliste — und niemand läse sie nach dem dritten Mal noch.

Derzeit 11 Fälle:

| Fall | Grund |
|---|---|
| `verw-lit-006`, `verw-lit-008`, `verw-comp-002`, `verw-comp-003`, `verw-comp-008`, `verw-comp-009`, `verw-hop-002`, `verw-hop-005`, `verw-hop-007`, `verw-hop-009` | Pfad-Asymmetrie in die andere Richtung, seit Issue #1049: auf dem **Pipeline**-Pfad durch den lexikalischen Pfad in der Fusion gelöst, auf dem Rohvektor-Pfad strukturell nicht lösbar — dieser misst `similaritySearch` direkt und kennt den Volltextpfad nicht. Bleiben `known_gap` nach derselben Regel wie `verw-comp-006`. |
| `verw-meta-001` | Seit #1070 auf dem Pipeline-Pfad **mit** dem geprüften Mechanismus gelöst, auf dem Rohvektor-Pfad nicht: Der Filter hält den Verwechslungspartner aus beiden Fenstern, aber die Einbettungsähnlichkeit stellt dort zwei gleich datierte Dienstanweisungen vor die erwartete Satzung. |

> **Offene Frage an die Spezifikation.** Zehn dieser Ausnahmen entstehen daraus, dass ein Fall erst
> als gelöst gilt, wenn ihn *beide* Messpfade lösen. Diese Definition stammt aus #1043, als beide
> Pfade praktisch dasselbe Retrieval maßen. Seit #1049 misst der Rohvektor-Pfad bewusst eine
> **nicht**-produktive Konfiguration; Fälle, die der lexikalische Pfad löst, kann er strukturell nie
> lösen. Ob die Definition auf den Pipeline-Pfad — den produktiven — umgestellt werden sollte, ist
> eine Entscheidung über den gemeinsamen Messvertrag beider Pfade und wurde in #1049 bewusst nicht
> getroffen (siehe ADR-0012, Nachtrag Volltextpfad, Entscheidung 23).

Jede Zustandsänderung ist ein bewusster Vorgang mit Datum und Begründung im selben PR wie ihr
Auslöser — nie eine Datenpflege nebenbei. Der Zustandsfelder-Abschnitt beider Reports **und** beider
Markdown-Delta-Tabellen (Job-Zusammenfassung, PR-Kommentar, Alarm-Issue) meldet Abweichungen in
beide Richtungen; er lässt den Lauf bewusst **nicht** fehlschlagen, weil die Entscheidung über einen
Zustandswechsel eine menschliche ist.

## `known_gap`-Fälle

**30 von 49 Fällen**, Stand 2026-09-05. Mit Issue #1070 (Teil 2) sind neun Fälle der Klasse
`metadata_filter` auf `solved` gewechselt — der erste Zustandswechsel dieser Klasse überhaupt, und
der Beleg dafür, dass der Kernfeld-Filter geliefert hat, was er versprochen hat (Einzelbegründung je
Fall im Datensatz, Zusammenfassung oben). Die Angaben des folgenden Absatzes beschreiben den Stand
davor.

**Stand vor #1070: 36 von 46 Fällen**, 2026-09-01. Mit Issue #1049 hatte sich genau **ein** Zustand geändert:
`verw-comp-006` ist von `known_gap` auf `solved` gewechselt — er ist der einzige Fall, den seither
**beide** Messpfade lösen, und damit der einzige, der die Solved-Definition erfüllt. Elf weitere
Fälle löst nur der Pipeline-Pfad; sie bleiben `known_gap` und haben ihre Pfad-Asymmetrie als
erwartete Abweichung nachgezogen bekommen (siehe oben). Das ist der Zweck dieser Domäne, kein
Mangel: „Ein Fall, den heute keine Variante löst, ist der wertvollste im Datensatz"
(`docs/features/retrieval-benchmark.md`, Abschnitt 4). Die Begründung steht je Fall im Feld
`expected_state_reason`; die Tabellen unten führen zusätzlich das gemessene Symptom.

| Klasse | Fälle | davon `known_gap` | fehlender Baustein |
|---|---|---|---|
| `literal_term_weak_embedding` | 9 | 9 | lexikalischer Pfad und Fusion (Roadmap 1a/1b) — die #938-Klasse |
| `exact_identifier` | 10 | 2 | Schutz unzerlegter Kennungs-Tokens (Roadmap 1a) |
| `compound_word` | 9 | 8 | Komposita-Zerlegung (Roadmap 1a) |
| `multi_hop` | 9 | 8 | Zusammenführung mehrgliedriger Ketten (Messgrundlage für Roadmap 3c) |
| `metadata_filter` | 12 | 3 | für `verw-meta-003`/`-005`: Bibliotheksfeld Gültigkeit (#1071); für `verw-meta-001`: kein fehlender Baustein, sondern die Rangfolge des Rohvektor-Pfads |

Der Befund der ersten Kuratierung (Stand vor #1049): `literal_term_weak_embedding` war
**vollständig** ungelöst (0 von 9), obwohl der Anfragebegriff wörtlich im Zieldokument steht —
während `exact_identifier` auf demselben Korpus 8 von 10 löste. Die Domäne ist also nicht pauschal
schwer; die Lücke ist klassenspezifisch.

**Stand nach Issue #1049** (lexikalischer Pfad in der Fusion), gemessen auf dem Pipeline-Pfad — der
Rohvektor-Pfad bleibt bei den Zahlen der ersten Kuratierung:

| Klasse | gelöst gemessen: Rohvektor | gelöst gemessen: Pipeline vor #1049 | gelöst gemessen: Pipeline nach #1049 |
|---|---|---|---|
| `literal_term_weak_embedding` | 0 von 9 | 0 von 9 | **2 von 9** |
| `exact_identifier` | 8 von 10 | 8 von 10 | 8 von 10 |
| `compound_word` | 1 von 9 | 0 von 9 | **5 von 9** |
| `multi_hop` | 1 von 9 | 1 von 9 | **5 von 9** |
| `metadata_filter` | 4 von 9 | 4 von 9 | 4 von 9 |

Zwölf Fälle löst der Pipeline-Pfad seither zusätzlich, **einen verliert er**: `verw-meta-003` stand
vorher auf Rang 1 der richtigen Fassung, seither belegt ihn dort ein lexikalischer Treffer. Die
Klasse `metadata_filter` bleibt deshalb bei vier gelösten Fällen, obwohl `verw-meta-001` neu
hinzukommt — die Zahl ist gleich, die Menge nicht. In jeder Metrik dieser Klasse geht es dennoch
aufwärts (Hit Rate@5 0,667 → 1,000), weil der verlorene Fall den ersten Rang, nicht das Fenster
verliert.

Von den zwölf neu gelösten Fällen löst nur `verw-comp-006` auch der Rohvektor-Pfad; nur er wechselt
deshalb auf `solved`. Die übrigen elf bleiben `known_gap` mit committeter Pfad-Asymmetrie (siehe die
offene Frage oben). Die Klassenwerte der Baseline bewegen sich unabhängig davon deutlich
(`eval/baseline/pipeline-verwaltung.json`).

### literal_term_weak_embedding (9 Fälle)

| Fall | Symptom im Lauf vom 2026-09-01 (Pipeline-Pfad, nach #1049) |
|---|---|
| `verw-lit-001` | außerhalb des Fensters: verwaltung-0038_verwaltungsgebuehrensatzung.md |
| `verw-lit-002` | außerhalb des Fensters: verwaltung-0043_formularhinweis-kaemmerei-8.md |
| `verw-lit-003` | im Fenster, aber Rang 1: verwaltung-0042_formularhinweis-kaemmerei-7.md |
| `verw-lit-004` | außerhalb des Fensters: verwaltung-0040_dienstanweisung-kaemmerei-1-2024.md, verwaltung-0041_dienstanweisung-kaemmerei-2-2024.md |
| `verw-lit-005` | im Fenster, aber Rang 1: verwaltung-vertretungsregelung.md |
| `verw-lit-006` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-lit-007` | außerhalb des Fensters: verwaltung-0038_verwaltungsgebuehrensatzung.md |
| `verw-lit-008` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-lit-009` | im Fenster, aber Rang 1: verwaltung-0040_dienstanweisung-kaemmerei-1-2024.md |

### exact_identifier (2 Fälle)

| Fall | Symptom im Lauf vom 2026-09-01 (Pipeline-Pfad, nach #1049) |
|---|---|
| `verw-id-002` | im Fenster, aber Rang 1: verwaltung-0004_dienstanweisung-sozialamt-1-2023.md |
| `verw-id-005` | im Fenster, aber Rang 1: verwaltung-0022_dienstanweisung-ordnungsamt-2-2024.md |

### compound_word (8 Fälle, `verw-comp-006` ist seit #1049 gelöst)

| Fall | Symptom im Lauf vom 2026-09-01 (Pipeline-Pfad, nach #1049) |
|---|---|
| `verw-comp-001` | außerhalb des Fensters: verwaltung-0031_personalausweisgebuehrensatzung-fassung-2023.md, verwaltung-0032_personalausweisgebuehrensatzung-fassung-2024.md, verwaltung-0033_gebuehrenordnung-buergeramt.md |
| `verw-comp-002` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-comp-003` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-comp-004` | außerhalb des Fensters: verwaltung-0017_gewerbeanmeldegebuehrensatzung-fassung-2023.md, verwaltung-0018_gewerbeanmeldegebuehrensatzung-fassung-2024.md |
| `verw-comp-005` | außerhalb des Fensters: verwaltung-0009_baugenehmigungsgebuehrensatzung-fassung-2023.md, verwaltung-0010_baugenehmigungsgebuehrensatzung-fassung-2024.md |
| `verw-comp-006` | seit #1049 auf beiden Pfaden gelöst — **nicht mehr `known_gap`**, siehe oben |
| `verw-comp-007` | außerhalb des Fensters: verwaltung-0025_personenstandsurkundengebuehrensatzung.md |
| `verw-comp-008` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-comp-009` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |

### multi_hop (8 Fälle)

| Fall | Symptom im Lauf vom 2026-09-01 (Pipeline-Pfad, nach #1049) |
|---|---|
| `verw-hop-001` | außerhalb des Fensters: verwaltung-0038_verwaltungsgebuehrensatzung.md, verwaltung-vertretungsregelung.md |
| `verw-hop-002` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-hop-004` | außerhalb des Fensters: verwaltung-0018_gewerbeanmeldegebuehrensatzung-fassung-2024.md |
| `verw-hop-005` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-hop-006` | außerhalb des Fensters: verwaltung-vertretungsregelung.md |
| `verw-hop-007` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-hop-008` | außerhalb des Fensters: verwaltung-vertretungsregelung.md |
| `verw-hop-009` | gelöst auf dem Pipeline-Pfad seit #1049 **(erwartete Abweichung: Pfad-Asymmetrie)** |

### metadata_filter (3 Fälle, Stand 2026-09-05 nach #1070)

| Fall | Symptom im Abnahmelauf vom 2026-09-05 (beide Messpfade, mit Kernfeld-Filter) |
|---|---|
| `verw-meta-001` | Filter greift (Fassung 2023 in keinem Fenster), aber auf dem Rohvektor-Pfad belegen zwei gleich datierte Dienstanweisungen des Sozialamts die Ränge 1 und 2 **(erwartete Abweichung: Pfad-Asymmetrie)** |
| `verw-meta-003` | ohne Kernfeld-Filter gemessen (`filter: null`, siehe `filter_note`): Rang 1 belegt verwaltung-0017_gewerbeanmeldegebuehrensatzung-fassung-2023.md — braucht #1071 |
| `verw-meta-005` | ohne Kernfeld-Filter gemessen: Rang 1 belegt verwaltung-0050_kindertagesstaettenbeitragssatzung-fassung-2023.md — braucht #1071 |

Die Einschätzung aus dem #1042-Stand dieser Datei — die `metadata_filter`-Fälle würden zunächst
vollständig als `known_gap` erwartet — hat sich in der Sache bestätigt, wenn auch aus einem anderen
Grund als vermutet: Nicht weil die Rangfolge sie alle verfehlte (vier von neun traf sie), sondern
weil ohne Filtermechanismus auch ein Treffer keine Fähigkeit belegte. Mit #1070 ist genau das
nachgeholt: Die neun gelösten Fälle sind es **mit** dem Mechanismus, und die beiden Fehlerrichtungen
des Filters (`MetadataFilterAudit`) sind auf beiden Pfaden ohne Befund.

### Was der Abnahmelauf an den übrigen vier Klassen bewegt hat

Die zwei Leerwert-Dokumente vergrößern den Korpus von 70 auf 72 Dateien; kein Fall außerhalb von
`metadata_filter` trägt einen Filter, ihr Delta ist die Selbstprüfung dieser Neuziehung. Auf dem
**Pipeline-Pfad ist es exakt null** — jede Zahl der vier Klassen ist unverändert (dort greift die
Produktionsschwelle 0,30, die beiden neuen Dokumente erreichen sie bei keiner ihrer Fragen). Auf dem
**Rohvektor-Pfad**, der ohne Schwelle bei `documentTopK=10` misst, tauchen sie in einzelnen Fenstern
auf und verschieben zwei Werte:

| Klasse | Kennzahl | vorher | nachher |
|---|---|---|---|
| `literal_term_weak_embedding` | HitRate@5 | 0,444 | 0,333 |
| `literal_term_weak_embedding` | MRR / nDCG@10 | 0,244 / 0,335 | 0,236 / 0,325 |
| `compound_word` | nDCG@10 | 0,849 | 0,844 |

Ein einziger Fall (`verw-lit-004`) verliert seinen Top-5-Treffer: Beide neuen Dokumente stehen dort
im Fenster (Ränge 1 und 4) und schieben das erwartete Dokument auf Rang 9. `exact_identifier` und
`multi_hop` sind unverändert. Das wird hier benannt
statt geglättet: Ein größerer Korpus kann auf dem schwellenlosen Messpfad jedes Fenster verschieben —
wer die beiden Dokumente entfernte, verlöre dafür die Messbarkeit der Leerwert-Regel.

## Mehrrunden-Fallklassen (Issues #1485/#1490)

Drei weitere Klassen messen seit #1485 Gespräche statt Einzelfragen: `anaphora_resolution`,
`topic_switch` und `constraint_carryover` (`docs/features/conversation-memory.md`, Abschnitt
„Messung"). Sie liegen in einem **eigenen** Datensatz mit **eigener** Baseline und laufen nicht im
`checkVerwaltungRetrievalBaseline` der Domäne mit, sondern in einem eigenen Task-Paar:

```bash
cd backend
./gradlew evaluateVerwaltungConversations     # nur messen und berichten
./gradlew checkVerwaltungConversationBaseline # messen und gegen die Baseline urteilen
```

Beide Tasks setzen `-Dopaa.eval.runConversations` und `-Dopaa.eval.queryDecomposition` selbst
(#1553); ohne beide meldet sich der Schritt als **nicht ausgeführt** und schreibt nichts. In der CI
trägt ihn seit #1553 der eigene Job `conversations` in
`.github/workflows/retrieval-regression.yml` — nächtlich, per `workflow_dispatch` und beim Label
`evaluation`, mit eigenem Zeitbudget. Der Preis ist der Grund für das eigene Budget: ein
Zerlegungs- und ein Notiz-Aufruf je **Runde**, und nach der Mehrfachlauf-Regel dreimal — 498
Modellaufrufe für 83 Runden. Gemessene Laufzeiten: rund 14 Minuten je Messung auf einer
Entwicklermaschine (CPU-Testcontainer), 52 Minuten für den ganzen Task-Lauf einschließlich
Indizierung und Einzelfragen-Teil; auf einem GitHub-Runner 118 Minuten (Lauf 34648243211).

**Einpfad-Regel.** Für diese drei Klassen gilt „gelöst" auf dem **Pipeline-Pfad allein**, nicht wie
sonst auf beiden: Der Rohvektor-Pfad misst `similaritySearch` direkt und kennt weder Gesprächsverlauf
noch Zerlegung, ein Mehrrunden-Fall kann dort konstruktionsbedingt nicht laufen
(`docs/features/retrieval-benchmark.md`, Abschnitt 5). Die Einpfadigkeit ist eine Eigenschaft des
Datensatzes und steht deshalb einmal je Bericht (`singlePathNote`) — sie ist **kein**
`expected_state_exception` am Fall, weil das Zustandsfeld-Audit sonst dauerhaft leer liefe.

### Zwei Konstruktionsregeln, die beim Kuratieren zu beachten sind

Beide stammen aus dem Review zu PR #1521 und haben dort je eine Neumessung ausgelöst; sie gelten
für jede künftige Ergänzung dieser Klassen.

1. **Das Zieldokument einer Runde, die eine Auflösung messen soll, muss amtsspezifisch sein.**
   `verwaltung-vertretungsregelung.md` und `verwaltung-geschaeftsverteilungsplan.md` decken alle
   zehn Ämter in je einer Datei ab und sind im Korpus einzigartig. Eine Rückfrage auf eines der
   beiden trägt ihre Trefferlast über „vertritt"/„zuständig" allein; der aufgelöste Bezug trägt
   nichts bei — im ungünstigen Fall **bestraft** eine korrekte Auflösung sie sogar, weil sie die
   Dokumente des aufgelösten Amtes nach vorn zieht. Maßstab: Die Runde muss ohne aufgelösten Bezug
   scheitern **und** mit ihm gelingen können.
2. **Die Kurzantwort der Runde 1 eines `constraint_carryover`-Falls darf den Verwechslungspartner
   nicht benennen.** Sie geht als `AssistantMessage` ins Gesprächsfenster, und das Fenster geht in
   die Zerlegung: Ein Satz wie „… galt bis zum 31. Dezember 2023; sie wurde durch die
   Fassung 2024 ersetzt" stellt den Bezeichner des Verwechslungspartners als Aussage des Assistenten
   in den Kontext. Inhaltlich ist der Satz belegt — als Messaufbau macht er den zentralen Befund
   mehrdeutig und prüfte die Gesprächsnotiz gegen einen selbst eingebauten Störer statt gegen die
   Rahmenübernahme.

### Wer pflegt was

| Teil | Pflegeverantwortung | Heutiger Stand |
|---|---|---|
| `anaphora_resolution` (9 Fälle, 21 Runden) | QA Engineer. Die Fälle hängen an einzelnen Korpusdokumenten: Eine Generator-Änderung, die einen Formularhinweis oder eine Dienstanweisung umbenennt, macht die Rückfrage der Folgerunde unauflösbar. Regel 1 oben gilt für jede neue Runde. | 5 von 9 gelöst (#1490; vorher 4) |
| `topic_switch` (9 Fälle, 30 Runden) | QA Engineer. Zusätzlich zu prüfen ist bei jeder Korpusänderung, dass die erwarteten Dokumente der Wechselrunde und die der Vorrunden **disjunkt** bleiben — sonst zählt die Bleed-Zahl das richtige Dokument mit. `topic_switch_turn` wird nie abgeleitet, sondern am Fall benannt. | 2 von 9 gelöst (#1490; vorher 2), Bleed 2 Dokumente in 2 von 9 Wechselrunden |
| `constraint_carryover` (9 Fälle, 32 Runden) | QA Engineer. Der Verwechslungspartner ist das Empfindliche: Er muss inhaltsgleich zum Ziel bleiben und sich nur in der Rahmenangabe (Fassung/Jahr) unterscheiden. Verschwindet eine der beiden Fassungen aus dem Korpus, misst die Klasse nichts mehr. Regel 2 oben gilt für jede neue Runde-1-Antwort. | 1 von 9 gelöst (#1490; vorher 4) |
| Mehrrunden-Datensatz (`eval/golden/verwaltung-conversations.json`) | QA Engineer, wie beim Einzelfragen-Datensatz. Von Hand kuratiert, kein Generator; die Regeln stehen in `io.opaa.eval.ConversationCaseCuration` und werden von `ConversationCaseCurationTest` Docker-frei auf die committete Datei angewandt. | 27 Fälle mit 83 Runden, 8 `solved` / 19 `known_gap` (#1490) |
| Mehrrunden-Baseline (`eval/baseline/pipeline-verwaltung-conversations.json`) | QA Engineer, analog zu den beiden anderen Baselines dieser Domäne; Neuziehung nach demselben Verfahren wie oben. | Neu gezogen mit #1490 (Lauf vom 2026-09-12); verglichen von `VerwaltungConversationBaselineRegressionTest` über `checkVerwaltungConversationBaseline` (#1553) |

### Referenzbefund vor Fenster und Notiz (2026-09-11, Issue #1485)

CPU-Testcontainer, `qwen2.5:1.5b-instruct` bei Temperatur 0, Gesprächsfenster 20 Nachrichten
(am produktiven `ChatMemory` gemessen), Suchfenster = ganzes Gesprächsfenster, keine Gesprächsnotiz.
Median aus drei Läufen. Dies ist die **Vorher**-Seite des Vergleichs unten.

| Klasse | Fälle | gelöst gemessen | Bleed |
|---|---|---|---|
| `anaphora_resolution` | 9 | 4 | — |
| `topic_switch` | 9 | 2 | 0 Dokumente in 0 von 9 Wechselrunden |
| `constraint_carryover` | 9 | 4 | — |

Je Runde (alle Klassen zusammen): Runde 1 nDCG@8 0,913 · Runde 2 0,649 · Runde 3 0,704 · Runde 4
0,857 · Runde 5 1,000; über alle 83 Runden Hit Rate@5 0,831 / MRR@8 0,756 / nDCG@8 0,771 /
Recall@8 0,827.

**Die Rundennummer ist nicht der saubere Schnitt.** Die Gruppe `turn:2` mischt drei Bauarten:
neun echte Rückfragen (`anaphora_resolution`), fünf Wechselrunden mit explizitem Marker und neun
`constraint_carryover`-Zwischenthemen, die konstruktionsbedingt eigenständige Fragen sind. Der
Abstand zwischen Runde 1 und Runde 2 belegt deshalb für sich genommen nichts — das galt für den
Referenzlauf und gilt unverändert für jede Nachmessung.

### Befund der Nachmessung (2026-09-12, Issue #1490)

Derselbe Aufbau, jetzt mit dem verengten Suchfenster (**2 Runden**, #1486) und der Gesprächsnotiz
(**Deckel 10**, #1487). Median aus drei Läufen; min = median = max in allen vier Metriken, 0 von 83
Runden mit abweichender Zerlegung, und ein zweiter, vollständig getrennter Aufruf lieferte jede Zahl
bitgleich. Alle 83 Notiz-Verdichtungen sind gelungen.

**Je Klasse, über die Runden der Klasse:**

| Klasse | n | Hit Rate@5 | MRR@8 | nDCG@8 | Recall@8 |
|---|---|---|---|---|---|
| `anaphora_resolution` | 21 | 0,762 → **0,810** | 0,724 → **0,762** | 0,722 → **0,763** | 0,746 → **0,794** |
| `topic_switch` | 30 | 0,767 → **0,833** | 0,701 → **0,761** | 0,713 → **0,776** | 0,767 → **0,833** |
| `constraint_carryover` | 32 | 0,938 → **0,969** | 0,829 → **0,790** | 0,857 → **0,835** | 0,938 → **0,969** |
| gesamt | 83 | 0,831 → **0,880** | 0,756 → **0,772** | 0,771 → **0,796** | 0,827 → **0,876** |

**Je Klasse, auf Fallebene** (gelöst = jede Runde der Klasse gelöst):

| Klasse | Fälle | gelöst vorher | gelöst jetzt | Bleed |
|---|---|---|---|---|
| `anaphora_resolution` | 9 | 4 | **5** | — |
| `topic_switch` | 9 | 2 | **2** | 0 → **2** Dokumente in 2 von 9 Wechselrunden |
| `constraint_carryover` | 9 | 4 | **1** | — |
| gesamt | 27 | 10 | **8** | |

**Je Runde:** Runde 1 (n=27) nDCG@8 0,913 → 0,936 · Runde 2 (n=27) 0,649 → 0,853 · Runde 3 (n=21)
0,704 → 0,597 · Runde 4 (n=7) 0,857 → 0,652 · Runde 5 (n=1) 1,000 → 0,631.

**Verbesserte Fälle (3):** `verw-conv-ana-006`, `verw-conv-ts-001`, `verw-conv-cc-002`.
**Verschlechterte Fälle (5):** `verw-conv-ts-009`, `verw-conv-cc-001`, `verw-conv-cc-003`,
`verw-conv-cc-005`, `verw-conv-cc-009`. Auf Rundenebene sind 10 Runden hinzugekommen und 12
weggefallen.

#### Die Vergleichsgröße: Zielrunden, deren Teilfrage die Rahmenangabe trägt

**4 von 9 im Referenzlauf, 1 von 9 hier** — und nicht einer der vier von damals. Die Erwartung war,
dass #1486 diese Zahl gegen null drückt und #1487 sie wiederherstellt; der erste Teil trifft zu, der
zweite nicht.

Der Bericht führt seit #1490 je Runde auch die Notizpunkte, die sie erhalten hat (Feld
`conversationNote`), sodass sich zwei Ursachen trennen lassen, die vorher beide wie „die Notiz
wirkt nicht" aussahen:

| Zielrunde | Notizpunkte, die sie erhielt | trägt die Teilfrage die Angabe? |
|---|---|---|
| `cc-001#3` | „Arzt" | nein |
| `cc-002#3` | „2023" | **ja** („galt es 2023") |
| `cc-003#4` | „Prüfer", „Bearbeiter" | nein |
| `cc-004#3` | „Rechtssprechende, Zuständigkeit: Ordnungsamt, Ort: Berlin, **Zeitraum: 2023**, …" | nein |
| `cc-005#4` | „Berater", „Formular für Personensegmentierung" | nein |
| `cc-006#3` | „Prüfer" | nein — die Teilfrage behauptet stattdessen die Fassung **2024** |
| `cc-007#3` | „Mitarbeiter", „Formular UMW-08" | nein |
| `cc-008#5` | „Bauamt" | nein |
| `cc-009#4` | „Person, Zuständigkeit: Finanzamt, …, **Zeitraum: seit 2024, Fassung: 2024**, …", „STA-08" | nein |

1. **Die Notiz trägt die Rahmenangabe in nur 3 von 9 Fällen** (`cc-002`, `cc-004`, `cc-009`). In den
   übrigen sechs ist der einzige `RAHMEN`-Punkt ein Rollenwort ohne Jahr. Ursache ist das Format der
   Verdichtung: `qwen2.5:1.5b-instruct` füllt die Aufzählung der Arten aus dem Prompt als Vorlage
   aus („RAHMEN: Arzt", „ZUSTANDGEBIT: …", „ZEITALGM: 2023", „FASUNG: Fassung 2023", …), statt
   höchstens zwei Zeilen mit `RAHMEN:`/`ANTWORTFORM:`-Präfix zu liefern. `ChatNoteExtraction.parse`
   nimmt die ersten zwei Zeilen — die Jahres- oder Fassungszeile steht an vierter bis sechster
   Stelle und fällt heraus.
2. **Wo die Notiz sie trägt, nimmt die Zerlegung sie in einem von drei Fällen auf** (`cc-002` ja,
   `cc-004` und `cc-009` nein). Die Teilfrage der Zielrunde ist fast durchgängig eine **erfundene
   Aussage** über den Gegenstand statt einer Suchanfrage — dasselbe Fehlerbild, das der
   Referenzbefund schon für `anaphora_resolution` beschrieben hat.

Beides ist ein Ergebnis, kein Messfehler: Die Notiz ist gebaut, verdrahtet und in jeder der 83
Runden erfolgreich verdichtet worden; sie erreicht die Zerlegung auch — aber der Weg von der
Nutzeräußerung über die Verdichtung bis in die Teilfrage hält die Fassungsangabe nicht. Als
Folgearbeit außerhalb von Epic #1482 aufgenommen in **#1586**.

#### Der Vergleich ist kein reines A/B über Fenster und Notiz

#1487 hat der **festen** Zerlegungs-Instruktion eine Zeile über die Gesprächsnotiz hinzugefügt, die
bei jedem Aufruf mitgeht — auch bei einem ohne Notiz und ohne Verlauf. Messbar: **18 der 27 ersten
Runden** liefern andere Teilfragen als im Referenzlauf, **13 der 27** eine andere Trefferliste,
obwohl eine erste Runde weder ein Gesprächsfenster noch eine Notiz hat. Drei Fall-Urteile hängen an
solchen Runden: `cc-006#1` und `cc-007#1` sind hinzugekommen, `cc-009#1` ist weggefallen.

Wer die Klassenzahlen liest, liest also die Summe aus drei Änderungen (Suchfenster, Notiz,
Instruktionszeile), nicht aus zweien. Eine saubere Trennung kostet einen weiteren Messlauf mit
abgeschalteter Notiz-Zeile und ist als **#1587** aufgenommen, nicht in #1490 enthalten.

#### Wo die Nachmessung von der Vorhersage der Spezifikation abweicht

`docs/features/conversation-memory.md`, Abschnitt „Reihenfolge", sagt für die Schritte 3 und 4 drei
Dinge voraus. **Eine trifft zu, zwei nicht.**

- **„`anaphora_resolution` darf nicht fallen" — trifft zu.** Die Klasse steigt in allen vier
  Metriken und von 4 auf 5 gelöste Fälle.
- **„`topic_switch` muss steigen" — trifft auf der Metrikebene zu, auf der Fallebene nicht.** Alle
  vier Rundenmetriken steigen, die Zahl gelöster Fälle bleibt bei 2 von 9: `ts-001` kommt hinzu,
  `ts-009` fällt weg.
- **„`constraint_carryover` muss mindestens die Referenz aus Schritt 2 wieder erreichen" — trifft
  nicht zu.** Auf Fallebene 1 statt 4; MRR@8 und nDCG@8 der Klasse liegen unter der Referenz
  (0,829 → 0,790 bzw. 0,857 → 0,835), Hit Rate@5 und Recall@8 darüber. Das ist der eine
  Nachweis, den dieser Schritt schuldig bleibt.

### Themen-Bleed: warum die Zahl wenig taugt

Die Bleed-Zahl zählt ausschließlich die **erwarteten Dokumente der Vorrunden dieses Falls**, die im
Trefferfenster der Wechselrunde stehen. Über die neun Wechselrunden sind das zusammen **zwölf**
namentlich festgelegte Dokumente: **siebenmal** genau eines (`ts-001`, `-002`, `-003`, `-004`,
`-005`, `-006`, `-009`), einmal zwei (`ts-008`) und einmal drei (`ts-007`). Eines von ein bis
drei Dokumenten aus 72 müsste also unter die acht Treffer der Wechselrunde geraten. Jede andere
Verschmutzung durch Geschwisterdokumente des Altthemas ist per Definition unsichtbar.

**Die Kennzahl hat auf diesem Datensatz praktisch keinen Dynamikbereich — in beide Richtungen**, und
war als Kriterium schon in #1485 zurückgezogen. Die Nachmessung bestätigt das: Sie steigt von 0 auf
2 Dokumente in 2 von 9 Wechselrunden (`ts-007#3`: die Dienstanweisung des Standesamts aus Runde 2;
`ts-008#2`: die Personalaktenauskunftsgebührensatzung aus Runde 1, auf Rang 4, während das Ziel der
Wechselrunde auf Rang 1 steht). **Beide Wechselrunden sind trotzdem gelöst** — das
Altthemen-Dokument steht im Fenster, ohne das Ziel von Rang 1 zu verdrängen. `topic_switch` wird deshalb über den **Anteil gelöster
Fälle** beurteilt, nicht über die Bleed-Zahl.

Der Datensatz misst zudem eine Verschmutzung nicht, die die Nachmessung sichtbar gemacht hat: In
`ts-002#3` — einer **Folgerunde** im neuen Thema, nicht der Wechselrunde — steht der Notizpunkt
„SOZ-08" aus dem Altthema in der Teilfrage und zieht den Formularhinweis des Sozialamts auf Rang 1.
Die Bleed-Zahl sieht nur die Wechselrunde und kann das nicht zeigen.

Zwei Gründe, warum die Zahl so klein bleibt:

1. **Die Zerlegung nutzt den Verlauf kaum.** In den Wechselrunden erzeugt sie meist keine an den
   Verlauf geankerte Teilfrage, sondern eine frei erfundene Aussage über das **neue** Thema. Ein
   Altthema, das nicht in die Teilfrage gerät, kann auch nicht ins Trefferfenster bluten. Der
   Sicherheitsgurt greift dabei oft: `QueryDecompositionService` verwirft die Zerlegung als
   „degenerate"/„pruned" und fällt auf die Einzelanfrage zurück.
2. **Fünf der neun Wechselfragen tragen eine Kennung** („Formular STA-07", „BUE-DA-2/2024") und sind
   damit für sich allein tragfähig; die vier übrigen (`ts-002`, `-003`, `-006`, `-007`) nennen ihr
   neues Thema immerhin mit vollem Namen. **Eine kurze, unterbestimmte Wechselfrage fehlt im
   Datensatz** — sie wäre die einzige Bauart, bei der die Zerlegung überhaupt auf den Verlauf
   zurückgreifen müsste. Ob ein bis zwei solche Fälle nachgezogen werden, ist eine Entscheidung
   außerhalb dieses Epics und keine Datenpflege: Sie kostet eine bewusste Baseline-Neuziehung.

### `known_gap`-Fälle

**19 von 27 Fällen**, Stand 2026-09-12. Die Einzelbegründung steht je Fall im Feld
`expected_state_reason`; die Tabellen führen zusätzlich das gemessene Symptom.

#### anaphora_resolution (4 Fälle)

| Fall | Symptom im Lauf vom 2026-09-12 (Pipeline-Pfad) |
|---|---|
| `verw-conv-ana-004` | Runde 2 mit falschem Rang 1 (verwaltung-0039_gebuehrenordnung-kaemmerei.md, Ziel auf Rang 2); Runde 3 außerhalb des Fensters |
| `verw-conv-ana-005` | Runde 2 außerhalb des Fensters: verwaltung-0022_dienstanweisung-ordnungsamt-2-2024.md; Rang 1 belegt die Vertretungsregelung |
| `verw-conv-ana-007` | Runde 2 außerhalb des Fensters: verwaltung-0029_formularhinweis-standesamt-7.md; Runde 3 hält es nur auf Rang 2 (im Referenzlauf war Runde 3 gelöst) |
| `verw-conv-ana-009` | Runde 1 findet die Gebührenordnung Jugendamt nicht (zwei von drei erwarteten Dokumenten); Runde 3 außerhalb des Fensters: verwaltung-0054_dienstanweisung-jugendamt-2-2024.md |

> **Vermerk zu `verw-conv-ana-004#3`** („Gilt **sie** auch für eine Eilbearbeitung?" →
> `verwaltung-0039_gebuehrenordnung-kaemmerei.md`): Diese Runde ist der Bauart nach kein
> Anaphern-, sondern ein Multi-Hop-Fall. Das Wort „Kämmerei" fällt im ganzen Gesprächsfenster nicht;
> der Schritt Verwaltungsgebührensatzung → Kämmerei → deren Gebührenordnung steht nur im
> Geschäftsverteilungsplan. Der Fall bleibt im Datensatz — er ist `known_gap` und verzerrt keine
> Zahl nach oben —, taugt aber nicht als Beleg für oder gegen einen Fenster-Baustein. Die
> Nachmessung bestätigt das: Die Runde ist unverändert offen.

#### topic_switch (7 Fälle)

| Fall | Wechselrunde | Symptom im Lauf vom 2026-09-12 (Pipeline-Pfad) |
|---|---|---|
| `verw-conv-ts-002` | 2 (gelöst) | Folgerunde 3 im neuen Thema offen: der Notizpunkt „SOZ-08" aus dem Altthema steht in der Teilfrage, verwaltung-0008_formularhinweis-sozialamt-8.md verdrängt die Abfallgebührensatzung |
| `verw-conv-ts-003` | 3 (gelöst) | Vorrunde 2 mit falschem Rang 1: verwaltung-0039_gebuehrenordnung-kaemmerei.md, Ziel auf Rang 2 |
| `verw-conv-ts-004` | 2 (gelöst) | Folgerunde 3 außerhalb des Fensters: verwaltung-0048_formularhinweis-personalamt-7.md; die Rückkehr zum Altthema in Runde 4 verliert verwaltung-0060_dienstanweisung-umweltamt-2-2024.md |
| `verw-conv-ts-005` | 2 (gelöst) | Folgerunde 3 außerhalb des Fensters: verwaltung-0035_dienstanweisung-buergeramt-2-2024.md |
| `verw-conv-ts-007` | 3 (gelöst, mit Bleed) | Vorrunde 2 mit falschem Rang 1, Ziel auf Rang 3; in der Wechselrunde 3 steht dasselbe Altthemen-Dokument im Fenster |
| `verw-conv-ts-008` | 2 (gelöst, mit Bleed) | Folgerunde 3 im neuen Thema außerhalb des Fensters: verwaltung-0006_dienstanweisung-sozialamt-2-2024.md |
| `verw-conv-ts-009` | 2 (offen) | **Rückschritt:** Wechselrunden 2 und 3 nennen KAE-DA-1/2024 in der Teilfrage, ordnen sie aber dem Jugendamt des Altthemas zu; verwaltung-0053 bzw. verwaltung-0054 belegen Rang 1 |

#### constraint_carryover (8 Fälle)

| Fall | Symptom im Lauf vom 2026-09-12 (Pipeline-Pfad) |
|---|---|
| `verw-conv-cc-001` | **Rückschritt:** Zielrunde 3 stellt die Fassung 2024 auf Rang 1; Notizpunkt „Arzt" ohne Jahr |
| `verw-conv-cc-003` | **Rückschritt:** Zielrunde 4 stellt SOZ-DA-2/2024 auf Rang 1, Ziel auf Rang 2; zusätzlich offen ist das Zwischenthema in Runde 2 |
| `verw-conv-cc-004` | Zielrunde 3 nimmt den Notizpunkt mit „Zeitraum: 2023" nicht auf: Verwechslungspartner ORD-DA-1/2024 auf Rang 1, Ziel auf Rang 2. Das Zwischenthema in Runde 2 ist neu gelöst |
| `verw-conv-cc-005` | **Rückschritt:** Zielrunde 4 stellt die Fassung 2023 auf Rang 1, Ziel auf Rang 4 |
| `verw-conv-cc-006` | Runde 1 neu gelöst; Zielrunde 3 dafür offen — die Teilfrage behauptet die Fassung 2024, die damit Rang 1 belegt |
| `verw-conv-cc-007` | Runde 1 neu gelöst; Zielrunde 3 offen, Ziel erst auf Rang 5 |
| `verw-conv-cc-008` | Zwischenthema in Runde 3 neu gelöst; offen sind Runde 1 und die Zielrunde 5 (BAU-DA-1/2023 auf Rang 1, Ziel auf Rang 2) |
| `verw-conv-cc-009` | **Rückschritt:** Zielrunde 4 nimmt den Notizpunkt mit „Fassung: 2024" nicht auf, Ziel auf Rang 3; Runde 1 ist ebenfalls weggefallen — sie hat weder Fenster noch Notiz und hängt an der Instruktionszeile aus #1487 |

### Erwartete Abweichungen dieser Klassen (`expected_state_exception`)

**Derzeit keine.** Die einzige, die es je gab, trug `verw-conv-cc-001`: Der Fall galt als
`known_gap`, wurde im Referenzlauf aber gelöst gemessen — ohne den geprüften Mechanismus, weil die
Teilfrage der Zielrunde die Angabe nicht trug. Mit der Nachmessung ist er **auch gemessen offen**;
die Ausnahme hat damit keinen Gegenstand mehr und ist am Fall entfernt. Ein `known_gap`-Fall, den
die heutige Rangfolge zufällig löst, bekommt sie jederzeit wieder — nach derselben Regel wie
`metadata_filter` vor #1070.

## Overfitting-Risiko

Siehe [`SOURCE.md`](SOURCE.md), Abschnitt „Overfitting-Risiko", und
`docs/features/retrieval-benchmark.md`, Abschnitt 4, „Ehrliche Einschränkung:
Benchmark-Overfitting". Kurzfassung: Diese Domäne hat keine echten Nutzerfragen; jedes Ergebnis
auf ihr ist eine Aussage über konstruierte Annahmen, nicht über echte Verwaltungsanfragen, bis
eine Stichprobe echter (anonymisierter) Anfragen aus einem Pilotbetrieb nachgezogen wird.
