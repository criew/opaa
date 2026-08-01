# ADR-0001: Kollaborations-Workflow für Menschen und KI-Agenten

## Status

Akzeptiert

## Kontext

OPAA ist ein neues Open-Source-Projekt, bei dem mehrere Menschen und KI-Coding-Agenten (Claude Code, GitHub Copilot, Cursor, usw.) zusammenarbeiten. Wir müssen von Anfang an Konventionen für Dokumentation, Branching, Commits und Code-Review etablieren.

## Entscheidung

Wir übernehmen den folgenden Workflow:

1. **Duale Instruktionsdateien**: `AGENTS.md` als universelle KI-Agenten-Instruktionsdatei (unterstützt von 60+ Tools) und `CLAUDE.md` für Claude-spezifisches Verhalten, das AGENTS.md importiert.

2. **Dokumentationsaufteilung**: GitHub Issues für Task-Tracking und Kollaboration. `docs/decisions/` für Architecture Decision Records (ADRs). `docs/features/` für Feature-Spezifikationen. Issues verlinken auf ihre entsprechenden Feature-Spezifikationsdateien.

3. **Branch-Benennung**: Format `feature/<issue-id>_<kurze-beschreibung>` (z. B. `feature/42_user-auth`, `feature/15_fix-null-pointer`). Jeder Branch ist über eine GitHub-Issue verknüpft. KI-generierte Worktree-Branch-Namen sind akzeptabel.

4. **Conventional Commits**: Alle Commit-Nachrichten folgen der Conventional-Commits-Spezifikation. KI-Agenten fügen `Co-Authored-By`-Trailer ein.

5. **PR-basierter Workflow**: Keine direkten Pushes zu `main`. Alle Änderungen durchlaufen Pull Requests mit Review. PR-Template enthält KI-Agenten-Offenlegung.

6. **Transparenz**: KI-Beiträge werden durch Commit-Trailer und PR-Template-Offenlegung klar gekennzeichnet. Dies ist Provenienz-Tracking, keine Einschränkung.

## Konsequenzen

- **Einfacher**: Einarbeitung neuer Beitragender (Mensch oder KI) — klare Konventionen von Anfang an. KI-Agenten können AGENTS.md lesen und die Projektnormen sofort verstehen. Architekturentscheidungen sind dokumentiert und auffindbar.
- **Schwieriger**: Etwas mehr Aufwand pro Beitrag (Branch-Benennung, PR-Template, Commit-Format). Dieser Aufwand ist jedoch minimal und verhindert Verwirrung, wenn das Team wächst.
