# Issue #295 — docs(agents): Branch-Regel für Fehlerbehebungen und Hotfixes klarstellen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, size:S
- PRs: #296 (2026-08-02)

**Laut Issue:** Beim Hotfix zu PR #287 wurde der Branch `fix/280_personal-space-transaction` statt `feature/...` verwendet — die Regel in `AGENTS.md` war zwar eindeutig, sagte aber nicht ausdrücklich, dass sie auch für Fehlerbehebungen und Hotfixes gilt. Maintainer-Entscheidung: kein `fix/`-Präfix, ausnahmslos `feature/`. Gefordert: Klarstellung in `AGENTS.md`, Abgleich mit `.claude/rules/workflow.md` und `CONTRIBUTING.md`.

**Geliefert:** PR #296 erledigt die Branch-Regel-Klarstellung und erweitert den Umfang zusätzlich um einen zweiten, verwandten Befund: Da in drei aufeinanderfolgenden PRs (#254, #280, #283) fehlerhafter Produktivcode trotz grüner Tests durchrutschte, wurde ein neuer Abschnitt „Reproduktionsnachweis" (Fix zurücknehmen, Fehlschlag belegen, Fix wiederherstellen) in `AGENTS.md`, `.claude/rules/workflow.md` und im PR-Template ergänzt — über den ursprünglichen Issue-Umfang hinaus, aber sachlich verwandt.

**Verifikation:** `AGENTS.md` enthält im Worktree unverändert den Abschnitt „Branch-Regel (verbindlich)" mit dem Satz „ausnahmslos, auch bei Fehlerbehebungen, dringenden Korrekturen und Dokumentationsänderungen" — deckt sich mit dem heutigen `AGENTS.md`, das dem Agenten selbst als Arbeitsgrundlage dient.

**Themen:** agenten-organisation, doku, ci, projektsetup
