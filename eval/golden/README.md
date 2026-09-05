# Golden Datasets: Comichelden, Sehenswürdigkeiten in europäischen Großstädten, Verwaltung

## Domäne `verwaltung` (Issue #1043)

`verwaltung.json` — 49 kuratierte, deutschsprachige Fälle über
[`eval/corpus/verwaltung/`](../corpus/verwaltung/). Anders als die beiden anderen Datasets misst
dieses keine Abdeckung, sondern **fünf benannte Fehlerbilder**
(`docs/features/retrieval-benchmark.md`, Abschnitt 5). Jeder Fall trägt seine Klasse als
`category`, sodass beide Messpfade sie als eigene Gruppe auswerten und jede Baseline sie einzeln
absichert.

| Klasse (`category`) | Anzahl | Was sie misst | Ground Truth |
|---|---|---|---|
| `literal_term_weak_embedding` | 9 | die #938-Klasse: „Gebührenbefreiung wegen Bedürftigkeit" steht wörtlich nur in den sechs Kämmerei-Dokumenten, die Frage ist in Bürgersprache formuliert | 1–2 Dokumente, verteilt über sechs verschiedene Zieldokument-Mengen |
| `exact_identifier` | 10 | Aktenzeichen, Formularnummern, Paragraphen mit Verwechslungspartner (`SOZ-DA-1/2023` vs. `…/2024`, `§ 3` vs. `§ 13`) | 1–2 Dokumente |
| `compound_word` | 9 | die Frage nennt einen Wortbestandteil („Ausweis"), das Dokument das Kompositum („Personalausweisgebührensatzung") | 2–3 Dokumente |
| `multi_hop` | 9 | zweistufige Ketten: Sachregelung **und** Vertretungsregelung/Geschäftsverteilungsplan | genau 2 Dokumente |
| `metadata_filter` | 12 | Fassungs- und Dokumentart-Fragen; der Verwechslungspartner ist die inhaltsgleiche andere Fassung. Seit #1070 (Teil 2) zusätzlich drei Fälle der Leerwert-Regel (`verw-meta-010/011/012`), deren Zieldokument im gefilterten Feld gar keinen Wert trägt | 1 Dokument |

