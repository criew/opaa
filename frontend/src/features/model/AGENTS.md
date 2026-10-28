# Thema model

Modelle der Installation: Chat-Modelle (Liste, Anlegen und Bearbeiten mit Modellerkennung am Endpunkt), Embedding-Modell (Anzeige mit Indexstatus, geführter Wechsel mit Probeeinbettung und Vorschau, Verwerfen, Endpunktwechsel), Rerank- und Transkriptionsmodell (je ein Reiter mit Formular und Verbindungstest; Zweck und Adresshinweis teilen sie mit dem Einrichtungs-Assistenten über `shared/ui/modelDiscovery/modelRoleTexts.ts`). Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei, solange kein anderes Thema das Thema braucht.
- **Spec-Tag:** `model` (`opaa-api/src/main/openapi/models.yaml`).
- **Backend-Modul:** knowledge (Paket `io.opaa.llm`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
