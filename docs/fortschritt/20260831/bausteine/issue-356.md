# Issue #356 — Organisationsgrenze über die Anwendungsschicht hinaus absichern
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #397 (2026-08-14)

**Laut Issue:** Teil von #344. Die Organisation sei die harte Mandantengrenze; #289 und #271 zeigten, dass sie heute nicht überall durchgesetzt wird. Zu klären: wo die Grenze heute geprüft wird und wo nicht, welche Absicherung auf DB- vs. Anwendungsebene gehört, wie die Einhaltung dauerhaft nachgewiesen wird. Ergebnis: Entscheidungsvorlage mit Bezug zu #289/#271.

**Geliefert:** Reine Dokumentationsänderung. Drei Schichten festgelegt: Anwendung, Datenbank, struktureller Prüflauf gegen das Schema (dritte Schicht als Lehre aus einem konkreten Befund). #289 und #271 als Voraussetzung für eine zweite Organisation vorgezogen. Neuer Vorgang #390 für den strukturellen Prüflauf angelegt, mit dem Abnahmekriterium, dass er an der heutigen Lücke aus #289 zunächst rot werden muss. Beim Lesen der Changelogs wurden zusätzliche, bis dahin nicht erfasste Verstöße gefunden (`spaces.owner_id`, `knowledge_libraries.owner_user_id`, `asset_grants`-Spalten, `groups.parent_group_id`) und dokumentiert.

**Verifikation:** `docs/features/spaces-and-assets.md` existiert; der geschnittene Prüflauf #390 wurde tatsächlich gebaut (`OrganizationBoundarySchemaTest.java` existiert unter `backend/src/test/java/io/opaa/migration/`, siehe Baustein #390). Die hier benannten Zusatzfunde zu `groups.parent_group_id` sind über #400 behoben, `indexing_jobs` über #401.

**Themen:** organisationsgrenze, mandantenfähigkeit, security, produktausrichtung, doku
