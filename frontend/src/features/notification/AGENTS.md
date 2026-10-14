# Thema notification

Benachrichtigungen des angemeldeten Kontos: Glocke mit Zähler und Liste, Ziel einer Benachrichtigung, Gelesen-Markierung. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** keine eigenen Seiten und kein `routes.ts`. `index.ts` gibt `NotificationBell` frei, die `app` in Rail und mobilen Kopf setzt. Die Meldungsleiste für Rückmeldungen der Oberfläche (`notify`) ist kein Teil des Themas, sie liegt in `shared`.
- **Spec-Tag:** `notification` (`opaa-api/src/main/openapi/notifications.yaml`).
- **Backend-Modul:** identity (Paket `io.opaa.notification`).
- **Erlaubte Abhängigkeiten:** nur `shared`.
