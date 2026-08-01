# Agenten-Organisation & Entwicklungs-Workflow

Wie OPAA von einem Team aus KI-Agenten mit Menschen in der Schleife entwickelt wird. Dieses Dokument beschreibt **wer was tut** (Agenten-Rollen), **wie Arbeit fließt** von der Idee bis zum Merge, und **welche Regeln gelten** für Menschen und Agenten. Es ergänzt [ADR-0001](./decisions/0001-collaboration-workflow.md) (Branching, Commits, PRs) — diese Konventionen gelten unverändert.

Menschen und Agenten verwenden denselben **Workflow**: dieselben Issues, dieselbe Branch-Benennung, dasselbe PR-Template. Ein Mensch, der ein Issue aufgreift, folgt genau den nachfolgend für den Entwickler-Agenten beschriebenen Schritten.

## Rollen

| Rolle | Verantwortung | Läuft als |
|---|---|---|
| **Orchestrator** | Einziger menschenzugewandter Einstiegspunkt. Nimmt Ziele vom Maintainer entgegen, priorisiert den Backlog (Projektmanager-Rolle), delegiert Arbeit an die nachfolgenden Agenten, überwacht PRs und eskaliert nur Entscheidungen, die einen Menschen erfordern. | Claude Code Hauptsitzung (Opus/Fable) |
| **Product Manager** | Verantwortlich für die funktionale Definition: hält Vision und Realität synchron (`docs/VISION.md`, `docs/MVP-STATUS.md`), schreibt Feature-Spezifikationen in `docs/features/`, erstellt und priorisiert GitHub-Issues. | Subagent `product-manager` (Sonnet) |
| **Developer** | Implementiert ein Issue vollständig (Backend **und** Frontend) in einem isolierten Git-Worktree, auf einem `feature/<issue-id>_<desc>`-Branch, und öffnet einen PR. | Subagent `developer` (Sonnet), möglicherweise mehrere Instanzen parallel — eine pro Issue |
| **Code Reviewer** | Adversariales Review jedes PRs mit frischem Kontext (keine Implementierungs-Bias): Korrektheit, ADR-Compliance, Wiederverwendung, fehlende Dokumentation. Entwirft ADRs, wenn er eine Architekturentscheidung erkennt. | Subagent `code-reviewer` (Opus) |
| **QA Engineer** | Produktqualität über das jeweilige PR-Review hinaus: alleiniger Eigentümer der E2E-Suite (implementiert die dedizierten `test(e2e)`-Issues, die zum Spezifikationszeitpunkt erstellt wurden), RAG-Antwortqualitäts-Evaluierung (Golden Dataset + Evaluatoren), Coverage-/Flakiness-Trends, Release-Bewertung. | Subagent `qa-engineer` (Sonnet) |
| **Marketing** | Positionierung zuerst: schärft Pitch und Mission, pflegt die Messaging-Quelle der Wahrheit (`docs/market/MESSAGING.md`), leitet stakeholder-spezifische Assets davon ab — Landing Page (`page/`), Pitch-Decks, One-Pager, README-Messaging, Website-i18n. Positionierungsentscheidungen verbleiben beim Maintainer. | Subagent `marketing` (Opus) |

Designprinzipien hinter dieser Struktur (basierend auf Multi-Agenten-Forschung und Anthropic-Leitfäden):

- **Ein Agent, eine Spur** — enge Geltungsbereiche halten den Kontext sauber und Ergebnisse zuverlässig.
- **Artefakte statt Dialog** — Agenten übergeben Arbeit durch Spezifikationen, Issues und PRs, niemals durch informelle Gespräche.
- **Schreibvorgänge sind single-threaded** — Parallelismus entsteht durch mehrere Entwickler an *verschiedenen* Issues, nicht durch Aufteilung eines Features auf Agenten.
- **Reviewer ist immer vom Implementierer getrennt** — der am besten dokumentierte Qualitätshebel in der Multi-Agenten-Entwicklung.

