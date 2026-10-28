# Thema branding

Verwaltung des Erscheinungsbilds: Name, Farben, Logos und Anmeldehintergrund mit Vorschau. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei. Das Lesen und Anwenden des Brandings in der ganzen Anwendung (API-Funktionen und Store) liegt in `shared/branding`, weil Anmeldeseiten und Rahmen es vor jeder Verwaltung brauchen. Texte, Grenzen, Kontrastwarnung und Vorschau des Formulars liegen in `shared/ui/branding`, weil der Schritt Branding des Einrichtungs-Assistenten sie teilt.
- **Spec-Tag:** `branding` (`opaa-api/src/main/openapi/branding.yaml`).
- **Backend-Modul:** identity (Paket `io.opaa.branding`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
