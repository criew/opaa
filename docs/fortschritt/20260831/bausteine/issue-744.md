# Issue #744 — Leistungsinventur: Bestandsaufnahme aller abgeschlossenen Issues und PRs für den Meilenstein-1-Report
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation
- PRs: #746 (2026-08-23)

**Laut Issue:** Meilenstein 1 (31.08.2026) verlangt eine Aufstellung der bisher implementierten Leistungen als Grundlage für Abnahme und Priorisierung, weil `docs/STATUS.md` als Grundlage nicht verlässlich genug ist. Vorgehen: Bausteine je geschlossenem Issue/PR, thematische Gruppierung, erster Report-Entwurf. Ein Diff-Anker (Commit-Hash + Zeitstempel) sollte spätere Fortschreibung als Delta ermöglichen, statt jedes Mal vollständig neu zu erheben.

**Geliefert:** Wie gefordert — dieses Issue hat das Format begründet, in dem der vorliegende Baustein selbst entsteht. PR #746 führt `docs/fortschritt/` als neues Dokumentationsformat ein: je Stichtag ein Delta-Ordner mit `anker.md`, `bausteine/` (378 Bausteine für 20260831: 351 Issues + 27 PRs ohne Issue-Verknüpfung), `gruppierung.md` und einem Entwurfs-`report.md`. `docs/fortschritt/gesamtstand.md` soll perspektivisch die Rolle von `STATUS.md` übernehmen (Entscheidung bei Finalisierung offen). Veröffentlichung des Reports selbst war ausdrücklich nicht Teil dieses Issues.

**Verifikation:** `docs/fortschritt/20260831/bausteine/` existiert im Worktree (dieser Chunk trägt weitere Bausteine zu diesem Ordner bei); `docs/fortschritt/gesamtstand.md`/`report.md` wurden nicht einzeln nachgelesen.

**Themen:** doku, agenten-organisation, projektsetup, fortschrittsbericht
