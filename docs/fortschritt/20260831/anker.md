# Diff-Anker dieser Inventur

Spätere Fortschreibungen erheben nur das Delta ab diesen Marken, statt erneut vollständig zu inventarisieren.

## Aktueller Anker (Nachzug vom 27.08.2026)

| Marke | Wert |
|---|---|
| Commit-Stand `main` | `1c38b80d` — ci(baseline-diff): über alle eval/baseline/*.json-Dateien statt nur comic-characters prüfen (#944) |
| Abfragezeitpunkt GitHub (Issues/PRs) | 2026-08-27 |
| Geschlossene Issues (kumuliert) | 482 |
| Gemergte PRs (kumuliert) | 433 |

## Erhebungsschritte

| Erhebung | Anker | Umfang |
|---|---|---|
| Erstinventur | `99f61ee1`, 2026-08-22 | 351 Issues, 324 PRs (davon 27 ohne Issue-Verknüpfung) |
| Nachzug 1 | `1c38b80d`, 2026-08-27 | +131 Issues, +112 PRs (davon 28 ohne Closing-Referenz) |

## Fortschreibung

- **Code:** `git log 1c38b80d..main --format="%h|%ad|%s" --date=short` — jeder Squash-Commit ist ein PR.
- **Issues:** `gh issue list --state closed --search "closed:>2026-08-27"` — alle seither geschlossenen Issues.
- Für das Delta neue Bausteine erzeugen, dann Gruppierung und Report fortschreiben; bestehende Bausteine bleiben unverändert.
