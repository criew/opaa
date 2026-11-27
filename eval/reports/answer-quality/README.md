# Antwortqualität: Berichte und Vorher/Nachher-Lauf

Hier liegen bewusst aufgenommene Läufe der Domäne
[`eval/corpus/answer-quality/`](../../corpus/answer-quality/SOURCE.md) (Epic #3888). Ein Lauf gilt
einer Kombination aus Modell, Temperatur und Stand des Codes; Zahlen zweier Läufe sind nur
vergleichbar, wenn alles außer dem Code gleich ist.

## Lauf

```bash
cd backend
export OPAA_EVAL_CHAT_API_KEY=...        # nur, wenn der Endpunkt einen Schlüssel verlangt
./gradlew evaluateAnswerQuality \
  -Dopaa.eval.chatBaseUrl=https://openrouter.ai/api/v1 \
  -Dopaa.eval.chatModel=<Modellkennung> \
  -Dopaa.eval.ollamaBaseUrl=http://localhost:11434   # optional: Host-Ollama statt Testcontainer
```

Der Embedding-Teil braucht Docker (Testcontainer) oder ein Host-Ollama mit `nomic-embed-text:v1.5`;
das Chat-Modell ist ein beliebiger OpenAI-kompatibler Endpunkt (Adresse, Modell, Schlüssel und
Temperatur wie beim Prompt-Injection-Testsatz, siehe `eval/README.md`). Ohne Modellschlüssel misst
`-Dopaa.eval.answerQuality.skipAnswers=true` nur die Kontextebene. Die Kontextmessung läuft in beiden
Modi ohne Teilfragen-Zerlegung, damit der deklarierte Zustand in beiden dasselbe bedeutet.

## Bericht

`build/eval-reports/answer-quality.json` und `.md`:

| Feld | Inhalt |
|---|---|
| `runConfiguration` | Modell, Endpunkt ohne Anmeldedaten, Temperatur, Embedding-Modell, Beginn, Dauer, Manifest-Prüfsumme, Fallzahl, `answersSkipped`, `queryDecompositionEnabled` |
| `context` | je Frage (auch jede Kennungs-Variante) `documentInContext`, `evidenceInOnePassage` und die Dokumente im Kontext |
| `answerSummary` | je Klasse `cases`, `passed`, `refusals` (Fehlanzeige), `caveats` (Hinweis), `wrongReferences`, `valuesMissing` |
| `answers` | je Fall das Urteil und die Antwort im Wortlaut |
| `contextStateDeviations` | Fälle, deren deklarierter Zustand der Kontextebene vom gemessenen abweicht |

## Vorher/Nachher für ein Paket des Epics

1. Auf dem Stand vor der Änderung (`origin/main`) mit festem Modell und `chatTemperature` (Vorgabe 0)
   laufen lassen und `answer-quality.json` als `<datum>-vorher.json` sichern.
2. Auf dem Stand mit der Änderung genauso laufen lassen, als `<datum>-nachher.json` sichern.
3. Im PR die Zeilen je Klasse aus `answerSummary` und die Zahl der Fragen mit
   `documentInContext` und `evidenceInOnePassage` gegenüberstellen. Ein Modell mit Temperatur über 0
   streut: dann dreimal laufen und die Spanne nennen.
4. Fälle, die dadurch von `known_gap` auf `solved` wechseln, im Generator umstellen
   (Ebene `format`: `FORMAT_GAPS`; Ebene `context`: `CONTEXT_GAPS`, beide im Generator) und
   den Generator laufen lassen.

Der Lauf misst nur: Er scheitert an einer verletzten Voraussetzung (Manifest, Indizierung), nie an
einer Quote. Der Antwortlauf mit Modell hat keinen CI-Job; die Kontextebene läuft als Workflow `answer-quality.yml`.

## Aufgenommene Läufe

- [`2026-11-27-kontext.md`](2026-11-27-kontext.md): Kontextebene, Ausgangswerte (Workflow `answer-quality.yml`, ohne Chat-Modell, ohne Teilfragen-Zerlegung).
