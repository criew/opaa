# Issue #586 — ci(frontend): Automatisierte Barrierefreiheits-Prüfungen in Lint und E2E-Suite
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M, ci
- PRs: #640 (2026-08-20)

**Laut Issue:** `eslint-plugin-jsx-a11y` in die Lint-Konfiguration aufnehmen, axe-core-Prüfung in die Playwright-E2E-Suite für mindestens Anmeldung, Chat, Spaces, Bibliotheken, Verwaltungsbereich integrieren (serious/critical lassen Suite fehlschlagen), beide Farbschemata mindestens für Chat prüfen, CI führt beides aus.

**Geliefert:** Wie gefordert, mit einer Abweichung bei der Bibliothekswahl: statt des offiziellen `eslint-plugin-jsx-a11y` kam der Fork `eslint-plugin-jsx-a11y-x` zum Einsatz, weil das Original ESLint 10 nicht als Peer-Dependency deklariert (Upstream-Issue vermerkt) — Rückwechsel als Folge-Issue #635 festgehalten. `e2e/tests/accessibility.spec.ts` deckt die geforderten Seiten ab, dunkles Schema zusätzlich für Chat. Zwei Befunde des Erstlaufs behoben (Listenstruktur der Seitenleiste) bzw. als dokumentierte Ausnahme mit Folge-Issue #634 (Kontrast der Akzentfarbe, deckt sich mit dem Nebenbefund aus #583) geführt. Der manuelle Tastatur-Durchgang der umgebauten Seitenleiste wurde im PR als vor dem Merge noch ausstehend vermerkt.

**Verifikation:** `frontend/eslint.config.js` importiert weiterhin `eslint-plugin-jsx-a11y-x` (Zeile 7), `eslint-plugin-jsx-a11y-x` steht in `frontend/package.json`; der im PR angekündigte Rückwechsel auf das Original-Plugin (#635) ist demnach noch nicht erfolgt. `e2e/tests/accessibility.spec.ts` existiert.

**Themen:** ci, barrierefreiheit, frontend, e2e, lint
