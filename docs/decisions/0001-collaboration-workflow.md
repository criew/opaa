# ADR-0001: Kollaborations-Workflow für Menschen und KI-Agenten

## Status

Akzeptiert

## Kontext

OPAA ist ein Open-Source-Projekt, an dem Menschen und KI-Coding-Agenten gemeinsam arbeiten. Dabei kommt nicht ein einzelnes KI-Werkzeug zum Einsatz, sondern mehrere parallel — Claude Code, Codex, OpenCode und GitHub Copilot. Jedes bringt ein eigenes Format für Projektanweisungen und ein eigenes Konfigurationsschema für Subagenten mit (Modellwahl, Tool-Freigaben, Sandbox- und Worktree-Verhalten).

Daraus folgen zwei Anforderungen, die nicht dieselbe sind:

- **Ein Workflow für alle.** Branching, Commits, PRs und Review dürfen nicht davon abhängen, wer oder was den Beitrag erzeugt hat.
- **Ein Verhalten je Rolle, unabhängig vom Werkzeug.** Was ein Entwickler- oder Reviewer-Agent tut, muss an einer Stelle definiert sein — sonst driften die Anweisungen pro Anbieter auseinander und niemand merkt es.

## Entscheidung

Wir übernehmen den folgenden Workflow:

1. **Werkzeugneutrale Instruktionsdateien**: `AGENTS.md` im Repository-Stamm ist die verbindliche, von zahlreichen KI-Werkzeugen unterstützte Anweisungsdatei und die einzige Quelle der Projektkonventionen. Anbieterspezifische Dateien bleiben dünn und verweisen darauf: `CLAUDE.md` importiert `AGENTS.md`, `.github/copilot-instructions.md` verweist darauf. Eine Regel wird nie in zwei Dateien gepflegt.

2. **Gemeinsame Rollenverträge, dünne Client-Adapter**: Das fachliche Verhalten jeder Agenten-Rolle steht in `agents/roles/` und ist anbieterneutral. Jeder unterstützte Client erhält einen projektlokalen Adapter (`.claude/agents/`, `.codex/agents/`, `.opencode/agents/`), der ausschließlich clientspezifische Konfiguration liefert — Modell, Tools, Berechtigungen, Sandbox, Worktree-Isolierung — und auf den gemeinsamen Vertrag verweist. Eine Rolle existiert erst in `agents/roles/`, dann in den Adaptern. Rollenzuschnitt, Zusammenspiel und Eskalationswege beschreibt [docs/AGENT-ORGANIZATION.md](../AGENT-ORGANIZATION.md).

3. **Rollen statt Personen**: Anweisungen und Prozessdokumente benennen Rollen (Maintainer, Entwickler, Code Reviewer, QA Engineer), nicht Personen und nicht Produktnamen. Welches Werkzeug eine Rolle ausführt, ist eine Konfigurationsfrage im Adapter und keine Aussage des Prozesses.

4. **Dokumentationsaufteilung**: GitHub-Issues für Task-Tracking und Kollaboration. `docs/decisions/` für Architecture Decision Records (ADRs). `docs/features/` für Feature-Spezifikationen. Issues verlinken auf ihre entsprechende Feature-Spezifikation.

5. **Branch-Benennung**: Format `feature/<issue-id>_<kurze-beschreibung>` (z. B. `feature/42_user-auth`), ausnahmslos mit `feature/`-Präfix; die Art der Änderung drückt der Conventional-Commit-Typ aus. Jeder Branch ist über seine ID mit einem GitHub-Issue verknüpft.

6. **Conventional Commits**: Alle Commit-Nachrichten folgen der Conventional-Commits-Spezifikation. KI-Agenten fügen einen `Co-Authored-By`-Trailer ein.

7. **PR-basierter Workflow**: Keine direkten Pushes zu `main`. Alle Änderungen durchlaufen Pull Requests mit Review. Das PR-Template enthält die KI-Agenten-Offenlegung. Gemergt wird ausschließlich von einem Maintainer.

8. **Transparenz**: KI-Beiträge werden durch Commit-Trailer und PR-Template-Offenlegung klar gekennzeichnet. Dies ist Provenienz-Tracking, keine Einschränkung.

## Konsequenzen

### Was einfacher wird

- Einarbeitung neuer Beitragender (Mensch oder KI) — klare Konventionen von Anfang an. Ein Agent liest `AGENTS.md` und versteht die Projektnormen sofort, unabhängig davon, mit welchem Werkzeug er läuft.
- Ein weiteres KI-Werkzeug aufzunehmen kostet nur einen Satz dünner Adapter; die Rollenverträge bleiben unverändert.
- Verhaltensänderungen an einer Rolle wirken für alle Werkzeuge gleichzeitig, weil sie nur an einer Stelle stehen.
- Architekturentscheidungen sind dokumentiert und auffindbar.

### Was schwieriger wird

- Etwas mehr Aufwand pro Beitrag (Branch-Benennung, PR-Template, Commit-Format). Dieser Aufwand ist minimal und verhindert Verwirrung, wenn das Team wächst.
- Die Trennung zwischen Rollenvertrag und Adapter muss diszipliniert eingehalten werden: Sobald anbieterspezifische Konfiguration in `agents/roles/` sickert oder fachliche Anweisungen in einen Adapter, ist der Vorteil verloren. Der Code Reviewer prüft das mit.
- Jeder zusätzlich unterstützte Client vergrößert die Menge der Dateien, die bei einer neuen Rolle angelegt werden müssen.
