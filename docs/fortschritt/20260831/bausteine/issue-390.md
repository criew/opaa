# Issue #390 — test(backend): Organisationsgrenze durch strukturellen Prüflauf gegen das Schema nachweisen
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, size:M, security
- PRs: #688 (2026-08-20)

**Laut Issue:** Aus #356 abgeleitet: dritte Schicht des Nachweiswegs für die Organisationsgrenze. Prüflauf soll das tatsächliche DB-Schema (nicht die Changelog-Dateien) auslesen, zur Laufzeit alle Tabellen mit `organization_id` ermitteln, für jede prüfen, ob ihre Fremdschlüssel zu organisationsgebundenen Zieltabellen zusammengesetzt geführt sind, alle Verstöße gemeinsam melden, mit begründeter Ausnahmeliste. Abnahmekriterium: Der Prüflauf muss zunächst an der Lücke aus #289 rot werden.

**Geliefert:** Wie beschrieben, als `OrganizationBoundarySchemaTest` im Paket `io.opaa.migration`. Beim ersten echten Lauf fand der Test einen bis dahin unbemerkten, von keiner vorherigen Analyse erfassten Verstoß: `fk_space_memberships_space` blieb seit Migration 008 als redundanter einspaltiger Fremdschlüssel neben dem bereits vorhandenen zusammengesetzten stehen. Nach Rücksprache mit dem Koordinator direkt im selben PR behoben (Migration 050, kein Ausnahme-Eintrag, kein Folge-Issue) — Beispiel für „kleiner, themennaher Fund direkt erledigt". Rot/Grün-Nachweis mit konkreter Fehlermeldung erbracht. `DOCUMENTED_EXCEPTIONS`-Liste existiert, blieb leer.

**Verifikation:** `OrganizationBoundarySchemaTest.java` existiert im Worktree unter `backend/src/test/java/io/opaa/migration/`. Migration `050-drop-redundant-space-memberships-space-fk.yaml` existiert ebenfalls.

**Themen:** organisationsgrenze, security, migration, struktureller-prüflauf, backend
