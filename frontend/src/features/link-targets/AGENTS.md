# Thema link-targets

Erlaubte Linkziele: Verwaltung der Origins, zu denen Links in Antworten klickbar sind. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** keine eigenen Seiten, kein `routes.ts`. `index.ts` gibt `LinkTargetsSection` für die Verbindungseinstellungen in `app` frei. Das Lesen der Liste und die Linkregel beim Rendern liegen in `shared/linkTargets` und `shared/ui/markdown`, weil jede Antwort sie braucht.
- **Spec-Tag:** `link-targets` (`opaa-api/src/main/openapi/link-targets.yaml`).
- **Backend-Modul:** identity (Paket `io.opaa.linktarget`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** `app`.
