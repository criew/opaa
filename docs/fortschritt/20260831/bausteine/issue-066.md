# Issue #66 — ⚠️ [HIGH] Missing Transaction Boundaries in QueryService
- Geschlossen: 2026-02-28 (completed)
- Labels: bug, backend, size:S, security
- PRs: #81 (2026-02-28)

**Laut Issue:** `QueryService.query()` führte mehrere DB-Operationen ohne Transaktionsklammer aus — Risiko für Race Conditions bei parallelem Indexing. Gefordert: `@Transactional(readOnly = true)` auf der Methode.

**Geliefert:** PR #81 ergänzt genau diese Annotation plus einen reflektionsbasierten Test, der ihre Anwesenheit prüft.

**Verifikation:** Abweichung, bewusst und dokumentiert: Die Annotation ist im heutigen `QueryService.query()` **nicht mehr** vorhanden. Der Javadoc an der Methode erklärt unter Verweis auf Issue #525 (Review Runde 2, Finding A) und #299, dass genau diese `@Transactional`-Klammer später zu einem Connection-Pool-Deadlock führte: Sie hielt eine JDBC-Verbindung über die gesamte Methodendauer offen — inklusive des LLM-Aufrufs, dem langsamsten Schritt —, während ein nachgelagerter Schreibzugriff (`ChatService#appendTurn`) eine zweite Verbindung brauchte. Bei mehr als 10 gleichzeitigen Anfragen (Hikari-Pool-Default) blockierte sich das System selbst. Der hier gelieferte Fix war also fachlich richtig gegen das ursprüngliche Risiko, erzeugte aber ein neues, schwerwiegenderes Problem und wurde revertiert zugunsten kurzlebiger, einzeln transaktionaler Aufrufe.

**Themen:** backend, transaktionen, deadlock, query, review-nachwirkung
