# Thema installation

Einrichtungs-Assistent einer Installation im Installationsmodus (ADR-0060): Token-Eingabe, Pflicht- und optionale Schritte mit Prüfung, Abschluss. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei; ob die Installation aussteht, liest `app` aus der öffentlichen Konfiguration im Sitzungskern (`shared/session`), nie über die Installations-API. Das Token hält die Seite nur im Speicher, den Stand der Schritte liefert allein die API. Was nach dem Abschluss in der Verwaltung geändert wird (Benutzer, Identitätsanbieter, Modelle, Branding), gehört zu den jeweiligen Themen.
- **Spec-Tag:** `installation` (`opaa-api/src/main/openapi/installation.yaml`).
- **Backend-Modul:** knowledge (Paket `io.opaa.installation`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
