# Tagesreport

Ein täglich laufender Workflow trägt zusammen, was im Projekt passiert ist, und
veröffentlicht das Ergebnis als Seite auf GitHub Pages. Über einen Atom-Feed
lässt sich der Report abonnieren.

- **Seite:** https://criew.github.io/opaa/
- **Feed:** https://criew.github.io/opaa/feed.xml

## Was im Report steht

Für den jeweiligen Vortag:

- Abgeschlossene Issues
- Gemergte Pull Requests mit Umfang der Änderung
- Neu angelegte Issues
- Zum Tagesende offene Pull Requests
- Status des letzten CI-Laufs auf `main`

Darüber steht eine Zusammenfassung in Fließtext, die beschreibt, was sich
inhaltlich geändert hat und was neu angesetzt wurde. Sie entsteht durch einen
Modellaufruf und ist optional — siehe [Zusammenfassung aktivieren](#zusammenfassung-aktivieren).

Tage ohne Issue- oder PR-Bewegung erzeugen keinen Report.

## Aufbau

| Bestandteil | Zweck |
| --- | --- |
| `.github/workflows/daily-report.yml` | Zeitsteuerung, Veröffentlichung im Branch `gh-pages` |
| `.github/scripts/daily_report.py` | Datenerhebung, Zusammenfassung, Erzeugung von Seite und Feed |
| Branch `gh-pages` | Veröffentlichte Seite samt Rohdaten aller bisherigen Tage |

Die Rohdaten jedes Tages liegen als JSON unter `data/` im Branch `gh-pages`.
Übersichtsseite und Feed werden bei jedem Lauf daraus neu erzeugt, sodass sich
Änderungen an der Darstellung rückwirkend auf alle Reports auswirken.

## Bedienung

Der Workflow läuft täglich um 04:30 UTC. Ein Lauf lässt sich jederzeit von Hand
auslösen, wahlweise für einen bestimmten Tag:

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
  --output /tmp/report
```

Das Skript benötigt eine angemeldete GitHub-CLI und kommt ohne zusätzliche
Pakete aus. Unter Windows fehlt Python häufig die Zeitzonendatenbank; dann
weicht das Skript auf UTC aus und die Tagesgrenzen verschieben sich um ein bis
zwei Stunden. Mit `pip install tzdata` lässt sich das beheben.

## Zusammenfassung aktivieren

Ohne API-Schlüssel entsteht der Report vollständig, nur ohne den Fließtext.
Zum Aktivieren einen Schlüssel als Repository-Secret hinterlegen:

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

Schlägt der Aufruf fehl, erscheint der Report ohne Zusammenfassung statt gar
nicht. Die Fehlermeldung des Anbieters steht im Protokoll des Workflows und
nennt typische Ursachen wie ein unbekanntes Modell oder einen abgelaufenen
Schlüssel.

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
