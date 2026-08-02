# Mitwirken an OPAA

Vielen Dank für Ihr Interesse, zu OPAA beizutragen! Dieses Projekt heißt Beiträge von Menschen und KI-Coding-Agenten gleichermaßen willkommen.

## Contributor License Agreement (CLA)

**Bevor Ihr erster Pull Request zusammengeführt werden kann, müssen Sie die CLA unterzeichnen.**

OPAA verwendet ein duales Lizenzmodell: Der Kern ist quelloffen, und kommerzielle Lizenzen werden für Organisationen angeboten, die die Open-Source-Lizenz nicht einhalten können. Die CLA gewährt dem Projekt das Recht, Ihre Beiträge sowohl unter Open-Source- als auch kommerziellen Bedingungen unterzulizenzieren — das macht das duale Lizenzmodell rechtlich möglich.

**So unterzeichnen Sie:** Lesen Sie [CLA.md](./CLA.md) (kurz und bündig), dann posten Sie diesen Kommentar in Ihrem ersten PR:

> I have read the CLA Document and I hereby sign the CLA

Ihre Unterschrift wird automatisch erfasst. Sie müssen nur einmal unterzeichnen.

**KI-Agenten-Betreiber:** Wenn Sie einen KI-Coding-Agenten einsetzen, unterzeichnen Sie (der Mensch) die CLA. Siehe [CLA.md § 8](./CLA.md#8-ai-agent-contributions) für Details.

**Unternehmensbeitragende:** Wenn Sie im Namen eines Arbeitgebers beitragen, lesen Sie bitte auch [CLA.md § 5](./CLA.md#5-corporate-contributors).

## Erste Schritte

1. Repository forken
2. Fork klonen
3. Feature-Branch erstellen: `git checkout -b feature/42_my-feature`
4. Änderungen vornehmen
5. Push und Pull Request öffnen

## Branch-Benennung

Verwenden Sie das Format `feature/<issue-id>_<kurze-beschreibung>`:

```
feature/42_user-authentication
feature/15_fix-null-pointer
feature/7_add-contributing-guide
```

Jeder Branch ist über seine ID mit einem GitHub-Issue verknüpft.

## Commit-Nachrichten

Wir verwenden [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
feat: add user authentication
fix(api): handle null response from service
docs: update architecture decision records
```

## Pull Requests

- Alle Änderungen gehen über PRs — keine direkten Pushes zu `main`
- PR-Template vollständig ausfüllen
- Zugehörige GitHub-Issues mit `Closes #N` verknüpfen
- Sicherstellen, dass Tests bestehen, bevor der PR zum Merge angeboten wird

### Wie ein PR nach `main` kommt

1. CI muss grün sein — die Prüfungen `backend`, `backend-integration` und `frontend` sind Voraussetzung für den Merge
2. Der Code Reviewer prüft die Änderung; offene Befunde und Konversationen werden vorher aufgelöst
3. Einer der Maintainer merged den PR

Ein formales Approval in GitHub ist dafür nicht erforderlich. Maintainer mit Merge-Recht sind [@criew](https://github.com/criew) und [@bigpuritz](https://github.com/bigpuritz).

## Wann einen E2E-Test schreiben?

Die browserbasierte End-to-End-Suite liegt unter [`e2e/`](e2e/README.md) (Playwright) und ist laut
[`docs/AGENT-ORGANIZATION.md`](docs/AGENT-ORGANIZATION.md) Sache des QA-Engineer-Agenten, der aus
den Abnahmekriterien eines Feature-Issues dedizierte `test(e2e)`-Issues ableitet — nicht jeder PR
braucht einen eigenen E2E-Test. Ein E2E-Test lohnt sich für:

- **Kritische, nutzersichtbare Abläufe end-to-end** (Anmeldung, Dokument indizieren, Frage stellen
  und Antwort erhalten) — Dinge, die durch Unit-/Integrationstests allein nicht abgedeckt sind,
  weil sie Frontend, Backend und Datenbank gemeinsam durchlaufen.
- **Regressionen, die nur im Zusammenspiel mehrerer Schichten auftreten** (z. B. CORS-Konfiguration,
  Auth-Redirects, Routing).

Kein E2E-Test nötig für:

- Reine Komponentenlogik oder isolierte Backend-Logik — dafür Vitest (Frontend) bzw. JUnit
  (Backend) verwenden.
- Visuelle Details oder Layout-Feinheiten (explizit außerhalb des Scopes der Suite).
- Fälle, die sich genauso zuverlässig und schneller mit einem Integrationstest abdecken lassen.

Neue Szenarien nutzen die vorhandenen Fixtures (z. B. die Anmeldung aus `e2e/fixtures/auth.ts`)
statt sie zu kopieren; siehe `e2e/README.md` für Details zum lokalen Ausführen, die
Selektor-Konvention (`getByRole`/`getByLabel` vor `data-testid` vor Text-/Placeholder-Selektoren)
und die Serialisierungs-Konvention für Specs, die gemeinsamen Zustand verändern.

## Issues

- **Issues müssen auf Deutsch verfasst werden** — ebenso Pull Requests und Dokumentation. Englisch bleibt dem Quellcode vorbehalten (Bezeichner, Dateinamen, Kommentare); Details in [AGENTS.md](AGENTS.md#projektsprache)
- Bereitgestellte Issue-Templates für Bug-Reports und Feature-Requests verwenden
- Für größere Features eine Feature-Spezifikation in `docs/features/` erstellen und vom Issue verlinken

## KI-Agenten-Beitragende

Dieses Projekt begrüßt ausdrücklich Beiträge von KI-Coding-Agenten (Claude Code, GitHub Copilot, Cursor, Codex, usw.).

### Erwartungen an KI-generierten Code

- Alle KI-Beiträge durchlaufen denselben Weg nach `main` wie menschlicher Code: CI, Code Review, Merge durch einen Maintainer
- Conventional-Commits-Format verwenden
- `Co-Authored-By`-Trailer in Commits einfügen (z. B. `Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>`)
- KI-Beteiligung im Abschnitt „AI Agent Disclosure" des PR-Templates kennzeichnen
- `AGENTS.md` (oder `CLAUDE.md` für Claude) vor dem Beginn der Arbeit lesen

### Für Menschen, die KI-generierten Code überprüfen

- KI-Code mit derselben Sorgfalt wie menschlichen Code überprüfen
- Auf halluzinierte Importe, nicht existierende APIs und subtile Logikfehler achten
- Sicherstellen, dass KI-Agenten die hier dokumentierten Projektkonventionen eingehalten haben

## Verhaltenskodex

Seien Sie respektvoll und konstruktiv. Wir bauen gemeinsam etwas auf.
