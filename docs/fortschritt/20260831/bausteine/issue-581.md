# Issue #581 — feat(frontend): Design-Tokens und Theme-Fundament des neuen Designsystems
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #622 (2026-08-20)

**Laut Issue:** Das bestehende Theme (`frontend/src/theme/theme.ts`) sollte durch eine vollständige Token-Ebene ersetzt werden — Farbskalen, semantische Flächen-/Text-Rollen, Typoskala, 4-px-Abstandsraster, 10-px-Radien, flache Flächen mit Rahmen statt Schatten. `createAppTheme` sollte Branding-Überschreibungen entgegennehmen können (zunächst ungenutzt), bestehende Seiten aber weiter benutzbar bleiben.

**Geliefert:** `frontend/src/theme/tokens.ts` als einzige Wertequelle (Farbskalen, semantische Rollen hell/dunkel, Typoskala, Radien, Schatten nur für schwebende Ebenen, Fokusring, Bewegungswerte). `createAppTheme` neu aufgebaut mit Komponenten-Overrides (Button, OutlinedInput, Tabellen, Dialoge, Chips, Tooltip, Links) und akzeptiert bereits `{ primaryColor }` als Branding-Override inkl. berechneter Hover-/Press-/Fokuszustände — Vorgriff auf #582/#583. JetBrains Mono als Mono-Schrift ergänzt. Deckt sich mit dem Issue-Zuschnitt, keine nennenswerten Abweichungen.

**Verifikation:** `frontend/src/theme/tokens.ts` und `frontend/src/theme/theme.ts` existieren im aktuellen Code; `tokens.ts` enthält weiterhin `accent: blue[500]` (`#1292EE`) und `primaryColor` als Override-Parameter.

**Themen:** frontend, design, theme, designsystem
