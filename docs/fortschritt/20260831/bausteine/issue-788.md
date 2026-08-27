# Issue #788 — feat(frontend): Benutzer-Einstellungen als globale Seite über den Avatar der Leiste
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #795 (2026-08-23)

**Laut Issue:** `/settings` sollte gemäß Mockup 2c aus dem Space-Rahmen in den globalen Rahmen (ohne Sekundärspalte) überführt werden, erreichbar über den Avatar der globalen Leiste: „GLOBAL“-Badge, Geltungsbereichs-Hinweis, ein reiner Anzeige-Profilblock (Avatar mit Initialen, Anzeigename, E-Mail, Anmeldeweg) sowie die bestehende Farbschema-Wahl. Bearbeitungsfunktionen aus dem Mockup (Anzeigename ändern, Sprache, Profilbild, Benachrichtigungs-Schalter) waren ausdrücklich außerhalb des Umfangs, weil das nötige Backend fehlt.

**Geliefert:** `/settings` rendert im sections-losen `GlobalAreaLayout` aus #787/#794, mit Badge, Geltungsbereichs-Hinweis und reinem Anzeige-Profilblock (Anmeldeweg aus dem Auth-Modus abgeleitet, nie ein technischer Modusname). Farbschema-Wahl samt „Vorgabe des Hauses übernehmen“ blieb erhalten. Die Seite war laut PR-Beschreibung bislang ungetestet — `SettingsPage.test.tsx` wurde neu angelegt. Kein erkennbarer Umfangs-Unterschied zum Issue.

**Verifikation:** `frontend/src/pages/SettingsPage.tsx` und `SettingsPage.test.tsx` existieren im heutigen Worktree.

**Themen:** frontend, navigation, einstellungen, design-system, barrierefreiheit
