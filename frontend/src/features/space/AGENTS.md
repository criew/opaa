# Thema space

Spaces: Übersicht, Anlegen mit Inhalten und Mitgliedern, Space-Seite, Einstellungen (Stammdaten, Inhalte, Mitglieder, Modellanweisung, Aufräumen alter Chats) und die zuletzt genutzten Spaces. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `useSpaceStore`, `getSpaces`, die zuletzt genutzten Spaces (`recentSpaces`) und den Wissenshinweis (`SpaceKnowledgeNotice`, `spaceKnowledgeGap`) frei, die Chat und Navigation brauchen.
- **Spec-Tag:** `space` (`opaa-api/src/main/openapi/spaces.yaml`).
- **Backend-Modul:** workspace (Paket `io.opaa.space`).
- **Erlaubte Abhängigkeiten:** `asset`, `principal`, `succession`. Genutzt wird das Thema von `chat`.
