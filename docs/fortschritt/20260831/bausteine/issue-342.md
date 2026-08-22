# Issue #342 — docs(marketing): Landing-Page, Pitch und One-Pager auf den Verwaltungston umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #369 (2026-08-14)

**Laut Issue:** Neue Wahrheitsquelle `docs/market/MESSAGING.md`, Neutextung von `page/index.html` (durchgehend „Sie", Vergleichstabelle auf Verwaltungskriterien), Neutextung von `docs/OPAA-pitch-de.html` und neues `docs/onepager-de.html`; englische Fassungen entfallen nach Maintainer-Rückfrage.

**Geliefert:** PR #369 liefert `docs/market/MESSAGING.md` als Wahrheitsquelle mit Positionierungssatz, Nutzenversprechen je Stakeholder und einem normativen „Was wir nicht sagen"-Abschnitt. `page/index.html`, `docs/OPAA-pitch-de.html` neu getextet, `docs/onepager-de.html` neu angelegt. Der Sprachumschalter (`data-de`/`data-en`) ist entfernt, `docs/OPAA-pitch-en.html` und `docs/OPAA-pitch-en.pdf` sind laut Maintainer-Entscheidung ersatzlos gelöscht. Zusätzlich, über den Issue-Umfang hinaus: externe Schriftarten-Einbindung entfernt (ADR-0004-Konformität) und `agents/roles/marketing.md` korrigiert. Bewusst offengelassen: Screenshots in `page/img/` zeigen weiterhin englischsprachige Firmenoberflächen und widersprechen den neuen (deutschen, verwaltungsnahen) Bildunterschriften — als eigenes Folge-Thema benannt, nicht in diesem PR behoben.

**Verifikation:** `docs/market/MESSAGING.md`, `docs/onepager-de.html`, `docs/OPAA-pitch-de.html`, `page/index.html` existieren im Worktree; `docs/OPAA-pitch-en.html` und `docs/OPAA-pitch-en.pdf` existieren nicht mehr — Löschung bestätigt.

**Themen:** doku, marketing, produktvision
