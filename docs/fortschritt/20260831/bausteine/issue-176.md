# Issue #176 — Expand coding-standards-reviewer into a full code-reviewer agent
- Geschlossen: 2026-07-17 (completed)
- Labels: enhancement, size:M
- PRs: #177 (2026-07-17)

**Laut Issue:** Zweiter Rollenagent: Erweiterung des bestehenden `coding-standards-reviewer` zu einem vollständigen `code-reviewer`, der zusätzlich Korrektheit/Bugs, Security, Testabdeckung neuer Logik und Dokumentationspflicht prüft, mit striktem Signalregime (max. 5 Nits, nur bestätigte Befunde, nie mergen/blocken).

**Geliefert:** PR #177 benennt die Agentendatei um (`code-reviewer.md`) und ergänzt die geforderten Kategorien, das Verify-Pass-Prinzip (CONFIRMED/PLAUSIBLE mit Datei:Zeile-Beleg) und das Read-only-Toolset. Deckt den Umfang vollständig ab.

**Verifikation:** `.claude/agents/code-reviewer.md` existiert im heutigen Worktree; die alte `coding-standards-reviewer.md` wurde entfernt (laut PR-Dateiliste).

**Themen:** agenten-organisation, code-review, dokumentation
