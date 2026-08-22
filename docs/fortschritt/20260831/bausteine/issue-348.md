# Issue #348 — Vektorspeicher-Austauschbarkeit: brauchen wir sie noch?
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #377 (2026-08-14)

**Laut Issue:** Prüfauftrag aus Epic #344: `docs/VISION.md`/`README.md` versprachen austauschbare Vektordatenbanken (Elasticsearch, pgvector, Milvus), obwohl ADR-0002 bereits pgvector festgelegt hatte. Zu klären: was ist im Code, gibt es einen realen Beschaffungsgrund für einen zweiten Vektorspeicher, und falls nein — Festlegung auf pgvector mit Begründung. Ergebnis sollte eine Entscheidungsvorlage sein, keine eigenmächtige Streichung.

**Geliefert:** PR #377 legt PostgreSQL mit pgvector als einzigen unterstützten Vektorspeicher fest. Portabilität der Schnittstelle bleibt als technische Eigenschaft benannt, aber ausdrücklich nicht als Angebot (kein Integrationstest, kein Betriebsleitfaden für Alternativen). Laut PR-Beschreibung wurde kein Anwendungscode geändert — reine Dokumentationsentscheidung. Geänderte Dateien: `data-indexing-rag.md` (neuer Begründungsabschnitt), ADR-0014 (neuer Nachtrags-Abschnitt), `CONCEPTS.md`, `deployment-infrastructure.md`, `deployment.md`, `STATUS.md`. Die bekannte Skalierungsgrenze von pgvector bei sehr großen Beständen wird offen benannt statt verschwiegen.

**Verifikation:** `docs/features/data-indexing-rag.md` enthält Zeile 294 „Der Vektorspeicher: PostgreSQL mit pgvector, und sonst keiner" und mehrfach die Festlegung im Text. Entscheidung ist im aktuellen Dokumentenbestand nachvollziehbar verankert.

**Themen:** doku, retrieval, architektur, produktvision
