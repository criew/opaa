# Issue #815 — test(e2e): space-chats Szenario 1 flaky im Gesamtlauf — zitierte Quelle erscheint nach Reload nicht

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend
- PRs: #961 (2026-08-28)

**Laut Issue:** Im vollen lokalen E2E-Lauf scheitert `space-chats.spec.ts` Szenario 1
reproduzierbar am zweiten `expectAnyCitedSource(page)` nach `page.reload()`; in Isolation ist
dieselbe Spec stabil grün — ordnungs-/lastabhängige Flakiness, keine Regression eines einzelnen
PRs.

**Geliefert:** PR #961 aktiviert den lokalen Retry wie in der CI (`retries: 1` in
`e2e/playwright.config.ts`) und dokumentiert den Lastbefund als Ursache. Kein Produktfehler;
die Antwortlatenz unter Volllast ist als Beobachtung festgehalten.

**Verifikation:** `e2e/playwright.config.ts` führt `retries: 1`; Commit `f0d24e1c` auf `main`.

**Themen:** E2E, Flaky-Test, Testinfrastruktur
