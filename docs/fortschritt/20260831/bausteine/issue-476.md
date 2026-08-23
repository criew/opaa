# Issue #476 — feat(library): Quellentyp und Quellkonfiguration an der Bibliothek (Schema, Entity, API)
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:L
- PRs: #489 (2026-08-19)

**Laut Issue:** `KnowledgeLibrary` sollte nach ADR-0018 selbst `sourceType` und typspezifische Konfiguration tragen: Liquibase-Migration mit Backfill auf `UPLOAD`, Validierung je Typ, unveränderlicher Typ nach Anlage, OpenAPI-Erweiterung ohne Zugangsdaten in Antworten.

**Geliefert:** Wie gefordert. Migration `027-library-source-type-and-configuration.yaml` (im Issue-Body als `024` angekündigt, im PR tatsächlich als `027` umgesetzt — Nummerierungsverschiebung durch parallele Migrationen, kein inhaltlicher Unterschied) legt `source_type` NOT NULL mit CHECK-Constraint an, backfillt Bestand auf `UPLOAD`. `KnowledgeLibraryService` validiert Konfiguration je Typ und lehnt Typwechsel beim Update ab. `sourceCredentials` ist strukturell reines Nur-Schreiben-Feld der Anfrage, `LibraryResponse` kennt es nicht. Migrationskante bewusst benannt: Alt-Bestand über den früheren globalen Anstoß wird durch den Backfill lauf-los, bis eine neue typisierte Bibliothek angelegt wird — akzeptierte Nebenwirkung, kein Bug.

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibrary.java` und `KnowledgeLibraryService.java` existieren; Migration liegt im Changelog. `sourceType` ist heute fester Bestandteil des Bibliotheksmodells, sichtbar u. a. in `KnowledgeLibraryServiceIntegrationTest.java`.

**Themen:** backend, spaces, retrieval, migration, adr
