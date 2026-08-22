# Issue #547 — test(e2e): E2E-Abdeckung für Upload-Limit, Verbindungstest, Dokumentliste und Quellkonfig-Bearbeitung
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, frontend, size:M
- PRs: #549 (2026-08-20)

**Laut Issue:** Die Nacharbeiten-Serie aus Epic #458 (#514, #516, #517, #519) hatte nutzersichtbares Verhalten ergänzt, das die E2E-Suite noch nicht abdeckte. Gefordert waren vier Szenarien: (1) Upload > 1 MB durch den echten nginx (Regressionsschutz für `client_max_body_size`), (2) Verbindungstest im Erstellungsdialog (Happy Path + Fehlerfall), (3) Dokumentliste mit Paging und Suche, (4) Quellkonfiguration bearbeiten (URL-Änderungshinweis, Credentials-Semantik). Explizit außerhalb des Umfangs: Negativtest „Erstanmeldung erzeugt keine Bibliothek" (#522) und RSS-Lauf-Abschlussmeldung (#518).

**Geliefert:** Neue Spec-Datei `e2e/tests/knowledge-library-nacharbeiten.spec.ts` deckt alle vier geforderten Szenarien ab. Bemerkenswerte technische Details: PDF für den Upload-Test wird zur Laufzeit mit `pdf-lib` und pseudo-zufälligem Text erzeugt (damit es trotz Kompression ~2 MB bleibt); ein neues statisches Fixture bildet das Apache-„HTMLTable"-Autoindex-Layout nach, da der Standard-`mod_autoindex` ohne diese Option nur eine `<pre>`-Liste liefert, die der Parser zu diesem Zeitpunkt nicht verstand (führte in der Folge zu Issue #550); Dateiname bewusst `knowledge-library-nacharbeiten.spec.ts` (Singular) statt `knowledge-libraries-...`, um die alphabetische Ausführungsreihenfolge der Playwright-Suite nicht zu stören. Ein begleitendes `aria-label` in `LibraryDetailPage.tsx` wurde für einen stabilen Pagination-Selektor ergänzt.

**Verifikation:** `e2e/tests/knowledge-library-nacharbeiten.spec.ts` existiert im Worktree.

**Themen:** e2e, library, retrieval, testing, ci
