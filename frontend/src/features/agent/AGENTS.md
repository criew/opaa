# Thema agent

Agenten als Asset-Typ: kurzes Anlegen, Detailseite mit den Bereichen Auftrag, Wissen und Werkzeuge, Ablauf, Freigaben und Zuordnungen, dazu die Seite „Agenten“ der Administration mit dem Installationsschalter. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei. Katalog, Freigaben und Eigentum kommen aus `asset`; ob die Schalter an sind, liest `shared/session/runtimeSwitches` (`agents`, für die Zeile „Vorlagen“ `documentTemplates`); `shared/session/agentAvailability` hält ihn zusätzlich dreiwertig für die Routen.
- **Spec-Tag:** `agent` (`opaa-api/src/main/openapi/agents.yaml`).
- **Backend-Modul:** assistant (Paket `io.opaa.agent`).
- **Erlaubte Abhängigkeiten:** `asset`, `principal`, `succession`.
- **Genutzt von:** kein anderes Thema.
