# Issue #429 — Rechtehistorie: Aufbewahrungshöchstdauer und Pseudonymisierung des Personenbezugs
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, security
- PRs: keine

**Laut Issue:** #238 hat die Historisierung von Rechten (`asset_grant_history`, `group_membership_history`, `library_visibility_history`) umgesetzt. `docs/features/security-and-compliance.md` verlangt dafür eine konfigurierbare Aufbewahrungshöchstdauer und eine Pseudonymisierung des Personenbezugs ab Schreibzeitpunkt — beides fehlt. Übergangsweise sind die Subjektspalten `ON DELETE RESTRICT` gegen die Nutzertabelle geschaltet (ADR-0015), was eine Kontolöschung blockiert, solange Rechtehistorie zu diesem Konto existiert.

**Geliefert:** Nichts. Das Issue war Phase 3 des Sammel-Epics #457 ("Audit-Betriebshärtung — Nacharbeiten aus Stage A"). Laut Abschlusskommentar des Epics wurde die gesamte Phase-2/3-Nacharbeit bewusst zurückgestellt ("Ticket-Hygiene, Maintainer-Entscheidung … bekannt, aber ohne offene Tickets, bis das Thema wieder ansteht"), alle Sub-Issues wurden ohne Umsetzung geschlossen. Die `ON DELETE RESTRICT`-Einschränkung aus ADR-0015 bleibt bestehen.

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrantHistory.java` und `backend/src/main/java/io/opaa/group/GroupMembershipHistory.java` existieren unverändert seit #238; kein Retention-/Pseudonymisierungsmechanismus im Code auffindbar.

**Themen:** auth, security, doku, rechtehistorie
