# Issue #317 — docs: GraphRAG-Recherche als Entscheidungsgrundlage aufnehmen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S, evaluation
- PRs: #318 (2026-08-14)

**Laut Issue:** Eine unversionierte Recherche zu GraphRAG (`docs/GraphRAG.md`) lag nur im Arbeitsverzeichnis und war damit für niemanden sonst zugänglich oder kommentierbar. Gefordert: Datei versionieren, im Doku-Index verlinken, Verweis auf ein projektfremdes Ticket-Kürzel im Dokumentkopf durch dieses Issue ersetzen. Ausdrücklich außerhalb des Umfangs: eine Entscheidung über die Empfehlungen der Recherche selbst.

**Geliefert:** PR #318 setzt alle drei Punkte um wie gefordert. Zusätzlich, nicht im Issue verlangt, aber als „Repository-Hygiene" mitgeliefert: `.gitignore` um `/.claude/worktrees/` ergänzt, sowie zwei versehentlich committete leere Artefaktdateien (`ablegt.`, `Ein` — Folge eines unquotierten Shell-Redirects) entfernt. Über die inhaltlichen Empfehlungen der Recherche wurde bewusst nicht entschieden, wie im Issue verlangt.

**Verifikation:** `docs/GraphRAG.md` existiert im Worktree. Die beiden Leerdateien `ablegt.` und `Ein` sind im heutigen Worktree nicht mehr vorhanden — die Hygiene-Bereinigung ist wirksam geblieben.

**Themen:** doku, evaluation, graphrag, retrieval, repository-hygiene
