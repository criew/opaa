# Issue #789 — feat(frontend): Wissensbibliotheken-Übersicht in den globalen Rahmen einbetten
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #799 (2026-08-23)

**Laut Issue:** `/libraries`, `/libraries/new` und `/libraries/:libraryId` sollten laut Schlussnotiz von Mockup-Abschnitt 2 in den globalen Rahmen (Rail sichtbar, keine Navy-Spalte) überführt werden, mit „GLOBAL“-Badge am Seitentitel der Übersicht und aktivem Rail-Eintrag „Katalog“. Bestehende Funktionen (Tabelle, Anlage, Detail, Upload) sollten unverändert bleiben.

**Geliefert:** Die drei Routen rendern im sections-losen `GlobalAreaLayout`; Badge nur am Titel der Übersicht (Unterseiten erben den Rahmen ohne eigenes Badge — bewusste, im PR begründete Abweichung von einer wörtlichen „Badge auf jeder Seite“-Lesart, aber im Sinne des Mockups). Rail-Eintrag „Katalog“ ist auf allen Bibliotheks-Routen aktiv. Die E2E-Bibliotheks-Flows navigierten laut PR bereits per `page.goto` und brauchten keine Anpassung. Damit ist laut PR-Beschreibung das gesamte Navigationskonzept aus Mockup-Abschnitt 2 (#786, #787, #788, #789) abgeschlossen.

**Verifikation:** `frontend/src/pages/LlmModelManagementPage.tsx` als Vergleichsmuster und `frontend/src/layouts/GlobalAreaLayout.tsx`/`globalArea.ts` existieren im Worktree; `LibraryManagementPage.tsx` als Kernziel dieses PRs ebenfalls (per Vorabprüfung des zugehörigen `LibraryController` bestätigt vorhandener Funktionsbereich).

**Themen:** frontend, navigation, bibliotheken, design-system, barrierefreiheit
