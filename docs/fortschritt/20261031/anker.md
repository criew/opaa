# Diff-Anker dieser Fortschreibung

Spätere Fortschreibungen erheben nur das Delta ab diesen Marken.

## Vorgänger-Anker

[20260930/anker.md](../20260930/anker.md): `main@17ec7fca8`, Abfragezeitpunkt 2026-09-30T07:11Z.

## Aktueller Anker (Stand 31.10.2026)

| Marke | Wert |
|---|---|
| Commit-Stand `main` | `39a00d1da` — fix(compliance): Datenfluss mail um alle Anlässe und Inhalte ergänzen (#3485), Stand von `v0.1.0-rc.5` |
| Abfragezeitpunkt GitHub (Issues/PRs) | 2026-10-31T08:42Z |
| Im Zeitraum geschlossene Issues | 681 |
| Im Zeitraum gemergte PRs | 725 |
| Commits auf `main` (`--first-parent`) im Zeitraum | 726 |

Erhoben über die REST-API (`gh api repos/criew/opaa/issues?state=closed&since=2026-09-29T00:00:00Z`,
seitenweise), weil GraphQL und die Suche in Cloud-Sessions gesperrt sind; gefiltert auf
`closed_at` bzw. `merged_at` ab dem 30.09.2026. Die Issue↔PR-Zuordnung stammt aus „Closes/Fixes/Resolves #N“ im
vollständigen PR-Text; 165 PRs haben keine solche Verknüpfung (davon 73 Renovate-Updates).

Die beiden PRs nach dem Abfragezeitpunkt (#3484, #3485, Release-Vorbereitung von `v0.1.0-rc.5`)
liegen im Anker-Commit, sind aber nicht in den Zahlen dieses Zeitraums enthalten; die nächste
Fortschreibung zieht sie mit ihren Issues #3481 und #3483 über die Nummern nach.

## Fortschreibung

- **Code:** `git log 39a00d1da..main --first-parent --format="%h|%ad|%s" --date=short`.
- **Issues:** `gh api 'repos/criew/opaa/issues?state=closed&since=2026-10-31T00:00:00Z&per_page=100&page=N'`
  und auf `closed_at` filtern; Vorgänge vom 31.10., die schon in dieser Erhebung enthalten sind,
  anhand der Nummernliste in `bausteine.md` abziehen.
- **PRs:** dieselbe Abfrage, Einträge mit `pull_request.merged_at`, ebenso.
