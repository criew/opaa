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
2026-08-31 gezogen. Der Docker-freie Chunk-Zahl-Nachweis
(`io.opaa.eval.VerwaltungChunkSizeDryRunTest`) bleibt daneben bestehen, ergänzt um
`io.opaa.eval.GoldenCaseCurationTest`, das die Kuratierungsregeln und jeden `answer_span` ohne
Docker prüft.

## Wer pflegt was

| Teil | Pflegeverantwortung | Heutiger Stand |
|---|---|---|
| Generator (`generate_verwaltung_corpus.py`) | Wer eine Korpusänderung vornimmt — reguläre Entwickler-Issue-Arbeit, kein dedizierter Owner. Review durch den Code Reviewer wie bei jedem PR. | vorhanden (#1042) |
| Korpus (`eval/corpus/verwaltung/*.md`, `MANIFEST.sha256`) | Wird nie von Hand editiert — jede Änderung läuft ausschließlich über einen Generator-Lauf, committet als Teil desselben PRs, der den Generator ändert. | vorhanden (#1042) |
| Golden Dataset (`eval/golden/verwaltung.json`) | QA Engineer (`docs/AGENT-ORGANIZATION.md`, „der QA Engineer ist Eigentümer der RAG-Evaluierung im laufenden Betrieb"). Von Hand kuratiert, kein Generator — die Regeln stehen in `io.opaa.eval.GoldenCaseCuration` und werden von `GoldenCaseCurationTest` auf die committete Datei angewandt. | vorhanden (#1043), 46 Fälle |
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
soll (9 von 9 „gelöst" ohne die Bedingung, 4 von 9 mit ihr).

**Eine bekannte Asymmetrie, absichtlich so geführt:** `verw-comp-006` ist auf dem Rohvektor-Pfad
gelöst, auf dem Pipeline-Pfad nicht, und wird deshalb als `known_gap` geführt. Der
Zustandsfelder-Abschnitt des **Rohvektor**-Reports nennt diesen Fall in jedem Lauf unter „als
known_gap geführt, aber gelöst". Das ist kein offener Befund, sondern die sichtbare Folge der
Regel „gelöst nur auf beiden Pfaden"; wer den Abschnitt liest, prüft, ob **weitere** Fälle
dazugekommen sind.

Jede Zustandsänderung ist ein bewusster Vorgang mit Datum und Begründung im selben PR wie ihr
Auslöser — nie eine Datenpflege nebenbei. Der Zustandsfelder-Abschnitt beider Reports meldet
Abweichungen in beide Richtungen; er lässt den Lauf bewusst **nicht** fehlschlagen, weil die
Entscheidung über einen Zustandswechsel eine menschliche ist.

## `known_gap`-Fälle

**33 von 46 Fällen**, Stand 2026-08-31 (erste Kuratierung, Issue #1043). Das ist der Zweck dieser
Domäne, kein Mangel: „Ein Fall, den heute keine Variante löst, ist der wertvollste im Datensatz"
(`docs/features/retrieval-benchmark.md`, Abschnitt 4). Die Begründung steht je Fall im Feld
`expected_state_reason`; die Tabellen unten führen zusätzlich das gemessene Symptom.

| Klasse | Fälle | davon `known_gap` | fehlender Baustein |
|---|---|---|---|
| `literal_term_weak_embedding` | 9 | 9 | lexikalischer Pfad und Fusion (Roadmap 1a/1b) — die #938-Klasse |
| `exact_identifier` | 10 | 2 | Schutz unzerlegter Kennungs-Tokens (Roadmap 1a) |
| `compound_word` | 9 | 9 | Komposita-Zerlegung (Roadmap 1a) |
| `multi_hop` | 9 | 8 | Zusammenführung mehrgliedriger Ketten (Messgrundlage für Roadmap 3c) |
| `metadata_filter` | 9 | 5 | Metadatenfilter in der Suche (Roadmap 2f) |

Der für die Eintrittsbedingung aus Abschnitt 6 der Spezifikation eigentliche Befund:
`literal_term_weak_embedding` ist **vollständig** ungelöst (0 von 9), obwohl der Anfragebegriff
wörtlich im Zieldokument steht — während `exact_identifier` auf demselben Korpus 8 von 10 löst.
Die Domäne ist also nicht pauschal schwer; die Lücke ist klassenspezifisch.

### literal_term_weak_embedding (9 Fälle)

| Fall | Symptom im Lauf vom 2026-08-31 (Pipeline-Pfad) |
|---|---|
| `verw-lit-001` | außerhalb des Fensters: verwaltung-0038_verwaltungsgebuehrensatzung.md |
| `verw-lit-002` | außerhalb des Fensters: verwaltung-0043_formularhinweis-kaemmerei-8.md |
| `verw-lit-003` | außerhalb des Fensters: verwaltung-0042_formularhinweis-kaemmerei-7.md, verwaltung-0043_formularhinweis-kaemmerei-8.md |
| `verw-lit-004` | außerhalb des Fensters: verwaltung-0040_dienstanweisung-kaemmerei-1-2024.md, verwaltung-0041_dienstanweisung-kaemmerei-2-2024.md |
| `verw-lit-005` | im Fenster, aber Rang 1: verwaltung-vertretungsregelung.md |
| `verw-lit-006` | im Fenster, aber Rang 1: verwaltung-geschaeftsverteilungsplan.md |
| `verw-lit-007` | außerhalb des Fensters: verwaltung-0038_verwaltungsgebuehrensatzung.md |
| `verw-lit-008` | im Fenster, aber Rang 1: verwaltung-0045_gebuehrenordnung-personalamt.md |
| `verw-lit-009` | außerhalb des Fensters: verwaltung-0038_verwaltungsgebuehrensatzung.md |

### exact_identifier (2 Fälle)

| Fall | Symptom im Lauf vom 2026-08-31 (Pipeline-Pfad) |
|---|---|
| `verw-id-002` | im Fenster, aber Rang 1: verwaltung-0004_dienstanweisung-sozialamt-1-2023.md |
| `verw-id-005` | im Fenster, aber Rang 1: verwaltung-0022_dienstanweisung-ordnungsamt-2-2024.md |

### compound_word (9 Fälle)

| Fall | Symptom im Lauf vom 2026-08-31 (Pipeline-Pfad) |
|---|---|
| `verw-comp-001` | außerhalb des Fensters: verwaltung-0031_personalausweisgebuehrensatzung-fassung-2023.md, verwaltung-0032_personalausweisgebuehrensatzung-fassung-2024.md, verwaltung-0033_gebuehrenordnung-buergeramt.md |
| `verw-comp-002` | außerhalb des Fensters: verwaltung-0057_abfallgebuehrensatzung.md |
| `verw-comp-003` | außerhalb des Fensters: verwaltung-0063_bibliotheksbenutzungsgebuehrensatzung.md |
| `verw-comp-004` | außerhalb des Fensters: verwaltung-0017_gewerbeanmeldegebuehrensatzung-fassung-2023.md, verwaltung-0018_gewerbeanmeldegebuehrensatzung-fassung-2024.md |
| `verw-comp-005` | außerhalb des Fensters: verwaltung-0009_baugenehmigungsgebuehrensatzung-fassung-2023.md, verwaltung-0010_baugenehmigungsgebuehrensatzung-fassung-2024.md |
| `verw-comp-006` | außerhalb des Fensters: verwaltung-0050_kindertagesstaettenbeitragssatzung-fassung-2023.md |
| `verw-comp-007` | außerhalb des Fensters: verwaltung-0025_personenstandsurkundengebuehrensatzung.md |
| `verw-comp-008` | außerhalb des Fensters: verwaltung-0044_personalaktenauskunftsgebuehrensatzung.md |
| `verw-comp-009` | außerhalb des Fensters: verwaltung-0001_sozialgebuehrenbefreiungssatzung-fassung-2023.md |

### multi_hop (8 Fälle)

| Fall | Symptom im Lauf vom 2026-08-31 (Pipeline-Pfad) |
|---|---|
| `verw-hop-001` | außerhalb des Fensters: verwaltung-0038_verwaltungsgebuehrensatzung.md, verwaltung-vertretungsregelung.md |
| `verw-hop-002` | außerhalb des Fensters: verwaltung-vertretungsregelung.md |
| `verw-hop-004` | außerhalb des Fensters: verwaltung-0018_gewerbeanmeldegebuehrensatzung-fassung-2024.md |
| `verw-hop-005` | im Fenster, aber Rang 1: verwaltung-0064_gebuehrenordnung-kulturamt.md |
| `verw-hop-006` | außerhalb des Fensters: verwaltung-vertretungsregelung.md |
| `verw-hop-007` | im Fenster, aber Rang 1: verwaltung-0064_gebuehrenordnung-kulturamt.md |
| `verw-hop-008` | außerhalb des Fensters: verwaltung-vertretungsregelung.md |
| `verw-hop-009` | außerhalb des Fensters: verwaltung-vertretungsregelung.md |

### metadata_filter (5 Fälle)

| Fall | Symptom im Lauf vom 2026-08-31 (Pipeline-Pfad) |
|---|---|
| `verw-meta-001` | im Fenster, aber Rang 1: verwaltung-0004_dienstanweisung-sozialamt-1-2023.md |
| `verw-meta-002` | außerhalb des Fensters: verwaltung-0009_baugenehmigungsgebuehrensatzung-fassung-2023.md |
| `verw-meta-004` | außerhalb des Fensters: verwaltung-0031_personalausweisgebuehrensatzung-fassung-2023.md |
| `verw-meta-006` | im Fenster, aber Rang 1: verwaltung-0004_dienstanweisung-sozialamt-1-2023.md |
| `verw-meta-008` | im Fenster, aber Rang 1: verwaltung-0021_dienstanweisung-ordnungsamt-1-2024.md |

Die Einschätzung aus dem #1042-Stand dieser Datei — die `metadata_filter`-Fälle würden zunächst
vollständig als `known_gap` erwartet — hat sich damit **nur teilweise** bestätigt: 4 der 9 Fälle
sind gelöst, weil die richtige Fassung dort auch ohne Metadatenfilter auf Rang 1 landet.
Festgehalten, weil eine Vorhersage, die man nach der Messung stillschweigend anpasst, nichts mehr
wert ist.

## Overfitting-Risiko

Siehe [`SOURCE.md`](SOURCE.md), Abschnitt „Overfitting-Risiko", und
`docs/features/retrieval-benchmark.md`, Abschnitt 4, „Ehrliche Einschränkung:
Benchmark-Overfitting". Kurzfassung: Diese Domäne hat keine echten Nutzerfragen; jedes Ergebnis
auf ihr ist eine Aussage über konstruierte Annahmen, nicht über echte Verwaltungsanfragen, bis
eine Stichprobe echter (anonymisierter) Anfragen aus einem Pilotbetrieb nachgezogen wird.
