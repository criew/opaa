# Issue #312 — fix(ci): Zeitfenster des Tagesreports nachvollziehbar und lückenlos machen
- Geschlossen: 2026-08-04 (completed)
- Labels: bug, size:S, ci
- PRs: #313 (2026-08-04)

**Laut Issue:** Drei Befunde am Tagesreport: stiller Rückfall auf UTC bei fehlender Zeitzonendatenbank (nur auf stderr protokolliert), Fenstergrenze auf `23:59:59` statt halboffen, und die verwendeten Grenzen sind weder in Rohdaten noch Seite nachvollziehbar. Gefordert: Zeitzone/Fenster in Rohdaten und Fußbereich ausweisen, halboffenes Fenster, UTC-Rückfall im Report sichtbar machen.

**Geliefert:** PR #313 setzt Nachvollziehbarkeit und UTC-Warnhinweis um wie gefordert (`timezone`, `window_start`, `window_end` in Rohdaten, Fußbereich der Seite). Abweichung vom Issue: Das geforderte halboffene Fenster wurde **nicht** umgesetzt — laut PR funktioniert das bei der GitHub-Suchsyntax nicht (zwei Bereichsangaben zum selben Feld verdrängen sich statt sich zu verknüpfen, empirisch mit Zahlen belegt). Der Bereichsoperator mit `23:59:59`-Grenze bleibt bestehen, mit Begründung im Code dokumentiert; da Zeitstempel sekundengenau sind, entsteht ohnehin keine Lücke. Zusätzlich, nicht im Issue gefordert: der geplante Lauf wurde von 04:30 UTC auf 00:30 UTC vorgezogen.

**Verifikation:** `.github/scripts/daily_report.py` im Worktree enthält `window_start`.

**Themen:** ci, tagesreport, doku, automatisierung
