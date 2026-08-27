# Issue #924 — fix(ci): Renovate-PRs scheitern am CLA-Check — gitAuthor ist keinem Konto zugeordnet
- Geschlossen: 2026-08-26 (completed)
- Labels: bug, ci
- PRs: #925 (2026-08-26)

**Laut Issue:** Der erste echte Renovate-Lauf erzeugte fünf Update-PRs (#916–#920), alle rot am `cla-check` — Renovates Standard-`gitAuthor` ist keinem GitHub-Konto zugeordnet, die Allowlist `criew,*[bot]` matcht nur echte App-Bot-Logins, nicht den selbst betriebenen PAT-Lauf. Gefordert: `gitAuthor` in `renovate.json5` auf eine dem PAT-Inhaber zugeordnete, CLA-signierte Identität setzen, plus Nacharbeit (Rebase-Checkbox der fünf bestehenden PRs).

**Geliefert:** `gitAuthor: 'Renovate Bot <1293732+bigpuritz@users.noreply.github.com>'` — exakt wie im Issue vorgeschlagen. Dokumentation in `docs/renovate.md` ergänzt. Die Nacharbeit (Rebase der fünf Branches) hat der PR-Autor selbst übernommen, außerhalb des PR-Diffs.

**Verifikation:** `gitAuthor: 'Renovate Bot <1293732+bigpuritz@users.noreply.github.com>'` steht in `renovate.json5` (Zeile 26) im Worktree.

**Themen:** ci, renovate, cla, projektsetup