**Erzeugung:** keine. Diese Fälle sind **von Hand gegen den Korpus kuratiert** — es gibt bewusst
kein Generatorskript, weil die Fälle laut Spezifikation „aus den Dokumenten heraus formuliert"
werden und ein Generator nur die mechanische Hälfte davon reproduzieren könnte. Abgesichert ist
das Ergebnis stattdessen durch Regeln statt durch einen Erzeugungsweg:
`io.opaa.eval.GoldenCaseCuration` und `GoldenCaseCurationTest`, das diese Regeln Docker-frei auf
die committete Datei anwendet und zusätzlich jeden `answer_span` **aller drei Domänen** durch die
produktive `MarkdownDocumentPipeline` auflöst (dieselbe Pipeline, auf die `DocumentPipelineRegistry`
`.md` seit #1103 routet — alle drei Korpora bestehen ausschließlich aus Markdown):

- mindestens acht Fälle je Klasse,
- mindestens **sechs unterschiedliche Treffermengen** je Klasse — die Fallzahl allein liefert nicht,
  wofür sie da ist: Neun Fälle, die alle dasselbe Dokument erwarten, sind eine Beobachtung, und die
  Toleranz aus ADR-0013 rechnet ohnehin mit `distinctExpectedDocumentSets` statt mit `n`
  (Herleitung der Sechs: `GoldenCaseCuration.MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS`),
- vollständige Zustandsfelder mit ISO-Datum, Treffermenge im Fenster [1, 15], eindeutige
  `id`/`query`, jedes erwartete Dokument im Manifest.

### Zustandsfelder (Issue #1043)

Jeder Fall führt drei Pflichtfelder — ab dem ersten committeten Fall, nicht nachgerüstet:

```json
"expected_state": "known_gap",
"expected_state_since": "2026-09-01",
"expected_state_reason": "Fehlender lexikalischer Pfad (Roadmap 1a/1b): …; gemessen am 2026-09-01 (Pipeline-Pfad): …"
```

Ein Fall darf zusätzlich `expected_state_exception` tragen: die committete Begründung, warum
seine gemessene Lage dauerhaft von der deklarierten abweicht (eine Pfad-Asymmetrie, oder ein
`known_gap`-Fall, den die Rangfolge heute ohne den geprüften Mechanismus zufällig löst). Das Audit
führt solche Fälle getrennt von den Befunden — sonst stünde in jedem Lauf dieselbe erwartete Meldung
in der Fundliste.

**Wann ein Fall als `solved` gilt** (`io.opaa.eval.ExpectedStateAudit#isSolved`): alle erwarteten
Dokumente im Fenster **und** ein erwartetes Dokument auf Rang 1 — und zwar auf **beiden**
Messpfaden. Die Rang-1-Bedingung ist nicht Kosmetik: Beide Fassungen einer Satzung sind inhaltlich
nahezu identisch und ranken deshalb nebeneinander, sodass „die richtige Fassung liegt irgendwo im
Fenster" auch dann erfüllt ist, wenn die falsche obenauf steht — genau die Fähigkeit, die
`metadata_filter` messen soll. Ohne die Zusatzbedingung wären 9 von 9 Fällen dieser Klasse
„gelöst", mit ihr sind es 4 — und auch diese vier werden als `known_gap` geführt, weil ein Treffer
ohne Filtermechanismus keine Fähigkeit belegt (Begründung in
[`../corpus/verwaltung/MAINTENANCE.md`](../corpus/verwaltung/MAINTENANCE.md)).

Der Bericht beider Pfade führt in jedem Lauf einen Abschnitt „Zustandsfelder", der die deklarierten
Zustände gegen die gemessenen hält und beide Abweichungsrichtungen namentlich nennt — als JSON-Feld
`expectedStateAudit`, als Textblock im Lauf-Log **und** als Markdown-Abschnitt unter der
Delta-Tabelle, die der nächtliche Job in Job-Zusammenfassung, PR-Kommentar und Alarm-Issue
veröffentlicht. Ein
`known_gap`-Fall, den ein neuer Baustein löst, wird damit sichtbar, statt als stillschweigende
Baseline-Verbesserung durchzugehen; das Nachziehen der Felder bleibt ein bewusster,
dokumentierter Schritt (Verfahren: [`../corpus/verwaltung/MAINTENANCE.md`](../corpus/verwaltung/MAINTENANCE.md)).

### Filterfelder der Klasse `metadata_filter` (Issue #1070, Teil 2)

Seit #1070 sagt ein `metadata_filter`-Fall selbst, mit welchem Kernfeld-Filter er gemessen wird —
beide Messpfade wenden genau diesen Filter innerhalb ihrer Abfrage an (Rohvektor als
`Filter.Expression`, Pipeline über den Request), gebaut aus demselben `MetadataFilterExpressions`
wie in der Produktion:

```json
"filter": {
  "documentType": ["DIENSTANWEISUNG"],
  "documentDateFrom": "2024-01-01",
  "documentDateTo": "2024-12-31"
},
"confusable_document": "verwaltung-0004_dienstanweisung-sozialamt-1-2023.md",
"no_value_field": "documentDate"
```

| Feld | Bedeutung |
|---|---|
| `filter` | Die zwei filterbaren Kernfelder: Dokumentart als Liste von **Produktionscodes** (`SATZUNG_ORDNUNG`, `DIENSTANWEISUNG`, … — nie die Frontmatter-Rohwerte des Korpus) und ein einschließendes Von/Bis-Fenster auf Datum/Stand. Jede Hälfte optional; ein Feld ohne Wert wird weggelassen, nicht auf `null` gesetzt. `null` für einen Fall, den kein Kernfeld ausdrücken kann. |
| `filter_note` | Pflicht, wenn ein `metadata_filter`-Fall **keinen** Filter trägt: die committete Begründung, warum kein Kernfeld die Frage trifft (`verw-meta-003`/`-005` fragen nach der „derzeit gültigen" Fassung — eine Gültigkeits-/Nachfolgeaussage, kein Datumsvergleich; sie bleiben `known_gap` bis #1071). |
| `confusable_document` | Das Dokument, das der Filter ausschließen soll — die andere Fassung, die falsche Dokumentart. Ohne dieses Feld ließe sich „der Filter greift nicht" nicht von „es wurde gar kein Filter ausgewertet" unterscheiden. |
| `no_value_field` | `documentType` oder `documentDate`: markiert einen Fall der **Leerwert-Regel** — das erwartete Dokument trägt in genau diesem gefilterten Feld keinen Wert und muss trotzdem im Fenster bleiben. |

`io.opaa.eval.GoldenCaseCuration` erzwingt die Zusammenhänge Docker-frei: ein
`metadata_filter`-Fall trägt einen Filter mit mindestens einer Bedingung **oder** eine
`filter_note`; ein leeres `filter`-Objekt (das, wozu ein vertippter Schlüssel deserialisiert) ist
ein Verstoß; ein `confusable_document` existiert im Manifest und ist nie selbst erwartet; ein
`no_value_field` verlangt einen Filter auf genau dieses Feld.

**Was daraus gemessen wird:** `io.opaa.eval.MetadataFilterAudit` weist die beiden Fehlerrichtungen
**getrennt** aus — „Filter greift nicht" (der Verwechslungspartner steht trotz Filter im Fenster)
und „Filter greift zu stark" (ein Leerwert-Fall verliert sein erwartetes Dokument) —, je Messpfad,
im JSON-Report, im Lauf-Log und in der Markdown-Delta-Tabelle. Gemittelt wird nichts: Ein
Gesamtwert aus beiden Richtungen verschwiege, welche der beiden gerade schiefliegt.

### `answer_span` bei mehreren Zieldokumenten — entschieden (offener Punkt 4)

`docs/features/retrieval-benchmark.md` ließ offen, ob die Chunkebenen-Metrik bei Fällen mit mehreren
Zieldokumenten **je Dokument** oder **je Fall** gebildet wird. Entschieden mit diesem Datensatz:
**je Fall — und nur für Fälle mit genau einem erwarteten Dokument.** 27 der 49 Fälle tragen deshalb
einen `answer_span`, die 22 mehrdokumentigen keinen. Begründung, Alternative und Durchsetzung stehen
bei `GoldenCaseCuration.SINGLE_DOCUMENT_ANSWER_SPAN_RULE` und im Nachtrag zu
[ADR-0012](../../docs/decisions/0012-messvertrag-retrieval-harness.md); kurz: ein einzelner Span auf
einem Fall, dessen Antwort über zwei Dokumente verteilt ist, misst eine Hälfte und meldet sie als
Ergebnis des ganzen Falls. Die Regel entspricht dem, was `city-landmarks` bereits praktiziert
(`multi_city`/`multi_topic` ohne `answer_span`), nur ist sie jetzt geprüft statt Gewohnheit.

---

## Domäne `city-landmarks` (Issue #234)

`city-landmarks.json` — 108 kuratierte Fälle, sechs Kategorien, alle Fragen auf Deutsch (siehe
`eval/corpus/city-landmarks/`):

| Kategorie | Anzahl | Ground Truth |
|---|---|---|
| `city_overview` | 15 | Dokument (Stadtfakt aus dem Dokumentkopf) |
| `landmark_detail` | 25 | Dokument + `answer_span` (Detail zu einer Sehenswürdigkeit) |
| `boundary_span` | 20 | Dokument + `answer_span`, gezielt nahe einer Chunk-Grenze ausgewählt |
| `cross_chunk` | 15 | Dokument + `answer_span` (Vergleichssatz zwischen zwei Sehenswürdigkeiten desselben Dokuments — siehe unten zur Schema-Einschränkung) |
| `multi_city` | 8 | mehrere Dokumente (Einwohnervergleich zwischen zwei Städten) |
| `multi_topic` | 20 | mehrere Dokumente (Frage nennt zwei Sehenswürdigkeiten aus zwei verschiedenen Stadtdokumenten, beide in `expected_documents` — Issue #913, Vorher-Messung für #912/#914). 15 Fälle `difficulty: medium`, 5 Tippfehler-Varianten in einer der beiden Entitäten mit `difficulty: hard` (analog zum realen Auslöser „perosonausweis" aus #912). Manuell kuratiert direkt gegen die generierten `.md`-Dateien, nicht über eines der beiden Generator-Skripte — die Fälle sind an dieser Stelle handgeschrieben, um gezielt Fakten aus zwei unterschiedlichen Dokumenten zu kombinieren, was keines der beiden bestehenden Skripte vorsieht. |

**Erzeugung:** Nicht über `generate_golden_dataset.py` (das Skript ist an das Frontmatter-Schema
von `comic-characters` gebunden), sondern über ein eigenständiges, ebenfalls deterministisches
Skript, das die generierten `.md`-Dateien direkt parst (reguläre Ausdrücke auf bekannte
Satzschablonen des Generators) und die Chunk-Map aus einem Docker-freien Trockenlauf
(`io.opaa.eval.CityLandmarksChunkSizeDryRunTest`, schreibt
`backend/build/eval-reports/chunk-map-city-landmarks-dryrun.json`) für die `boundary_span`-Auswahl
liest — siehe PR-Beschreibung von #234 für den vollständigen Skriptinhalt. Jeder `answer_span` ist
vor dem Schreiben der Datei gegen den Volltext des jeweiligen Dokuments geprüft (`in`-Test in
Python) — kein Fall mit einem nicht auffindbaren Ausschnitt wurde geschrieben. Die endgültige
Auflösung auf den tatsächlichen Chunk-Index erfolgt weiterhin durch den echten Harness-Lauf
(`evaluateCityLandmarksRetrieval`), der die Chunk-Map aus den tatsächlich indexierten Dokumenten neu
berechnet — der Trockenlauf ist eine Kuratierungshilfe, kein Ersatz für diese Verifikation (siehe
`io.opaa.eval.EvaluationReport.AnswerSpanResolutionResult`, ADR-0012 §9).

**Schema-Einschränkung bei `cross_chunk`:** Das von #721 gebaute Golden-Case-Schema (`GoldenCase`)
führt genau **ein** `answer_span`-Feld, keine Liste — die Issue-#234-Beschreibung skizziert
`cross_chunk` mit zwei Spans in verschiedenen Chunks, das tatsächlich implementierte Schema aus #721
unterstützt das nicht. Die hier gewählte, mit diesem Schema kompatible Auslegung: `answer_span` zeigt
auf den generatoreigenen Vergleichssatz („X entstand früher als Y" o. Ä.), der beide Sehenswürdigkeiten
nennt und die Antwort auf die Vergleichsfrage bereits enthält. Die Frage selbst bleibt trotzdem nur
aus dem Dokumentkontext (nicht aus dem Vergleichssatz-Chunk allein, falls dieser Satz durch
Chunk-Overlap in einen anderen Chunk als die Einzel-Fakten fällt) zuverlässig beantwortbar — eine
Erweiterung auf echte Mehr-Span-Fälle ist ein Folge-Issue, kein Teil dieses Umfangs.

**Bekannte Einschränkung:** Die `landmark_detail`/`boundary_span`-Fragen sind text-strukturell an
den Fakten-Satzschablonen des Generators orientiert (Baujahr, Höhe, Schutzstatus, Besucherzahl) —
sie prüfen damit eher gezielten Faktenabruf als freie Formulierungsvarianz. Das ist eine bewusste,
zeitbedingte Vereinfachung dieser ersten Fassung, keine grundsätzliche Schema-Entscheidung.

---

# Golden Dataset: Domäne Comichelden

Das Golden Dataset für die Domäne `comic-characters` (Issue #226, gemergt in PR #273; erste Runde
Review-Nachziehbedarf in Issue #274/PR #277, gemergt; zweite Runde — Sentinel-Feldbezogenheit und
Ground-Truth-Fingerabdruck — in Issue #282, dieser Stand). Spezifikation in
[`docs/features/search-quality-evaluation.md`](../../docs/features/search-quality-evaluation.md),
Abschnitt „Golden Dataset"; [ADR-0011](../../docs/decisions/0011-search-quality-evaluation-harness.md).
Es ist die Ground Truth für den Retrieval-Regressionstest aus #227.

## Dateien

| Datei | Inhalt |
|---|---|
| `comic-characters.json` | Das kuratierte Golden Dataset — 121 Fälle. Die von #227 gelesene Datei. |
| `comic-characters.candidates.json` | Alle 477 automatisch erzeugten Rohkandidaten, vor der Kuratierung. |

Beide Dateien werden von [`eval/generator/generate_golden_dataset.py`](../generator/generate_golden_dataset.py)
erzeugt. Das Skript ist deterministisch (fixe Iterationsreihenfolge, keine Zeitstempel, keine
Zufallsquelle) — zwei Läufe gegen denselben Korpus erzeugen byte-identische Ausgaben, geprüft per
`diff` zweier aufeinanderfolgender Läufe.

Es gibt bewusst **keine dritte Datei** für verworfene Kandidaten mehr (siehe
[„Warum keine `discarded.json`"](#warum-keine-discardedjson) unten).

## Lauf

```bash
cd eval/generator
python generate_golden_dataset.py
```

Voraussetzung: der Korpus unter `eval/corpus/comic-characters/` muss vorhanden sein (#225). Das
Skript liest ausschließlich das YAML-Frontmatter der dortigen Markdown-Dateien; es lädt nichts aus
dem Netz und benötigt keine Zusatzpakete (Python-Standardbibliothek genügt).

## Wie die Ground Truth entsteht

Jeder Fall wird aus dem strukturierten Frontmatter berechnet, nie aus einer LLM-Vermutung oder von
Hand geschätzt (siehe Spezifikation, Abschnitt „Ableitung aus dem Frontmatter"). Fünf Kategorien,
mit Vorlage und Berechnungsregel:

| Kategorie | Vorlage | Ground Truth | Beispiel |
|---|---|---|---|
| `attribute_lookup` | „What eye color does {name} have?" | genau die eine Datei | `comic-attr-001` |
| `entity_description` | Paraphrase über 3–4 unterscheidende Attribute, ohne den Namen zu nennen | genau die eine Datei — nur aufgenommen, wenn die Attributkombination im gesamten Korpus eindeutig ist | `comic-desc-001` |
| `multi_attribute_filter` | „Which {alignment}-aligned characters created by {creator} have the ability {ability}?" | alle passenden Dateien, Fenster [2, 15] | `comic-filter-001` |
| `numeric_range` | „Which characters have {a/an} {attribute} score {below/above} {n}?" | alle passenden Dateien, Fenster [2, 15] | `comic-range-001` |
| `crosslingual` | deutsche Übersetzung eines bereits validierten Falls aus einer der vier Kategorien oben | identisch zur Quelle | `comic-de-001` |

Alle Vergleiche auf Freitextfeldern (`eye_color`, `alignment`, `creator`, Fähigkeitsnamen, Team-
und Beruf-Werte) sind case-insensitive (`_ci_eq`/`_ci_in` in `generate_golden_dataset.py`) — der
Korpus führt einzelne Werte in zwei Schreibweisen (siehe [„Case-Insensitivität"](#case-insensitivität-bei-freitextfeldern)
unten), und kein Embedding unterscheidet „brown" von „Brown".

## Die verbindlichen Vorgaben aus dem Review von #225 (PR #249) und #226 (PR #273)

### 1. Verunreinigte Quellspalten (`first_appearance`, `occupation`) ausschließen

`first_appearance` (Schwelle: 100 Zeichen) und `occupation` (Schwelle: 120 Zeichen) werden mit
einer Längenprüfung gegen die im Review genannten Verunreinigungsfälle abgesichert
(`Entity.first_appearance_is_plausible` / `occupation_is_plausible`). Verifiziert: Die beiden im
Review namentlich genannten Dokumente (`comic-0226_brainiac-5.md`, dessen `first_appearance` eine
Kraftfeld-Beschreibung ist; `comic-0498_gambit.md`, dessen `occupation` eine Adressliste ist)
erscheinen in keinem der 477 Rohkandidaten — durch die Plausibilitätsprüfung, nicht durch Zufall.

Zusätzlich (Issue #274): `first_appearance` ist im Quelldatensatz nicht immer ein Comic-Heft —
`comic-0099_atom-cw.md`s `first_appearance` ist z. B. „Arrow Season 3: Episode 1", eine Serie. Die
`attribute_lookup`/`crosslingual`-Vorlage fragt deshalb neutral „Where did {name} first appear?" /
„Wo trat {name} zuerst auf?" statt „In which comic did … first appear?" — die Ground Truth war
vorher schon korrekt, die Frage war nur fachlich schief formuliert.

### 2. Die Sentinel-Regel (verbindlich, feldbezogen)

Jedes numerisch verwendete Feld kann einen **Sentinel**-Wert führen: einen Platzhalter der Quelle
für „kein echter Wert", der zufällig numerisch aussieht (oder, bei `overall_score`, als die
Zeichenkette `"∞"` auftritt). Die Regel — verbindlich, unverändert bei einer künftigen Domäne
anzuwenden (siehe Spezifikation, die pro Domäne eine solche Sentinel-Tabelle mit expliziten
„keine"-Einträgen verlangt) — hat zwei Eigenschaften:

1. **Feldbezogen, nicht dokumentbezogen.** Ein Sentinel auf Feld X schließt eine Entität nur aus
   `numeric_range`-Fragen **über Feld X** aus — nicht aus jeder anderen Frage zu derselben Entität.
   Ein dokumentweiter Ausschluss würde unnötig Korpusabdeckung kosten: In `comic-characters` tragen
   123 von rund 1.450 Entitäten irgendeinen Sentinel (105× `overall_score: null`, 18×
   `overall_score: "∞"`) — über ein Viertel der Domäne, wenn der Ausschluss dokumentweit griffe.
2. **Vor der Schwellenwert-/Fensterbestimmung angewendet, nie als Nachfilter.** Sonst verschiebt
   sich die Treffermenge unbemerkt aus dem Fenster [2, 15]: Eine Frage, die mit 16 Treffern
   konstruiert und erst danach um Sentinels bereinigt wird, ist eine andere Frage als die
   validierte.

Sentinel-Tabelle für `comic-characters` (sechs numerisch verwendete Felder):

| Feld | Sentinel(s) | Geltungsbereich |
|---|---|---|
| `intelligence_score` | keine | s. Cross-Field-Regel unten |
| `strength_score` | keine | s. u. |
| `speed_score` | keine | s. u. |
| `durability_score` | keine | s. u. |
| `combat_score` | keine | s. u. |
| `overall_score` | `null`, `"∞"` | `numeric_range` auf `overall_score` selbst: beide vor Fenster-/Schwellenwertbestimmung ausgeschlossen (`Entity.has_numeric_overall` ist `isinstance(overall_score, int)` — schließt `null` und `"∞"` in einem Test aus) |

**Cross-Field-Ausnahme, klar von der Sentinel-Regel zu unterscheiden:** Die fünf Attributwerte
haben selbst keinen Sentinel. Sie werden trotzdem zusätzlich über `overall_score is not null`
gefiltert (`Entity.is_rated` in `generate_numeric_range`s `scored_entities`) — nicht weil sie selbst
einen Sentinel führen, sondern weil 104 der 105 `overall_score: null`-Dokumente im Fließtext
wortwörtlich „scores 0 for intelligence, 0 for strength, …" enthalten (Review-Fund aus #226): Dieser
Text ist für die fünf Attributfragen kontaminiert, unabhängig vom Sentinel-Konzept.
`overall_score: "∞"`-Entitäten tragen **keine** solche Kontamination — ihre fünf Attributwerte sind
gewöhnliche, vertrauenswürdige Zahlen — und werden deshalb korrekt **nicht** aus den
Attributwert-Fragen ausgeschlossen.

**Korrektur (Issue #282, zweite Review-Runde nach #277):** Die #277-Fassung dieses READMEs behauptete
an dieser Stelle, beide Eigenschaften hätten bereits in der PR-#273-Implementierung korrekt gegolten.
Das war sachlich falsch. Tatsächlich hatte der #277-Fix von Issue #274 `Entity.is_scored` (Stand
#273: `overall_score is not None`) auf ein einziges `isinstance(overall_score, int)`-Prädikat
geändert und dieses sowohl für `scored_entities` (die Cross-Field-Regel) als auch für `overall_ints`
(die `overall_score`-Sentinel-Regel selbst) wiederverwendet. Dadurch schlossen die 18
`overall_score: "∞"`-Entitäten seit dieser Änderung auch aus den fünf Attributwert-Fragen aus —
genau die Feldbezogenheit verletzend, die oben gefordert ist.

Behoben durch zwei getrennte Prädikate: `Entity.is_rated` (`overall_score is not None`) für die
Cross-Field-Regel auf den fünf Attributen, `Entity.has_numeric_overall`
(`isinstance(overall_score, int)`) für die Sentinel-Regel auf `overall_score` selbst. Code und
Dokumentation stimmen jetzt überein.

**Datenwirkung, selbst nachgerechnet:** keine. Unabhängig von der Codeänderung geprüft: Die Menge
der Entitäten, die durch den Prädikat-Fix neu in den `scored_entities`-Pool aufgenommen werden (also
zuvor fälschlich ausgeschlossen waren), umfasst genau die 18 `"∞"`-Figuren. Ihre niedrigsten Werte
je Attribut liegen bei 95 (Intelligenz), 100 (Stärke), 100 (Geschwindigkeit), 85
(Widerstandsfähigkeit) und 50 (Kampf) — alle über jeder in `BELOW_THRESHOLDS_BY_ATTRIBUTE`
verwendeten Schwelle (maximal 50). Kein aktueller Rohkandidat und kein kuratierter Fall ändert sich
dadurch; `git diff` von `comic-characters.json` und `comic-characters.candidates.json` gegen den
Stand vor dem Prädikat-Fix ist leer. In einer künftigen Domäne mit niedrigen Attributwerten bei
Sentinel-Entitäten (#234) hätte der ursprüngliche Fehler dagegen falsche Ground Truth erzeugt — dann
wäre die Feldbezogenheit nicht nur dokumentarisch, sondern faktisch verletzt gewesen.

**Bekannter Rest, außerhalb dieses Umfangs:** Der Ausschluss in der Ground Truth repariert nicht den
Widerspruch im Korpus-Text selbst — „scores 0 for intelligence, …" (bei `null`) und „his overall
score is ∞" (bei `"∞"`) stehen weiterhin im Vektorraum und werden von Embeddings gefunden. Das
gehört zum Korpus-Generator (#225), nicht zu diesem Golden Dataset — festgehalten hier, damit es bei
der Auswertung in #227 niemanden überrascht, wenn ein an sich funktionierender Retriever eine
sentinel-ausgeschlossene Entität dennoch mit hoher Ähnlichkeit zurückgibt.

### 3. `overall_score` liegt auf einer anderen Skala als die fünf Attributwerte

`numeric_range`-Fälle auf `overall_score` verwenden eigene, empirisch ermittelte Schwellenwerte
(1–237-Skala, siehe `OVERALL_SCORE_BELOW_THRESHOLDS`/`OVERALL_SCORE_ABOVE_THRESHOLDS`), nie
dieselben Zahlen wie bei den 0–100-Attributwerten. Kein Fall vermischt beide Skalen in einem
Vergleich.

## Case-Insensitivität bei Freitextfeldern

Issue #274, Fund 1: Der Korpus führt einzelne Werte in zwei Schreibweisen — `eye_color` trägt
`"Brown"` 271-mal und `"brown"` 3-mal (analog `Blue`/`blue`). Vor der Behebung verglich die
Eindeutigkeitsprüfung von `entity_description` case-sensitiv, sodass die Kandidatenkombination
„Marvel Comics, good-aligned, brown eyes, Force Fields" nur die eine kleingeschriebene Figur
(`comic-0302_cloak.md`) traf, obwohl sieben weitere Figuren mit großgeschriebenem `"Brown"` dieselbe
fachliche Antwort gewesen wären — ein Retriever, der stattdessen `comic-0358_darkhawk.md` an Position
1 setzt, wäre fachlich richtig gewesen und hätte als Fehltreffer gezählt.

Behoben durch `_ci_eq`/`_ci_in` (`str.strip().casefold()`-Vergleich) in `_matches_description` und
konsistent auch in `generate_multi_attribute_filter`. Der betroffene Kandidat fällt danach
automatisch aus der Kandidatenmenge (`len(matches) != 1`) — er wurde nicht manuell aus der
Kuratierung gestrichen, sondern existiert nach dem Fix gar nicht mehr als Kandidat. Nachprüfung nach
dem Fix: `comic-characters.candidates.json` enthält 60 `entity_description`-Kandidaten mit 60
eindeutigen Fragen und 60 eindeutigen Ground-Truth-Dateien — keine weiteren mehrdeutigen Fälle sind
durch die Normalisierung neu entstanden.

## Kuratierung

### Automatische Filter (im Generator, nicht Teil der manuellen Runde)

- Kontaminationsschwellen für `occupation`/`first_appearance`
- Sentinel-Regel für `overall_score` (feldbezogen) und die Cross-Field-Regel für die fünf
  Attributwerte (`overall_score is not null`) — siehe oben
- Fenster [2, 15] für `multi_attribute_filter` und `numeric_range`
- Case-insensitive Eindeutigkeitsprüfung für `entity_description`, case-insensitive Filterung für
  `multi_attribute_filter`
- Deduplizierung ist strukturell ausgeschlossen: jede Anfrage kombiniert Feld/Vorlage und Entität
  eindeutig, es gibt keine zwei Kandidaten mit identischer Frage

Nach diesen Filtern bleiben 477 Rohkandidaten — bei 1.448 Entitäten und einem Streufaktor von 7
(`SPREAD_STRIDE`) plus einer Obergrenze pro Feld/Vorlage (`MAX_CANDIDATES_PER_FIELD` = 20), damit
die Kandidatenliste überhaupt manuell durchsehbar bleibt.

### Streuung über die Entitäten (Issue #274, Fund „Entitäts-Konzentration" — behoben)

Vor #274 begann `_spread()` für jedes Feld/jede Vorlage an derselben Position (Index 0) im rotierten
Korpus, sodass sich viele Felder auf dieselbe kleine Gruppe „erster gültiger" Entitäten
konzentrierten: In der ursprünglichen PR-#273-Fassung trugen 60 Einzeldokument-Fälle
(`attribute_lookup` + `entity_description`) nur 29 unterschiedliche Entitäten, mit `comic-0008_abin-sur.md`
allein 11-mal.

Behoben: `_spread()` erhält jetzt einen `offset`, und `generate_attribute_lookup`/
`generate_entity_description` verteilen ihre Felder/Vorlagen auf gleichmäßig über den ganzen Korpus
verteilte Startpunkte (`field_index * (len(entities) // Feldanzahl)`). Wirkung auf die 477
Rohkandidaten: 260 Einzeldokument-Kandidaten (`attribute_lookup` + `entity_description`) verteilen
sich jetzt auf 249 unterschiedliche Entitäten, keine mehr als zweimal. Im kuratierten Datensatz (60
Einzeldokument-Fälle inkl. der `crosslingual`-Übersetzungen) liegt die maximale Wiederholung einer
Entität bei 2 — und diese Wiederholung ist beabsichtigt: die deutsche `crosslingual`-Übersetzung
eines `attribute_lookup`-Falls fragt bewusst dieselbe Entität auf Deutsch ab, das ist keine
zufällige Konzentration.

Relevanz für #228: Vor dem Fix korrelierten bis zu 11 Fälle vollständig (dieselbe erwartete Datei),
sodass die 121 Fälle effektiv weniger unabhängige Beobachtungen für eine Metrik wie Hit Rate waren
als ihre Anzahl suggeriert. Nach dem Fix ist diese Korrelation auf die beabsichtigten
en/de-Pärchen reduziert.

### Streuung bei `crosslingual` (Issue #274, Fund „`[::step][:12]`" — behoben)

Die ursprüngliche `crosslingual`-Stichprobe für `multi_attribute_filter`/`numeric_range` nahm eine
positionale Schrittweite (`items[::step][:12]`) über die bereits generierte Kandidatenliste. Das
erreichte die im Kommentar versprochene „gleichmäßige Streuung" nicht:

- Bei 16 `numeric_range`-Kandidaten rundete `step = 16 // 12` auf 1 ab — die „Stichprobe" waren
  schlicht die ersten 12 Kandidaten in Erzeugungsreihenfolge, und die Erzeugungsreihenfolge listet
  alle unteren Schwellenwerte vor den oberen. Ergebnis: keine einzige deutsche
  Bereichsfrage mit „über", alle 12 „unter".
- Bei 167 `multi_attribute_filter`-Kandidaten (`step = 13`) blieben die letzten ~23 Indizes
  (Alignment `Neutral`, spät in der Iterationsreihenfolge) unerreichbar.

Behoben durch `_sample_across_groups()`: Kandidaten werden zuerst nach Alignment (bei
`multi_attribute_filter`) bzw. Operator (`<`/`>`, bei `numeric_range`) gruppiert, dann innerhalb
jeder Gruppe mit einer eigenen Schrittweite abgetastet — weiterhin deterministisch (feste
Gruppierreihenfolge, feste Schrittweite je Gruppe), aber mit garantierter Repräsentation jeder
Gruppe. Ergebnis im kuratierten Datensatz: 12 `multi_attribute_filter`-Übersetzungen verteilt auf
4/4/4 über Good/Bad/Neutral; 12 `numeric_range`-Übersetzungen verteilt auf 8 „unter"/4 „über" (alle
4 verfügbaren „über"-Rohkandidaten sind enthalten, „unter" hat mit 12 Rohkandidaten mehr Auswahl).

### Deutsche Fähigkeits-Formulierung (Issue #274, Fund „schiefe Resistenz-Queries" — behoben)

„beherrschen die Fähigkeit {ability}" passt nicht für passive Resistenzen/Eigenschaften — eine
Resistenz „beherrscht" man nicht. Betroffen waren u. a. „Mind Control Resistance" und
„Self-Sustenance". Ersetzt durch „verfügen über die Fähigkeit {ability}", das für aktive Kräfte und
passive Resistenzen/Eigenschaften gleichermaßen passt.

### Manuelle Runde

Die manuelle Runde (Spezifikation, Abschnitt „Kuratierung": „Silver → Gold") reduziert die 477
automatisch gültigen Kandidaten auf 121 kuratierte Fälle. Da nach den automatischen Filtern fast
jeder verbliebene Kandidat einzeln korrekt ist, bestand die manuelle Arbeit vor allem aus:

1. **Streuung statt Fülle**: pro Feld/Vorlage wurden 2–8 Fälle behalten, verteilt über
   unterschiedliche Entitäten und (bei `multi_attribute_filter`) unterschiedliche
   Alignment/Creator/Fähigkeit-Kombinationen, statt alle 20 pro Feld zu übernehmen.
2. **Gewichtung der `entity_description`-Vorlagen**: Die Vorlage, die `place_of_birth` und
   `occupation` kombiniert, erzeugt lesbar unruhigeren Text als die anderen beiden — das
   `occupation`-Feld enthält im Quelldatensatz uneinheitliche Groß-/Kleinschreibung, Kommas,
   Semikola und vereinzelt fehlerhafte Wörter (z. B. „Otogakure Scientis" statt „Scientist" bei
   `comic-0986_orochimaru.md`; „Cyrus borg his helper" bei `comic-1007_p-i-x-a-l.md"). Beides sind
   echte, im Quelldatensatz so vorhandene Werte, keine Pipeline-Fehler — die Ground Truth bleibt
   korrekt, die Fälle sind nur schwerer lesbar. Diese Vorlage ist deshalb mit 4 von 20 möglichen
   Fällen unterrepräsentiert statt mit den vollen 8, die die anderen beiden Vorlagen erhalten haben,
   **und** die vier ausgewählten Fälle sind gezielt auf besonders klare `occupation`-Werte
   ausgesucht (Poison Ivy, Raphael, Starfire, Superman-2006) statt positionell aus der
   Rohkandidatenliste übernommen zu werden.
3. **`numeric_range` und `crosslingual` vollständig übernommen** (16 bzw. 34 von jeweils allen
   automatisch erzeugten Kandidaten): Beide Mengen sind bereits durch die Schwellenwertsuche bzw.
   die gruppierte Stichprobe klein und nicht-redundant; jede weitere Kürzung hätte nur Abdeckung
   gekostet, ohne Qualität zu gewinnen.

### Wie die Auswahl abgesichert ist (Issue #274, Fund 3 — behoben)

Die ursprüngliche Auswahl (PR #273) war eine Liste sequenzieller `id`-Werte
(`CURATED_CASE_IDS = ["comic-attr-001", "comic-attr-004", ...]`). Das trug nur, solange jedes der
zehn `attribute_lookup`-Felder exakt `MAX_CANDIDATES_PER_FIELD = 20` Kandidaten lieferte (200 =
10 × 20): Liefert ein Feld nach einer Korpusänderung nur noch 19, verschieben sich alle
nachfolgenden `id`s — `comic-attr-101` existiert weiterhin, fragt aber eine andere Entität und
möglicherweise ein anderes Feld ab. Der ursprüngliche Guard prüfte nur, ob die `id` **existiert**,
nicht, ob sie noch **dasselbe** bezeichnet.

Behoben: Die Auswahl (`CURATED_CASES` in `generate_golden_dataset.py`) besteht aus
(`natural_key`, `query`, `expected_documents`-Fingerabdruck)-Tripeln. `natural_key` wird
ausschließlich aus den erzeugenden Parametern abgeleitet (Feld/Entität-Dateiname,
Vorlagenindex/Entität-Dateiname, Alignment/Creator/Fähigkeit-Tripel,
Feld/Operator/Schwellenwert) — nie aus der Position in einer Liste — und identifiziert damit
„denselben" Kandidaten unabhängig davon, wie viele andere Kandidaten um ihn herum existieren.
`main()` prüft beim Lauf:

1. **Kollisionsfreiheit**: kein `natural_key` taucht bei zwei verschiedenen Kandidaten auf (harter
   Abbruch bei Verletzung — Absicherung dafür, dass ein künftiges Feld/eine künftige Vorlage die
   Eindeutigkeit nicht versehentlich verletzt).
2. **Existenz**: jeder `natural_key` aus `CURATED_CASES` muss unter den aktuell generierten
   Kandidaten auftauchen — sonst bricht der Lauf mit den fehlenden Schlüsseln ab.
3. **Fragetext**: der zur `natural_key` gehörende Kandidat muss exakt die kuratierte
   `query`-Zeichenkette tragen — sonst bricht der Lauf ab, weil sich die erzeugende Logik unter einer
   unverändert aussehenden Auswahl geändert hat.
4. **Ground-Truth-Fingerabdruck** (Issue #282, zweite Review-Runde nach #277, Fund 2): `natural_key` und `query`
   fangen eine dritte Drift-Form nicht ab — die Erwartungsmenge (`expected_documents`) ändert sich,
   während beide identisch bleiben. Der Reviewer stellte das gezielt nach: `comic-0001_3-d-man.md`
   um die Fähigkeit „Reality Warping" ergänzt (also den Korpus manipuliert, um eine
   Matching-Logik-Änderung zu simulieren) und den Generator laufen lassen —
   `comic-filter-001` ging von 7 auf 8 Treffer, `EXITCODE = 0`, `comic-characters.json` wurde still
   neu geschrieben. Der realistische Auslöser ist nicht der Korpus (den schützt
   `MANIFEST.sha256`), sondern eine Generatoränderung — z. B. ein robusterer Parser als das
   aktuelle `.split(", ")` für `superpowers`.

   Behoben durch ein drittes Tripel-Element: `sha256("|".join(sorted(expected_documents)))`
   (`expected_documents_fingerprint()`), ordnungsunabhängig über die Menge der erwarteten
   Dokumente. `main()` berechnet ihn für den zur `natural_key` gehörenden Live-Kandidaten neu und
   bricht ab, wenn er nicht mit dem kuratierten Fingerabdruck übereinstimmt.

**Verifiziert** (nicht nur behauptet, alle drei Fehlerpfade einzeln provoziert):

- Geänderte erwartete `query` → Abbruch mit „still exist but now generate a different query".
- Aus `CURATED_CASES` entfernter `natural_key` → Abbruch mit „no longer exist in the generated
  candidates".
- **Der vom Reviewer nachgestellte Fall selbst reproduziert**: `comic-0001_3-d-man.md`s
  `superpowers` im Korpus temporär um „Reality Warping" ergänzt (nicht committet, per `sha256sum -c
  MANIFEST.sha256` nach dem Zurücksetzen als unverändert bestätigt), Generator laufen lassen — Ergebnis
  jetzt: Abbruch mit „still generate the same query, but a different expected_documents set" für
  `filter::Good::Marvel Comics::Reality Warping` **und** dessen `crosslingual`-Übersetzung
  (`de::filter::Good::Marvel Comics::Reality Warping`) — der Fehler propagiert korrekt durch beide
  Kategorien, weil die deutsche Übersetzung denselben `natural_key`-Stamm referenziert. `EXITCODE = 1`,
  `comic-characters.json` wird nicht überschrieben.

Die ersten beiden Testläufe liefen mit einer temporären Kopie des Skripts, der dritte — die direkte
Nachstellung des Reviewer-Falls — mit dem committeten Code gegen eine temporär veränderte, danach
wieder zurückgesetzte Korpusdatei.

### Warum keine `discarded.json`

PR #273 schrieb zusätzlich `comic-characters.discarded.json` mit allen 356 nicht kuratierten
Kandidaten. Issue #274 stellte fest: Jeder der 356 Einträge trug denselben konstanten Text „not
selected in the manual curation round" — keine der im Modul-Docstring versprochenen automatischen
Verwerfungsgründe (Kontaminationsschwelle, leeres/zu großes Ergebnis, Duplikat) landete tatsächlich
dort, weil alle diese Fälle bereits **vor** der Kandidatenerzeugung herausgefiltert werden und nie
als `Candidate`-Objekt existieren. Die Datei war damit vollständig aus
`candidates.json − comic-characters.json` ableitbar, ohne eigenen Informationsgehalt, kostete aber
rund 370 KB in jedem Checkout.

**Entscheidung: gestrichen**, nicht mit echten Gründen nachgerüstet. Wer die verworfenen Kandidaten
braucht, rekonstruiert sie bei Bedarf:

```bash
cd eval/golden
python3 -c "
import json
curated_ids = {c['id'] for c in json.load(open('comic-characters.json', encoding='utf-8'))}
candidates = json.load(open('comic-characters.candidates.json', encoding='utf-8'))
discarded = [c for c in candidates if c['id'] not in curated_ids]
print(len(discarded), 'nicht kuratierte Kandidaten')
"
```

Der eigentliche, informative Kuratierungsgrund steht ohnehin nicht pro Kandidat, sondern hier im
README (Feld-/Vorlagen-Quoten, die drei behobenen Bugs, die Gewichtung der schwächeren
`entity_description`-Vorlage) — eine pauschale Datei mit 356 identischen Textbausteinen hätte diese
Information nicht ersetzt.

### Im Generator behoben (nicht nur aus der Kuratierung entfernt)

Drei Text-Bugs wurden während der Durchsicht der Rohkandidaten gefunden und im Generator-Code selbst
behoben, damit sie nicht nur für die kuratierten Fälle, sondern für alle Rohkandidaten korrigiert
sind:

- **„has No Hair hair"** (PR #273): Der `hair_color`-Wert „No Hair" ist ein Sentinel für „kahl",
  keine Farbe. Die `entity_description`-Vorlage, die `hair_color` verwendet, überspringt solche
  Entitäten jetzt statt den unsinnigen Satz zu erzeugen.
- **„works as cEO"** (PR #273): Die Vorlage senkt für die Mid-Satz-Einbettung den ersten Buchstaben
  von `occupation`. Bei Akronymen wie „CEO" erzeugte das „cEO". Behoben durch eine Prüfung, ob das
  erste Wort vollständig großgeschrieben ist (`_lowercase_first_word`).
- **Abgeschnittene mehrwortige Werte in `crosslingual`-Übersetzungen** (PR #273): Eine frühere
  Version extrahierte Creator- und Fähigkeitsnamen durch Aufsplitten des menschenlesbaren
  Audit-Strings am Leerzeichen, was Werte mit eigenem Leerzeichen am ersten Wort abschnitt („Dark
  Horse Comics" → „Dark", „Mind Control Resistance" → „Mind"). Behoben durch ein strukturiertes
  `meta`-Feld auf jedem Kandidaten, das die Übersetzung direkt aus den ursprünglichen Werten aufbaut
  statt sie aus Text zurückzugewinnen.

### Eigene inhaltliche Prüfung (statt nur die Erzeugung zu testen)

Sieben kuratierte Fälle wurden unabhängig vom Generator-Code gegen den Korpus nachgerechnet
(`grep`/eigenständiges Python-Skript, nicht `generate_golden_dataset.py`) — plus, im Rahmen von
#274, zwei weitere gezielte Nachrechnungen zu den neuen Fixes:

| Fall | Nachrechnung | Ergebnis |
|---|---|---|
| `attr::eye_color::comic-0050_amygdala.md` (Welche Augenfarbe hat Amygdala?) | Frontmatter von `comic-0050_amygdala.md` gelesen | `eye_color: "Black"` — korrekt |
| `filter::Good::Marvel Comics::Reality Warping` (gute Marvel-Figuren mit Reality Warping) | Alignment/Creator/Fähigkeit unabhängig aus allen 1.448 Dateien gefiltert | identische 7 Dateien wie im Golden-Fall |
| `desc::0::comic-0274_castiel.md` (Castiel-Paraphrase über Erschaffer/Alignment/Augenfarbe/Fähigkeit) | Attributkombination unabhängig gegen alle 1.448 Dateien geprüft | genau 1 Treffer, `comic-0274_castiel.md` |
| `range::intelligence_score::<::35` (Intelligenzwert unter 35) | Fünf-Attribut- und `overall_score`-Felder unabhängig geparst, `overall_score is not null` angewendet | identische 2 Dateien wie im Golden-Fall |
| Kontrolle zur Cross-Field-Regel | Ohne den `overall_score is not null`-Filter hätten 105 zusätzliche (unbewertete) Figuren `range::intelligence_score::<::35` u. ä. fälschlich getroffen | bestätigt die Notwendigkeit der Regel, nicht nur ihre Umsetzung |
| Kontrolle zur Kontaminationsschwelle | `comic-0226_brainiac-5.md`/`comic-0498_gambit.md` explizit gegen die Plausibilitätsprüfung getestet | beide korrekt als unplausibel erkannt |
| `desc::2::comic-1224_starfire.md` (Neuauswahl nach der `occupation`-Gewichtung) | Geburtsort/Beruf/Augenfarbe unabhängig gegen alle 1.448 Dateien geprüft | genau 1 Treffer, `comic-1224_starfire.md` |
| Case-Insensitivitäts-Fix | Nach der Normalisierung: `comic-characters.candidates.json` auf doppelte `entity_description`-Fragen/Ground-Truth-Dateien geprüft | 60 Fragen, 60 eindeutige Ground-Truth-Dateien — keine neuen Mehrdeutigkeiten |
| `is_rated`/`has_numeric_overall`-Trennung | Menge der Entitäten verglichen, die vor/nach dem Prädikat-Fix in `scored_entities` (fünf Attributfragen) landen; niedrigste Attributwerte der 18 neu betroffenen `"∞"`-Entitäten je Feld ermittelt | Differenzmenge = exakt die 18 `"∞"`-Entitäten; niedrigste Werte 95/100/100/85/50, alle über jeder verwendeten Schwelle (max. 50) — `comic-characters.json` und `.candidates.json` vor/nach dem Fix per `git diff` verglichen: keine Änderung |
| Fingerabdruck-Guard (Query/Existenz) | Zwei künstlich provozierte Abweichungen (geänderte `query`, entfernter `natural_key`) gegen eine temporäre Kopie des Skripts | beide lösten den erwarteten kontrollierten Abbruch aus |
| Fingerabdruck-Guard (Ground Truth) | Vom Reviewer nachgestellter Fall selbst reproduziert: `comic-0001_3-d-man.md`s `superpowers` im Korpus temporär um „Reality Warping" ergänzt, Generator mit dem committeten Code laufen lassen, danach Korpusdatei zurückgesetzt und per `sha256sum -c MANIFEST.sha256` als unverändert bestätigt. Auf dem `#282`-Branch (Rebase auf `main` nach dem #277-Merge) erneut mit dem tatsächlich committeten Stand wiederholt, nicht nur einmalig auf dem ursprünglichen Branch | Abbruch mit „still generate the same query, but a different expected_documents set" für `filter::Good::Marvel Comics::Reality Warping` und dessen `crosslingual`-Übersetzung, `EXITCODE = 1`, `comic-characters.json` nicht überschrieben — beide Male identisch |
| Determinismus | Generator zweimal hintereinander laufen lassen, `diff` beider Ausgaben (nach allen #274- und #282-Fixes erneut geprüft) | byte-identisch |

Keiner der sieben ursprünglichen Fälle musste korrigiert werden; die Nachrechnung bestätigt, dass
die Ground-Truth-Berechnung — nicht nur der Code, der sie aufruft — richtig ist. Die drei
#274-spezifischen Prüfungen bestätigen, dass die jeweiligen Fixes tatsächlich wirken, nicht nur
kompilieren.

## Kalibrierungshinweis für #227/#228

Der Korpus ist durch seine Satzschablonen messbar uniform: Der Reviewer von #225 hat einen
Jaccard-Median von 0,51 über ganze Dokumente und 0,38 über die reine Prosa gemessen. Das ist
gewollt (dieselbe Struktur macht Frontmatter-Ground-Truth erst möglich), staucht aber die
Score-Verteilung von Ähnlichkeitssuchen und macht Hit Rate/MRR unempfindlicher gegenüber echten
Regressionen als bei einem heterogenen Korpus. Schwellenwerte für die Retrieval-Regression in #227
und die CI-Gates in #228 sollten deshalb **gegen die tatsächliche, hier gemessene Score-Verteilung
dieses Datasets kalibriert werden, nicht gegen Erfahrungswerte aus anderen, heterogeneren
RAG-Korpora** — eine auf einem uniformen Korpus „normale" Baseline kann auf einem heterogenen Korpus
bereits eine Regression sein, und umgekehrt.

Zusätzlich (Issue #274): Vor der Kalibrierung die 121 Fälle nicht als 121 unabhängige Beobachtungen
behandeln, ohne die absichtlichen en/de-Pärchen (`attribute_lookup`/`crosslingual` auf derselben
Entität) und die im Absatz „Streuung über die Entitäten" beschriebene Restkorrelation zu
berücksichtigen.

## Schema

```json
{
  "id": "comic-attr-001",
  "domain": "comic-characters",
  "query": "What eye color does Abin Sur have?",
  "expected_documents": ["comic-0008_abin-sur.md"],
  "category": "attribute_lookup",
  "difficulty": "easy",
  "language": "en",
  "type": "factual"
}
```

`expected_documents` referenziert Dateinamen relativ zu `eval/corpus/comic-characters/`. `domain`
entspricht dem Korpus-Verzeichnisnamen; ein Retrieval-Harness (#227), der mehrere Domänen
zusammenführt, kann Treffer über `domain` + `expected_documents` eindeutig zuordnen.

### Optionales Feld `answer_span` (Issue #721)

Seit Issue #721 unterstützt der Harness eine zweite, chunkbezogene Metrikfamilie
(`answerSpanHitRate@5`, Rang des ersten Treffer-Chunks — siehe
[ADR-0012, Nachtrag](../../docs/decisions/0012-messvertrag-retrieval-harness.md#nachtrag-dokumentbezogenes-k-fenster-und-chunkebene-issue-721)).
Ein Fall kann dafür ein zusätzliches, **optionales** Feld führen:

```json
"answer_span": "the exact literal text excerpt the answer is known to sit in"
```

Bewusst ein eingefrorener, wörtlicher Textausschnitt, kein Chunk-Index — ein Chunk-Index wird bei
jeder Änderung von `chunk-size`/`chunk-overlap` lautlos falsch (siehe ADR-0012 Nachtrag). `comic-characters.json`
führt dieses Feld **nicht**: Die Ein-Chunk-Invariante (ADR-0010) macht eine Chunk-Ebene für diese
Domäne bedeutungslos — jedes Dokument ist bereits genau ein Chunk. Das Fehlen des Felds lädt
unverändert (`GoldenCase#answerSpan()` ist dann `null`, `io.opaa.eval.ChunkAnswerSpanMetrics` behandelt
einen Fall ohne `answer_span` als nicht anwendbar, nicht als Fehlschlag). Eine künftige mehrchunkige
Domäne (#234) füllt dieses Feld für Fälle, deren Antwort nachweislich in genau einem Abschnitt steht.
