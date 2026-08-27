# Issue #927 — docs: Doku-Struktur nach Achsen konsolidieren (Stand, Handbuch, Recherche)
- Geschlossen: 2026-08-26 (completed)
- Labels: documentation, size:M
- PRs: #928 (2026-08-26)

**Laut Issue:** `docs/` vermischte die Achsen Vision/Plan, Spezifikation, Ideen/Diskussion, Stand und Produktdokumentation. Konkret gefordert: `STATUS.md` (veraltet, z. B. Bereich G fälschlich als „kein Protokoll“) zugunsten von `docs/fortschritt/gesamtstand.md` als einziger Stand-Quelle löschen; `GraphRAG.md` nach `docs/discussions/` verschieben; veraltete Marketing-Artefakte (`onepager-de.html`, `OPAA-pitch-de.html/.pdf`) löschen; neuer Ordner `docs/handbuch/` für `deployment.md`/`demo-walkthrough.md`; `INDEX.md` um eine Achsen-Erklärung ergänzen.

**Geliefert:** Alle vier Maßnahmen wie gefordert umgesetzt, plus ein vom Maintainer nachgetragener fünfter Punkt außerhalb des ursprünglichen Issue-Texts: `docs/tagesreport.md` → `docs/fortschritt/tagesreport.md` und `docs/renovate.md` im INDEX verlinkt. Historische Dokumente unter `docs/fortschritt/20260831/` bewusst unangetastet gelassen, wie gefordert.

**Verifikation:** Verzeichnis `docs/handbuch/` existiert im Worktree.

**Themen:** doku, projektstruktur, gesamtstand
