# Thema model

Sprach- und Embedding-Modelle der Installation: Liste, Anlegen und Bearbeiten mit Modellerkennung am Endpunkt, Rollen. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei, solange kein anderes Thema das Thema braucht.
- **Spec-Tag:** `model` (`opaa-api/src/main/openapi/models.yaml`).
- **Backend-Modul:** knowledge (Paket `io.opaa.llm`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
