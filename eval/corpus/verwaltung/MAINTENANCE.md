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
Korpusdatei läuft durch die produktive `MarkdownDocumentPipeline` und den produktiven
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

## Overfitting-Risiko

Siehe [`SOURCE.md`](SOURCE.md), Abschnitt „Overfitting-Risiko", und
`docs/features/retrieval-benchmark.md`, Abschnitt 4, „Ehrliche Einschränkung:
Benchmark-Overfitting". Kurzfassung: Diese Domäne hat keine echten Nutzerfragen; jedes Ergebnis
auf ihr ist eine Aussage über konstruierte Annahmen, nicht über echte Verwaltungsanfragen, bis
eine Stichprobe echter (anonymisierter) Anfragen aus einem Pilotbetrieb nachgezogen wird.
