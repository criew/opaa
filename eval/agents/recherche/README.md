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

1. Korpus als Wissensbibliothek einspielen (Dateien `eval/corpus/verwaltung/verwaltung-*.md` in
   eine Upload-Bibliothek hochladen, warten bis alle indiziert sind; `MAINTENANCE.md` und
   `SOURCE.md` sind Pflegetexte und gehören nicht hinein) und die Bibliothek einem Space zuordnen,
   in dem die fragende Person Mitglied ist.
2. Agenten einschalten; „Recherche“ entsteht dabei. Das Chat-Modell braucht die Werkzeugfähigkeit
   „Fähig“. „Recherche“ ebenfalls dem Space zuordnen: Ohne Zuordnung lehnt die Frage mit
   `AGENT_NOT_AVAILABLE` ab.
3. Lauf starten (nur Standardbibliothek von Python, kein Docker):

   ```bash
   python3 -I eval/agents/recherche/run_cases.py \
     --base-url http://localhost:8080 --space-id <Space-ID> --token <Bearer-Token>
   ```

   Im Modus `dev` ersetzt `--dev-user dev-admin` das Token.

   Je Fall sendet das Skript die Frage (der Lauf endet mit dem Plan) und danach die Bestätigung
   „Plan bestätigt.“ beziehungsweise die Planänderung des Falls (der Lauf arbeitet den Plan ab).
   Es schreibt `run-report.json` neben das Skript (nicht einchecken, außer als bewusste
   Ausgangsmessung unter `eval/reports/`): Dauer je Lauf, Endzustand, Zahl der Schritte und
   Werkzeugaufrufe je Werkzeug, Schritte an der Grenze, gesammelte Dateien (`retrieved`, alle
   Fundstellen des Laufs), im Text gültig zitierte Dateien (`cited`, laut Feld `cited` der Quellen
   der Antwort), die Zahl ungültiger Belegmarker (`invalidCitations`), fehlende erwartete und zitierte unerwünschte Dokumente (beide nach `cited`) sowie gesammelte
   unerwünschte Dokumente (`retrievedForbidden`).
4. Dieselben Läufe in der Metrik gegenprüfen: `opaa_agent_runs_total{kind="builtin",result=…}` und
   `opaa_agent_run_duration_seconds` (der Planlauf zählt als `result="plan"`).

## Messwerte

Messlauf am 11.11.2026 mit Claude Haiku 4.5 (`claude-haiku-4-5`) als Chat-Modell und bge-m3 als
Embedding-Modell, lokales OPAA vom Stand `main` 8b36fb06 mit frischer Datenbank und den
Startgrenzen. Drei Durchgänge je Fall; die Tabelle nennt die Spanne.

| Fall | Laufdauer (Plan) | Laufdauer (Ausführung) | Werkzeugaufrufe | Suchen | Endzustand |
|---|---|---|---|---|---|
| `rech-001` | 6,3–6,8 s | 26,2–29,3 s | 4–7 | 2–4 | 3 × `LIMIT_VOLUME` |
| `rech-002` | 5,5–6,6 s | 23,5–28,0 s | 4 | 4 | 3 × `LIMIT_VOLUME` |
| `rech-003` | 5,6–5,8 s | 5,1–43,7 s | 1–11 | 0–8 | 2 × `COMPLETED`, 1 × `LIMIT_VOLUME` |

**Einordnung.** Die Startgrenzen von 12 Werkzeugaufrufen, 8 Suchen und 240 Sekunden reichen und
binden kaum: Kein Lauf kam über 11 Aufrufe oder 44 Sekunden, die Suchgrenze erreichte nur ein
Durchgang von `rech-003`. Es bindet die Sammelgrenze `OPAA_AGENT_MAX_COLLECTED_CHARS` (60 000
Zeichen, „Recherche“ setzt keine eigene): Sieben von neun Läufen enden nach vier bis elf Aufrufen mit
`LIMIT_VOLUME` und antworten aus dem bis dahin Gesammelten. Nachzustellen ist deshalb diese
Grenze, nicht die drei Startwerte. Für die Stufen von `OPAA_METRICS_SLO_AGENT` liegen alle Läufe
unter 45 Sekunden, alle Planläufe unter 7 Sekunden. Die Metrik zählt dieselben Läufe
(`result="plan"`, `"limit_volume"`, `"completed"`); ihre Dauer liegt bis etwa 2 Sekunden unter der
vom Skript gemessenen.

Fehlende erwartete Dokumente (im Text nicht zitiert):

- `rech-001`: `verwaltung-0011_gebuehrenordnung-bauamt.md` in zwei von drei Durchgängen; der
  Bericht nennt die Gebührenordnung dann als Lücke.
- `rech-002`: `verwaltung-0041_dienstanweisung-kaemmerei-2-2024.md` und
  `verwaltung-vertretungsregelung.md` in allen drei, `verwaltung-0043_formularhinweis-kaemmerei-8.md`
  in einem; die Frage nach der Vertretung bleibt damit unbeantwortet.
- `rech-003`: `verwaltung-0019_gebuehrenordnung-ordnungsamt.md` in allen drei,
  `verwaltung-0018_gewerbeanmeldegebuehrensatzung-fassung-2024.md` in zwei. In einem Durchgang
  stützt sich der Bericht inhaltlich auf beide, setzt aber keinen Belegmarker; in einem anderen
  beantwortet „Recherche“ die Planänderung mit einem neuen Plan und der Bitte um Bestätigung, und
  der Lauf endet nach einem Aufruf als `COMPLETED`, ohne zu recherchieren.

Das unerwünschte Dokument `verwaltung-0017_gewerbeanmeldegebuehrensatzung-fassung-2023.md` wurde in
keinem Durchgang zitiert und auch nicht gesammelt: Die Eingrenzung des Plans hält.
