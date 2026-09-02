# Korpus-Generator: Verwaltung (Issue #1042)

Erzeugt den Evaluierungskorpus unter `eval/corpus/verwaltung/` — vollständig synthetisch, ohne
Fremdquelle (siehe [`../corpus/verwaltung/SOURCE.md`](../corpus/verwaltung/SOURCE.md)). Details
zum Gesamtvorhaben stehen in
[`docs/features/retrieval-benchmark.md`](../../docs/features/retrieval-benchmark.md), Abschnitt 4
„Verwaltungs-Evaldomäne".

```bash
cd eval/generator
python generate_verwaltung_corpus.py
```

Kein Netzzugriff und kein Rohdaten-Snapshot: Das Skript liest ausschließlich seine eigenen, im
Quelltext hinterlegten Daten (`AEMTER`, Textbausteine je Dokumenttyp). Zwei Läufe erzeugen
byte-identische Ausgabe.

Chunk-Zahl-Verifikation ohne Docker: `io.opaa.eval.VerwaltungChunkSizeDryRunTest`
(`backend/src/evalTest/java/io/opaa/eval/`) chunked den generierten Korpus mit der echten,
produktiven `MarkdownDocumentPipeline` (dieselbe Pipeline, auf die `DocumentPipelineRegistry` `.md`
seit #1103 routet; kein Testcontainers nötig) und schlägt fehl, sobald ein Dokument
unter die Mindestzahl von 3 Chunks fällt — siehe `eval/corpus/verwaltung/SOURCE.md` für die
zuletzt gemessenen Werte und [`../corpus/verwaltung/MAINTENANCE.md`](../corpus/verwaltung/MAINTENANCE.md)
für Pflegeverantwortung und das Verfahren bei einer Korpus-Neuziehung. Golden Dataset, Baseline
und die Registrierung im Retrieval-Harness (`EvalDomainConfig`) sind **nicht** Teil dieses
Issues — siehe `MAINTENANCE.md`, Abschnitt „Stand dieser Domäne".

---

# Korpus-Generator: Sehenswürdigkeiten in europäischen Großstädten (Issue #234)

Erzeugt den Evaluierungskorpus unter `eval/corpus/city-landmarks/` ausschließlich aus den
eingefrorenen Wikidata-SPARQL-Rohdaten unter [`frozen/`](frozen/) (CC0-1.0, siehe
[`frozen/SOURCE.md`](frozen/SOURCE.md) für die vollständigen Abfragen, Auswahlregeln und
dokumentierten Fallstrick-Entscheidungen).

```bash
cd eval/generator
python generate_city_landmarks_corpus.py
```

Kein Netzzugriff: Das Skript liest ausschließlich `frozen/*.json`, prüft deren SHA-256 gegen die in
`generate_city_landmarks_corpus.py` (`FROZEN_HASHES`) hinterlegten Werte und bricht ab, falls eine
der Dateien nachträglich verändert wurde. Reproduktion eines Frischlaufs erfordert daher **keinen**
Wikidata-Zugriff — nur bei einer bewussten Neueinfrierung der Rohdaten (neue Abfrage, neues
Abrufdatum) wird der Live-Endpunkt erneut kontaktiert (nicht Teil dieses Skripts, siehe
`frozen/SOURCE.md`).

Determinismus: Zwei Läufe erzeugen byte-identische Ausgabe (per `diff -rq` zweier aufeinander
folgender Läufe belegt, siehe PR-Beschreibung von #234) — Städte sind bereits nach Rang sortiert in
`frozen/wikidata-cities-200.json` gelistet, Sehenswürdigkeiten je Stadt nach aufsteigender QID.

Chunk-Zahl-Verifikation ohne Docker: `io.opaa.eval.CityLandmarksChunkSizeDryRunTest`
(`backend/src/evalTest/java/io/opaa/eval/`) chunked den generierten Korpus mit der echten,
produktiven `MarkdownDocumentPipeline` (dieselbe Pipeline, auf die `DocumentPipelineRegistry` `.md`
seit #1103 routet; kein Testcontainers nötig) und meldet die Chunk-Zahl-Verteilung —
siehe `eval/corpus/city-landmarks/SOURCE.md` für die zuletzt gemessenen Werte.

---

# Korpus-Generator: Comichelden

Erzeugt den Evaluierungskorpus unter `eval/corpus/comic-characters/` aus dem
HuggingFace-Datensatz [`jrtec/Superheroes`](https://huggingface.co/datasets/jrtec/Superheroes)
(CC0-1.0). Details zum Gesamtvorhaben stehen in
[`docs/features/search-quality-evaluation.md`](../../docs/features/search-quality-evaluation.md)
(Abschnitt „Der Testkorpus") und [ADR-0011](../../docs/decisions/0011-search-quality-evaluation-harness.md).

Dieses Werkzeug ist bewusst **außerhalb** des Gradle-Builds und der CI angesiedelt (siehe
ADR-0011, Entscheidung 2). Es läuft nie automatisch, sondern nur, wenn der Korpus bewusst
neu erzeugt werden soll — die Ausgabe wird dann als reguläre Änderung committet und reviewt.

## Voraussetzungen

- Python 3.11 oder neuer (nutzt nur die Standardbibliothek, keine zusätzlichen Pakete)
- Netzzugriff beim ersten Lauf, um die beiden CSV-Dateien des Datensatzes von HuggingFace zu
  laden (danach genügt der lokale Cache unter `raw-source/`)

## Lauf

```bash
cd eval/generator
python generate_corpus.py
```

Das Skript:

1. Lädt `train.csv` und `test.csv` von einem **fest verankerten Dataset-Commit**
   (`a2f7f35c36a4d551625a0607c7759ae7916fc6be`, nicht `main`) nach `raw-source/` — oder nutzt die
   dort bereits vorhandenen Dateien, falls ihr SHA-256 zu den unten dokumentierten Werten passt.
2. Bricht mit einem Fehler ab, falls der SHA-256 der heruntergeladenen bzw. lokal vorhandenen
   Dateien nicht zum eingefrorenen Snapshot passt — die Quelle darf sich nicht unbemerkt ändern.
3. Kombiniert `train.csv` und `test.csv`, sortiert stabil nach der numerischen `id`-Spalte des
   Datensatzes und vergibt sequenzielle Korpus-IDs (`comic-0001`, `comic-0002`, …).
4. Schreibt pro Entität eine Markdown-Datei mit YAML-Frontmatter (strukturierte Faktenfelder) und
   einem kurzen, aus diesen Feldern selbst formulierten Fließtext nach
   `eval/corpus/comic-characters/` (vorhandener Inhalt dieses Verzeichnisses wird vorher gelöscht).
5. Schreibt `MANIFEST.sha256` mit dem SHA-256 jeder erzeugten Datei.

`raw-source/*.csv` wird **nicht committet** (siehe `.gitignore`) — nur der SHA-256 und die
Bezugs-URL sind in diesem Skript und in
[`../corpus/comic-characters/SOURCE.md`](../corpus/comic-characters/SOURCE.md) dokumentiert. Wer
den Lauf reproduzieren will, lädt die Rohdaten entweder automatisch über das Skript oder legt sie
manuell unter `raw-source/train.csv` bzw. `raw-source/test.csv` ab; das Skript prüft in beiden
Fällen den SHA-256, bevor irgendetwas verarbeitet wird.

## Verifikation

Manifest gegen den erzeugten Korpus prüfen (Standardwerkzeug, keine Zusatzsoftware nötig):

```bash
cd eval/corpus/comic-characters
sha256sum -c MANIFEST.sha256
```

Zwei Läufe des Generators mit denselben Rohdaten erzeugen byte-identische Ausgaben — geprüft über
`diff -r` zweier Läufe, siehe PR-Beschreibung von #225.

## Verwerfungsregel

Eine Zeile des Datensatzes wird verworfen, wenn ihr `name`-Feld leer/nur Leerzeichen ist oder ihre
`id`-Spalte keine gültige Ganzzahl ist. Im aktuellen Snapshot (1.448 Zeilen über `train.csv` und
`test.csv`) trifft das auf keine einzige Zeile zu — die Regel ist als Absicherung für künftige
Datensatz-Versionen dokumentiert, nicht weil sie heute etwas ausschließt.

## Was aus dem Datensatz übernommen wird — und was nicht

- Übernommen werden ausschließlich strukturierte Faktenfelder (Name, Herkunft, Attribute,
  Bewertungen, boolesche Fähigkeits-Merkmale als Liste der gesetzten Fähigkeiten, …).
- **Nicht übernommen**: die Freitextfelder `history_text` (bis zu 130.000 Zeichen je Zeile) und
  `powers_text`. Beide sind fremder Prosa-Ursprung und würden — insbesondere `history_text` — die
  Eigenschaft „ein Dokument = ein Chunk" zerstören, die die gesamte spätere Ground Truth (#226)
  trägt. Ebenfalls nicht übernommen: die `img`-Spalte (Fremd-Bild-URLs) sowie die freie
  `superpowers`-Spalte, `power_score`, `full_name`, `aliases`, `alter_egos`, `base`, `relatives`
  und `skin_color` — sie sind für die im Issue definierten Pflichtfelder nicht erforderlich.
- Der Fließtext jedes Dokuments wird vom Generator ausschließlich aus den übernommenen
  Faktenfeldern formuliert; es wird kein Text aus der Quelle kopiert.

## Bekannte Eigenheiten der Quelldaten

- `height`/`weight` liegen als gemischte Zeichenketten vor (z. B. `6'11 • 211 cm` oder für sehr
  große Figuren `100'0 • 30.5 meters` / `40,000 lb • 18.0 tons`). Der Generator extrahiert den
  metrischen Wert per Regex und normalisiert Meter/Tonnen zu cm/kg.
- Die Quelle kodiert „unbekannt" bei `height` doppelt: als `-` **und** als `0'0 • 0 cm` (16 Zeilen
  im aktuellen Snapshot). Beide Formen werden zu `height_cm: null`; kein Dokument behauptet eine
  Körpergröße von 0 cm. Dieselbe Normalisierung gilt vorsorglich auch für `weight_kg`, auch wenn im
  aktuellen Snapshot keine `0 kg`-Zeile auftritt.
- `hair_color` enthält bei 190 Zeilen (13 %) `No Hair`/`None` statt einer Farbe. Der Fließtext
  formuliert das als eigene Klausel ("is bald"/"are bald") statt der unsinnigen „has No Hair hair".
- Die sechs Bewertungsfelder liegen auf **zwei verschiedenen Skalen**: die fünf Attributwerte
  (`intelligence_score`, `strength_score`, `speed_score`, `durability_score`, `combat_score`) auf
  0–100, `overall_score` unabhängig davon auf 1–237 (plus 18-mal die Zeichenkette `∞` bei
  omnipotenten Figuren). `overall_score` ist **keine** Ableitung aus den fünf Attributwerten — der
  Fließtext formuliert das absichtlich als eigenständigen Satz, um keine Kausalität zu suggerieren,
  die es in den Quelldaten nicht gibt.
- `0` bei den fünf Attributwerten wird als echter Wert behandelt, nicht als „fehlend" — mit einer
  dokumentierten Einschränkung zu einer 104-Zeilen-Korrelation zwischen „alle fünf Werte 0" und
  „`overall_score` leer"; Details im Kommentar auf `parse_score()` und in
  [`../corpus/comic-characters/SOURCE.md`](../corpus/comic-characters/SOURCE.md).
- `teams` kann Namen mit eingebettetem Komma enthalten (z. B. „Villainy, Inc."). Im Frontmatter
  daher als echte YAML-Sequenz (`teams: ["Villainy, Inc."]`) statt als kommagetrennter String
  abgebildet — anders als `superpowers`, dessen Werte nie ein Komma enthalten und deshalb bewusst
  als kommagetrennter String bleiben (siehe Kommentar auf `yaml_sequence()`).
- Fehlende Werte werden durchgängig als YAML `null` abgebildet — mit den oben genannten,
  dokumentierten Ausnahmen bei den fünf Attributwerten (wo `0` ein echter Wert ist).
- `overall_score` enthält bei einigen wenigen (18 von 1.448) omnipotenten Figuren den Wert `∞`
  statt einer Zahl; er wird unverändert als Zeichenkette übernommen.

## Größenbegrenzung je Dokument

`MAX_DOCUMENT_BYTES` (3.000 Bytes) ist eine **konservative Byte-Annäherung** an ein
Token-Limit — siehe den ausführlichen Kommentar auf der Konstanten in `generate_corpus.py` und
[ADR-0010](../../docs/decisions/0010-ein-chunk-invariante-evaluierungskorpus.md). Die Ein-Chunk-Invariante
selbst ist eine Aussage über Tokens im `TokenTextSplitter` (`opaa.indexing.chunk-size`), nicht über
Bytes; die endgültige Absicherung ist der Java-Integrationstest in #227, der den echten Splitter
verwendet.
