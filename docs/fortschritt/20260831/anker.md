# Diff-Anker dieser Inventur

Spätere Fortschreibungen erheben nur das Delta ab diesen Marken, statt erneut vollständig zu inventarisieren.

## Aktueller Anker (Stand 30.08.2026)

| Marke | Wert |
|---|---|
| Commit-Stand `main` | `a51e6b8c` — docs(discussions): Retrieval-Strategien-Recherche mit Roadmap und Dateityp-/Metadaten-Konzept (#1025) |
| Abfragezeitpunkt GitHub (Issues/PRs) | 2026-08-30 |
| Geschlossene Issues (kumuliert) | 504 |
| Gemergte PRs (kumuliert) | 496 |

## Erhebungsschritte

| Erhebung | Anker | Umfang |
|---|---|---|
| Erstinventur | `99f61ee1`, 2026-08-22 | 351 Issues, 324 PRs (davon 27 ohne Issue-Verknüpfung) |
| Nachzug 1 | `1c38b80d`, 2026-08-27 | +131 Issues, +112 PRs (davon 28 ohne Closing-Referenz) |
| Nachzug 2 | `a51e6b8c`, 2026-08-30 | +22 Issues, +63 PRs (davon 2 ohne Closing-Referenz; 43 Renovate-Updates gesammelt in einem Baustein) |

## Fortschreibung

- **Code:** `git log a51e6b8c..main --format="%h|%ad|%s" --date=short` — jeder Squash-Commit ist ein PR.
- **Issues:** `gh issue list --state closed --search "closed:>2026-08-30"` — alle seither geschlossenen Issues.
- Für das Delta neue Bausteine erzeugen, dann Gruppierung und Report fortschreiben; bestehende Bausteine bleiben unverändert.
