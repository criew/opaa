# Thema prompt

Prompt-Bibliotheken als Asset-Typ: Anlegen, Detailseite mit Pflege der Prompts, Editor mit Variablen, Hervorhebung und Vorschau sowie das Einsetzen der Variablenwerte in den Prompt-Text. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `getPrompt`, `listAvailablePrompts`, `PromptVariableInput` und die Auflösung der Vorlage (`promptTemplate`) frei, mit denen der Chat einen Prompt auswählt und ausfüllt. Katalog, Freigaben und Eigentum kommen aus `asset`.
- **Spec-Tag:** `prompt` (`opaa-api/src/main/openapi/prompts.yaml`).
- **Backend-Modul:** assistant (Paket `io.opaa.prompt`).
- **Erlaubte Abhängigkeiten:** `asset`, `principal`, `succession`. Genutzt wird das Thema von `chat`.
