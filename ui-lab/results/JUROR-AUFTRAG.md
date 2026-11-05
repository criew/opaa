# Juror-Auftrag UI-Lab Dienstkonten (#3589, Stufe „schnell“)

Ein frischer Juror (nicht der UX-Designer) bewertet die Varianten A, B, C gegen den Ist-Stand mit
allen drei Blickwinkeln aus `.claude/skills/ui-lab/SKILL.md`, Abschnitt 5 (Regeln, Skalierung,
Ruhe und Konsistenz).

Unterlagen auf Branch `feature/3589_ui-lab-varianten`:

- Prototyp: `cd e2e && node ui-lab/build-prototype.mjs`, dann `ui-lab/out/` statisch ausliefern
  (`python3 -m http.server 8765` in `e2e/ui-lab/out`) und
  `http://localhost:8765/prototype/index.html?variant=a#/admin/connections/external-tools`
  bzw. `?variant=b#/admin/connections/service-accounts`, `?variant=c#/admin/connections/external-tools`,
  Bibliothek `#/libraries/library-nextcloud-projekte?tab=freigaben`; Szenarien über
  `?mockScenario=small|default|scale|empty|failed|partial` vor dem `#`.
- Kennzahlen: `ui-lab/results/metrics-summary.md`, je Variante `ui-lab/results/<X>/metrics.jsonl`
  und Screenshots, Berichte `ui-lab/results/<X>/REPORT.md`, Ist in `ui-lab/results/ist/`.
- Maßstab: `docs/design/ui-pruefliste.md`, `docs/design/guidelines.md`, `docs/design/accessibility.md`.
- Spezifikation: Epic #3587.

Je Variante liefern: bestanden/verfehlt je Prüflistenblock (Visuell, Text, Große Mengen, Allgemeine
Qualität, Layout), die fünf schwersten Befunde mit Beleg (Prüflistennummer + Datei oder
Prototyp-Zustand), die Teile, die in eine Mischung gehören. Keine Gesamtnote ohne Belege.