## Agenten-Definitionen und Client-Adapter

Die obige Tabelle beschreibt die Rollen der Organisation. Die gemeinsamen Rollenverträge in `agents/roles/` sind die Quelle der Wahrheit für das konkrete Agenten-Verhalten. Sie enthalten absichtlich keine anbieterspezifische Modell-, Tool-, Berechtigungs-, Speicher- oder Worktree-Konfiguration.

Jeder unterstützte Client hat einen dünnen projektlokalen Adapter, der auf den entsprechenden gemeinsamen Vertrag verweist:

| Client | Adapter-Pfad | Hinweise |
|---|---|---|
| Claude Code | `.claude/agents/` | YAML-Frontmatter liefert Claude Code-Tools, Modellauswahl, visuelle Einstellungen, Speicher und Worktree-Isolierung. |
| Codex | `.codex/agents/` | TOML-Dateien definieren Codex Custom Agents. Der Reviewer verwendet eine schreibgeschützte Sandbox. |
| OpenCode | `.opencode/agents/` | Markdown-Frontmatter definiert OpenCode-Subagenten und ihre Berechtigungen. Der Reviewer verweigert Bearbeitungen. |

Alle Adapter weisen ihren Agenten an, `AGENTS.md`, dieses Organisationsdokument und seinen gemeinsamen Rollenvertrag zu lesen, bevor er arbeitet. Eine Rolle muss in `agents/roles/` hinzugefügt werden, bevor sie Provider-Adapter erhält. Die fünf konkreten Rollendefinitionen sind Product Manager, Developer, Code Reviewer, QA Engineer und Marketing.

## Workflow: von der Idee bis zum Merge

```mermaid
flowchart TD
    A[Maintainer nennt ein Ziel] --> B[Product Manager:\nKlärungsfragen → Feature-Spezifikation → GitHub-Issues]
    B --> C{Maintainer genehmigt Issues?}
    C -- anpassen --> B
    C -- ja --> D[Orchestrator verteilt einen\nDeveloper pro Issue]
    D --> E[Developer: Worktree + Feature-Branch\n→ Implementierung + Tests + Docs → PR]
    E --> F[Code Reviewer + CI]
    F -- Befunde --> E
    F -- genehmigt --> G[Maintainer merged]
    G -.-> H[QA Engineer: geplante Läufe auf main\nE2E, RAG-Evaluierung, Coverage]
    H -. Befunde werden neue Issues .-> C
```

1. **Ziel** — Der Maintainer gibt dem Orchestrator ein Ziel ("einen Confluence-Connector hinzufügen").
2. **Definition** — Der Product Manager recherchiert Repository-Kontext, stellt seine Klärungsfragen **einmal, gebündelt, im Voraus** (durch den Orchestrator weitergeleitet), schreibt dann die Feature-Spezifikation in `docs/features/` und erstellt beschriftete GitHub-Issues.
3. **Genehmigung** — Der Maintainer prüft die Issues, bevor die Implementierung beginnt.
4. **Implementierung** — Für jedes genehmigte Issue arbeitet ein Entwickler-Agent in einem isolierten Worktree auf einem `feature/<issue-id>_<desc>`-Branch und öffnet einen PR unter Verwendung des PR-Templates (einschließlich KI-Agenten-Offenlegung).
5. **Review** — Der Code Reviewer und CI agieren als Schranken. Befunde gehen zurück zum Entwickler; der PR ist nur bereit, wenn beide bestehen.
6. **Merge** — **Nur Menschen mergen.** Kein Agent mergt jemals einen PR. (Diese Richtlinie kann schrittweise gelockert werden, wenn Vertrauen aufgebaut ist — jede Änderung daran muss hier festgehalten werden.)

### Wo QA passt: zwei Qualitätsschleifen

