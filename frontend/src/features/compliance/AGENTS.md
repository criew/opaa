# Thema compliance

Selbstauskunft und Aufbewahrung: der Bereich „Meine Daten“ in den eigenen Einstellungen mit Download nach erneuter Anmeldung und die Seite „Datenschutz“ der Administration mit Notabschaltung, Tagesgrenze und den Aufbewahrungsfristen der Chats und der anlassbezogenen Klärungen. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `MyDataSection` frei, den Reiter „Meine Daten“ der Einstellungsseite in `app`. Die Auskunft über die Datenerhebung hat noch keine Oberfläche.
- **Spec-Tag:** `compliance` (`opaa-api/src/main/openapi/compliance.yaml`).
- **Backend-Modul:** identity (Pakete `io.opaa.selfdisclosure`, `io.opaa.compliance` und für die Löschfrist der Klärungen `io.opaa.audit`), workspace (`io.opaa.space`, Chat-Bereinigung) und assistant (`io.opaa.chat`, Chats gesperrter Konten); die Fristen tragen alle den Tag `compliance`.
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema; `app` nutzt `index.ts`.
