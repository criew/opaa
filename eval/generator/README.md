# Korpus-Generator: Comichelden

Erzeugt den Evaluierungskorpus unter `eval/corpus/comic-characters/` aus dem
HuggingFace-Datensatz [`jrtec/Superheroes`](https://huggingface.co/datasets/jrtec/Superheroes)
(CC0-1.0). Details zum Gesamtvorhaben stehen in
[`docs/features/search-quality-evaluation.md`](../../docs/features/search-quality-evaluation.md)
(Abschnitt „Der Testkorpus") und [ADR-0008](../../docs/decisions/0008-search-quality-evaluation-harness.md).

Dieses Werkzeug ist bewusst **außerhalb** des Gradle-Builds und der CI angesiedelt (siehe
ADR-0008, Entscheidung 2). Es läuft nie automatisch, sondern nur, wenn der Korpus bewusst
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
- `overall_score` enthält bei einigen wenigen (18 von 1.448) omnipotenten Figuren den Wert `∞`
  statt einer Zahl; er wird unverändert als Zeichenkette übernommen.
- Fehlende Werte (`-` oder leer) werden als YAML `null` abgebildet, nie als „0" oder Platzhaltertext.
