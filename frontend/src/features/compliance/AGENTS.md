# Thema compliance

Selbstauskunft: der Bereich „Meine Daten“ in den eigenen Einstellungen mit Download nach erneuter Anmeldung und die Seite „Datenschutz“ der Administration mit Notabschaltung und Tagesgrenze. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `MyDataSection` frei, den Reiter „Meine Daten“ der Einstellungsseite in `app`. Die Auskunft über die Datenerhebung hat noch keine Oberfläche.
- **Spec-Tag:** `compliance` (`opaa-api/src/main/openapi/compliance.yaml`).
- **Backend-Modul:** identity (Pakete `io.opaa.selfdisclosure` und `io.opaa.compliance`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema; `app` nutzt `index.ts`.
