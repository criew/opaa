# ADR-0001: Kollaborations-Workflow für Menschen und KI-Agenten

## Status

Akzeptiert · zuletzt geändert 2026-10-14

## Kontext

An OPAA arbeiten Menschen und mehrere KI-Coding-Agenten parallel (Claude Code, Codex, OpenCode,
GitHub Copilot). Jedes Werkzeug bringt ein eigenes Format für Projektanweisungen und ein eigenes
Konfigurationsschema für Subagenten mit (Modellwahl, Tool-Freigaben, Sandbox, Worktree). Daraus
folgen zwei getrennte Anforderungen: ein Workflow für alle Beiträge, unabhängig davon, wer oder was
sie erzeugt hat, und ein Verhalten je Rolle, das an genau einer Stelle definiert ist, damit die
Anweisungen je Anbieter nicht auseinanderdriften.

## Entscheidung

### 1. Werkzeugneutrale Instruktionsdateien

`AGENTS.md` im Repository-Stamm ist die einzige Quelle der Projektkonventionen. Anbieterspezifische
Dateien bleiben dünn und verweisen darauf: `CLAUDE.md` importiert `AGENTS.md`,
`.github/copilot-instructions.md` verweist darauf. Eine Regel wird nie in zwei Dateien gepflegt.
Regeln für ein Teilverzeichnis stehen in einer verschachtelten `AGENTS.md` dort (`backend/AGENTS.md`,
`frontend/AGENTS.md`, `docs/handbuch/AGENTS.md`, je logischem Backend-Modul eine `AGENTS.md` im
Hauptpaket), jeweils mit einer `CLAUDE.md` daneben, die sie importiert. Die Stamm-Datei verweist
verbindlich darauf und bleibt für alles Übergreifende zuständig.

### 2. Gemeinsame Rollenverträge, dünne Client-Adapter

Das fachliche Verhalten jeder Agenten-Rolle steht anbieterneutral in `agents/roles/`. Jeder
unterstützte Client hat einen projektlokalen Adapter (`.claude/agents/`, `.codex/agents/`,
`.opencode/agents/`), der nur clientspezifische Konfiguration liefert (Modell, Tools, Berechtigungen,
Sandbox, Worktree-Isolierung) und auf den Vertrag verweist. Eine Rolle existiert erst in
`agents/roles/`, dann in den Adaptern. Rollenzuschnitt, Zusammenspiel und Eskalationswege beschreibt
[docs/AGENT-ORGANIZATION.md](../AGENT-ORGANIZATION.md).

### 3. Rollen statt Personen

Anweisungen und Prozessdokumente benennen Rollen (Maintainer, Entwickler, Code Reviewer, QA
Engineer), nicht Personen und nicht Produktnamen. Welches Werkzeug eine Rolle ausführt, entscheidet
der Adapter, nicht der Prozess.

### 4. Dokumentationsaufteilung

GitHub-Issues für Aufgaben und Kollaboration, `docs/decisions/` für ADRs, `docs/features/` für
Feature-Spezifikationen. Issues verlinken auf ihre Feature-Spezifikation.

### 5. Branch-Benennung

`feature/<issue-id>_<kurze-beschreibung>`, ausnahmslos mit `feature/`-Präfix; die Art der Änderung
drückt der Conventional-Commit-Typ aus. Jeder Branch ist über seine ID mit einem Issue verknüpft.
Einzige Ausnahme sind die Bot-Branches `renovate/<slug>` (`docs/renovate.md`).

### 6. Conventional Commits

Alle Commit-Nachrichten folgen der Conventional-Commits-Spezifikation. KI-Agenten fügen einen
`Co-Authored-By`-Trailer ein.

### 7. PR-basierter Workflow

Keine direkten Pushes auf `main`. Jede Änderung durchläuft einen Pull Request mit Review; das
PR-Template (`.github/PULL_REQUEST_TEMPLATE.md`) enthält die KI-Agenten-Offenlegung. Gemergt wird
ausschließlich von einem Maintainer.

### 8. Transparenz

KI-Beiträge sind über Commit-Trailer und PR-Offenlegung gekennzeichnet. Das ist Provenienz, keine
Einschränkung.

## Konsequenzen

Einfacher: Einarbeitung neuer Beitragender, ob Mensch oder Agent, über eine Datei; ein weiteres
Werkzeug kostet nur einen Satz Adapter; eine Verhaltensänderung an einer Rolle wirkt für alle
Werkzeuge zugleich.

Schwieriger: etwas Aufwand je Beitrag (Branch-Name, PR-Template, Commit-Format); jeder weitere
Client vergrößert die Zahl der Dateien je neuer Rolle.

Restrisiken: Sobald anbieterspezifische Konfiguration in `agents/roles/` sickert oder fachliche
Anweisungen in einen Adapter, ist die Trennung verloren. Der Code Reviewer prüft das mit.

## Verworfene Alternativen

- Je Werkzeug eine eigene vollständige Anweisungsdatei — Regeln driften unbemerkt auseinander.
- Rollenverhalten direkt in den Client-Adaptern — dasselbe Verhalten müsste je Anbieter gepflegt werden.

## Offen

Nichts.
