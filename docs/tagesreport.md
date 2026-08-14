# Tagesreport

Ein täglich laufender Workflow trägt zusammen, was im Projekt passiert ist, und
veröffentlicht das Ergebnis als Seite auf GitHub Pages. Über einen Atom-Feed
lässt sich der Report abonnieren.

- **Seite:** https://criew.github.io/opaa/
- **Feed:** https://criew.github.io/opaa/feed.xml

## Was im Report steht

Der Report ist eine Management Summary: Er soll in einer halben Minute lesbar
sein und beantworten, woran gearbeitet wurde. Vollständige Listen aller
Vorgänge stehen bewusst nicht darin — dafür gibt es GitHub, und die Linkleiste
führt mit einem Klick dorthin.

Für den jeweiligen Vortag, von oben nach unten:

| Bereich | Inhalt |
| --- | --- |
| Linkleiste | Testumgebung, Repository, Issues, Pull Requests, CI |
| Kennzahlen | offene Issues insgesamt, am Tag neu angelegt, abgeschlossen, gemergte Pull Requests |
| Je Epic ein Abschnitt | Fortschritt (`x / y erledigt`), Stichpunkte zum Tag, gemergte Pull Requests mit Umfang |
| Sonstiges | dasselbe für alles ohne Epic-Bezug |

Der CI-Eintrag der Linkleiste trägt den Zustand des letzten Laufs auf `main`
als farbigen Punkt: grün bei Erfolg, sonst rot. Der Zustand steht zusätzlich
im Klartext im Tooltip, da Farbe allein ihn nicht zugänglich macht.

