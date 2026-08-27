# Issue #929 — docs: Demo-Dokumentation konsolidieren und deployment.md zum allgemeinen Betriebshandbuch machen
- Geschlossen: 2026-08-26 (completed)
- Labels: documentation, size:L, demo
- PRs: #931 (2026-08-26)

**Laut Issue:** Die Demo-Instanz „Stadt Rheinfurt“ war über vier sich überschneidende Dokumente beschrieben (`deployment.md`, `demo-walkthrough.md`, `demo/README.md`, `demo-instance.md`), mit belegten Drift-Fällen (falsche `docker-compose.yml`-Zeilennummern, falsche Aussage zu `OPAA_CSP_CONNECT_SRC_EXTRA`, ~80 Zeilen zu entfallenen Variablen). Zielstruktur: `deployment.md` wird rein allgemeines Betriebshandbuch; `demo/README.md` wird die eine Quelle für die Demo-Umgebung; das Vorführ-Drehbuch zieht nach `docs/market/demo-drehbuch.md`; `demo-instance.md` bleibt reines Konzept.

**Geliefert:** Alle vier Strukturpunkte umgesetzt, inklusive der belegten Sanierungen (Zeilennummern korrigiert 62/64–65/40/56/68, CSP-Aussage korrigiert, `#762`-Migrationsblock eingedampft, `OPAA_AUTH_BASIC_*`-Nachrufe gestrichen). `demo-walkthrough.md` vollständig aufgelöst. **Abweichung von der Schätzung:** Die Kürzung von `deployment.md` fiel mit 1105 → 979 Zeilen kleiner aus als die im Issue geschätzten ~400–450 Zeilen — im PR begründet: die H3-Abschnitte „Aktualisierung“ und „Sicherheitshinweis“ beschrieben tatsächlich allgemeines, instanzunabhängiges Betriebswissen und wurden zu eigenständigen H2-Abschnitten promoviert statt ausgelagert.

**Verifikation:** `demo/README.md` existiert im Worktree.

**Themen:** doku, demo, betrieb, deployment
