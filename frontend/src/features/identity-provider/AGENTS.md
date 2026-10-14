# Thema identity-provider

Anmeldeanbieter (OIDC) der Installation: Liste und Reihenfolge, Anlegen und Bearbeiten mit Einrichtungshinweisen, Abschalten. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt die Anbieterliste (`useOidcProviderStore`) frei, mit der Konten-, Gruppen- und Verzeichnisansichten nach Anbieter filtern. Der Verzeichnisabgleich ist ein eigenes Thema (`directory-sync`).
- **Spec-Tag:** `identity-provider` (`opaa-api/src/main/openapi/identity-providers.yaml`).
- **Backend-Modul:** identity (Paket `io.opaa.auth`).
- **Erlaubte Abhängigkeiten:** nur `shared`. Genutzt wird das Thema von `user`, `group` und `directory-sync`.
