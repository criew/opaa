# Issue #245 — fix(ci): CLA-Workflow schlägt bei Kommentaren auf Issues fehl
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, ci
- PRs: #246 (2026-08-02)

**Laut Issue:** Der CLA-Workflow lief bei jedem `issue_comment`-Event, auch auf gewöhnlichen Issues (nicht nur PRs). Die CLA-Action erwartet dort einen Pull Request und brach mit einem GraphQL-Fehler ab — betroffen waren sieben fehlgeschlagene Läufe auf den Issue-Kommentaren zu #239, #241, #242, #243. Gefordert: Bedingung ergänzen, sodass der Job bei `issue_comment` nur läuft, wenn der Kommentar zu einem PR gehört; `pull_request_target` unverändert lassen.

**Geliefert:** Genau wie gefordert — Bedingung `github.event_name == 'pull_request_target' || github.event.issue.pull_request` am Job in `.github/workflows/cla.yml` ergänzt. Keine Abweichung vom Issue-Umfang.

**Verifikation:** `.github/workflows/cla.yml` enthält die Bedingung `if: github.event_name == 'pull_request_target' || github.event.issue.pull_request` unverändert im heutigen Stand.

**Themen:** ci, agenten-organisation
