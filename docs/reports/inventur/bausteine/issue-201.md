# Issue #201 — Knowledge library as the document container, with data migration
- Geschlossen: 2026-08-03 (completed)
- Labels: enhancement, backend, size:L
- PRs: #305 (2026-08-03)

**Laut Issue:** `KnowledgeLibrary` als erster Asset-Typ (id, name, Beschreibung, Eigentümer user/group, Organisation, Sichtbarkeit, gelistet). `Document.libraryId` und `library_id` auf Chunk-Metadaten, nicht nullbar. Persönliche Bibliothek "My documents" wird zusammen mit dem persönlichen Space erzeugt. Library-CRUD-API und Dokumenten-Endpoint. Upload zielt auf eine Library statt einen Space. Relationale DB ist führender Speicher, Vektorstore ist abgeleitet. Bestehende Dokumente werden in eine nur für System-Admins lesbare Systembibliothek migriert. Trockenlauf mit Mengengerüst vor dem Backfill, resumierbare Migration, dokumentierte Rollback-Reihenfolge.

**Geliefert:** PR #305 liefert `KnowledgeLibrary` mit zwei separaten FK-Spalten `owner_user_id`/`owner_group_id` statt einer polymorphen `owner_id`, Migration 012 mit Systembibliothek-Seed und Single-UPDATE-Backfill (bewusst nicht gebatcht, im Migrationsdokument begründet), Löschschutz für Bibliotheken mit Dokumenten bzw. Gruppen mit Bibliotheken (409). Nutzeranlage erzeugt persönlichen Space und persönliche Bibliothek — laut PR ausdrücklich **nicht** als eine gemeinsame DB-Transaktion umgesetzt ("atomisch" im Sinn von "gemeinsam versucht, unabhängige Fehlergrenzen, Fehlschlag wird geloggt statt Login zu blockieren"), abweichend von der wörtlichen Formulierung "atomically" im Issue, aber mit Race-Schutz über partielle Unique-Indizes. Ein erster Reviewdurchgang deckte eine echte Nebenläufigkeits-Regression auf (kaschiert durch einen Test-Pool-Override), die daraufhin durch `INSERT ... ON CONFLICT ... DO NOTHING` plus prozesslokalem Lock behoben wurde.

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibrary.java` existiert im heutigen Worktree.

**Themen:** spaces, retrieval, migration, backend, dokumente
