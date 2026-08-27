# Issue #805 — test(frontend): Nachweis-Lücken aus dem Review zu #803 schließen — 320-px-Geometrie, Rollenbindung, Doku
- Geschlossen: 2026-08-25 (completed)
- Labels: bug, frontend, size:S
- PRs: #907 (2026-08-25)

**Laut Issue:** Nach dem (wieder per Auto-Merge erfolgten) Merge von #803 lief ein weiteres Review nach: `main` sei funktional in Ordnung, aber zwei Kernzusagen aus #800 seien nicht durch Tests gedeckt — der bestehende E2E-Durchklick fängt die mobile 320-px-Unerreichbarkeit nicht (Desktop-Viewport, Playwright scrollt vor jedem Klick automatisch), und die Rollenbindung „Nicht-Admins sehen die Admin-Spalte nicht“ hat keine Zusicherung. Dazu mehrere kleinere Nits (Label-Umbruch, Test-Harness-Drift, veraltete Doku, falsche Spec-Zuordnung, Unicode-Bug in `userInitial`).

**Geliefert:** Neuer Spec `admin-area-navigation.spec.ts` mit echtem 320-px-Viewport und Geometrie-Zusicherungen (`scrollWidth`, `boundingBox()` je Ziel); Rollenbindung über `getByRole('navigation', {name: 'Administration'})` → `toHaveCount(0)` für Nicht-Admins abgesichert; `flex`-Anpassung für lange Labels; Test-Harness- und Doku-Nachzug; `userInitial` nimmt das erste Zeichen jetzt per Code-Point statt per UTF-16-Halbwert. Laut PR-Reproduktionsnachweis wird der Geometrie-Test bei zurückgebautem Mobil-Umbruch tatsächlich rot (bemerkenswert: der reine `scrollWidth`-Check allein bleibt grün, erst `boundingBox()` je Ziel deckt den Fehler auf — was den ursprünglichen Befund bestätigt). Deckt den Issue-Umfang vollständig.

**Verifikation:** `e2e/tests/admin-area-navigation.spec.ts` und `frontend/src/utils/userInitial.ts`/`userInitial.test.ts` existieren im Worktree.

**Themen:** frontend, e2e, barrierefreiheit, mobile, code-review, doku
