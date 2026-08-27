# Issue #819 — docs(library): ADR und Spezifikation für Ordner in Bibliotheken
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation, size:S
- PRs: #825 (2026-08-23)

**Laut Issue:** Teil von Epic #520 (Phase 1 — Konzept & Spezifikation). Verlangt ein ADR in `docs/decisions/` zur Entscheidung „Ordner in Bibliotheken als Navigation, keine Rechtegrenze" (echte Ordner-Entität statt virtueller Pfad-Präfixe, Grants bleiben auf Bibliotheksebene, Retrieval vorerst ohne Ordner-Filter), eine Aktualisierung von `docs/features/knowledge-sources.md` für UPLOAD- und FILESYSTEM-Bibliotheken sowie einen ergänzenden Absatz in `docs/CONCEPTS.md`. Reine Dokumentationsänderung ohne Code.

**Geliefert:** ADR-0020 (`docs/decisions/0020-ordner-in-bibliotheken-navigation.md`) mit der beschriebenen Entscheidung inkl. Detailpunkten (Unique-Constraint je `(library_id, parent_folder_id, name)`, Löschen mit Bestätigung durch den Service statt DB-Kaskade, Dedup-Index bleibt bibliotheksweit, Abgrenzung zu „kein Ordner in einem Raum"). `docs/features/knowledge-sources.md` und `docs/CONCEPTS.md` wie gefordert ergänzt. Deckt sich vollständig mit dem Issue-Umfang.

**Verifikation:** `docs/decisions/0020-ordner-in-bibliotheken-navigation.md` existiert im Worktree.

**Themen:** doku, ordner, spaces, architektur, adr
