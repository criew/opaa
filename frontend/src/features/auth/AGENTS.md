# Thema auth

Anmeldeseiten und Self-Service lokaler Konten: Anmeldung (auch Systemanmeldung und OIDC-Rückkehr), Passwort ändern, setzen und vergessen, Registrierung, E-Mail-Bestätigung und Übergabe eines Kontos per Link; dazu der Merker zum Hinweis „Neu hier?“ des Rundgangs. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nur `markQuickTourHintDone` frei, den Schreibaufruf des Rundgangs für die Shell; Hinweis und Rundgang selbst liegen in `app/shell/tour/`. Der Sitzungskern (Anmeldestatus, Token, Store) liegt nicht hier, sondern in `shared/session`.
- **Spec-Tag:** `auth` (`opaa-api/src/main/openapi/auth.yaml`).
- **Backend-Modul:** identity (Pakete `io.opaa.auth`, `io.opaa.account`).
- **Erlaubte Abhängigkeiten:** `user`.
- **Genutzt von:** `app` (Rundgang der Shell).
