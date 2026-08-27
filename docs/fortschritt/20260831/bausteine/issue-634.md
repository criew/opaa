# Issue #634 — fix(frontend): Akzentfarbe erreicht mit weißem Text nur 3,29:1 Kontrast (blue-500)
- Geschlossen: 2026-08-25 (completed)
- Labels: frontend, size:S
- PRs: #909 (2026-08-25)

**Laut Issue:** Die erste axe-core-Prüfung (#586) meldete auf jeder Seite einen "serious"-Verstoß: weißer Text (`accentFg`) auf `accent = blue[500]` erreicht nur 3,29:1 statt der geforderten 4,5:1 (WCAG 1.4.3). Betroffen waren gefüllte primäre Flächen (`Button variant="contained"`, `Chip color="primary"`) in beiden Farbschemata. Zur Wahl standen drei Varianten: Akzent verschieben, `accentFg` dunkel wählen, oder eine eigene Rolle für Text auf Akzentflächen einführen.

**Geliefert:** Variante 3 (eigene Rolle), erweitert um den Befund, dass `accent` auch Textfarbe ist (Links, Fußnoten, Ghost-Buttons) und deshalb nicht global auf Blau-700 wandern durfte. Neue Rolle `accent-surface` = Blau-700 für gefüllte Aktionsflächen (Weiß darauf: 5,2:1); `accent` bleibt Text-/Indikatorfarbe, jetzt je Schema nachgewiesen (hell Blau-700, dunkel/navy/rail Blau-500). Branding-Ableitung (`deriveAccentSurface`) passt die Aktionsfläche einer Betreiberfarbe an, begrenzt auf sechs Abdunklungsschritte. Alle axe-Ausnahmen (Buttons/Chips, Einstellungsseiten-Link) wurden ersatzlos aus `e2e/tests/accessibility.spec.ts` entfernt. Reproduktionsnachweis erbracht: 6 von 22 Theme-Tests rot vor dem Fix (u. a. `expected 3.2957… to be greater than or equal to 4.5`), 22/22 grün danach.

**Verifikation:** `frontend/src/theme/tokens.ts` zeigt `accentSurface: blue[700]` in allen vier Rollensätzen (Zeilen 100, 126, 142, 163, 188).

**Themen:** frontend, barrierefreiheit, theme, design
