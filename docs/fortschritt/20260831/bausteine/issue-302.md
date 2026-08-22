# Issue #302 — docs(agents): Umgang mit Transaktionen in die Entwickler-Rollendefinition aufnehmen
- Geschlossen: 2026-08-03 (completed)
- Labels: documentation, size:S
- PRs: #303 (2026-08-03)

**Laut Issue:** Dieselbe Transaktions-Konstruktion (`REQUIRES_NEW` neben/innerhalb einer offenen Transaktion) hatte in Stufe A von #198 dreimal Fehler verursacht (PR #280, #297, #299), jedes Mal erst im Review gefunden. Gefordert: ein Abschnitt in `agents/roles/developer.md` mit den Lehren (Sichtbarkeit, Commit-Reihenfolge, Ressourcen, `readOnly=true` schützt nicht strukturell), belegt mit den drei PR-Nummern; Client-Adapter (`.claude/`, `.codex/`, `.opencode/`) bleiben inhaltsfrei.

**Geliefert:** PR #303 ergänzt genau diesen Abschnitt in `agents/roles/developer.md`, mit Verweis auf die drei Fälle und der Frage „braucht die Methode überhaupt `@Transactional`" an erster Stelle. Client-Adapter wurden laut PR-Beschreibung geprüft und blieben unverändert. Reine Dokumentationsänderung, keine Abweichung vom Issue erkennbar.

**Verifikation:** `agents/roles/developer.md` enthält 7 Treffer für „Transaktion" im heutigen Worktree — Abschnitt ist vorhanden.

**Themen:** doku, agenten-organisation, transaktionen
