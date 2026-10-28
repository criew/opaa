# Thema installation

Einrichtungs-Assistent einer Installation im Installationsmodus (ADR-0060): Token-Eingabe, Pflicht- und optionale Schritte mit Prüfung, Abschluss. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei; ob die Installation aussteht, liest `app` aus der öffentlichen Konfiguration im Sitzungskern (`shared/session`), nie über die Installations-API. Das Token hält die Seite nur im Speicher, den Stand der Schritte liefert allein die API. Was nach dem Abschluss in der Verwaltung geändert wird (Benutzer, Identitätsanbieter, Modelle, E-Mail, Branding), gehört zu den jeweiligen Themen; Regeln und Texte des Mailservers teilt der Schritt E-Mail-Versand mit der Mail-Verwaltung über `shared/ui/smtp`, Texte, Regeln, Kontrastwarnung und Vorschau des Brandings der Schritt Branding mit der Branding-Verwaltung über `shared/ui/branding`, Modellabruf und Kennungsfeld die Modellschritte mit der Modellverwaltung über `shared/ui/modelDiscovery`, dort auch Zweck und Adresshinweis der Modellrollen neben Chat und Einbettung (`modelRoleTexts.ts`), die der Schritt Rerank-Modell mit dem Reiter „Reranking“ teilt. Ein weiterer Modellschritt ist ein Wert in `MODEL_STEPS` (`api/types.ts`) und baut auf `ModelEndpointFields` auf; eine Rolle ohne Anbietervorlagen beginnt bei der Adresse.
- **Spec-Tag:** `installation` (`opaa-api/src/main/openapi/installation.yaml`).
- **Backend-Modul:** knowledge (Paket `io.opaa.installation`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
