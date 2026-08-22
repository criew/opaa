# Issue #182 — Add marketing agent definition (positioning-first)
- Geschlossen: 2026-07-17 (completed)
- Labels: enhancement, size:M
- PRs: #183 (2026-07-17)

**Laut Issue:** Fünfter Rollen-Agent der Agentenorganisation: ein `marketing`-Subagent (Opus), dessen primäre Aufgabe die Schärfung von OPAAs Pitch und Mission ist — Positionierung zuerst, Assets werden daraus abgeleitet. Grund: festgestellter Message-Drift zwischen Vision, Pitch und Landing Page, fehlendes GDPR-by-design/EU-AI-Act-Messaging, zwei konkurrierende Wettbewerbsanalysen, keine Personas, keine dokumentierte Tonalität. Verlangt wurden `.claude/agents/marketing.md` mit Methodenstack (JTBD → Dunford → Moore-Statement → Messaging-House), Interview-first-Arbeitsweise mit Hard Stop beim Maintainer, Pflege von `docs/market/MESSAGING.md` als Quelle der Wahrheit, sowie Aktualisierung der Rollen-Tabelle in `docs/AGENT-ORGANIZATION.md`.

**Geliefert:** PR #183 legt `.claude/agents/marketing.md` genau mit diesem Methodenstack, Zwei-Spur-Tonalität (Community informell/EN, Buyer formell Sie/DE+EN) und Claim-Disziplin an und aktualisiert die Rollen-Tabelle in `docs/AGENT-ORGANIZATION.md`. Deckt sich mit der Forderung, keine erkennbaren Abweichungen.

**Verifikation:** `.claude/agents/marketing.md` existiert im Worktree. `docs/AGENT-ORGANIZATION.md` enthält die Marketing-Zeile mit Positionierungs-Beschreibung und Verweis auf den Subagenten `marketing` (Opus) sowie `docs/market/MESSAGING.md`. Rolle ist seither auch als Cross-Platform-Adapter (`.codex/agents/marketing.toml`, `.opencode/agents/marketing.md`, `agents/roles/marketing.md`) vorhanden (siehe Issue #184).

**Themen:** agenten-organisation, marketing, doku, positionierung
