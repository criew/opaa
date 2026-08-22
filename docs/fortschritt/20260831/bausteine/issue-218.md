# Issue #218 — feat(agents): add six public-administration stakeholder agents for concept review
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #220 (2026-08-02)

**Laut Issue:** Forderte sechs Stakeholder-Agenten (sachbearbeiter, referatsleitung, ki-champion, betrieb, skeptiker, personalrat), die Konzepte aus einer benannten Verwaltungsperspektive schriftlich bewerten, ohne Produktionscode zu schreiben. Umfang: gemeinsame Rollenverträge in `agents/roles/`, Client-Adapter für `.claude/`, `.codex/`, `.opencode/`, sowie Erweiterung von `docs/AGENT-ORGANIZATION.md`.

**Geliefert:** PR #220 liefert alle sechs Rollen deckungsgleich mit dem Issue: Rollenverträge in `agents/roles/`, Adapter für alle drei Clients (`.claude/agents/`, `.codex/agents/`, `.opencode/agents/`), sowie die Dokumentationserweiterung. Alle sechs teilen laut PR-Beschreibung ein gemeinsames Bewertungsformat; die Skeptiker-Rolle trägt explizit die Regel, dass Einwände konkret und überprüfbar sein müssen. Keine erkennbaren Abweichungen vom Issue-Umfang.

**Verifikation:** Alle sechs Dateien liegen sowohl unter `agents/roles/` als auch unter `.claude/agents/` im heutigen Worktree-Stand (stakeholder-betrieb, -ki-champion, -personalrat, -referatsleitung, -sachbearbeiter, -skeptiker).

**Themen:** agenten-organisation, doku, stakeholder-review
