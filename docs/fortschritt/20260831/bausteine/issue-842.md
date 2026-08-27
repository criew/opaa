# Issue #842 — docs: Kommentar-Konvention — Vertrag statt PR-Historie, projektweit in AGENTS.md verankern
- Geschlossen: 2026-08-24 (completed)
- Labels: documentation, backend, frontend, size:S
- PRs: #858 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 2. ~30 % der Backend-Main-Zeilen sind Kommentare, viel davon Review-Runden-Nacherzählung statt Vertrag. Konvention projektweit in AGENTS.md verankern (Vertrag/Invariante in 1–5 Zeilen statt Entstehungsgeschichte); Code-Reviewer-Rolle um die Prüfung ergänzen; Kurzbefund zur Frontend-Kommentardichte.

**Geliefert:** Wie gefordert. Konvention in AGENTS.md, Abschnitt Code-Konventionen, verankert (mit Positiv-/Negativbeispiel, wie im heute gültigen AGENTS.md sichtbar). `agents/roles/code-reviewer.md` um die Prüfung ergänzt. Kurzbefund Frontend: ~9,4 % Kommentaranteil (162 Dateien, 36.363 Zeilen, 3.423 Kommentarzeilen) — deutlich unter dem Backend-Befund; Empfehlung „kein Folgeticket" für eine Frontend-Bestandskürzung.

**Verifikation:** AGENTS.md im Worktree enthält den Abschnitt „Code-Kommentare" mit Positiv-/Negativbeispiel, deckungsgleich mit der PR-Beschreibung.

**Themen:** doku, agenten-organisation, code-konventionen
