# Recherchefälle des Agenten „Recherche“

Drei realistische Fälle für den mitgelieferten Agenten „Recherche“ (Issue #3674, Epic #1747). Sie
dienen dazu, Laufzeit und Aufrufzahl eines Laufs zu messen und daraus die Grenzen des Agenten und
die Stufen der Metrik `opaa.agent.run.duration` (`OPAA_METRICS_SLO_AGENT`) festzulegen.

Der Lauf misst nur; er urteilt nicht und liegt, wie alles unter `eval/`, bewusst außerhalb von
Gradle-Build und CI. Er braucht ein laufendes OPAA mit eingeschaltetem Schalter „Agenten“, ein
werkzeugfähiges Modell und ein echtes Modell: Mit dem Stub der E2E-Suite sind die Zahlen wertlos.

## Fälle

| Fall | Was er prüft |
|---|---|
| `rech-001` Fassungen vergleichen | mehrere Fassungen einer Satzung plus Gebührenordnung; benennt der Bericht Unterschiede und Widersprüche mit beiden Fundstellen |
| `rech-002` Antrag über mehrere Dokumentarten | fünf Dokumente aus vier Dokumentarten für eine Frage; Belege aus allen Schritten |
| `rech-003` Plan eingrenzen | die Antwort auf den Plan schränkt ein; ein nicht gewünschtes Dokument darf nicht zitiert werden |

Korpus ist `eval/corpus/verwaltung`; die erwarteten Dokumente stehen je Fall in `cases.json`
(`expected_documents`, bei `rech-003` zusätzlich `forbidden_documents`).

## Ablauf

1. Korpus als Wissensbibliothek einspielen (Dateien aus `eval/corpus/verwaltung/*.md` in eine
   Upload-Bibliothek hochladen, warten bis alle indiziert sind) und der Bibliothek in einem Space
   zuordnen, in dem die fragende Person Mitglied ist.
2. Agenten einschalten; „Recherche“ entsteht dabei und ist für alle nutzbar.
3. Lauf starten (nur Standardbibliothek von Python, kein Docker):

   ```bash
   python3 -I eval/agents/recherche/run_cases.py \
     --base-url http://localhost:8080 --space-id <Space-ID> --token <Bearer-Token>
   ```

   Je Fall sendet das Skript die Frage (der Lauf endet mit dem Plan) und danach die Bestätigung
   „Plan bestätigt.“ beziehungsweise die Planänderung des Falls (der Lauf arbeitet den Plan ab).
   Es schreibt `run-report.json` neben das Skript (nicht einchecken, außer als bewusste
   Ausgangsmessung unter `eval/reports/`): Dauer je Lauf, Endzustand, Zahl der Schritte und
   Werkzeugaufrufe je Werkzeug, Schritte an der Grenze, zitierte Dateien, fehlende erwartete
   Dokumente und zitierte unerwünschte Dokumente.
4. Dieselben Läufe in der Metrik gegenprüfen: `opaa_agent_runs_total{kind="builtin",result=…}` und
   `opaa_agent_run_duration_seconds` (der Planlauf zählt als `result="plan"`).

## Messwerte

**Noch nicht gemessen.** Die Grenzen von „Recherche“ (12 Werkzeugaufrufe, 8 Suchen, 240 Sekunden,
150 000 Zeichen Lesemenge) und die Systemgrenzen (Lesemenge 200 000 Zeichen) sind Startwerte, die Stufen von `OPAA_METRICS_SLO_AGENT` bis 300 Sekunden
ebenfalls. Die Werte aus dem ersten Messlauf kommen hierher und stellen dann die Grenzen und Stufen
nach; vorher steht hier keine Zahl.

| Fall | Laufdauer (Plan) | Laufdauer (Ausführung) | Werkzeugaufrufe | Suchen | Endzustand |
|---|---|---|---|---|---|
| `rech-001` | – | – | – | – | – |
| `rech-002` | – | – | – | – | – |
| `rech-003` | – | – | – | – | – |
