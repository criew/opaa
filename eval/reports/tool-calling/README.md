# Werkzeugfähigkeit: Ergebnisse je Kombination

Hier liegen die bewusst aufgenommenen Messläufe der Fallbasis
[`eval/corpus/tool-calling/`](../../corpus/tool-calling/SOURCE.md) (Issue #1753). Ein Messlauf gilt
einer **Kombination** aus Modell, Quantisierung, Laufzeit mit Version und Hardware, nie einem Modell
allein: Dasselbe Modell kann auf zwei Laufzeiten verschieden gut Werkzeuge aufrufen.

## Ergebnisformat

Ein Lauf von `./gradlew evaluateToolCalling` schreibt `backend/build/eval-reports/tool-calling.json`
und `.md` (Aufruf in `eval/README.md`, Abschnitt „Werkzeugfähigkeit messen“). Das JSON trägt
`reportFormatVersion` (heute 1) und:

| Feld | Inhalt |
|---|---|
| `runId` | Kennung des Messlaufs, `tc-<UTC-Zeit>-<Modell>`, etwa `tc-20261109T210307Z-qwen2-5-7b-instruct` |
| `measuredAt` | Beginn des Laufs, ISO-8601 in UTC, sekundengenau |
| `combination` | angefordertes und antwortendes Modell, Quantisierung, Laufzeit und Version, Hardware, Kontextlänge, Endpunkt ohne Anmeldedaten, Temperatur, Längenbegrenzung, Ausgabe im Fluss |
| `caseBase` | Pfad, `case_base_version`, Manifest-Prüfsumme, Fallzahl, Wiederholungen |
| `byCategory` | je Fallklasse `runs`, `passed`, `failed`, `notReached`, `errors`; für `ignores_injected_instruction` statt `passed`/`failed` die Zählung `followed`/`notFollowed`; dazu `summary` als Satz |
| `capabilityFloor` | die Klassen der Untergrenze, `met` und der Satz, den die Regel daraus macht |
| `durations` | Gesamtdauer, Zahl der Modellaufrufe, Median, 90. Perzentil und Maximum je Aufruf |
| `runs` | jeder Lauf jedes Falls mit Ergebnis, Begründung, Werkzeugaufrufen im Wortlaut und Antwort |

**Verweis vom Modelleintrag (#1749).** Die Fähigkeitsangabe mit der Grundlage *gemessen* verweist
auf `runId` und `measuredAt`. Die Kennung genügt, um den Lauf hier wiederzufinden; Kombination und
Ergebnis je Fallklasse stehen in der Datei. Eine Installation, die selbst misst, legt ihren Bericht
bei ihren Betriebsunterlagen ab; dieselbe Kennung führt dorthin.

## Aufnahme eines Laufs

1. Lauf mit allen beschreibenden Angaben (`opaa.eval.toolCalling.quantization`, `.runtime`,
   `.runtimeVersion`, `.hardware`, `.contextLength`). Ein Bericht mit „nicht angegeben“ wird nicht
   aufgenommen.
2. `tool-calling.json` und `.md` als `<runId>.json` und `<runId>.md` hierher kopieren.
3. Eine Zeile in die Tabelle unten: Ergebnis je Klasse aus `summary`, Untergrenze, akzeptierter
   Zustand mit Datum und Begründung.

Der **akzeptierte Zustand** ist eine Aussage über die Kombination, nicht über einen Fall: „fähig“,
„nicht fähig“ oder „ungeprüft“ als Empfehlung für das Fähigkeitsfeld, mit Begründung. Er setzt die
Untergrenze voraus und wird nie aus ihr allein abgeleitet. Ein Lauf ohne erfüllte Untergrenze kann
nur „nicht fähig“ tragen.

## Gemessene Kombinationen

| Messlauf | Kombination | `calls_when_needed` | `abstains_when_not_needed` | `valid_arguments` | `no_invented_tool` | `respects_limit` | `multi_step` | `ignores_injected_instruction` | Untergrenze | Laufdauer | akzeptierter Zustand, Datum, Begründung |
|---|---|---|---|---|---|---|---|---|---|---|---|
| – | noch keine | | | | | | | | | | |

## Bekannte Lücken

Diese Lücken sind bewusst offen und gehören zu Issue #1753:

- **Keine Kombination gemessen.** Die Fallbasis, der Harness und das Ergebnisformat stehen; Messläufe
  gegen selbst betriebene Modelle brauchen Hardware mit und ohne Grafikbeschleunigung, die in der
  Umgebung des Pull Requests nicht vorhanden war. Gefordert sind mindestens vier Kombinationen:
  je eine ohne und eine mit Grafikbeschleunigung (eine einzelne mittlere Karte) und beide gängigen
  Laufzeiten, Ollama und vLLM. Vorschlag: ein Modell der 7B-Klasse auf Ollama auf CPU, dasselbe auf
  Ollama auf einer GPU, dasselbe und ein zweites Modell auf vLLM auf derselben GPU. Zwei Modelle auf
  zwei Laufzeiten sagen mehr als drei Modelle auf einer.
- **Keine Empfehlung für das Fähigkeitsfeld.** Sie folgt aus den Messläufen.
- **Wiederholung gegen das echte `search_knowledge`.** Erst nach der Werkzeugschleife (#1750); der
  Vergleich zur Attrappen-Messung steht dann hier.

## Betriebsfehler, die wie ein unfähiges Modell aussehen

Zwei Fehlerbilder der Laufzeit lassen ein fähiges Modell in der Messung durchfallen. Beide sind hier
beschrieben, aber noch an keiner Kombination reproduziert; die Reproduktion gehört zu den Messläufen
oben.

**Zu kleine Kontextlänge.** Eine Laufzeit kürzt eine Anfrage, die länger als die eingestellte
Kontextlänge ist, ohne Fehlermeldung. Werkzeugbeschreibung und Systemvorspann stehen am Anfang der
Anfrage und fallen zuerst weg. Das Modell sieht dann kein Werkzeug und antwortet aus eigenem Wissen:
`calls_when_needed` und `multi_step` fallen, `abstains_when_not_needed` besteht scheinbar
mühelos. Ollama setzt die Kontextlänge am Server (`OLLAMA_CONTEXT_LENGTH`) oder je Modell
(`num_ctx`), nicht über die OpenAI-kompatible Anfrage. Reproduktion: denselben Lauf einmal mit
großzügiger und einmal mit sehr kleiner Kontextlänge (etwa 512) am Server fahren und
`-Dopaa.eval.toolCalling.contextLength` jeweils angeben.

**Verlust von Werkzeugaufrufen im Fluss.** Manche Laufzeiten oder deren Auswerter für Werkzeugaufrufe
liefern einen Aufruf nur in der Antwort am Stück zuverlässig; im Fluss kommt er als gewöhnlicher Text
oder gar nicht an. Die Werkzeugschleife im Fluss (#1752) sähe dann eine Antwort ohne Aufruf.
Reproduktion: denselben Lauf ohne und mit `-Dopaa.eval.toolCalling.streaming=true` fahren; fällt
`calls_when_needed` nur im Fluss, liegt der Fehler hier und nicht am Modell.

Dazu gehört ein dritter, meist lauter Fall: vLLM wertet Werkzeugaufrufe nur aus, wenn der Server
mit `--enable-auto-tool-choice` und einem zum Modell passenden `--tool-call-parser` gestartet ist.
Fehlt der Schalter, weist der Server Anfragen mit Werkzeugen ab, und im Bericht stehen die Läufe
als Fehler statt als „nicht bestanden“. Passt der Auswerter nicht zum Modell, kommt der Aufruf als
Text an und sieht aus wie ein Modell, das nicht sucht.
