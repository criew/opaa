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
- Sicherstellen, dass Tests bestehen, bevor ein Review angefordert wird

## Issues

- **Issues müssen auf Englisch verfasst werden**
- Bereitgestellte Issue-Templates für Bug-Reports und Feature-Requests verwenden
- Für größere Features eine Feature-Spezifikation in `docs/features/` erstellen und vom Issue verlinken

## KI-Agenten-Beitragende

Dieses Projekt begrüßt ausdrücklich Beiträge von KI-Coding-Agenten (Claude Code, GitHub Copilot, Cursor, Codex, usw.).

### Erwartungen an KI-generierten Code

- Alle KI-Beiträge durchlaufen denselben PR-Review-Prozess wie menschlicher Code
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
