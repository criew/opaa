# Thema installation

Einrichtungs-Assistent einer Installation im Installationsmodus (ADR-0060): Token-Eingabe, Pflicht- und optionale Schritte mit Prüfung, Abschluss. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `probeInstallationPending` frei, mit der `app` beim Start auf den Assistenten umleitet. Das Token hält die Seite nur im Speicher, den Stand der Schritte liefert allein die API. Was nach dem Abschluss in der Verwaltung geändert wird (Benutzer, Identitätsanbieter, Modelle, Branding), gehört zu den jeweiligen Themen.
- **Spec-Tag:** `installation` (`opaa-api/src/main/openapi/installation.yaml`, entsteht mit #3189; bis dahin folgen die Typen in `api/types.ts` der Skizze in `docs/features/installation-and-settings.md`).
- **Backend-Modul:** entsteht mit #3189.
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** `app` (`InstallationRedirect`).
