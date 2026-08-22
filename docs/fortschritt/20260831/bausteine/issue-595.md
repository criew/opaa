# Issue #595 — feat(frontend): Wissensbibliotheken-Übersicht als Tabelle mit Herkunft, Verteilungsstufe und Stand
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M
- PRs: #685 (2026-08-20)

**Laut Issue:** Die Bibliotheksübersicht (`LibraryManagementPage`) sollte laut Mockup 1d zu einer Tabelle mit den Spalten Name, Herkunft, Umfang, Verteilungsstufe, Rolle und Stand (inkl. laufendem Indizierungsfortschritt) werden, responsiv als Kartenliste unterhalb Tablet-Breite.

**Geliefert:** PR #685 baut die Übersicht als Tabelle mit den sechs Mockup-Spalten, Zeilen als vollflächige Links, Fortschrittsbalken für laufende Indizierungsläufe und der geforderten Fußnote. Responsives Verhalten (Kartenliste unterhalb `md`) bleibt erhalten. Abweichung: Der letzte erfolgreiche Indexstand („indiziert DD.MM.YYYY“ / „abgerufen heute HH:MM“) fehlt in `LibraryListResponse` und wurde als Folge-Issue #684 ausgelagert; bis dahin zeigt die Spalte ohne aktiven Lauf „–“.

**Verifikation:** `frontend/src/pages/LibraryManagementPage.tsx` und Test existieren im heutigen Worktree.

**Themen:** frontend, wissensbibliotheken, redesign, ui
