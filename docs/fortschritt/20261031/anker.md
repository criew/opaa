# Diff-Anker dieser Fortschreibung

Spätere Fortschreibungen erheben nur das Delta ab diesen Marken.

## Vorgänger-Anker

[20260930/anker.md](../20260930/anker.md): `main@17ec7fca8`, Abfragezeitpunkt 2026-09-30T07:11Z.

## Aktueller Anker (Stand 31.10.2026)

| Marke | Wert |
|---|---|
| Commit-Stand `main` | `ANKER_KURZ` — ANKER_TITEL, Stand von `v0.1.0-rc.5` |
| Abfragezeitpunkt GitHub (Issues/PRs) | 2026-10-31T08:42Z |
| Im Zeitraum geschlossene Issues | 680 |
| Im Zeitraum gemergte PRs | 725 |
| Commits auf `main` (`--first-parent`) im Zeitraum | ANKER_COMMITS |

Erhoben über die REST-API (`gh api repos/criew/opaa/issues?state=closed&since=2026-09-29T00:00:00Z`,
seitenweise), weil GraphQL und die Suche in Cloud-Sessions gesperrt sind; gefiltert auf
`closed_at` bzw. `merged_at` ab dem 30.09.2026. Abgezogen wurde Issue #2057, das schon in der
Erhebung zum 30.09. enthalten war. Die Issue↔PR-Zuordnung stammt aus „Closes/Fixes/Resolves #N“ im
vollständigen PR-Text; 165 PRs haben keine solche Verknüpfung (davon 73 Renovate-Updates).

Die PRs nach dem Abfragezeitpunkt bis zum Anker-Commit (#3484, #3485, Release-Vorbereitung von
`v0.1.0-rc.5`) zählen zum nächsten Zeitraum.

## Fortschreibung

- **Code:** `git log ANKER_KURZ..main --first-parent --format="%h|%ad|%s" --date=short`.
- **Issues:** `gh api 'repos/criew/opaa/issues?state=closed&since=2026-10-31T00:00:00Z&per_page=100&page=N'`
  und auf `closed_at` filtern; Vorgänge vom 31.10., die schon in dieser Erhebung enthalten sind,
  anhand der Nummernliste in `bausteine.md` abziehen.
- **PRs:** dieselbe Abfrage, Einträge mit `pull_request.merged_at`, ebenso.
