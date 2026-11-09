# Thema agent

Agenten als Asset-Typ (UI-Lab #3670, nur Mock): Einrichtung in vier Varianten, Detailseite und die Wahl im Chat. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `useCallableAgents` und `AgentChatChip` für die Wahl im Chat frei. Katalog, Freigaben und Eigentum kommen aus `asset`.
- **Spec-Tag:** noch keiner; die Typen in `types.ts` sind Lab-Modelle bis zur Spec.
- **Backend-Modul:** noch keins.
- **Erlaubte Abhängigkeiten:** `asset`.
- **Genutzt von:** `chat`.
