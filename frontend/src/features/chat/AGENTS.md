# Thema chat

Chat im Space: Fragen und Antworten mit Quellenbelegen, Metadatenfilter, Prompt-Befehle, Diktat und Transkription, die Chatliste in der Seitenleiste und die Chatübersicht mit Suche, Anheften und Archiv. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt die Chatliste (`ChatList`) und die Stores (`useChatStore`, `useChatListStore`) für Seitenleiste und Space-Seite frei.
- **Spec-Tags:** `chat` (`chats.yaml`), `query` und `search` (`search.yaml`), `transcription` (`transcriptions.yaml`), alle unter `opaa-api/src/main/openapi/`.
- **Backend-Module:** assistant (Pakete `io.opaa.chat`, `io.opaa.query`, `io.opaa.search`), für die Transkription knowledge (Paket `io.opaa.llm`).
- **Erlaubte Abhängigkeiten:** `space`, `prompt`, `library`. Das Thema steht oben im Graphen; nur `app` nutzt es.
