# Thema installation

Einrichtungs-Assistent einer Installation im Installationsmodus (ADR-0060): Token-Eingabe, Pflicht- und optionale Schritte mit Prüfung, Abschluss. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei; ob die Installation aussteht, liest `app` aus der öffentlichen Konfiguration im Sitzungskern (`shared/session`), nie über die Installations-API. Das Token hält die Seite nur im Speicher, den Stand der Schritte liefert allein die API. Was nach dem Abschluss in der Verwaltung geändert wird (Benutzer, Identitätsanbieter, Modelle, E-Mail, Branding), gehört zu den jeweiligen Themen; Regeln und Texte des Mailservers teilt der Schritt E-Mail-Versand mit der Mail-Verwaltung über `shared/ui/smtp`, Texte, Regeln, Kontrastwarnung und Vorschau des Brandings der Schritt Branding mit der Branding-Verwaltung über `shared/ui/branding`, Texte und Sprachregel des Transkriptionsmodells der Schritt Transkriptionsmodell mit „Modelle → Spracheingabe“ über `shared/ui/transcription`. Den Schritt Transkriptionsmodell listet die API nur bei eingeschalteter Spracheingabe; der Assistent zeigt, was sie listet.
- **Spec-Tag:** `installation` (`opaa-api/src/main/openapi/installation.yaml`).
- **Backend-Modul:** knowledge (Paket `io.opaa.installation`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
