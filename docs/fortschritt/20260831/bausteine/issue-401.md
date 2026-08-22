# Issue #401 — feat(db): Indizierungsläufe an die Organisation binden
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #681 (2026-08-20)

**Laut Issue:** Aus #356 abgeleitet: `indexing_jobs` trug keine `organization_id`. Bei einer zweiten Organisation wäre ein Indizierungslauf nicht mandantengebunden gewesen (Statusabfrage zeigt fremde Läufe, Nebenläufigkeitssperre wirkt organisationsübergreifend). Verlangt: Spalte ergänzen, Auftragsanlage/Statusabfrage/Sperre auf Organisation beziehen, Ratenbegrenzung je Organisation prüfen.

**Geliefert:** Migration 049 mit Backfill (über `library_id`, sonst die einzige damals existierende Organisation, per `preConditions`-Sperre gegen Mehrfachorganisationen abgesichert), `NOT NULL`, zusammengesetzter Fremdschlüssel. Wichtiger Kontextwechsel gegenüber dem Issue: `GET /api/v1/indexing/status` existiert auf dem heutigen Stand gar nicht mehr — die Läufe sind seit #478/ADR-0018 bibliotheksbezogen, und die dort beschriebene HTTP-Lücke besteht nicht mehr. Der PR liefert `organization_id` trotzdem als zweiten, von der Bibliotheksprüfung unabhängigen Schutz direkt auf `indexing_jobs`. Ratenbegrenzung je Organisation bewusst **nicht umgesetzt** — im Issue als offene Frage benannt, im PR als eigener, nicht in diesem Scope zu klärender Vorgang eingestuft. `indexing_run_events` bleibt bewusst ohne eigene `organization_id` (Kindtabellen-Ausnahme analog `chat_messages`, begründet). Kein klassischer rot/grün-Reproduktionsnachweis, da kein reproduzierbarer Bugfix — stattdessen Schema- und Verhaltensnachweis mit zwei echten Organisationen.

**Verifikation:** Migration `049-bind-indexing-jobs-to-organization.yaml` und `IndexingJob.java` existieren im Worktree.

**Themen:** organisationsgrenze, security, migration, indexierung, backend, mandantenfähigkeit
