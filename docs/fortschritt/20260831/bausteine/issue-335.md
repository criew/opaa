# Issue #335 — Epics auf native GitHub-Sub-Issues umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #336 (2026-08-14)

**Laut Issue:** Epics verlinkten ihre Tickets bisher als Markdown-Checkliste im Body, die einzige Zuordnungsgrundlage des Tagesreports und driftanfällig. Umstellung auf native GitHub-Sub-Issues (GraphQL `subIssues`/`subIssuesSummary`), Checkliste nur noch als Rückfall während der Migration. Betroffen: `daily_report.py`, `epic.md`-Template (Abschnitt Tickets entfällt, Phasen als Prosa), `docs/tagesreport.md`, Migration bestehender Epics.

**Geliefert:** PR #336 setzt die Umstellung um: Zuordnung primär über Sub-Issues, Rückfall auf Checkliste mit Protokollmeldung. Migration von 86 Parent/Child-Beziehungen über die GitHub-API (außerhalb des Diffs) für die Epics #106, #107, #198, #224 sowie #18 (geschlossen, Liste dort bewusst als Abschlussbericht belassen); #4 und #60 bewusst ohne Sub-Issues, da sie nie als Ticket-Epics geführt wurden. 8 neue Tests plus Mutationsnachweis, zusätzlich Vergleichslauf gegen die Rohdaten vom 3. August in drei Zuständen (vor Migration/nach Migration/nach Checklisten-Entfernung), jeweils identisches Ergebnis. Deckt sich mit dem Issue.

**Verifikation:** `.github/ISSUE_TEMPLATE/epic.md` enthält heute „Phasen" statt „Tickets" als Abschnitt, mit Hinweis auf Sub-Issues über die Seitenleiste (bestätigt).

**Themen:** ci, tagesreport, agenten-organisation, doku