Die Stichpunkte entstehen durch einen Modellaufruf und sind optional — siehe
[Zusammenfassung aktivieren](#zusammenfassung-aktivieren). Fehlen sie, treten
die Titel der betroffenen Vorgänge an ihre Stelle; der Report bleibt also auch
ohne Modell brauchbar.

Tage ohne Issue- oder PR-Bewegung erzeugen keinen Report.

Die Adresse der Testumgebung ist `https://opaa.ewerlin.com/chat`. Sie lässt
sich ohne Codeänderung umstellen, über `--test-url` oder die Umgebungsvariable
`OPAA_REPORT_TEST_URL`. Ein leerer Wert lässt den Link weg.

### Welcher Tag ein Vorgang ist

Maßgeblich ist der Zeitpunkt des **Ereignisses**, nicht der Anlage. Ein im
Januar erstelltes Issue, das am 3. August geschlossen wird, erscheint im Report
vom 3. August.

| Gruppe | Zeitstempel |
| --- | --- |
| Neu angelegte Issues | `created_at` |
| Abgeschlossene Issues | `closed_at` |
| Gemergte Pull Requests | `merged_at` |

Der Tag wird nach **Europe/Berlin** abgegrenzt, nicht nach UTC. Das Fenster
reicht von `00:00:00` bis `23:59:59` desselben Tages, jeweils mit Offset, etwa
`2026-08-03T00:00:00+02:00..2026-08-03T23:59:59+02:00`. Beide Grenzen gehören
dazu; da die Zeitstempel der API sekundengenau sind, entsteht zum Folgetag
keine Lücke.

Ein halboffenes Fenster wäre sauberer, ist über die Suche aber nicht
ausdrückbar: Zwei Bereichsangaben zum selben Feld verknüpft GitHub nicht, die
zweite verdrängt die erste. `merged:>=A merged:<B` liefert deshalb alles vor
`B` statt des Tages.

**Nachprüfbarkeit.** Jeder Report weist im Fußbereich das tatsächlich
verwendete Fenster samt Zeitzone aus, die Rohdaten führen es als
`window_start`, `window_end` und `timezone`. Wer wissen will, in welchem Report
ein Vorgang von kurz vor Mitternacht gelandet ist, vergleicht dessen
Zeitstempel mit diesen Grenzen.

**Wenn die Zeitzonendatenbank fehlt**, weicht das Skript auf UTC aus; die
Grenzen verschieben sich dann um ein bis zwei Stunden. Der Workflow installiert
`tzdata` deshalb ausdrücklich. Sollte das einmal fehlschlagen, weist der Report
sichtbar darauf hin, statt stillschweigend andere Grenzen zu verwenden. Die
Auswirkung ist real: Für den 3. August 2026 liefert das UTC-Fenster sieben
abgeschlossene Issues, das Berliner Fenster neun.

### Gliederung nach Epics

Der Report folgt den Epics, weil diese die thematisch zusammenhängenden
Einheiten des Projekts sind. Je Epic mit Tagesbewegung entsteht ein Abschnitt,
der die Bewegung des Tages in den Gesamtfortschritt einordnet — etwa
„1 / 25 erledigt". Die Abschnitte stehen nach Umfang der Tagesbewegung, den
Abschluss bildet „Sonstiges".

Die Gliederung und sämtliche Zahlen werden aus den Daten erhoben und dem Modell
vorgegeben. Es liefert nur die Stichpunkte, und zwar strukturiert je Abschnitt,
damit sie dem richtigen Epic zugeordnet werden können. Damit bleibt der Aufbau
von Tag zu Tag gleich und die Zahlen stammen nicht aus einer Schätzung.

Die Zuordnung stützt sich auf die Ticketliste im Body des Epic-Issues, da
native Sub-Issues im Repository nicht verwendet werden:

| Schritt | Grundlage |
| --- | --- |
| Epics finden | Issues mit dem Label `epic` |
| Tickets zuordnen | Checklisteneinträge `- [ ] #123 titel` im Body des Epics |
| Pull Requests zuordnen | die von GitHub verknüpften Issues (`closingIssuesReferences`) |
| Fortschritt | Anteil geschlossener Tickets der Liste |

Damit ein Ticket erkannt wird, muss die Nummer **unmittelbar** auf die Checkbox
folgen, so wie es die [Epic-Vorlage](../.github/ISSUE_TEMPLATE/epic.md) vorsieht.
Eine Nummer im Fließtext ist ein Querverweis, kein Ticket. Zusätzlich muss die
Nummer zu einem existierenden Issue gehören, und Epics zählen nicht als Tickets
anderer Epics.

Diese Genauigkeit ist nötig, weil `#N` in Markdown mehrdeutig ist: Epic #60
nummeriert seine Befunde als `- [ ] **#1 CORS Wildcard Headers**`. Ohne die
Bedingung würde daraus ein Epic mit zwanzig erfundenen Tickets.

Aus demselben Grund wird für Pull Requests **nicht** der Body ausgewertet:
Beschreibungen enthalten Zeichenfolgen wie `Closes #221` auch als Beispiel oder
Zitat. Maßgeblich ist allein die Verknüpfung, die GitHub selbst pflegt.

Ein Vorgang, der in keiner Ticketliste steht, wird **nicht** geraten, sondern
unter „ohne Epic-Bezug" geführt. Wer die Zuordnung verbessern will, trägt die
Ticketnummer im Epic nach; der nächste Lauf greift sie auf.

## Aufbau

| Bestandteil | Zweck |
| --- | --- |
| `.github/workflows/daily-report.yml` | Zeitsteuerung, Veröffentlichung im Branch `gh-pages` |
| `.github/scripts/daily_report.py` | Datenerhebung, Zusammenfassung, Erzeugung von Seite und Feed |
| `.github/scripts/test_daily_report.py` | Tests für Auswertung und Darstellung, laufen in der CI |
| Branch `gh-pages` | Veröffentlichte Seite samt Rohdaten aller bisherigen Tage |

Die Rohdaten jedes Tages liegen als JSON unter `data/` im Branch `gh-pages`.
**Sämtliche Seiten werden bei jedem Lauf daraus neu erzeugt**, nicht nur die
des Berichtstags. Eine Änderung an der Darstellung wirkt damit rückwirkend auf
den gesamten Bestand, ohne dass die Daten erneut von GitHub geholt werden
müssten. Rohdaten aus der Zeit vor einer Änderung kennen neuere Felder nicht;
für sie greifen dieselben Rückfälle wie bei einem ausgefallenen Modellaufruf.

Die Tests laufen im CI-Job `report-script` bei jedem Push und Pull Request.
Ohne sie fiele ein Fehler erst beim nächtlichen Lauf auf.

```bash
pip install pytest
pytest .github/scripts/test_daily_report.py
```

## Bedienung

Der Workflow läuft täglich um 00:30 UTC. Die Uhrzeit ist ein Wunsch, keine
Zusage: GitHub führt geplante Läufe bei Last auch mehrere Stunden später aus,
beobachtet wurden bis zu dreieinhalb Stunden. Ein ausgefallener Tag holt sich
nicht von selbst nach und muss von Hand nachgezogen werden.

Ein Lauf lässt sich jederzeit von Hand auslösen, wahlweise für einen bestimmten
Tag:

```bash
# Vortag
gh workflow run daily-report.yml

# bestimmter Tag, etwa zum Nachziehen
gh workflow run daily-report.yml -f date=2026-08-01
```

Lokal ausprobieren, ohne etwas zu veröffentlichen:

```bash
python .github/scripts/daily_report.py \
  --repo criew/opaa \
  --date 2026-08-01 \
  --output /tmp/report \
  --test-url https://opaa.ewerlin.com/chat
```

Das Skript benötigt eine angemeldete GitHub-CLI und kommt ohne zusätzliche
Pakete aus. Unter Windows fehlt Python häufig die Zeitzonendatenbank; dann
weicht das Skript auf UTC aus und die Tagesgrenzen verschieben sich um ein bis
zwei Stunden. Mit `pip install tzdata` lässt sich das beheben.

## Zusammenfassung aktivieren

Ohne API-Schlüssel entsteht der Report vollständig, nur mit den Titeln der
Vorgänge statt formulierter Stichpunkte. Zum Aktivieren einen Schlüssel als
Repository-Secret hinterlegen:

```bash
gh secret set OPAA_REPORT_API_KEY
```

Unterstützt werden **Anthropic** und **OpenAI**. Welcher Anbieter angesprochen
wird, ergibt sich aus dem Präfix des Schlüssels: `sk-ant-` bedeutet Anthropic,
alles andere wird als OpenAI-kompatibel behandelt. Explizit setzen lässt es sich
über `OPAA_REPORT_PROVIDER` mit den Werten `anthropic` oder `openai`.

Modell und Endpunkt sind über Repository-Variablen wählbar:

```bash
gh variable set OPAA_REPORT_MODEL --body "claude-haiku-4-5-20251001"
gh variable set OPAA_REPORT_BASE_URL --body "https://mein-endpunkt.example"
```

Ohne diese Variablen gelten je Anbieter:

| Anbieter | Vorgabemodell | Endpunkt |
| --- | --- | --- |
| Anthropic | `claude-haiku-4-5-20251001` | `https://api.anthropic.com` |
| OpenAI | `gpt-4o` | `https://api.openai.com` |

Das Modell antwortet mit einem JSON-Objekt, dessen Schlüssel die Epic-Nummern
und `sonstiges` sind. Diese Struktur ist nötig, damit die Stichpunkte dem
richtigen Abschnitt zugeordnet werden; aus Fließtext ließe sich das nicht
zurückgewinnen.

Der Report entsteht auch dann, wenn dabei etwas schiefgeht — bei fehlendem
Schlüssel, gescheitertem Aufruf, einer Antwort ohne verwertbares JSON oder
einzelnen unbrauchbaren Einträgen. An die Stelle der betroffenen Stichpunkte
treten die Titel der Vorgänge. Die Fehlermeldung des Anbieters steht im
Protokoll des Workflows und nennt typische Ursachen wie ein unbekanntes Modell
oder einen abgelaufenen Schlüssel.

### Warum ein eigenes Secret

Der Report verwendet bewusst **nicht** den Anwendungsschlüssel
`OPAA_OPENAI_API_KEY`. Dieser steuert in [`ci.yml`](../.github/workflows/ci.yml),
ob der Job `backend-integration` die OpenAI-Integrationstests ausführt —
`OpenAiIntegrationTest` ist über `@EnabledIfEnvironmentVariable` an dieselbe
Variable gebunden.

Wären beide gekoppelt, hätte das Aktivieren der Zusammenfassung zur Folge, dass
bei jedem Push und jedem Pull Request echte Aufrufe gegen die OpenAI-API laufen.
Da `backend-integration` ein erforderlicher Status-Check ist, würde zudem eine
Störung beim Anbieter Merges blockieren.

| Secret | Zweck |
| --- | --- |
| `OPAA_REPORT_API_KEY` | Zusammenfassung im Tagesreport |
| `OPAA_OPENAI_API_KEY` | Anwendung und ihre Integrationstests in der CI |

## Einmalige Einrichtung

Der Branch `gh-pages` entsteht beim ersten Lauf des Workflows. Danach muss
GitHub Pages einmalig auf diesen Branch gestellt werden:

```bash
gh api -X POST repos/criew/opaa/pages \
  -f 'source[branch]=gh-pages' -f 'source[path]=/'
```

Alternativ unter *Settings → Pages → Branch: gh-pages*.
