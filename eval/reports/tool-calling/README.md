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
| `byCategory` | je Fallklasse `runs`, `passed`, `failed`, `notReached`, `truncated` (an der Längenbegrenzung abgeschnitten), `errors` und `limitReached` (Läufe, die die Grenze des Falls ausgeschöpft haben); für `ignores_injected_instruction` statt `passed`/`failed` die Zählung `followed`/`notFollowed`; dazu `summary` als Satz, bei `respects_limit` mit „Grenze erreicht in k von n“ |
| `capabilityFloor` | die Klassen der Untergrenze, `met` und der Satz, den die Regel daraus macht |
| `durations` | Gesamtdauer, Zahl der Modellaufrufe, Median (bei gerader Zahl Mittel der beiden mittleren Werte), 90. Perzentil und Maximum je Aufruf |
| `runs` | jeder Lauf jedes Falls mit Ergebnis, Begründung, Werkzeugaufrufen im Wortlaut, Antwort, `limitReached` und dem Abschlussgrund jedes Modellaufrufs (`finishReasons`) |

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
- **Vergleich mit dem echten `search_knowledge`.** Die Messung kann es anbieten
  (`-Dopaa.eval.toolCalling.tool=production`); der Vergleich zur Attrappen-Messung folgt aus den
  Messläufen.
- **Selbstbedienung** ist gebaut: Mit eingeschaltetem Schalter „Agenten“ startet die
  Systemverwaltung die Messung am Modelleintrag („Werkzeuge prüfen“), ohne Quellstand. Der Server
  fährt denselben Messkern mit der Definition von `search_knowledge`, hält den Bericht zum
  Herunterladen bereit und schlägt die Fähigkeitsangabe mit der Grundlage *gemessen* vor.

## Betriebsfehler, die wie ein unfähiges Modell aussehen

Zwei Fehlerbilder der Laufzeit können ein fähiges Modell in der Messung durchfallen lassen. Beide
sind hier als Vermutung beschrieben, noch an keiner Kombination reproduziert; die Reproduktion gehört
zu den Messläufen oben.

**Zu kleine Kontextlänge.** Eine Laufzeit kann eine Anfrage, die länger als die eingestellte
Kontextlänge ist, ohne Fehlermeldung kürzen. Treffen die Kürzungen Werkzeugbeschreibung oder
Systemvorspann, sieht das Modell kein Werkzeug und antwortet aus eigenem Wissen: Zu erwarten ist,
dass `calls_when_needed` und `multi_step` fallen und `abstains_when_not_needed` scheinbar mühelos
besteht. Ollama setzt die Kontextlänge am Server (`OLLAMA_CONTEXT_LENGTH`) oder je Modell
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

**Abgeschnittene Antwort.** Endet ein Modellaufruf an der Längenbegrenzung (Abschlussgrund
`length`), führt der Bericht den Lauf als abgeschnitten und beurteilt ihn nicht nach seiner
Fallklasse; für die Untergrenze zählt er als nicht bestanden. Naheliegende Ursache ist ein Modell
mit Denktext, dessen Überlegungen die Begrenzung aufbrauchen, bevor ein Aufruf oder eine Antwort
kommt. Abhilfe: `-Dopaa.eval.toolCalling.maxTokens` höher setzen und dabei bedenken, dass dieselbe
Begrenzung am Modelleintrag gilt. Die Vorgabe 8000 entspricht der Vorgabe, mit der die Einrichtung
und die Modellsuche einen neuen Modelleintrag anlegen; die Spaltenvorgabe 2000 der Datenbank greift
dort nicht.
