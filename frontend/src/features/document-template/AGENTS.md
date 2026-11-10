# Thema document-template

Dokumentvorlagen als Asset-Typ: Word-Dateien mit Platzhaltern, die ein Agent in der Sandbox ausfüllt. Anlegen mit Datei, Detailseite mit Platzhaltern, Download und Ersetzen der Datei, dazu der Installationsschalter „Vorlagen“ der Administration. Das Frontend rendert keine Vorlage. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei. Katalog, Freigaben und Eigentum kommen aus `asset`; ob der Schalter an ist, liest `shared/session/runtimeSwitches`.
- **Spec-Tag:** `document-template` (`opaa-api/src/main/openapi/document-templates.yaml`).
- **Backend-Modul:** assistant (Paket `io.opaa.template`).
- **Erlaubte Abhängigkeiten:** `asset`, `principal`, `succession`.
- **Genutzt von:** keinem Thema.
