# Issue #792 — fix(frontend): Space-Navigation der Seitenleiste erzeugt axe-Verstoß — li ohne ul-Elternelement
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend
- PRs: #793 (2026-08-23)

**Laut Issue:** Seit PR #791 rendert der Fußbereich der Space-Spalte als `<List component="nav">`, wodurch MUI das `<ul>` durch ein `<nav>` ersetzt — die `<li>`-Kinder stehen ohne Listen-Elternelement im DOM, axe-core wertet das als „serious“ (WCAG 1.3.1). Der verursachende PR wurde per Auto-Merge auf Basis der Required Checks gemergt, bevor der (nicht required) E2E-Lauf fertig war. Erwartung: `<nav><ul><li>…` statt `<nav>` als Ersatz für `<ul>`.

**Geliefert:** Der `nav`-Container liegt jetzt um die Liste statt an ihrer Stelle. Zusätzlich behebt der PR einen zweiten, im selben main-Lauf gefundenen „serious“-Verstoß: unzureichender Kontrast (3,29:1) des aktiven `AdminSectionNav`-Links, behoben durch Wechsel auf `text.primary`. Damit liefert der PR mehr als im Issue beschrieben, aber im selben Fehlerbild-Kontext (derselbe rote E2E-Lauf) — keine sachfremde Erweiterung.

**Verifikation:** `frontend/src/layouts/Sidebar.tsx` und `Sidebar.test.tsx` existieren im Worktree; `AdminSectionNav.tsx` wurde in #787/#794 kurz darauf entfernt (siehe issue-787.md) — der Kontrast-Fix betraf eine Komponente, die im Projekt inzwischen nicht mehr existiert, was der PR selbst bereits ankündigte („Die Komponente entfällt ohnehin mit #787“).

**Themen:** frontend, barrierefreiheit, navigation, ci
