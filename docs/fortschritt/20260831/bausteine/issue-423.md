# Issue #423 — feat(frontend): Rechte an einer Wissensbibliothek verwalten
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, frontend, size:M, auth, workspace
- PRs: #446 (2026-08-17)

**Laut Issue:** MANAGER einer Wissensbibliothek sollen Freigaben (Grants) einsehen, erteilen, befristen, ändern und entziehen können, mit aufgelösten Namen (nicht UUID) und erklärten Rollen. Technischer Hinweis warnte, dass die Namensauflösung über admin-beschränkte Endpunkte scheitern könnte und das dann ein eigenes Backend-Issue sein müsse.

**Geliefert:** PR #446 liefert die Rechteansicht vollständig. Im Review (Runde 2) zeigte sich genau das im Issue vorhergesehene Problem: `GET /v1/admin/users`/`/v1/admin/groups` sind SYSTEM_ADMIN-only, ein regulärer MANAGER sah nur UUIDs. Statt eines separaten Folge-Issues wurde dies direkt im selben PR behoben — `AssetGrantResponse` bekam serverseitig aufgelöste `subjectDisplayName`/`grantedByDisplayName`-Felder. Zusätzlich behoben: Button „Rechte verwalten" auf der persönlichen Bibliothek ausgeblendet (dort lehnt das Backend jede Vergabe ab), Freitext-Fallback für Gruppen-ID ergänzt. Drei Folge-Issues entstanden: #445 (Personen-/Gruppensuche unabhängig von Systemrolle), #448 (rohe Enum-Namen/fehlende Umlaute in Backend-Fehlermeldungen).

**Verifikation:** `frontend/src/components/LibraryGrantsDialog.tsx` existiert im heutigen Code.

**Themen:** workspace, spaces, auth, grants, rechteverwaltung, frontend
