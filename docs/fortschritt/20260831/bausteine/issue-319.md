# Issue #319 — docs: Agentenanweisungen entpersonalisieren, eval/ ergänzen und Duplikat auflösen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation
- PRs: #320 (2026-08-14)

**Laut Issue:** Drei Befunde in den Agenten-/Beitragsanweisungen: (1) Maintainer sind an vier Stellen namentlich statt als Rolle genannt (AGENTS.md, CONTRIBUTING.md, docs/AGENT-ORGANIZATION.md zweimal), (2) `eval/` fehlt unter „Wichtige Pfade" in AGENTS.md, (3) `.claude/rules/workflow.md` dupliziert Git-Workflow/Worktree-Regeln/Pre-Push-Checkliste aus AGENTS.md nahezu wortgleich. Aufgabe: Rollen statt Namen, eval/ ergänzen, Duplikatdatei entfernen, CLA-Abschnitt in AGENTS.md kürzen.

**Geliefert:** PR #320 setzt alle vier Punkte um — Maintainer-Nennungen auf Rollenaussage umgestellt, `eval/` unter „Wichtige Pfade" ergänzt (inkl. Sonderregel: außerhalb Gradle-Build/CI, Generatoren nur bei bewussten Korpus-Änderungen), `.claude/rules/workflow.md` entfernt, CLA-Abschnitt in AGENTS.md gekürzt und auf CONTRIBUTING.md/CLA.md verwiesen. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `.claude/rules/workflow.md` existiert im Worktree nicht mehr (bestätigt). `AGENTS.md` enthält den `eval/`-Eintrag unter „Wichtige Pfade" mit dem beschriebenen Wortlaut (Zeile 190). Beides deckt sich mit dem PR-Anspruch.

**Themen:** doku, agenten-organisation, projektsetup