Der QA Engineer ist bewusst **nicht** Teil des PR-Gates — das ist die Aufgabe des Code Reviewers und CI, und eine Verdoppelung würde beide Geltungsbereiche verwischen. QA arbeitet in einer zweiten, langsameren Schleife rund um den Merge:

- **Issue-getrieben, wie ein Entwickler, in einer eigenen Spur.** QA-Infrastruktur ist reguläre Backlog-Arbeit (E2E-Test-Suite, Coverage-Reporting, RAG-Antwortqualitäts-Evaluierung). Der Orchestrator verteilt solche Issues an den QA-Agenten statt an einen Entwickler; die resultierende Arbeit durchläuft denselben PR → Review → Merge-Pfad.
- **Wiederkehrender Hüter nach dem Merge.** Nach einem Zeitplan (geplante Routine oder CI-Job auf `main`) übt der QA-Agent den aktuellen Produktstand — E2E-Läufe, RAG-Evaluierung, Coverage-Trends. **Seine Befunde werden neue Issues** (Bug-Reports mit Reproduktionsschritten), die den Workflow bei Schritt 3 wieder eintreten.
- **Zum Definitionszeitpunkt** leitet der Product Manager E2E-relevante Szenarien aus den Abnahmekriterien ab und erstellt dedizierte `test(e2e)`-Issues; der QA Engineer implementiert sie in der Suite, sobald das Feature gelandet ist.

Es gibt also zwei Schleifen: die schnelle **PR-Schleife** (Code Reviewer + CI, vor dem Merge) und die langsame **Produkt-Schleife** (QA Engineer, nach dem Merge, produziert neue Issues).

## Regeln

### Issues

Issues sind die Arbeitseinheit und müssen ausreichend in sich geschlossen sein, damit jeder Entwickler — Mensch oder Agent — sie aufgreifen kann. Jedes Issue enthält:

- **Kontext / Warum** — Link zur Vision, zum Epic oder zur Feature-Spezifikation
- **Ziel / Ergebnis** — ein Satz, der beschreibt, was danach möglich ist
- **Abnahmekriterien** — einzeln testbare Checkboxen; "Dokumentation aktualisiert" ist ein ständiges Kriterium für nutzerseitige oder architektonische Änderungen
- **Umfang / Außerhalb des Umfangs** — explizite Grenzen
- **Betroffene Module** — z. B. `io.opaa.indexing`, Frontend, OpenAPI-Spezifikation (Spec-Änderungen sind ein Koordinationspunkt — siehe [ADR-0006](./decisions/0006-openapi-dto-generation.md))
- **Abhängigkeiten** — blockierende Issues
- **Labels** — einschließlich `size:S/M/L`

### Dokumentation

- **Feature-Dokumentation wird von demjenigen geschrieben, der das Feature baut, im selben PR.** Kein separater Dokumentationsdurchgang; dies wird durch die Abnahmekriterien durchgesetzt und vom Code Reviewer geprüft.
- **ADRs**: Wenn der Code Reviewer oder ein Entwickler eine echte Architekturentscheidung identifiziert, schreibt er einen ADR-Entwurf in `docs/decisions/` mit dem Status `proposed` und hängt ihn an den PR. Der Maintainer entscheidet: `accepted` (gemergt) oder abgelehnt. Nichts Architektonisches wird implizit festgelegt.

### Autonomie und Eskalation

- Subagenten interagieren nie direkt mit dem Maintainer; Fragen werden gebündelt und vom Orchestrator weitergeleitet, vorzugsweise während des Definitionsschritts statt mitten in der Implementierung.
- Qualitätsgates sind deterministisch (CI, Hooks), keine Versprechen in Prompts: Tests müssen bestehen, bevor ein Agent ein Issue als erledigt melden darf.
- Agenten arbeiten unter einer Allow/Deny-Berechtigungsrichtlinie (`.claude/settings.json`); destruktive Befehle und `gh pr merge` sind für Agenten verweigert.
