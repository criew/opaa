# Issue #424 — test(e2e): Wissensbibliotheken — Upload, Freigabe und rechtebewusste Suche
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, size:M, workspace
- PRs: #453 (2026-08-17)

**Laut Issue:** Sieben E2E-Szenarien für den kompletten Weg hochladen → freigeben → finden, inklusive des wichtigsten Negativfalls (kein Grant → kein Treffer) und Entzugs eines Grants. Kriterium: Szenarien 4/5 müssen nachweislich fehlschlagen, wenn der Rechtefilter der Suche entfernt wird.

**Geliefert:** PR #453 implementiert alle sieben Szenarien in `e2e/tests/knowledge-libraries.spec.ts`. Da der E2E-Stack bislang keinen funktionierenden Embedding-/Chat-Anbieter hatte (`OPAA_OPENAI_BASE_URL` zeigte auf einen Discard-Port, echte Modellbereitstellung ist eigenständiges Issue #256), wurde zusätzlich ein minimaler `ai-stub`-Service (`e2e/ai-stub/server.mjs`) gebaut — fester Embedding-Vektor, Chat-Antwort spiegelt Zitationsmarkierungen. Das ist eine über den Issue-Umfang hinausgehende Zusatzlieferung, ohne die die Szenarien gar nicht hätten laufen können. Ein dritter Testnutzer `dev-outsider` kam hinzu. Der geforderte Nachweis (Filter entfernen → Szenarien 4/5 rot) wurde erbracht und dokumentiert. Ein Folge-Issue #443 (Löschen von FILESYSTEM/HTTP_DIRECTORY-Dokumenten wirkt nur bis zum nächsten Indizierungslauf) wurde im Review gefunden.

**Verifikation:** `e2e/tests/knowledge-libraries.spec.ts` und `e2e/ai-stub/server.mjs` existieren im heutigen Code.

**Themen:** e2e, workspace, spaces, retrieval, auth, testinfrastruktur
