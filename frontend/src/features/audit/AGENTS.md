# Thema audit

Revision: Rechtehistorie (Zugriff zu einem Zeitpunkt), Verbindungsprotokoll und dessen Aufbewahrungsfrist, Entscheidung über Anträge der Systemverwaltung auf die Rolle Revision. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. Die Aufbewahrungsfrist (`ConnectionLogRetentionSection`) und die Beschriftung der Verbindungsenden (`endCauseLabel`) gibt `index.ts` frei; die Anbindungseinstellungen betten den Abschnitt ein.
- **Spec-Tag:** `audit` (`opaa-api/src/main/openapi/audit.yaml`).
- **Backend-Modul:** identity (Paket `io.opaa.audit`, die Rollenanträge in `io.opaa.auth`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** `connection`.
