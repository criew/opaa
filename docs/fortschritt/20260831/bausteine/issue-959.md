# Issue #959 — fix(frontend): Fokus geht nach Escape beim Inline-Umbenennen eines Chats verloren

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #968 (2026-08-28)

**Laut Issue:** Befund aus dem Tastatur-Durchgang des Abschluss-Audits (#598, Schweregrad
niedrig): Nach Escape (und nach Enter) beim Inline-Umbenennen eines Chats fällt der Fokus auf
`document.body` statt zum auslösenden Element zurückzukehren — Tastaturnutzer müssen sich von
vorn durch die Seite tabben.

**Geliefert:** PR #968 lässt den Fokus nach Abbruch oder Commit zur Aktionen-Schaltfläche des
Chats zurückkehren, wie es die Checkliste in `docs/design/accessibility.md` (2.1) verlangt.

**Verifikation:** Commit `fc935fe5` auf `main`.

**Themen:** Barrierefreiheit, Audit-Befund, Fokusführung
