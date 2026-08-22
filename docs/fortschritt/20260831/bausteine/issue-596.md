# Issue #596 — feat(frontend): Bibliothek-Anlage als Assistent mit Herkunfts-Auswahl
- Geschlossen: 2026-08-21 (completed)
- Labels: frontend, size:M
- PRs: #696 (2026-08-21)

**Laut Issue:** Die Bibliothek-Anlage (bisher `CreateLibraryDialog`) sollte laut Mockup 1e zu einem dreistufigen Assistenten werden: Stammdaten, Herkunft (vier Auswahlkarten: Upload, Dateisystem, Webverzeichnis, RSS-Feed, je mit passendem Verbindungsformular), Rechte. Zugangsdaten sollten als Passwortfelder nie im Klartext zurückgespiegelt werden; die Schrittlogik sollte nach DRY-Prinzip mit dem Space-Assistenten geteilt werden.

**Geliefert:** PR #696 liefert die Seite `/libraries/new` mit den drei Schritten Stammdaten, Herkunft (2×2-Kartenraster als Radiogruppe, typgebundenes Verbindungsformular inkl. Verbindungstest) und Rechte (Verteilungsstufe + Freigaben an Personen/Gruppen über die Grant-API). Die im Issue geforderte gemeinsame Schrittleiste wurde tatsächlich extrahiert (`components/wizard/WizardStepBar`, `FieldLabel`) und rückwirkend auch vom Space-Assistenten (#594) übernommen. `CreateLibraryDialog` wurde entfernt, E2E-Tests auf den Assistenten umgestellt.

**Verifikation:** `frontend/src/pages/LibraryCreatePage.tsx`, `frontend/src/components/wizard/WizardStepBar.tsx` und `FieldLabel.tsx` existieren im heutigen Worktree.

**Themen:** frontend, wissensbibliotheken, redesign, wizard, ui
