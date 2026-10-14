# Thema mail

Mailversand der Installation: Serverkonfiguration, Status und die bearbeitbaren Mailvorlagen samt Vorschau. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt den Mailstatus (`useMailStore`, `mailStatusOf`) frei; die Kontenverwaltung zeigt damit, ob Einladungen verschickt werden können.
- **Spec-Tag:** `mail` (`opaa-api/src/main/openapi/mail.yaml`).
- **Backend-Modul:** identity (Paket `io.opaa.mail`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** `user`.
