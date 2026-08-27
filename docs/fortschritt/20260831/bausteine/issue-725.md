# Issue #725 — fix(a11y): Farbkontrast in der Wissensbibliotheken-Tabelle unzureichend
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, frontend, size:S
- PRs: #852 (2026-08-24)

**Laut Issue:** Die Tabelle der Wissensbibliotheken zeigte für Metadaten-Spalten (u. a. "Stand", Beschreibungstext) einen Farbkontrast von 3,68:1 gegen Weiß — unter der WCAG-2.1-AA-Anforderung von 4,5:1. Der Fehler war vorher unentdeckt, weil vor #233 (Umstellung der E2E-Suite auf das gemeinsame Seed-Profil) die Tabelle in den a11y-Tests immer leer gerendert wurde. Gefordert war eine Kontrastkorrektur ohne das übrige Design zu brechen, und Entfernung der dafür eingeführten axe-Ausnahme.

**Geliefert:** Wie gefordert. Statt die Grau-Skala selbst zu ändern (erste Fassung hätte die Skalenabstufung verzerrt), zeigt die Rolle `fg-3` (Tertiärtext) im hellen Schema jetzt auf `gray[500]` (`#556473`, 6,08:1) statt `gray[400]` (`#778797`, 3,68:1). `gray[400]` selbst bleibt für andere Verwendungen (Ränder, UI-Kontrast ≥ 3:1) unverändert. Die `.MuiTable-root`-Ausnahme in `e2e/tests/accessibility.spec.ts` wurde entfernt. Reproduktionsnachweis: Theme-Test zeigt vor der Umstellung `expected 3.686… to be greater than or equal to 4.5`, danach bestehen alle drei Kombinationen (6,08/5,71/5,40:1 gegen bg1/bg2/bg3).

**Verifikation:** Nicht erneut im Code geprüft — Änderung ist eine reine Token-/Rollenzuordnung in `frontend/src/theme/tokens.ts`, Nachweis im PR-Body als Test dokumentiert.

**Themen:** frontend, barrierefreiheit, theme, wissensbibliotheken
