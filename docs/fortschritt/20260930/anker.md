# Diff-Anker dieser Fortschreibung

Spätere Fortschreibungen erheben nur das Delta ab diesen Marken.

## Vorgänger-Anker

[20260831/anker.md](../20260831/anker.md): `main@a51e6b8c`, Abfragezeitpunkt 2026-08-30.

## Aktueller Anker (Stand 30.09.2026)

| Marke | Wert |
|---|---|
| Commit-Stand `main` | `17ec7fca8` — Merge pull request #2067 (Kopierfunktion im Chat, #1924) |
| Abfragezeitpunkt GitHub (Issues/PRs) | 2026-09-30T07:11Z |
| Im Zeitraum geschlossene Issues | 502 |
| Im Zeitraum gemergte PRs | 489 |
| Commits auf `main` (`--first-parent`) im Zeitraum | 467 |

Erhoben mit `gh issue list --state closed --search "closed:>2026-08-30"` und
`gh pr list --state merged --search "merged:>2026-08-30"`. Nachgezogen wurden die drei Vorgänge
vom 30.08., die erst nach dem Vorgänger-Anker abgeschlossen wurden: Issue #945 und PR #1026
(Finalisierung des Meilenstein-1-Berichts). Die ebenfalls am 30.08. geschlossenen Issues #1022 und
#1023 gehören mit ihren PRs #1024/#1025 noch zum Vorgänger-Anker.

Die Differenz zwischen PRs und `main`-Commits sind 22 PRs, die in einen anderen Branch als `main`
gemergt wurden (gestapelte PRs); ihr Inhalt erreicht `main` über den PR des Basis-Branchs. Ein Teil
der PRs geht per Merge-Commit statt per Squash ein — `--first-parent` zählt auch dann genau einen
Commit je PR. Maßgeblich für das Delta ist die PR-Liste.

## Fortschreibung

- **Code:** `git log 17ec7fca8..main --first-parent --format="%h|%ad|%s" --date=short`.
- **Issues:** `gh issue list --state closed --search "closed:>=2026-09-30"`; Vorgänge vom 30.09.,
  die schon in dieser Erhebung enthalten sind, anhand der Nummernliste in `bausteine.md` abziehen.
- **PRs:** `gh pr list --state merged --search "merged:>=2026-09-30"`, ebenso.
