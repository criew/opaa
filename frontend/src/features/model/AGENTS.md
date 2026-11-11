# Thema model

Modelle der Installation: Chat-Modelle (Liste, Anlegen und Bearbeiten mit Modellerkennung am Endpunkt), Embedding-Modell (Anzeige mit Indexstatus, geführter Wechsel mit Probeeinbettung und Vorschau, Verwerfen, Endpunktwechsel), Rerank- und Transkriptionsmodell (je ein Reiter mit Formular und Verbindungstest; Zweck und Adresshinweis teilen sie mit dem Einrichtungs-Assistenten über `shared/ui/modelDiscovery/modelRoleTexts.ts`), Texterkennung (eigener Reiter mit Anbindungsart und gestuftem Verbindungstest). Endpunkt, Modell, Parameter, Live-Transkript und Texterkennung fragen die Formulare über die Feldblöcke aus `shared/ui/setup` ab, die auch der Einrichtungs-Assistent einbindet (Gestaltungsleitlinien 5.16); die Reiter heißen wie dessen Schritte (`SETUP_NAMES`). Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei, solange kein anderes Thema das Thema braucht.
- **Spec-Tag:** `model` (`opaa-api/src/main/openapi/models.yaml`).
- **Backend-Modul:** knowledge (Paket `io.opaa.llm`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
