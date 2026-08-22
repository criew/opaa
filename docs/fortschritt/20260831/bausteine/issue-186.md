# Issue #186 — Projektsprache auf Deutsch umstellen
- Geschlossen: 2026-08-01 (completed)
- Labels: documentation, size:L
- PRs: #187 (2026-08-01)

**Laut Issue:** Die gesamte Projektdokumentation (README, CONTRIBUTING, AGENTS.md, CLAUDE.md, CLA.md, alles unter `docs/`, GitHub-Templates, Agentendefinitionen, Workflow-Regeln) sollte fachlich korrekt und natürlich ins Deutsche übersetzt werden. Quellcode, Code-Kommentare und Build-Konfiguration bleiben Englisch.

**Geliefert:** PR #187 übersetzt 50 Markdown-Dateien: README, CONTRIBUTING, AGENTS.md, CLA.md, GitHub-Templates, Claude-/OpenCode-Agentendefinitionen, `.claude/rules/workflow.md`, `agents/roles/`, die gesamte `docs/`-Hierarchie (ADRs, Feature-Specs, Konzepte, MVP-Doku, Deployment, Design). Explizit unverändert gelassen: `CLAUDE.md` (enthält nur die `@AGENTS.md`-Direktive) sowie bereits deutschsprachige Diskussionsdateien. Deckt sich mit der Forderung; keine Abweichung erkennbar.

**Verifikation:** `README.md` im heutigen Worktree ist vollständig auf Deutsch (Titel, Säulen-Beschreibung). `AGENTS.md` und `docs/AGENT-ORGANIZATION.md` sind ebenfalls Deutsch und seither weiter deutschsprachig fortgeschrieben (neue ADRs 0009–0019 sind durchgehend auf Deutsch betitelt und verfasst). Die Sprachumstellung wurde offensichtlich zur dauerhaften Konvention (siehe AGENTS.md-Abschnitt „Projektsprache"), nicht nur einmalig für Altbestand angewendet.

**Themen:** doku, i18n, projektsprache, agenten-organisation
